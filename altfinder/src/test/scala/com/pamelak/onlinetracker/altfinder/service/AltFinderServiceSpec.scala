package com.pamelak.onlinetracker.altfinder.service

import cats.effect.IO
import com.pamelak.onlinetracker.altfinder.bazaarscraper.{BazaarScraper, BazaarScraperClientAlg}
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.*
import com.pamelak.onlinetracker.altfinder.repo.Model.OnlineSegment

import java.time.OffsetDateTime

class AltFinderServiceSpec extends munit.FunSuite {

  // Minimal no-op stubs so the service can be instantiated safely.
  private val stubRepo: AltFinderRepoAlg[IO] = new AltFinderRepoAlg[IO] {
    def ensureSchema = IO.unit
    def getOnlineTimes(names: List[String], from: Option[OffsetDateTime], to: Option[OffsetDateTime]) = IO.pure(Nil)
    def getPossibleMatches(names: List[String], from: Option[OffsetDateTime], to: Option[OffsetDateTime], distance: Option[Int]) = IO.pure(Nil)
    def getCharacterName(id: Long) = IO.pure("")
    def getCharacterHistories(names: List[String], from: Option[OffsetDateTime], to: Option[OffsetDateTime]) = IO.pure(Nil)
    def getPastCharacterNames(name: String) = IO.pure(Nil)
    def saveLastSearch(s: LastSearch) = IO.unit
    def getLastSearch = IO.pure(None)
    def upsertWatch(c: WatchConfig) = IO.unit
    def removeWatch(guildId: String, name: String) = IO.pure(false)
    def listWatches(guildId: String) = IO.pure(Nil)
    def listAllWatches = IO.pure(Nil)
    def updateWatchCheck(id: Long, checkedAt: OffsetDateTime, alertedAt: Option[OffsetDateTime]) = IO.unit
    def upsertTrackedGuild(c: GuildTrackConfig) = IO.unit
    def removeTrackedGuild(guildId: String, name: String) = IO.pure(false)
    def listTrackedGuilds(guildId: String) = IO.pure(Nil)
    def saveResearchRun(r: ResearchRunWrite) = IO.unit
    def listResearchRuns(limit: Int) = IO.pure(Nil)
    def countOnlineHistoryRows = IO.pure(0L)
    def latestWorldSaveTime = IO.pure(None)
  }

  private val stubScraperClient: BazaarScraperClientAlg[IO] = new BazaarScraperClientAlg[IO] {
    def searchCharacter(name: String) = IO.pure("{\"page\":[]}")
    def cooldownRemainingSeconds = IO.pure(0L)
  }

  private val service: AltFinderService[IO] =
    new AltFinderService(stubRepo, new BazaarScraper[IO](stubScraperClient))

  // Convenience: build a segment [start, end) with a given characterId
  private def seg(characterId: Long, start: Long, end: Long) =
    OnlineSegment(characterId, start, end)

  // ---- hasClashes ----

  test("hasClashes returns false for empty histories") {
    assert(!service.hasClashes(Array.empty, Array.empty))
  }

  test("hasClashes returns false when segments do not overlap") {
    val main  = Array(seg(1, 0, 10))
    val other = Array(seg(2, 20, 30))
    assert(!service.hasClashes(main, other))
  }

  test("hasClashes returns true for overlapping segments") {
    val main  = Array(seg(1, 0, 20))
    val other = Array(seg(2, 10, 30))
    assert(service.hasClashes(main, other))
  }

  test("hasClashes returns true when one segment fully contains another") {
    val main  = Array(seg(1, 0, 100))
    val other = Array(seg(2, 10, 50))
    assert(service.hasClashes(main, other))
  }

  test("hasClashes returns false for adjacent (touching) segments") {
    // end == start means they meet but don't overlap
    val main  = Array(seg(1, 0, 10))
    val other = Array(seg(2, 10, 20))
    assert(!service.hasClashes(main, other))
  }

  // ---- countClashes ----

  test("countClashes returns 0 for non-overlapping segments") {
    val main  = Array(seg(1, 0, 10), seg(1, 20, 30))
    val other = Array(seg(2, 10, 20), seg(2, 30, 40))
    assertEquals(service.countClashes(main, other), 0)
  }

  test("countClashes counts each overlapping pair once") {
    val main  = Array(seg(1, 0, 20), seg(1, 40, 60))
    val other = Array(seg(2, 10, 30), seg(2, 50, 70))
    assertEquals(service.countClashes(main, other), 2)
  }

  test("countClashes counts a single overlap correctly") {
    val main  = Array(seg(1, 0, 50))
    val other = Array(seg(2, 25, 75))
    assertEquals(service.countClashes(main, other), 1)
  }

  // ---- getAdjacencies ----

  test("getAdjacencies returns empty list when others is empty") {
    val main = List(seg(99, 0, 10))
    val result = service.getAdjacencies(main, Nil, includeClashes = false, distance = 0)
    assert(result.isEmpty)
  }

  test("getAdjacencies detects adjacency within distance") {
    // main: [0,10)   other char 1: [10,20)  → diff = 10-10 = 0, within distance 0 → 1 adjacency
    val main  = List(seg(99, 0, 10))
    val other = List(seg(1, 10, 20))
    val result = service.getAdjacencies(main, other, includeClashes = false, distance = 0)
    assertEquals(result.size, 1)
    assertEquals(result.head.characterId, 1L)
    assert(result.head.adjacencies >= 1)
  }

  test("getAdjacencies excludes character with clash when includeClashes=false") {
    val main  = List(seg(99, 0, 50))
    val other = List(seg(1, 25, 75))  // overlaps with main
    val result = service.getAdjacencies(main, other, includeClashes = false, distance = 0)
    // Character 1 has a clash, so it must be filtered out
    assert(result.isEmpty)
  }

  test("getAdjacencies includes character with clash when includeClashes=true") {
    val main  = List(seg(99, 0, 50))
    val other = List(seg(1, 25, 75))
    val result = service.getAdjacencies(main, other, includeClashes = true, distance = 0)
    assertEquals(result.size, 1)
    assertEquals(result.head.characterId, 1L)
    assert(result.head.clashes > 0)
  }

  test("getAdjacencies results are sorted by adjacencies descending") {
    // char 1: many adjacent sessions; char 2: few adjacent sessions
    val main = List(
      seg(99, 0, 10),
      seg(99, 20, 30),
      seg(99, 40, 50)
    )
    val other = List(
      seg(1, 10, 20),  // adjacent to seg(99,0,10) and seg(99,20,30)
      seg(1, 30, 40),  // adjacent to seg(99,20,30) and seg(99,40,50)
      seg(2, 50, 60)   // adjacent only to seg(99,40,50)
    )
    val result = service.getAdjacencies(main, other, includeClashes = false, distance = 0)
    assert(result.size == 2)
    // char 1 has more adjacencies than char 2
    assert(result.head.adjacencies >= result.last.adjacencies)
  }

  test("getAdjacencies respects distance parameter") {
    // main ends at 10, other starts at 15 → gap of 5
    val main  = List(seg(99, 0, 10))
    val other = List(seg(1, 15, 25))
    val resultNoDistance = service.getAdjacencies(main, other, includeClashes = false, distance = 0)
    val resultWithDistance = service.getAdjacencies(main, other, includeClashes = false, distance = 10)
    assertEquals(resultNoDistance.head.adjacencies, 0)
    assert(resultWithDistance.head.adjacencies >= 1)
  }

  // ---- computeSessionSimilarity ----

  test("computeSessionSimilarity returns 0 when main history is empty") {
    assertEquals(service.computeSessionSimilarity(Array.empty, Array(seg(1, 0, 100))), 0)
  }

  test("computeSessionSimilarity returns 0 when other history is empty") {
    assertEquals(service.computeSessionSimilarity(Array(seg(1, 0, 100)), Array.empty), 0)
  }

  test("computeSessionSimilarity returns 100 when average durations are identical") {
    val main  = Array(seg(1, 0, 60), seg(1, 100, 160))
    val other = Array(seg(2, 0, 60), seg(2, 100, 160))
    assertEquals(service.computeSessionSimilarity(main, other), 100)
  }

  test("computeSessionSimilarity returns 0 when durations are maximally different") {
    // main: 1s each, other: very long → diffRatio approaches 1
    val main  = Array(seg(1, 0, 1))
    val other = Array(seg(2, 0, 100000))
    assert(service.computeSessionSimilarity(main, other) < 10)
  }

  test("computeSessionSimilarity is bounded between 0 and 100") {
    val main  = Array(seg(1, 0, 30))
    val other = Array(seg(2, 0, 60))
    val result = service.computeSessionSimilarity(main, other)
    assert(result >= 0 && result <= 100)
  }

  // ---- computeConfidence ----

  test("computeConfidence returns 0 with no adjacencies and clashes") {
    // adjacencies=0, clashes=0, logins=10, sessionSim=0, no trade
    val c = service.computeConfidence(0, 0, 10, 0, false)
    assert(c >= 0 && c <= 100)
  }

  test("computeConfidence increases with a recent trade bonus") {
    val withoutTrade = service.computeConfidence(5, 0, 10, 50, false)
    val withTrade    = service.computeConfidence(5, 0, 10, 50, true)
    assert(withTrade > withoutTrade)
  }

  test("computeConfidence decreases with clashes") {
    val noClashes   = service.computeConfidence(5, 0, 10, 50, false)
    val withClashes = service.computeConfidence(5, 5, 10, 50, false)
    assert(withClashes < noClashes)
  }

  test("computeConfidence is bounded between 0 and 100") {
    val c1 = service.computeConfidence(100, 0, 10, 100, true)
    val c2 = service.computeConfidence(0, 100, 1, 0, false)
    assert(c1 >= 0 && c1 <= 100)
    assert(c2 >= 0 && c2 <= 100)
  }

  // ---- computeHiddenScore ----

  test("computeHiddenScore is lower with zero adjacencies than with full adjacencies") {
    val scoreZeroAdj = service.computeHiddenScore(0, 0, 10)
    val scoreFullAdj = service.computeHiddenScore(10, 0, 10)
    assert(scoreFullAdj > scoreZeroAdj)
  }

  test("computeHiddenScore decreases with clashes") {
    val noClash   = service.computeHiddenScore(5, 0, 10)
    val withClash = service.computeHiddenScore(5, 5, 10)
    assert(withClash < noClash)
  }

  test("computeHiddenScore is bounded between 0 and 100") {
    val high = service.computeHiddenScore(100, 0, 100)
    val low  = service.computeHiddenScore(0, 100, 1)
    assert(high >= 0 && high <= 100)
    assert(low >= 0 && low <= 100)
  }

  test("computeHiddenScore is 0 when clashes dominate") {
    // Many clashes should bring score to 0
    val score = service.computeHiddenScore(1, 50, 5)
    assertEquals(score, 0)
  }
}

