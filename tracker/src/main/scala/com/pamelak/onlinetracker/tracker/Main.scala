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
import skunk.SSL
import skunk.Session

import scala.concurrent.duration.*
import cats.effect.std.Dispatcher

object Main extends IOApp {

  given Logger[IO] = Slf4jLogger.getLogger[IO]
  given Tracer[IO] = Tracer.noop

  override def run(args: List[String]): IO[ExitCode] = {
    AppConfig.loadDatabaseConfigIO.flatMap { dbCfg =>
      val sslMode = sys.env.get("DB_SSL").map(_.trim.toLowerCase) match {
        case Some("false") | Some("0") | Some("no") => SSL.None
        case Some("true") | Some("1") | Some("yes") => SSL.System
        case _ if dbCfg.host == "localhost" || dbCfg.host == "127.0.0.1" => SSL.None
        case _ => SSL.System
      }
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
      }
    }
  }

}
