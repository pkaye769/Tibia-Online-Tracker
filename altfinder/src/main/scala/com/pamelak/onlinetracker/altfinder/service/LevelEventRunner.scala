package com.pamelak.onlinetracker.altfinder.service

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.{LevelEventEntry, LevelSnapshotWrite}
import com.pamelak.onlinetracker.altfinder.tibiadata.TibiaDataClientAlg
import io.circe.Json
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*

class LevelEventRunner[F[_]: Async](
    repo: AltFinderRepoAlg[F],
    tibiaDataClient: TibiaDataClientAlg[F],
    interval: FiniteDuration
) {

  given Logger[F] = Slf4jLogger.getLogger[F]

  def run: F[Unit] = loop

  private def loop: F[Unit] =
    checkAll.handleErrorWith(e => Logger[F].warn(e)("Level event loop error")) *>
      Async[F].sleep(interval) *>
      Async[F].defer(loop)

  private def checkAll: F[Unit] =
    repo.listAllLevelEvents.flatMap(_.traverse_(checkOneSafe))

  private def checkOneSafe(event: LevelEventEntry): F[Unit] =
    checkOne(event).handleErrorWith(e =>
      Logger[F].warn(e)(s"Level event check failed for ${event.tibiaGuildName}")
    )

  private def checkOne(event: LevelEventEntry): F[Unit] =
    tibiaDataClient.getGuild(event.tibiaGuildName).attempt.flatMap {
      case Left(err) =>
        Logger[F].warn(err)(s"Failed to fetch guild data for ${event.tibiaGuildName}")
      case Right(guildJson) =>
        val worldName = guildJson.hcursor.downField("guild").get[String]("world").getOrElse("")
        val members = extractGuildMembers(guildJson)
        if (members.isEmpty)
          Logger[F].warn(s"No members found for guild ${event.tibiaGuildName}")
        else
          for {
            onlineLevels <- if (worldName.nonEmpty) fetchOnlineLevels(worldName)
                            else Logger[F].warn(s"Could not extract world name for guild ${event.tibiaGuildName}").as(Map.empty[String, Int])
            latestLevels <- repo.getLatestLevelsByGuild(event.guildId, event.tibiaGuildName)
            _ <- members.traverse_ { case (name, guildLevel) =>
              val effectiveLevel = onlineLevels.getOrElse(name.toLowerCase, guildLevel)
              val shouldRecord = latestLevels.get(name.toLowerCase).fold(true)(_ != effectiveLevel)
              if (shouldRecord)
                repo.insertLevelSnapshot(
                  LevelSnapshotWrite(event.guildId, event.tibiaGuildName, name, effectiveLevel)
                )
              else Async[F].unit
            }
          } yield ()
    }

  private def fetchOnlineLevels(worldName: String): F[Map[String, Int]] =
    tibiaDataClient.getWorld(worldName).attempt.map {
      case Left(_)     => Map.empty
      case Right(json) => extractOnlinePlayers(json)
    }

  private def extractGuildMembers(json: Json): List[(String, Int)] = {
    val cursor = json.hcursor.downField("guild")
    val membersDirect = cursor.downField("members").as[List[Json]].toOption
    val membersNested = cursor.downField("members").downField("members").as[List[Json]].toOption
    membersDirect.orElse(membersNested).getOrElse(Nil).flatMap { m =>
      for {
        name  <- m.hcursor.get[String]("name").toOption
        level <- m.hcursor.get[Int]("level").toOption
      } yield (name, level)
    }
  }

  private def extractOnlinePlayers(json: Json): Map[String, Int] =
    json.hcursor
      .downField("world")
      .downField("online_players")
      .as[List[Json]]
      .toOption
      .getOrElse(Nil)
      .flatMap { p =>
        for {
          name  <- p.hcursor.get[String]("name").toOption
          level <- p.hcursor.get[Int]("level").toOption
        } yield (name.toLowerCase, level)
      }
      .toMap
}
