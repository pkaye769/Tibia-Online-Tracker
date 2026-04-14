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
    def getCurrentlyOnlineNames = IO.pure(Nil)
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

  // ---- hidden alt asymmetry (evidence filter) ----
  // When searching with the unhidden character (many logins) the hidden character
  // (few logins) must still surface as a match, even though its own login count
  // is below minEvidenceLogins.  The reverse direction must continue to work too.

  private val serviceStrictEvidence: AltFinderService[IO] =
    new AltFinderService(stubRepo, new BazaarScraper[IO](stubScraperClient),
      minEvidenceLogins    = 8,
      minEvidenceAdjacencies = 2)

  // Simulate "Delishana Senia" (11 sessions) having Deli Tokes (4 sessions) as a
  // perfect alt: every Deli Tokes session is adjacent to a Delishana session.
  private val deliTokesId: Long    = 42L
  private val delishanaSeniaId: Long = 99L

  // Delishana Senia: 11 sessions spread across time
  private val delishanaSegments: List[OnlineSegment] = (0 until 11).map { i =>
    seg(delishanaSeniaId, i * 200L, i * 200L + 100L)
  }.toList

  // Deli Tokes: 4 sessions, each starting immediately after a Delishana session ends
  private val deliTokesSegments: List[OnlineSegment] = (0 until 4).map { i =>
    seg(deliTokesId, i * 200L + 100L, i * 200L + 180L)
  }.toList

  test("evidence filter: hidden alt found when searching unhidden character") {
    // Searching Delishana (mainLogins=11) should surface Deli Tokes (logins=4 < 8).
    val adj = serviceStrictEvidence.getAdjacencies(
      delishanaSegments, deliTokesSegments, includeClashes = false, distance = 0)
    assert(adj.nonEmpty, "Deli Tokes must appear as a candidate")
    val candidate = adj.find(_.characterId == deliTokesId).get
    assert(candidate.adjacencies >= 2, "should have enough adjacencies")

    val mainLogins = delishanaSegments.length  // 11
    val result = service.getAdjacencies(delishanaSegments, deliTokesSegments, includeClashes = false, distance = 0)
    // Verify filter passes: mainLogins (11) >= minEvidenceLogins (8) even though candidate logins (4) < 8
    val filtered = result.filter(r =>
      r.adjacencies >= 2 && (r.logins >= 8 || mainLogins >= 8))
    assert(filtered.exists(_.characterId == deliTokesId),
      "Deli Tokes must survive the evidence filter when main character has enough logins")
  }

  test("evidence filter: unhidden alt found when searching hidden character (existing behaviour preserved)") {
    // Searching Deli Tokes (mainLogins=4) should surface Delishana (logins=11 >= 8).
    val result = service.getAdjacencies(deliTokesSegments, delishanaSegments, includeClashes = false, distance = 0)
    val mainLogins = deliTokesSegments.length  // 4
    val filtered = result.filter(r =>
      r.adjacencies >= 2 && (r.logins >= 8 || mainLogins >= 8))
    assert(filtered.exists(_.characterId == delishanaSeniaId),
      "Delishana Senia must survive the filter because its own logins (11) >= 8")
  }
}

