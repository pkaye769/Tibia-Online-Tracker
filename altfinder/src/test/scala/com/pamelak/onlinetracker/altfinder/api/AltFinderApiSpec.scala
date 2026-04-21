package com.pamelak.onlinetracker.altfinder.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.pamelak.onlinetracker.altfinder.bazaarscraper.{BazaarScraper, BazaarScraperClientAlg}
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.*
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import com.pamelak.onlinetracker.altfinder.service.AltFinderService.CharacterAdjacencies
import com.pamelak.onlinetracker.altfinder.tibiadata.TibiaDataClientAlg
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.implicits.*

import java.time.{LocalDate, OffsetDateTime}

class AltFinderApiSpec extends munit.FunSuite {

  // ---- Stubs ----------------------------------------------------------------

  private val stubRepo: AltFinderRepoAlg[IO] = new AltFinderRepoAlg[IO] {
    def ensureSchema                                                                                         = IO.unit
    def getOnlineTimes(names: List[String], from: Option[OffsetDateTime], to: Option[OffsetDateTime])       = IO.pure(Nil)
    def getPossibleMatches(names: List[String], from: Option[OffsetDateTime], to: Option[OffsetDateTime], distance: Option[Int]) = IO.pure(Nil)
    def getCharacterName(id: Long)                                                                          = IO.pure("Unknown")
    def getCharacterHistories(names: List[String], from: Option[OffsetDateTime], to: Option[OffsetDateTime]) = IO.pure(Nil)
    def getPastCharacterNames(name: String)                                                                 = IO.pure(Nil)
    def saveLastSearch(s: LastSearch)                                                                       = IO.unit
    def getLastSearch                                                                                       = IO.pure(None)
    def upsertWatch(c: WatchConfig)                                                                         = IO.unit
    def removeWatch(guildId: String, name: String)                                                         = IO.pure(false)
    def listWatches(guildId: String)                                                                        = IO.pure(Nil)
    def listAllWatches                                                                                       = IO.pure(Nil)
    def updateWatchCheck(id: Long, checkedAt: OffsetDateTime, alertedAt: Option[OffsetDateTime])           = IO.unit
    def upsertTrackedGuild(c: GuildTrackConfig)                                                            = IO.unit
    def removeTrackedGuild(guildId: String, name: String)                                                  = IO.pure(false)
    def listTrackedGuilds(guildId: String)                                                                 = IO.pure(Nil)
    def saveResearchRun(r: ResearchRunWrite)                                                                = IO.unit
    def listResearchRuns(limit: Int)                                                                        = IO.pure(Nil)
    def countOnlineHistoryRows                                                                              = IO.pure(42L)
    def countTotalLogins(names: List[String])                                                               = IO.pure(0)
    def latestWorldSaveTime                                                                                 = IO.pure(None)
    def getCurrentlyOnlineNames                                                                             = IO.pure(Nil)
  }

  private val stubScraperClient: BazaarScraperClientAlg[IO] = new BazaarScraperClientAlg[IO] {
    def searchCharacter(name: String)  = IO.pure("""{"page":[]}""")
    def searchWorld(world: String, pageSize: Int) = IO.pure("""{"page":[]}""")
    def cooldownRemainingSeconds       = IO.pure(0L)
  }

  private val stubTibiaClient: TibiaDataClientAlg[IO] = new TibiaDataClientAlg[IO] {
    def getWorld(world: String) =
      IO.pure(parse("""{"world":{"name":"Antica","status":"online"}}""").getOrElse(Json.Null))

    def getGuild(name: String) =
      IO.pure(parse(
        s"""{"guild":{"name":"$name","world":"Antica","members_total":1,"members_online":1,
           |"members":[{"name":"Hero","status":"online"}]}}""".stripMargin
      ).getOrElse(Json.Null))

    def getCharacter(name: String) =
      IO.pure(parse(
        s"""{"character":{"character":{"name":"$name","level":100.0,"vocation":"Knight",
           |"world":"Antica","sex":"male","former_names":null,"title":"",
           |"unlocked_titles":0,"achievement_points":0,"residence":"Thais",
           |"account_status":"Premium Account","last_login":null}}}""".stripMargin
      ).getOrElse(Json.Null))
  }

  private val stubService: AltFinderService[IO] =
    new AltFinderService[IO](stubRepo, new BazaarScraper[IO](stubScraperClient))

  private val api = new AltFinderApi[IO](stubService, stubRepo, stubTibiaClient, stubScraperClient)

  private val routes = api.routes

  /** Execute a GET request against the routes and return the response. */
  private def get(path: String): Response[IO] = {
    val uri     = Uri.unsafeFromString(path)
    val request = Request[IO](Method.GET, uri)
    routes.run(request).value.unsafeRunSync().getOrElse(Response.notFound)
  }

  private def bodyOf(resp: Response[IO]): String =
    resp.bodyText.compile.string.unsafeRunSync()

  private def jsonOf(resp: Response[IO]): Json =
    parse(bodyOf(resp)).getOrElse(Json.Null)

  // ---- GET / (UI) -----------------------------------------------------------

  test("GET / returns 200 with HTML content") {
    val resp = get("/")
    assertEquals(resp.status, Status.Ok)
    assert(bodyOf(resp).contains("<html"))
  }

  // ---- GET /api/altfinder/health -------------------------------------------

  test("GET /api/altfinder/health returns 200 with status ok") {
    val resp = get("/api/altfinder/health")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assertEquals(body.hcursor.get[String]("status").toOption, Some("ok"))
  }

  // ---- GET /api/altfinder/status -------------------------------------------

  test("GET /api/altfinder/status returns 200 with numeric fields") {
    val resp = get("/api/altfinder/status")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assert(body.hcursor.get[Long]("onlineHistoryRows").isRight)
    assert(body.hcursor.get[Long]("bazaarCooldownSeconds").isRight)
    assert(body.hcursor.get[Int]("queryCacheSize").isRight)
  }

  // ---- GET /api/altfinder/alts ---------------------------------------------

  test("GET /api/altfinder/alts without characters returns 400") {
    val resp = get("/api/altfinder/alts")
    assertEquals(resp.status, Status.BadRequest)
    val body = jsonOf(resp)
    assert(body.hcursor.get[List[String]]("details").toOption.getOrElse(Nil)
      .exists(_.contains("characters")))
  }

  test("GET /api/altfinder/alts with valid params returns 200") {
    val resp = get("/api/altfinder/alts?characters=Hero&distance=0&includeClashes=false")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assert(body.hcursor.get[List[Json]]("possibleMatches").isRight)
  }

  test("GET /api/altfinder/alts with negative distance returns 400") {
    val resp = get("/api/altfinder/alts?characters=Hero&distance=-1")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/alts with invalid format returns 400") {
    val resp = get("/api/altfinder/alts?characters=Hero&format=unknown")
    assertEquals(resp.status, Status.BadRequest)
    val body = jsonOf(resp)
    assert(body.hcursor.get[List[String]]("details").toOption.getOrElse(Nil)
      .exists(_.toLowerCase.contains("format")))
  }

  test("GET /api/altfinder/alts with malformed from date returns 400") {
    val resp = get("/api/altfinder/alts?characters=Hero&from=not-a-date")
    assertEquals(resp.status, Status.BadRequest)
    val body = jsonOf(resp)
    assert(body.hcursor.get[List[String]]("details").toOption.getOrElse(Nil)
      .exists(_.contains("YYYY-MM-DD")))
  }

  test("GET /api/altfinder/alts with classic format returns formattedText") {
    val resp = get("/api/altfinder/alts?characters=Hero&distance=0&includeClashes=false&format=classic")
    assertEquals(resp.status, Status.Ok)
    assert(jsonOf(resp).hcursor.get[String]("formattedText").isRight)
  }

  test("GET /api/altfinder/alts response includes searched characters") {
    val resp = get("/api/altfinder/alts?characters=Alpha,Beta&distance=0&includeClashes=false")
    assertEquals(resp.status, Status.Ok)
    val searched = jsonOf(resp).hcursor.get[List[String]]("searchedCharacters").toOption.getOrElse(Nil)
    assert(searched.contains("Alpha") && searched.contains("Beta"))
  }

  // ---- GET /api/altfinder/trades -------------------------------------------

  test("GET /api/altfinder/trades without characters uses world default and returns 200") {
    val resp = get("/api/altfinder/trades")
    assertEquals(resp.status, Status.Ok)
  }

  test("GET /api/altfinder/trades with valid params returns 200") {
    val resp = get("/api/altfinder/trades?characters=Hero")
    assertEquals(resp.status, Status.Ok)
    assert(jsonOf(resp).hcursor.get[List[Json]]("results").isRight)
  }

  test("GET /api/altfinder/trades with lookbackDays=0 returns 400") {
    val resp = get("/api/altfinder/trades?characters=Hero&lookbackDays=0")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/trades with lookbackDays=366 returns 400") {
    val resp = get("/api/altfinder/trades?characters=Hero&lookbackDays=366")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/trades with lookbackDays=30 returns 200") {
    val resp = get("/api/altfinder/trades?characters=Hero&lookbackDays=30")
    assertEquals(resp.status, Status.Ok)
    assertEquals(jsonOf(resp).hcursor.get[Int]("lookbackDays").toOption, Some(30))
  }

  // ---- GET /api/altfinder/clashes ------------------------------------------

  test("GET /api/altfinder/clashes without required params returns 400") {
    val resp = get("/api/altfinder/clashes")
    assertEquals(resp.status, Status.BadRequest)
    val details = jsonOf(resp).hcursor.get[List[String]]("details").toOption.getOrElse(Nil)
    assert(details.exists(_.contains("characters")))
    assert(details.exists(_.contains("targets")))
  }

  test("GET /api/altfinder/clashes without targets returns 400") {
    val resp = get("/api/altfinder/clashes?characters=Alice")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/clashes with valid params returns 200") {
    val resp = get("/api/altfinder/clashes?characters=Alice&targets=Bob&distance=0")
    assertEquals(resp.status, Status.Ok)
    assert(jsonOf(resp).hcursor.get[List[Json]]("clashes").isRight)
  }

  test("GET /api/altfinder/clashes response includes searched and checked characters") {
    val resp = get("/api/altfinder/clashes?characters=Alice&targets=Bob")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assertEquals(body.hcursor.get[List[String]]("searchedCharacters").toOption, Some(List("Alice")))
    assertEquals(body.hcursor.get[List[String]]("checkedCharacters").toOption, Some(List("Bob")))
  }

  // ---- GET /api/altfinder/research -----------------------------------------

  test("GET /api/altfinder/research returns 200 with a JSON array") {
    val resp = get("/api/altfinder/research")
    assertEquals(resp.status, Status.Ok)
    assert(jsonOf(resp).isArray)
  }

  test("GET /api/altfinder/research respects limit param") {
    val resp = get("/api/altfinder/research?limit=10")
    assertEquals(resp.status, Status.Ok)
  }

  // ---- GET /api/altfinder/watchlist ----------------------------------------

  test("GET /api/altfinder/watchlist without guildId returns 400") {
    val resp = get("/api/altfinder/watchlist")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/watchlist with guildId returns 200") {
    val resp = get("/api/altfinder/watchlist?guildId=guild123")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assertEquals(body.hcursor.get[String]("guildId").toOption, Some("guild123"))
    assert(body.hcursor.get[List[Json]]("watches").isRight)
  }

  // ---- GET /api/altfinder/watchlist/add ------------------------------------

  test("GET /api/altfinder/watchlist/add without required params returns 400") {
    val resp = get("/api/altfinder/watchlist/add")
    assertEquals(resp.status, Status.BadRequest)
    val details = jsonOf(resp).hcursor.get[List[String]]("details").toOption.getOrElse(Nil)
    assert(details.nonEmpty)
  }

  test("GET /api/altfinder/watchlist/add with only guildId returns 400") {
    val resp = get("/api/altfinder/watchlist/add?guildId=g")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/watchlist/add with all required params returns 200") {
    val resp = get("/api/altfinder/watchlist/add?guildId=g&channelId=c&character=Hero")
    assertEquals(resp.status, Status.Ok)
  }

  // ---- GET /api/altfinder/watchlist/remove ---------------------------------

  test("GET /api/altfinder/watchlist/remove without params returns 400") {
    val resp = get("/api/altfinder/watchlist/remove")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/watchlist/remove with valid params returns 200") {
    val resp = get("/api/altfinder/watchlist/remove?guildId=g&character=Hero")
    assertEquals(resp.status, Status.Ok)
    assertEquals(jsonOf(resp).hcursor.get[Boolean]("removed").toOption, Some(false))
  }

  // ---- GET /api/altfinder/character ----------------------------------------

  test("GET /api/altfinder/character without name returns 400") {
    val resp = get("/api/altfinder/character")
    assertEquals(resp.status, Status.BadRequest)
  }

  test("GET /api/altfinder/character with valid name returns 200") {
    val resp = get("/api/altfinder/character?name=Hero")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assert(body.hcursor.get[String]("name").isRight)
    assert(body.hcursor.get[String]("tibiaComUrl").toOption.getOrElse("").contains("tibia.com"))
    assert(body.hcursor.get[String]("exevopanUrl").toOption.getOrElse("").contains("exevopan.com"))
  }

  // ---- Unknown routes ------------------------------------------------------

  test("GET /api/altfinder/online returns 200 with count and names") {
    val resp = get("/api/altfinder/online")
    assertEquals(resp.status, Status.Ok)
    val body = jsonOf(resp)
    assert(body.hcursor.get[Int]("count").isRight)
    assert(body.hcursor.get[List[Json]]("names").isRight)
  }

  test("GET /api/altfinder/unknown returns 404") {
    val resp = get("/api/altfinder/unknown")
    assertEquals(resp.status, Status.NotFound)
  }

  // ==========================================================================
  // Pure helper unit tests (private[api])
  // ==========================================================================

  // ---- parseDate -----------------------------------------------------------

  test("parseDate parses a valid ISO date and sets time to 10:00 Berlin") {
    val result = api.parseDate("2024-06-01")
    assert(result.isRight)
    val dt = result.toOption.get
    assertEquals(dt.toLocalDate.toString, "2024-06-01")
  }

  test("parseDate returns Left for an invalid date string") {
    val result = api.parseDate("not-a-date")
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("YYYY-MM-DD"))
  }

  test("parseDate returns Left for a partial date") {
    val result = api.parseDate("2024-13")
    assert(result.isLeft)
  }

  // ---- buildDateRange ------------------------------------------------------

  test("buildDateRange returns 'Max range' when both None") {
    assertEquals(api.buildDateRange(None, None), "Max range")
  }

  test("buildDateRange returns 'Until ...' when only to is provided") {
    val to = OffsetDateTime.parse("2024-06-30T10:00:00Z")
    assertEquals(api.buildDateRange(None, Some(to)), "Until 2024-06-30")
  }

  test("buildDateRange returns 'From ...' when only from is provided") {
    val from = OffsetDateTime.parse("2024-01-01T10:00:00Z")
    assertEquals(api.buildDateRange(Some(from), None), "From 2024-01-01")
  }

  test("buildDateRange returns 'From ... until ...' when both provided") {
    val from = OffsetDateTime.parse("2024-01-01T10:00:00Z")
    val to   = OffsetDateTime.parse("2024-06-30T10:00:00Z")
    assertEquals(api.buildDateRange(Some(from), Some(to)), "From 2024-01-01 until 2024-06-30")
  }

  // ---- appendMinutes -------------------------------------------------------

  test("appendMinutes returns singular 'minute' for 1") {
    assertEquals(api.appendMinutes(1), "1 minute")
  }

  test("appendMinutes returns plural 'minutes' for 0") {
    assertEquals(api.appendMinutes(0), "0 minutes")
  }

  test("appendMinutes returns plural 'minutes' for values > 1") {
    assertEquals(api.appendMinutes(30), "30 minutes")
  }

  // ---- formatClassic -------------------------------------------------------

  private def adj(
      name: String,
      adjacencies: Int = 5,
      clashes: Int     = 0,
      logins: Int      = 10
  ): CharacterAdjacencies =
    CharacterAdjacencies(1L, Some(name), adjacencies, clashes, logins, 50, 60, 40, false, true, Nil)

  test("formatClassic produces 'name: adj / clashes / logins'") {
    assertEquals(api.formatClassic(adj("Hero", 5, 1, 10)), "Hero: 5 / 1 / 10")
  }

  test("formatClassic renders clashes=-1 as 'yes'") {
    assertEquals(api.formatClassic(adj("Hero", 5, -1, 10)), "Hero: 5 / yes / 10")
  }

  test("formatClassic uses 'Unknown' when characterName is None") {
    val a = CharacterAdjacencies(1L, None, 3, 0, 8, 50, 60, 30, false, true, Nil)
    assert(api.formatClassic(a).startsWith("Unknown"))
  }

  // ---- formatDetailed ------------------------------------------------------

  test("formatDetailed contains all key fields") {
    val a = adj("Hero", 5, 2, 10)
    val s = api.formatDetailed(a)
    assert(s.contains("Hero"))
    assert(s.contains("adj 5"))
    assert(s.contains("clashes 2"))
    assert(s.contains("logins 10"))
  }

  test("formatDetailed renders clashes=-1 as 'yes' in detail line") {
    val s = api.formatDetailed(adj("Hero", 5, -1, 10))
    assert(s.contains("clashes yes"))
  }

  test("formatDetailed shows 'traded' field") {
    val a = CharacterAdjacencies(1L, Some("Hero"), 5, 0, 10, 50, 60, 30, false, true,
      List(LocalDate.parse("2024-01-15")))
    val s = api.formatDetailed(a)
    assert(s.contains("2024-01-15"))
  }

  test("formatDetailed shows 'traded none' when no trade dates") {
    val s = api.formatDetailed(adj("Hero"))
    assert(s.contains("traded none"))
  }
}
