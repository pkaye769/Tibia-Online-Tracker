package com.pamelak.onlinetracker.altfinder.service

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.WatchEntry
import net.dv8tion.jda.api.JDA
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.{OffsetDateTime, ZoneId}
import scala.concurrent.duration.*
import scala.collection.concurrent.TrieMap

class WatchRunner[F[_]: Async](
    repo: AltFinderRepoAlg[F],
    service: AltFinderService[F],
    jda: JDA,
    interval: FiniteDuration,
    alertCooldown: FiniteDuration
) {

  given Logger[F] = Slf4jLogger.getLogger[F]
  private val lastMaxClashesByWatchId = TrieMap.empty[Long, Int]

  def run: F[Unit] = loop

  private def loop: F[Unit] = {
    checkAll.handleErrorWith(e => Logger[F].warn(e)(s"Watch loop error")) *>
      Async[F].sleep(interval) *>
      Async[F].defer(loop)
  }

  private def checkAll: F[Unit] = repo.listAllWatches.flatMap(_.traverse_(checkOneSafe))

  private def checkOneSafe(entry: WatchEntry): F[Unit] =
    checkOne(entry).handleErrorWith(e => Logger[F].warn(e)(s"Watch check failed for ${entry.characterName}"))

  private def checkOne(entry: WatchEntry): F[Unit] = {
    val now = OffsetDateTime.now(ZoneId.of("Europe/Berlin"))
    val from = Some(now.minusDays(entry.windowDays.toLong))
    service.findAndPrintAlts(
      List(entry.characterName),
      from,
      None,
      Some(entry.distance),
      entry.includeClashes
    ).flatMap { results =>
      val matches = results.adjacencies.filter(_.confidence >= entry.confidenceThreshold)
      val maxClashes = results.adjacencies.map(_.clashes).filter(_ > 0).maxOption.getOrElse(0)
      val previousMaxClashes = lastMaxClashesByWatchId.getOrElse(entry.id, 0)
      val clashJump = maxClashes > previousMaxClashes
      lastMaxClashesByWatchId.put(entry.id, maxClashes)
      val shouldAlert = matches.nonEmpty && entry.lastAlertAt.forall { last =>
        last.isBefore(now.minusSeconds(alertCooldown.toSeconds))
      }
      val shouldClashAlert = clashJump && maxClashes > 0

      val sendAlert =
        if (!shouldAlert) Async[F].unit
        else if (!entry.channelId.forall(_.isDigit)) Async[F].unit
        else {
          val channel = Option(jda.getTextChannelById(entry.channelId))
          channel match
            case None =>
              Logger[F].warn(s"Watch channel not found for ${entry.channelId}")
            case Some(ch) =>
              val lines = matches.take(10).map(formatMatch).mkString("\n")
              val clashText = if (maxClashes > 0) s"\nMax clashes observed: $maxClashes (prev: $previousMaxClashes)" else ""
              val msg =
                s"Watch match for **${entry.characterName}** (threshold ${entry.confidenceThreshold}, window ${entry.windowDays}d)$clashText\n$lines"
              Async[F].delay(ch.sendMessage(msg).queue())
        }

      val sendClashOnlyAlert =
        if (!shouldClashAlert || shouldAlert) Async[F].unit
        else if (!entry.channelId.forall(_.isDigit)) Async[F].unit
        else {
          val channel = Option(jda.getTextChannelById(entry.channelId))
          channel match
            case None => Logger[F].warn(s"Watch channel not found for ${entry.channelId}")
            case Some(ch) =>
              val msg = s"Clash spike for **${entry.characterName}**: max clashes changed from $previousMaxClashes to $maxClashes."
              Async[F].delay(ch.sendMessage(msg).queue())
        }

      val alertAt = if (shouldAlert) Some(now) else None
      (sendAlert *> sendClashOnlyAlert) *> repo.updateWatchCheck(entry.id, now, alertAt)
    }
  }

  private def formatMatch(adj: AltFinderService.CharacterAdjacencies): String = {
    val name = adj.characterName.getOrElse("Unknown")
    val clashText = if (adj.clashes < 0) "yes" else adj.clashes.toString
    val tradeText =
      if (adj.recentTradeDates.nonEmpty) adj.recentTradeDates.map(_.toString).mkString(", ") else "none"
    val hiddenText = if (adj.hiddenLikely) s"yes (${adj.hiddenScore})" else s"no (${adj.hiddenScore})"
    s"$name | conf ${adj.confidence} | hidden $hiddenText | adj ${adj.adjacencies} | clashes $clashText | logins ${adj.logins} | traded $tradeText"
  }
}
