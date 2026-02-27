package com.pamelak.onlinetracker.altfinder

import cats.effect.*
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.api.AltFinderApi
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraperHttp4sClient
import com.pamelak.onlinetracker.altfinder.bazaarscraper.TibiaComAuctionHttp4sClient
import com.pamelak.onlinetracker.altfinder.bot.BotListener
import com.pamelak.onlinetracker.altfinder.bot.command.CompareCommand
import com.pamelak.onlinetracker.altfinder.bot.command.FindAltsCommand
import com.pamelak.onlinetracker.altfinder.bot.command.GuildCommand
import com.pamelak.onlinetracker.altfinder.bot.command.HistoryCommand
import com.pamelak.onlinetracker.altfinder.bot.command.LastAltsCommand
import com.pamelak.onlinetracker.altfinder.bot.command.WatchCommand
import com.pamelak.onlinetracker.altfinder.bot.command.WorldCommand
import com.pamelak.onlinetracker.altfinder.bot.command.Command
import com.pamelak.onlinetracker.altfinder.repo.AltFinderSkunkRepo
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import com.pamelak.onlinetracker.altfinder.service.WatchRunner
import com.pamelak.onlinetracker.altfinder.tibiadata.TibiaDataHttp4sClient
import com.pamelak.onlinetracker.common.config.AppConfig
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import org.http4s.client.Client
import org.http4s.blaze.server.BlazeServerBuilder
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
    val targetGuilds = allGuilds

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
      AppConfig.config.load[IO].flatMap { cfg =>
        val dbCfg = cfg.database
        val dbSessionResource: Resource[IO, Session[IO]] = Session.single(
          host = dbCfg.host,
          port = dbCfg.port,
          user = dbCfg.user,
          database = dbCfg.database,
          password = dbCfg.password.some
        )
        val httpClientResource: Resource[IO, Client[IO]] = BazaarScraperHttp4sClient.clientResource

        val jdaResource: Resource[IO, JDA] = Resource.eval(IO.delay(JDABuilder.createDefault(cfg.bot.token).build()))

        (dbSessionResource, httpClientResource, jdaResource).tupled.use { case (dbSession, httpClient, jda) =>
          val bazaarScraperClient = new BazaarScraperHttp4sClient(httpClient)
          val tibiaComClient = new TibiaComAuctionHttp4sClient(httpClient)
          val bazaarScraper = new BazaarScraper(bazaarScraperClient, Some(tibiaComClient))
          val repo = new AltFinderSkunkRepo(dbSession)
          val tradeLookbackDays = sys.env.get("TRADE_LOOKBACK_DAYS").flatMap(_.toIntOption).getOrElse(30)
          val candidateTradeLimit = sys.env.get("CANDIDATE_TRADE_CHECK_LIMIT").flatMap(_.toIntOption).getOrElse(20)
          val hiddenLikelyMinScore = sys.env.get("HIDDEN_LIKELY_MIN_SCORE").flatMap(_.toIntOption).getOrElse(70)
          val hiddenLikelyMinAdjacencies =
            sys.env.get("HIDDEN_LIKELY_MIN_ADJACENCIES").flatMap(_.toIntOption).getOrElse(3)
          val hiddenLikelyMaxClashRatio =
            sys.env.get("HIDDEN_LIKELY_MAX_CLASH_RATIO").flatMap(_.toDoubleOption).getOrElse(0.25)
          val service = new AltFinderService(
            repo,
            bazaarScraper,
            tradeLookbackDays,
            candidateTradeLimit,
            hiddenLikelyMinScore,
            hiddenLikelyMinAdjacencies,
            hiddenLikelyMaxClashRatio
          )
          val findAltsCommand = new FindAltsCommand[IO](service)
          val altCommand = new FindAltsCommand[IO](service, "alt", "Alias for /alts")
          val lastAltsCommand = new LastAltsCommand[IO](service)
          val historyCommand = new HistoryCommand[IO](service)
          val compareCommand = new CompareCommand[IO](service)
          val tibiaDataClient = new TibiaDataHttp4sClient[IO](httpClient)
          val worldCommand = new WorldCommand[IO](tibiaDataClient)
          val guildCommand = new GuildCommand[IO](tibiaDataClient)
          val watchCommand = new WatchCommand[IO](service, repo)
          val commands =
            List(findAltsCommand, altCommand, lastAltsCommand, historyCommand, compareCommand, worldCommand, guildCommand, watchCommand)
          val botListener = new BotListener[IO](commands, dispatcher)
          val guildIdOverride = sys.env.get("DISCORD_GUILD_ID")
            .map(_.trim)
            .map(_.replaceAll("[^0-9]", ""))
            .filter(_.nonEmpty)
          val discordSetup =
            IO.delay(jda.awaitReady()) *>
              IO.delay(jda.addEventListener(botListener)) *>
              registerCommands(jda, commands, guildIdOverride)

          val apiHost = sys.env.getOrElse("ALTFINDER_API_HOST", "0.0.0.0")
          val requestedApiPort = sys.env.get("ALTFINDER_API_PORT").flatMap(_.toIntOption).getOrElse(8080)
          val api = new AltFinderApi[IO](service)
          val httpApp = CORS.policy.withAllowOriginAll(api.routes).orNotFound
          val watchIntervalSeconds = sys.env.get("WATCH_INTERVAL_SECONDS").flatMap(_.toIntOption).getOrElse(300)
          val watchCooldownMinutes = sys.env.get("WATCH_ALERT_COOLDOWN_MINUTES").flatMap(_.toIntOption).getOrElse(360)
          val watchRunner = new WatchRunner[IO](
            repo,
            service,
            jda,
            watchIntervalSeconds.seconds,
            watchCooldownMinutes.minutes
          )
          discordSetup *> findAvailablePort(requestedApiPort).flatMap { apiPort =>
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
              portLog *> watchRunner.run.start *> IO.never
            }
          }
        }
      }
    }
  }

}



