package com.pamelak.onlinetracker.tracker.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.pamelak.onlinetracker.tracker.repo.Model.*
import com.pamelak.onlinetracker.tracker.repo.OnlineTrackerRepoAlg
import com.pamelak.onlinetracker.tracker.tibiadata.TibiaDataClientAlg
import com.pamelak.onlinetracker.tracker.tibiadata.response.*

import java.time.OffsetDateTime
import scala.collection.mutable.ListBuffer

class OnlineTrackerServiceSpec extends munit.FunSuite {

  // ---- Helpers ----

  private val worldId   = 1L
  private val worldName = "Antica"

  private def makeApi = Api(3, "1.0", "abc")
  private def makeStatus = Status(200)

  private def makeInfo(timestamp: String) =
    Information(makeApi, timestamp, makeStatus)

  private def makeWorld(
      players: List[OnlinePlayers] = Nil,
      timestamp: String = "2024-06-01T10:00:00Z"
  ): WorldResponse =
    WorldResponse(
      World(
        worldName, "online", 100, 1000, "2000-01-01", "2002-01-01",
        "Europe", "Optional PvP", false, "regular", Nil, false, "", "Regular", "",
        Some(players)
      ),
      makeInfo(timestamp)
    )

  private def makeCharResponse(formerNames: List[String] = Nil): CharacterResponse =
    CharacterResponse(
      CharacterSheet(
        Character(
          "NewName",
          if (formerNames.isEmpty) None else Some(formerNames),
          "male", "", 0, "Knight", 100, 0, worldName, None, "Thais",
          None, None, None, None, "Premium Account"
        ),
        None, None
      ),
      makeInfo("2024-06-01T10:00:00Z")
    )

  /** Minimal stub repo; callers override the fields they care about via vars. */
  private class StubRepo(
      var latestSave: Option[OffsetDateTime]   = None,
      var onlinePlayers: List[OnlineNameTime]  = Nil,
      var existingChar: Option[CharacterRow]   = None
  ) extends OnlineTrackerRepoAlg[IO] {

    val insertedCharacters = ListBuffer.empty[String]
    val insertedOnline     = ListBuffer.empty[String]
    val deletedOnline      = ListBuffer.empty[String]
    val insertedHistory    = ListBuffer.empty[String]
    var saveTimeInserted   = false

    def ensureSchema                                                    = IO.unit
    def getWorld(name: String)                                          = IO.pure(WorldRow(worldId, name))
    def getOrCreateWorld(name: String)                                  = IO.pure(WorldRow(worldId, name))
    def getLatestSaveTime(wId: Long)                                    = IO.pure(latestSave)
    def getAllOnline(wId: Long)                                         = IO.pure(onlinePlayers)
    def getMaxSequenceId(wId: Long)                                     = IO.pure(Some(0L))
    def insertWorldSaveTime(w: WorldSaveTimeRow)                        = IO { saveTimeInserted = true; 1L }
    def insertCharacterNameHistory(r: CharacterNameHistoryRow)          = IO.unit
    def updateCharacterName(id: Long, name: String, t: OffsetDateTime) = IO.unit
    def insertCharacter(c: CharacterRow)                                = IO { insertedCharacters += c.name; () }
    def getCharacter(name: String)                                      = IO.pure(existingChar)
    def insertOnline(o: OnlineNameTime, wId: Long)                     = IO { insertedOnline += o.name; () }
    def deleteOnline(name: String, wId: Long)                          = IO { deletedOnline += name; () }
    def insertOnlineHistory(n: String, login: Long, logout: Long)      = IO { insertedHistory += n; () }
  }

  private class StubTibiaClient(
      worldResponse: WorldResponse,
      charResponse: CharacterResponse = makeCharResponse()
  ) extends TibiaDataClientAlg[IO] {
    def getWorld(world: String)     = IO.pure(worldResponse)
    def getCharacter(name: String)  = IO.pure(charResponse)
  }

  // ---- updateDataForWorld: skip / proceed ----

  test("updateDataForWorld skips update when latestSaveTime equals TibiaData timestamp") {
    val timestamp = "2024-06-01T10:00:00Z"
    val repo      = new StubRepo(latestSave = Some(OffsetDateTime.parse(timestamp)))
    val service   = new OnlineTrackerService[IO](repo, new StubTibiaClient(makeWorld(timestamp = timestamp)))
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(!repo.saveTimeInserted, "insertWorldSaveTime should NOT be called when timestamp is not newer")
  }

  test("updateDataForWorld skips update when latestSaveTime is after TibiaData timestamp") {
    val tdTimestamp = "2024-06-01T10:00:00Z"
    val dbTime      = OffsetDateTime.parse("2024-06-02T10:00:00Z") // db is newer
    val repo        = new StubRepo(latestSave = Some(dbTime))
    val service     = new OnlineTrackerService[IO](repo, new StubTibiaClient(makeWorld(timestamp = tdTimestamp)))
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(!repo.saveTimeInserted)
  }

  test("updateDataForWorld proceeds when latestSaveTime is None") {
    val repo    = new StubRepo(latestSave = None)
    val service = new OnlineTrackerService[IO](repo, new StubTibiaClient(makeWorld()))
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(repo.saveTimeInserted, "insertWorldSaveTime SHOULD be called when latestSaveTime is None")
  }

  test("updateDataForWorld proceeds when latestSaveTime is strictly before TibiaData timestamp") {
    val tdTimestamp = "2024-06-01T10:00:00Z"
    val dbTime      = OffsetDateTime.parse("2024-05-31T10:00:00Z") // db is older
    val repo        = new StubRepo(latestSave = Some(dbTime))
    val service     = new OnlineTrackerService[IO](repo, new StubTibiaClient(makeWorld(timestamp = tdTimestamp)))
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(repo.saveTimeInserted)
  }

  // ---- updateOnlineList: logged-on / logged-off diff ----

  test("players absent from TibiaData response are logged off and recorded in history") {
    // DB has Alice online; TibiaData returns nobody → Alice logged off
    val loginTime = 42L
    val repo = new StubRepo(
      latestSave    = None,
      onlinePlayers = List(OnlineNameTime("Alice", loginTime))
    )
    val service = new OnlineTrackerService[IO](repo, new StubTibiaClient(makeWorld(players = Nil)))
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(repo.deletedOnline.contains("Alice"))
    assert(repo.insertedHistory.contains("Alice"))
  }

  test("players new in TibiaData response are logged on and inserted into the online table") {
    // DB has nobody; TibiaData returns Bob → Bob logged on
    val repo = new StubRepo(latestSave = None, onlinePlayers = Nil)
    val service = new OnlineTrackerService[IO](
      repo,
      new StubTibiaClient(makeWorld(players = List(OnlinePlayers("Bob", 50, "Knight"))))
    )
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(repo.insertedOnline.contains("Bob"))
  }

  test("players present in both DB and TibiaData are neither deleted nor re-inserted") {
    // DB has Carol; TibiaData also has Carol → no change for Carol
    val repo = new StubRepo(
      latestSave    = None,
      onlinePlayers = List(OnlineNameTime("Carol", 10L))
    )
    val service = new OnlineTrackerService[IO](
      repo,
      new StubTibiaClient(makeWorld(players = List(OnlinePlayers("Carol", 100, "Paladin"))))
    )
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(!repo.deletedOnline.contains("Carol"))
    assert(!repo.insertedOnline.contains("Carol"))
  }

  test("a new character is inserted when it has no former names in TibiaData") {
    // Dave is new; getCharacter returns None; no former names → insert new character
    val repo = new StubRepo(latestSave = None, existingChar = None)
    val service = new OnlineTrackerService[IO](
      repo,
      new StubTibiaClient(
        makeWorld(players = List(OnlinePlayers("Dave", 10, "Druid"))),
        charResponse = makeCharResponse(formerNames = Nil)
      )
    )
    service.updateDataForWorld(worldName).unsafeRunSync()
    assert(repo.insertedCharacters.contains("NewName"))
  }
}
