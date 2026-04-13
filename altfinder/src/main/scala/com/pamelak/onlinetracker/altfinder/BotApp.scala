package com.pamelak.onlinetracker.altfinder

import cats.effect.*
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.api.AltFinderApi
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraperHttp4sClient
import com.pamelak.onlinetracker.altfinder.bazaarscraper.TibiaComAuctionHttp4sClient
import com.pamelak.onlinetracker.altfinder.bot.BotListener
import com.pamelak.onlinetracker.altfinder.bot.command.Command
import com.pamelak.onlinetracker.altfinder.bot.command.CompareCommand
import com.pamelak.onlinetracker.altfinder.bot.command.ClashesCommand
import com.pamelak.onlinetracker.altfinder.bot.command.FindAltsCommand
import com.pamelak.onlinetracker.altfinder.bot.command.GuildCommand
import com.pamelak.onlinetracker.altfinder.bot.command.GuildTrackCommand
import com.pamelak.onlinetracker.altfinder.bot.command.HistoryCommand
import com.pamelak.onlinetracker.altfinder.bot.command.LastAltsCommand
import com.pamelak.onlinetracker.altfinder.bot.command.TradesCommand
import com.pamelak.onlinetracker.altfinder.bot.command.WatchCommand
import com.pamelak.onlinetracker.altfinder.bot.command.WorldCommand
import com.pamelak.onlinetracker.altfinder.repo.AltFinderSkunkRepo
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import com.pamelak.onlinetracker.altfinder.service.WatchRunner
import com.pamelak.onlinetracker.altfinder.tibiadata.TibiaDataHttp4sClient
import com.pamelak.onlinetracker.common.config.AppConfig
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.client.Client
import org.http4s.server.middleware.CORS
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

import java.net.InetSocketAddress
import java.net.ServerSocket
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object BotApp extends IOApp {

  given Logger[IO] = Slf4jLogger.getLogger[IO]
  given Tracer[IO] = Tracer.noop

  private def registerCommands(
      jda: JDA,
      commands: List[Command[IO]],
      guildIdOverride: Option[String]
  ): IO[Unit] = {
    val commandData = commands.map(_.command).asJava
    val allGuilds = jda.getGuilds.asScala.toList
    val targetGuilds = guildIdOverride match {
      case Some(gid) => allGuilds.filter(_.getId == gid) match {
        case Nil     => allGuilds // override not found – fall back to all (warning already logged above)
        case specific => specific
      }
      case None => allGuilds
    }

    for {
      _ <- Logger[IO].info(s"Registering ${commands.length} commands: ${commands.map(_.command.getName).mkString(", ")}")
      _ <- {
        val visibleGuilds = allGuilds.map(g => s"${g.getName} (${g.getId})")
        if (visibleGuilds.isEmpty) Logger[IO].warn("Bot currently sees 0 guilds.")
        else Logger[IO].info(s"Bot visible guilds: ${visibleGuilds.mkString(", ")}")
      }
      _ <- guildIdOverride match {
        case Some(gid) if allGuilds.forall(_.getId != gid) =>
          Logger[IO].warn(
            s"DISCORD_GUILD_ID=$gid not found in bot guild cache. Ignoring override and syncing all visible guilds."
          )
        case _ => IO.unit
      }
      _ <- if (targetGuilds.isEmpty) Logger[IO].warn("No guilds available for instant command registration.")
      else Logger[IO].info(s"Registering commands for ${targetGuilds.length} guild(s).")
      _ <- targetGuilds.traverse_ { guild =>
        IO.delay {
          val update: CommandListUpdateAction = guild.updateCommands()
          val registered = update.addCommands(commandData).complete()
          (guild.getName, guild.getId, registered.size())
        }.flatMap { case (name, id, count) =>
          Logger[IO].info(s"Guild command sync complete: $name ($id), $count command(s)")
        }
      }
      _ <- IO.delay {
        val globalUpdate: CommandListUpdateAction = jda.updateCommands()
        globalUpdate.addCommands(commandData).complete().size()
      }.flatMap { count =>
        Logger[IO].info(s"Global command sync submitted: $count command(s)")
      }
    } yield ()
  }


  private def findAvailablePort(startPort: Int, attempts: Int = 20): IO[Int] = IO.delay {
    (startPort until (startPort + attempts)).find { port =>
      var socket: ServerSocket = null
      try {
        socket = new ServerSocket()
        socket.setReuseAddress(true)
        socket.bind(new InetSocketAddress("0.0.0.0", port))
        true
      } catch {
        case _: Throwable => false
      } finally {
        if (socket != null) socket.close()
      }
    }.getOrElse(startPort)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    Dispatcher[IO].use { dispatcher =>
      AppConfig.loadConfigIO.flatMap { cfg =>
        val dbCfg = cfg.database
        val sslMode = AppConfig.resolveSSL(dbCfg)

        val maxRetries = 10
        val retryDelay = 5.seconds

        def acquireWithRetry(attempt: Int): IO[ExitCode] = {
          val dbPoolResource: Resource[IO, Resource[IO, Session[IO]]] = Session.pooled(
            host = dbCfg.host,
            port = dbCfg.port,
            user = dbCfg.user,
            database = dbCfg.database,
            password = dbCfg.password.some,
            ssl = sslMode,
            max = 4
          )
          val httpClientResource: Resource[IO, Client[IO]] = BazaarScraperHttp4sClient.clientResource

          (dbPoolResource, httpClientResource).tupled.use { case (sessionPool, httpClient) =>
            val bazaarScraperClient = new BazaarScraperHttp4sClient(httpClient)
            val tibiaComClient = new TibiaComAuctionHttp4sClient(httpClient)
            val bazaarScraper = new BazaarScraper(bazaarScraperClient, Some(tibiaComClient))
            val repo = new AltFinderSkunkRepo(sessionPool)

            val tradeLookbackDays = sys.env.get("TRADE_LOOKBACK_DAYS").flatMap(_.toIntOption).getOrElse(30)
            val candidateTradeLimit = sys.env.get("CANDIDATE_TRADE_CHECK_LIMIT").flatMap(_.toIntOption).getOrElse(20)
            val hiddenLikelyMinScore = sys.env.get("HIDDEN_LIKELY_MIN_SCORE").flatMap(_.toIntOption).getOrElse(70)
            val hiddenLikelyMinAdjacencies =
              sys.env.get("HIDDEN_LIKELY_MIN_ADJACENCIES").flatMap(_.toIntOption).getOrElse(3)
            val hiddenLikelyMaxClashRatio =
              sys.env.get("HIDDEN_LIKELY_MAX_CLASH_RATIO").flatMap(_.toDoubleOption).getOrElse(0.25)
            val minEvidenceLogins = sys.env.get("MIN_EVIDENCE_LOGINS").flatMap(_.toIntOption).getOrElse(8)
            val minEvidenceAdjacencies = sys.env.get("MIN_EVIDENCE_ADJACENCIES").flatMap(_.toIntOption).getOrElse(2)
            val includeLowEvidenceMatches =
              sys.env.get("INCLUDE_LOW_EVIDENCE_MATCHES").exists(_.trim.equalsIgnoreCase("true"))

            val service = new AltFinderService(
              repo,
              bazaarScraper,
              tradeLookbackDays,
              candidateTradeLimit,
              hiddenLikelyMinScore,
              hiddenLikelyMinAdjacencies,
              hiddenLikelyMaxClashRatio,
              minEvidenceLogins,
              minEvidenceAdjacencies,
              includeLowEvidenceMatches
            )

            val tibiaDataClient = new TibiaDataHttp4sClient[IO](httpClient)

            val commands = List[Command[IO]](
              new FindAltsCommand[IO](service),
              new LastAltsCommand[IO](service),
              new HistoryCommand[IO](service),
              new CompareCommand[IO](service),
              new ClashesCommand[IO](service),
              new WorldCommand[IO](tibiaDataClient),
              new GuildCommand[IO](tibiaDataClient),
              new WatchCommand[IO](service, repo),
              new GuildTrackCommand[IO](tibiaDataClient, repo),
              new TradesCommand[IO](service)
            )

            val guildIdOverride = sys.env.get("DISCORD_GUILD_ID")
              .map(_.trim)
              .map(_.replaceAll("[^0-9]", ""))
              .filter(_.nonEmpty)

            val maybeToken = List(
              Option(cfg.bot.token),
              sys.env.get("ALTFINDER_TOKEN")
            ).flatten.map(_.trim).find(_.nonEmpty)

            val apiHost = sys.env.getOrElse("ALTFINDER_API_HOST", "0.0.0.0")
            val requestedApiPort = sys.env.get("PORT")
              .orElse(sys.env.get("ALTFINDER_API_PORT"))
              .flatMap(_.toIntOption)
              .getOrElse(8080)

            val api = new AltFinderApi[IO](service, repo, tibiaDataClient, bazaarScraperClient)
            val httpApp = CORS.policy.withAllowOriginAll(api.routes).orNotFound

            val watchIntervalSeconds = sys.env.get("WATCH_INTERVAL_SECONDS").flatMap(_.toIntOption).getOrElse(300)
            val watchCooldownMinutes = sys.env.get("WATCH_ALERT_COOLDOWN_MINUTES").flatMap(_.toIntOption).getOrElse(360)

            def startDiscordIfConfigured: IO[Unit] =
              maybeToken match {
                case None =>
                  Logger[IO].warn("TOKEN/ALTFINDER_TOKEN is empty. Discord bot and watch runner are disabled.")
                case Some(token) =>
                  IO.delay(JDABuilder.createDefault(token).build()).flatMap { jda =>
                    val botListener = new BotListener[IO](commands, dispatcher)
                    val watchRunner = new WatchRunner[IO](
                      repo,
                      service,
                      jda,
                      watchIntervalSeconds.seconds,
                      watchCooldownMinutes.minutes
                    )

                    (IO.delay(jda.awaitReady()) *>
                      IO.delay(jda.addEventListener(botListener)) *>
                      registerCommands(jda, commands, guildIdOverride) *>
                      watchRunner.run.start.void)
                  }.handleErrorWith(e => Logger[IO].warn(e)("Discord setup failed; API will keep running"))
              }

            repo.ensureSchema *> findAvailablePort(requestedApiPort).flatMap { apiPort =>
              val serverResource = BlazeServerBuilder[IO]
                .bindHttp(apiPort, apiHost)
                .withHttpApp(httpApp)
                .resource

              val portLog =
                if (apiPort == requestedApiPort) Logger[IO].info(s"AltFinder API listening on http://$apiHost:$apiPort")
                else Logger[IO].warn(
                  s"Requested API port $requestedApiPort is busy. Using port $apiPort instead: http://$apiHost:$apiPort"
                )

              serverResource.use { _ =>
                portLog *>
                  startDiscordIfConfigured.start *>
                  IO.never
              }
            }
        }.handleErrorWith { e =>
          if (attempt < maxRetries)
            Logger[IO].warn(e)(
              s"DB connection failed (attempt $attempt/$maxRetries), retrying in ${retryDelay.toSeconds}s..."
            ) *> IO.sleep(retryDelay) *> acquireWithRetry(attempt + 1)
          else
            Logger[IO].error(e)(s"DB connection failed after $maxRetries attempts. Giving up.") *>
              IO.pure(ExitCode.Error)
        }
      }

      acquireWithRetry(1)
    }
  }
}
}
