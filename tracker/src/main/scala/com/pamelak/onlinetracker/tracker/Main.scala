package com.pamelak.onlinetracker.tracker

import cats.effect.*
import cats.syntax.all.*
import com.pamelak.onlinetracker.common.config.AppConfig
import com.pamelak.onlinetracker.tracker.repo.OnlineTrackerSkunkRepo
import com.pamelak.onlinetracker.tracker.service.OnlineTrackerService
import com.pamelak.onlinetracker.tracker.tibiadata.TibiaDataHttp4sClient
import fs2.Stream
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import skunk.Session

import scala.concurrent.duration.*

object Main extends IOApp {

  given Logger[IO] = Slf4jLogger.getLogger[IO]
  given Tracer[IO] = Tracer.noop

  override def run(args: List[String]): IO[ExitCode] = {
    AppConfig.loadDatabaseConfigIO.flatMap { dbCfg =>
      val sslMode = AppConfig.resolveSSL(dbCfg)

      val maxRetries = 10
      val retryDelay = 5.seconds

      def acquireWithRetry(attempt: Int): IO[ExitCode] = {
        val dbSessionResource: Resource[IO, Session[IO]] = Session.single(
          host = dbCfg.host,
          port = dbCfg.port,
          user = dbCfg.user,
          database = dbCfg.database,
          password = dbCfg.password.some,
          ssl = sslMode
        )
        val tibiaDataClientResource = TibiaDataHttp4sClient.clientResource

        (dbSessionResource, tibiaDataClientResource).tupled.use { case (dbSession, tibiaDataClientSession) =>
          val repo = new OnlineTrackerSkunkRepo(dbSession)
          val tibiaDataClient = new TibiaDataHttp4sClient(tibiaDataClientSession)
          val service = new OnlineTrackerService(repo, tibiaDataClient)

          val worlds: List[String] =
            sys.env
              .get("TRACKER_WORLDS")
              .orElse(sys.env.get("WORLD"))
              .map(_.split(",").map(_.trim).filter(_.nonEmpty).toList)
              .getOrElse(List("Nefera"))
          val intervalSeconds = sys.env.get("TRACKER_INTERVAL_SECONDS").flatMap(s => s.toIntOption).getOrElse(15)

          Stream.fixedRateStartImmediately[IO](intervalSeconds.seconds).evalTap { _ =>
            worlds.traverse_(world =>
              service.updateDataForWorld(world).handleErrorWith { e =>
                Logger[IO].warn(e)(s"Recovering from error in stream for $world:${System.lineSeparator}")
              }
            )
          }.compile.drain.as(ExitCode.Success)
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
