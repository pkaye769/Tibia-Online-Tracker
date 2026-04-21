package com.pamelak.onlinetracker.altfinder.service

import cats.Parallel
import cats.effect.kernel.Async
import cats.implicits.*
import com.carrotsearch.sizeof.RamUsageEstimator
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper.*
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.LastSearch
import com.pamelak.onlinetracker.altfinder.repo.Model.OnlineDateSegment
import com.pamelak.onlinetracker.altfinder.repo.Model.OnlineSegment
import com.pamelak.onlinetracker.altfinder.service.AltFinderService.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.{LocalDate, OffsetDateTime, ZoneId, ZonedDateTime}
import scala.concurrent.duration.*

object AltFinderService {
  case class CharacterLoginHistory(characterId: Long, segments: Array[OnlineSegment], segmentsByEnd: Array[OnlineSegment])

  case class CharacterAdjacencies(
      characterId: Long,
      characterName: Option[String],
      adjacencies: Int,
      clashes: Int,
      logins: Int,
      sessionSimilarity: Int,
      confidence: Int,
      hiddenScore: Int,
      hiddenLikely: Boolean,
      evidencePassed: Boolean,
      recentTradeDates: List[LocalDate]
  ) {
    override def toString: String = {
      s"${characterName.getOrElse("Unknown")}: $adjacencies / $clashes / $logins"
    }
  }

  case class AltsResults(
      searchedCharacters: List[String],
      searchedFrom: Option[OffsetDateTime],
      searchedTo: Option[OffsetDateTime],
      mainLogins: Int,
      adjacencies: List[CharacterAdjacencies],
      sales: CharacterSalesList,
      candidateTradeErrors: Int
  )

  case class TrackerStatus(onlineHistoryRows: Long, latestWorldSave: Option[OffsetDateTime])
  case class CharacterTradeStatus(
      characterName: String,
      checkedNames: List[String],
      recentTradeDates: List[LocalDate],
      hadError: Boolean
  )
  case class CompareResults(
      aToB: CharacterAdjacencies,
      bToA: CharacterAdjacencies
  )
  case class ClashResults(
      searchedCharacters: List[String],
      checkedCharacters: List[String],
      searchedFrom: Option[OffsetDateTime],
      searchedTo: Option[OffsetDateTime],
      clashes: List[CharacterAdjacencies]
  )

}

class AltFinderService[F[_]: Async: Parallel](
    repo: AltFinderRepoAlg[F],
    bazaarScraper: BazaarScraper[F],
    tradeLookbackDays: Int = 30,
    candidateTradeCheckLimit: Int = 20,
    hiddenLikelyMinScore: Int = 70,
    hiddenLikelyMinAdjacencies: Int = 3,
    hiddenLikelyMaxClashRatio: Double = 0.25,
    minEvidenceLogins: Int = 8,
    minEvidenceAdjacencies: Int = 2,
    includeLowEvidenceMatches: Boolean = false,
    defaultLookbackDays: Int = 90
) {

  given Logger[F] = Slf4jLogger.getLogger[F]

  def onlineHistories(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): F[List[OnlineDateSegment]] = {
    for
      history <- repo.getCharacterHistories(characterNames, from, to)
      _ <- history.map(i => Logger[F].info(i.toString)).sequence
    // _ = LoginPlotter.plot(history)
    yield history
  }

  def getHistory(
      characterName: String,
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      limit: Int
  ): F[List[OnlineDateSegment]] = {
    repo.getCharacterHistories(List(characterName), from, to).map { rows =>
      if (rows.length <= limit) rows else rows.takeRight(limit)
    }
  }

  def findAndPrintAlts(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Option[Int],
      includeClashes: Boolean
  ): F[AltsResults] = {
    for
      _ <- Logger[F].info(s"Searching for: ${characterNames.mkString(", ")}")
      _ <- Logger[F].info(s"Date range: $from - $to")
      sales <- characterNames.map(n => bazaarScraper.multipleCharacterSales(List(n))).parSequence
      latestSale = BazaarScraper.latestSale(sales)
      defaultFrom = OffsetDateTime.now(ZoneId.of("Europe/Berlin")).minusDays(defaultLookbackDays.toLong)
      tradedFrom = from.orElse { latestSale.map(_.toOffsetDateTime()) }.orElse(Some(defaultFrom))
      mainSegments <- repo.getOnlineTimes(characterNames, tradedFrom, to)
      _ <- Logger[F].info(s"Got online times for searched characters (${mainSegments.length} rows)")
      _ <- Logger[F].info(RamUsageEstimator.humanSizeOf(mainSegments))
      matchesToCheck <- repo.getPossibleMatches(characterNames, tradedFrom, to, distance)
      _ <- Logger[F].info("Got online times for possible matched characters")
      _ <- Logger[F].info(RamUsageEstimator.humanSizeOf(matchesToCheck))
      _ <- Logger[F].info(s"${matchesToCheck.length} rows to analyse")
      adj <- Async[F].blocking(getAdjacencies(mainSegments, matchesToCheck, includeClashes, distance.getOrElse(0)).take(20))
      adjWithNames <- adj.map(a =>
        repo.getCharacterName(a.characterId)
          .map { i => a.copy(characterName = Some(i)) }
          .handleErrorWith { e =>
            Logger[F].warn(s"Could not fetch name for character ID ${a.characterId}: ${e.getMessage}") *>
              Async[F].pure(a)
          }
      ).parSequence
      // Cap trade-enrichment at 10 s so that slow or unreachable bazaar/tibia.com
      // calls do not push the total request time past the 45 s backend timeout or
      // Render's 60 s HTTP timeout.  On timeout we fall back to empty trade data
      // (no trade dates shown) but still return the adjacency results.
      tradeInfo <- Async[F].timeoutTo(
        enrichWithCandidateTrades(adjWithNames),
        10.seconds,
        Async[F].pure(CandidateTradeInfo(Map.empty, adjWithNames.length))
      )
      mainLogins <- repo.countTotalLogins(characterNames)
      _ <- Logger[F].info(s"Total all-time logins for searched characters: $mainLogins")
      results = adjWithNames.map(a => addTradeAndConfidence(a, tradeInfo.tradeMap, mainLogins))
      filteredResults =
        if (includeLowEvidenceMatches) results
        else results.filter(r => r.adjacencies >= minEvidenceAdjacencies && (r.logins >= minEvidenceLogins || mainLogins >= minEvidenceLogins))
      altsResults = AltsResults(
        characterNames,
        tradedFrom,
        to,
        mainSegments.length,
        filteredResults,
        CharacterSalesList(sales),
        tradeInfo.errorCount
      )
      _ <- results.map(i => Logger[F].info(i.toString)).sequence
    yield altsResults
  }

  def getTrackerStatus: F[TrackerStatus] = {
    (repo.countOnlineHistoryRows, repo.latestWorldSaveTime).mapN(TrackerStatus.apply)
  }
  def checkForClashes(
      characterNames: List[String],
      toCheck: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): F[Unit] = {
    for
      mainSegments <- repo.getOnlineTimes(characterNames, from, to)
      toCheckSegments <- repo.getOnlineTimes(toCheck, from, to)
      _ <- Logger[F].info(s"${toCheckSegments.length} rows to analyse from ${mainSegments.length} segments")
      adj <- Async[F].blocking(getAdjacencies(mainSegments, toCheckSegments, includeClashes = true, distance = 0))
      results <- adj.map(a => repo.getCharacterName(a.characterId).map { i => a.copy(characterName = Some(i)) })
        .sequence
      _ <- results.map(i => Logger[F].info(i.toString)).sequence
    yield ()
  }

  def findClashes(
      characterNames: List[String],
      toCheck: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Int
  ): F[ClashResults] = {
    for
      mainSegments <- repo.getOnlineTimes(characterNames, from, to)
      toCheckSegments <- repo.getOnlineTimes(toCheck, from, to)
      adj <- Async[F].blocking {
        getAdjacencies(mainSegments, toCheckSegments, includeClashes = true, distance)
          .filter(_.clashes > 0)
          .sortBy(a => (-a.clashes, -a.adjacencies))
          .take(30)
      }
      clashes <- adj.map(a => repo.getCharacterName(a.characterId).map(n => a.copy(characterName = Some(n)))).sequence
    yield ClashResults(characterNames, toCheck, from, to, clashes)
  }

  def compareCharacters(
      characterA: String,
      characterB: String,
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Int
  ): F[CompareResults] = {
    for
      aSegments <- repo.getOnlineTimes(List(characterA), from, to)
      bSegments <- repo.getOnlineTimes(List(characterB), from, to)
      aToBAdj <- Async[F].blocking(getAdjacencies(aSegments, bSegments, includeClashes = true, distance)
        .headOption.getOrElse(CharacterAdjacencies(-1, Some(characterB), 0, 0, bSegments.length, 0, 0, 0, false, false, Nil)))
      bToAAdj <- Async[F].blocking(getAdjacencies(bSegments, aSegments, includeClashes = true, distance)
        .headOption.getOrElse(CharacterAdjacencies(-1, Some(characterA), 0, 0, aSegments.length, 0, 0, 0, false, false, Nil)))
    yield CompareResults(aToBAdj, bToAAdj)
  }

  def checkTradedCharacters(characterNames: List[String], lookbackDays: Int): F[List[CharacterTradeStatus]] = {
    val cutoff = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusDays(lookbackDays.toLong)
    characterNames.distinct.map { name =>
      for {
        past <- repo.getPastCharacterNames(name)
        allNames = (name :: past).distinct
        sales <- bazaarScraper.multipleCharacterSales(allNames)
      } yield {
        sales.saleDates match {
          case Left(_) =>
            CharacterTradeStatus(name, allNames, Nil, hadError = true)
          case Right(dates) =>
            val filtered = dates.filter(_.isAfter(cutoff)).map(_.toLocalDate).distinct.sorted.reverse
            CharacterTradeStatus(name, allNames, filtered, hadError = false)
        }
      }
    }.parSequence
  }

  def saveLastSearch(
      characters: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Option[Int],
      includeClashes: Boolean
  ): F[Unit] = {
    repo.saveLastSearch(LastSearch(characters, from, to, distance, includeClashes))
  }

  def getLastSearch: F[Option[LastSearch]] = repo.getLastSearch

  private[service] def getAdjacencies(
      mainHistory: List[OnlineSegment],
      others: List[OnlineSegment],
      includeClashes: Boolean,
      distance: Int
  ): List[CharacterAdjacencies] = {
    val characterHistories = others.groupBy(_.characterId).toList
      .map { case (id, segs) =>
        val arr = segs.toArray
        CharacterLoginHistory(id, arr.sortBy(_.start), arr.sortBy(_.end))
      }
    val mhArray = mainHistory.toArray.sortBy(_.start)

    characterHistories.flatMap { h =>
      val clashes =
        if (includeClashes) { countClashes(mhArray, h.segments) }
        else {
          val clashes = hasClashes(mhArray, h.segments)
          if (clashes) -1 else 0
        }
      if clashes == 0 || includeClashes then
        Some(CharacterAdjacencies(
          h.characterId,
          None,
          countAdjacencies(mhArray, h.segments, h.segmentsByEnd, distance),
          clashes,
          h.segments.length,
          computeSessionSimilarity(mhArray, h.segments),
          0,
          0,
          false,
          true,
          Nil
        ))
      else None
    }.sortBy(-_.adjacencies)
  }

  def hasClashes(mainHistory: Array[OnlineSegment], other: Array[OnlineSegment]): Boolean = {
    // Using two sliding pointers to check more efficiently
    var i = 0
    var j = 0
    while (i < mainHistory.length && j < other.length) {
      val mi = mainHistory(i)
      val oj = other(j)

      if (oj.start < mi.end && mi.start < oj.end) return true

      // Increment the earliest pointer, keeping them kind of in sync
      if (mi.end < oj.end) i += 1 else j += 1
    }

    false
  }

  def countClashes(mainHistory: Array[OnlineSegment], other: Array[OnlineSegment]): Int = {
    // Similar to hasClashes (sliding pointers)
    var i = 0
    var j = 0
    var count = 0
    while (i < mainHistory.length && j < other.length) {
      val mi = mainHistory(i)
      val oj = other(j)

      if (oj.start < mi.end && mi.start < oj.end) count += 1

      if (mi.end < oj.end) i += 1 else j += 1
    }

    count
  }

  // Distance is the acceptable gap in minutes between a logout and a login.
  // start/end in OnlineSegment are epoch seconds, so we multiply by 60 to convert.
  private def countAdjacencies(
      mainHistory: Array[OnlineSegment],
      other: Array[OnlineSegment],      // sorted by start
      otherByEnd: Array[OnlineSegment], // sorted by end
      distance: Int
  ): Int = {
    val distanceSecs = distance * 60L
    val otherStarts = other.map(_.start)
    val otherEnds   = otherByEnd.map(_.end)

    // Direction 1: o starts within distanceSecs after m ends (m.end <= o.start <= m.end + distanceSecs)
    val dir1 = mainHistory.count { m =>
      val lo = lowerBound(otherStarts, m.end)
      lo < other.length && otherStarts(lo) <= m.end + distanceSecs
    }

    // Direction 2: o ends within distanceSecs before m starts (m.start - distanceSecs <= o.end <= m.start)
    val dir2 = mainHistory.count { m =>
      val lo = lowerBound(otherEnds, m.start - distanceSecs)
      lo < otherByEnd.length && otherEnds(lo) <= m.start
    }

    dir1 + dir2
  }

  // Returns the first index i in arr where arr(i) >= value (lower bound).
  private def lowerBound(arr: Array[Long], value: Long): Int = {
    var lo = 0
    var hi = arr.length
    while (lo < hi) {
      val mid = (lo + hi) >>> 1
      if (arr(mid) < value) lo = mid + 1
      else hi = mid
    }
    lo
  }

  private case class CandidateTradeInfo(tradeMap: Map[String, List[LocalDate]], errorCount: Int)

  private def enrichWithCandidateTrades(
      candidates: List[CharacterAdjacencies]
  ): F[CandidateTradeInfo] = {
    val names = candidates.flatMap(_.characterName).distinct.take(candidateTradeCheckLimit)
    if names.isEmpty then Async[F].pure(CandidateTradeInfo(Map.empty, 0))
    else {
      val cutoff = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusDays(tradeLookbackDays.toLong)
      names.map { name =>
        repo.getPastCharacterNames(name).flatMap { past =>
          val allNames = (name :: past).distinct
          bazaarScraper.multipleCharacterSales(allNames).map { sales =>
            sales.saleDates match
              case Left(_) => (name, Left(()))
              case Right(dates) =>
                val filtered = dates.filter(_.isAfter(cutoff)).map(_.toLocalDate).distinct.sorted
                (name, Right(filtered))
          }
        }
      }.parSequence.map { results =>
        val errors = results.count(_._2.isLeft)
        val tradeMap = results.collect { case (name, Right(dates)) if dates.nonEmpty => name -> dates }.toMap
        CandidateTradeInfo(tradeMap, errors)
      }
    }
  }

  private def addTradeAndConfidence(
      adj: CharacterAdjacencies,
      tradeMap: Map[String, List[LocalDate]],
      mainLogins: Int = 0
  ): CharacterAdjacencies = {
    val trades = adj.characterName.flatMap(name => tradeMap.get(name)).getOrElse(Nil)
    // Evidence passes when either the candidate or the searched character has enough logins.
    // This ensures hidden characters (few DB records) are not penalised when the searched
    // character itself provides a sufficient data baseline.
    val evidencePassed = (adj.logins >= minEvidenceLogins || mainLogins >= minEvidenceLogins) && adj.adjacencies >= minEvidenceAdjacencies
    val confidence = computeConfidence(adj.adjacencies, adj.clashes, adj.logins, adj.sessionSimilarity, trades.nonEmpty)
    val hiddenScore = computeHiddenScore(adj.adjacencies, adj.clashes, adj.logins)
    val clashRatio =
      if (adj.adjacencies <= 0) Double.PositiveInfinity
      else math.max(0, adj.clashes).toDouble / adj.adjacencies.toDouble
    val hiddenLikely =
      evidencePassed &&
      hiddenScore >= hiddenLikelyMinScore &&
        adj.adjacencies >= hiddenLikelyMinAdjacencies &&
        clashRatio <= hiddenLikelyMaxClashRatio
    val cappedConfidence = if (evidencePassed) confidence else math.min(confidence, 45)
    adj.copy(
      confidence = cappedConfidence,
      hiddenScore = hiddenScore,
      hiddenLikely = hiddenLikely,
      evidencePassed = evidencePassed,
      recentTradeDates = trades
    )
  }

  private[service] def computeConfidence(
      adjacencies: Int,
      clashes: Int,
      logins: Int,
      sessionSimilarity: Int,
      recentTrade: Boolean
  ): Int = {
    val loginCount = math.max(1, logins)
    val adjacencyRatio = math.min(1.0, adjacencies.toDouble / loginCount.toDouble)
    val clashRatio =
      if (clashes < 0) 1.0 else math.min(1.0, clashes.toDouble / loginCount.toDouble)
    val base = adjacencyRatio * 70.0
    val volume = math.min(20.0, loginCount.toDouble * 0.5)
    val sessionBonus = math.min(15.0, sessionSimilarity.toDouble * 0.15)
    val penalty = clashRatio * 40.0
    val bonus = if (recentTrade) 10.0 else 0.0
    val raw = base + volume + sessionBonus - penalty + bonus
    math.max(0, math.min(100, raw)).round.toInt
  }

  private[service] def computeHiddenScore(adjacencies: Int, clashes: Int, logins: Int): Int = {
    val loginCount = math.max(1, logins)
    val adjacencyRatio = math.min(1.0, adjacencies.toDouble / loginCount.toDouble)
    val volume = math.min(20.0, loginCount.toDouble * 0.8)
    val clashPenalty =
      if (clashes <= 0) 0.0
      else math.min(55.0, clashes.toDouble * 6.0)

    val raw = (adjacencyRatio * 80.0) + volume - clashPenalty
    math.max(0, math.min(100, raw)).round.toInt
  }

  private[service] def computeSessionSimilarity(mainHistory: Array[OnlineSegment], other: Array[OnlineSegment]): Int = {
    val mainDurations = mainHistory.map(s => math.max(1L, s.end - s.start))
    val otherDurations = other.map(s => math.max(1L, s.end - s.start))
    if (mainDurations.isEmpty || otherDurations.isEmpty) 0
    else {
      val mainAvg = mainDurations.sum.toDouble / mainDurations.length.toDouble
      val otherAvg = otherDurations.sum.toDouble / otherDurations.length.toDouble
      val maxAvg = math.max(1.0, math.max(mainAvg, otherAvg))
      val diffRatio = math.abs(mainAvg - otherAvg) / maxAvg
      val similarity = (100.0 * (1.0 - diffRatio)).round.toInt
      math.max(0, math.min(100, similarity))
    }
  }

}

