package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper.*

import java.time.{Instant, ZonedDateTime, ZoneId}

class BazaarScraperSpec extends munit.FunSuite {

  private val noopClient: BazaarScraperClientAlg[IO] = new BazaarScraperClientAlg[IO] {
    def searchCharacter(name: String) = IO.pure("""{"page":[]}""")
    def cooldownRemainingSeconds      = IO.pure(0L)
  }

  private def clientReturning(json: String): BazaarScraperClientAlg[IO] = new BazaarScraperClientAlg[IO] {
    def searchCharacter(name: String) = IO.pure(json)
    def cooldownRemainingSeconds      = IO.pure(0L)
  }

  private val scraper = new BazaarScraper[IO](noopClient)

  // ---- CharacterSalesList ----

  private val zone = ZoneId.of("Europe/Berlin")

  private def zdt(epochSecond: Long): ZonedDateTime =
    Instant.ofEpochSecond(epochSecond).atZone(zone)

  test("CharacterSalesList.latestSale returns None when list is empty") {
    val csl = CharacterSalesList(Nil)
    assertEquals(csl.latestSale, None)
  }

  test("CharacterSalesList.latestSale returns None when all sales have errors") {
    val csl = CharacterSalesList(List(
      CharacterSales("A", Left(BazaarScraperError("err")))
    ))
    assertEquals(csl.latestSale, None)
  }

  test("CharacterSalesList.latestSale returns the latest ZonedDateTime across all characters") {
    val early = zdt(1000L)
    val late  = zdt(9000L)
    val csl = CharacterSalesList(List(
      CharacterSales("A", Right(List(early))),
      CharacterSales("B", Right(List(late, early)))
    ))
    assertEquals(csl.latestSale, Some(late))
  }

  test("CharacterSalesList.allSales flattens all sale dates, ignoring errors") {
    val d1 = zdt(1000L)
    val d2 = zdt(2000L)
    val csl = CharacterSalesList(List(
      CharacterSales("A", Right(List(d1))),
      CharacterSales("B", Left(BazaarScraperError("err"))),
      CharacterSales("C", Right(List(d2)))
    ))
    assertEquals(csl.allSales.toSet, Set(d1, d2))
  }

  test("CharacterSalesList.numberOfErrors counts error entries") {
    val csl = CharacterSalesList(List(
      CharacterSales("A", Right(Nil)),
      CharacterSales("B", Left(BazaarScraperError("err1"))),
      CharacterSales("C", Left(BazaarScraperError("err2")))
    ))
    assertEquals(csl.numberOfErrors, 2)
  }

  test("CharacterSalesList.numberOfErrors returns 0 when no errors") {
    val csl = CharacterSalesList(List(
      CharacterSales("A", Right(Nil)),
      CharacterSales("B", Right(Nil))
    ))
    assertEquals(csl.numberOfErrors, 0)
  }

  // ---- BazaarScraper.latestSale (companion object) ----

  test("BazaarScraper.latestSale returns None when all are errors") {
    val sales = List(CharacterSales("A", Left(BazaarScraperError("err"))))
    assertEquals(BazaarScraper.latestSale(sales), None)
  }

  test("BazaarScraper.latestSale returns None when list is empty") {
    assertEquals(BazaarScraper.latestSale(Nil), None)
  }

  test("BazaarScraper.latestSale returns max across all successes") {
    val early = zdt(1000L)
    val late  = zdt(9000L)
    val sales = List(
      CharacterSales("A", Right(List(early))),
      CharacterSales("B", Right(List(late)))
    )
    assertEquals(BazaarScraper.latestSale(sales), Some(late))
  }

  // ---- parseJson (via package-private access) ----

  test("parseJson returns empty list for page with no matching name") {
    val json = """{"page":[{"nickname":"OtherChar","auctionEnd":1000,"hasBeenBidded":true,"auctionId":1}]}"""
    val result = scraper.parseJson(json, "MyChar")
    assertEquals(result, Right(Nil))
  }

  test("parseJson returns empty list when hasBeenBidded is false") {
    val json = """{"page":[{"nickname":"MyChar","auctionEnd":1000,"hasBeenBidded":false,"auctionId":1}]}"""
    val result = scraper.parseJson(json, "MyChar")
    assertEquals(result, Right(Nil))
  }

  test("parseJson matches name case-insensitively") {
    val json = """{"page":[{"nickname":"mychar","auctionEnd":1000,"hasBeenBidded":true,"auctionId":1}]}"""
    val result = scraper.parseJson(json, "MyChar")
    assert(result.isRight)
    assert(result.toOption.get.nonEmpty)
  }

  test("parseJson returns a BazaarAuction with correct end timestamp") {
    val epochSec = 1700000000L
    val json = s"""{"page":[{"nickname":"Hero","auctionEnd":$epochSec,"hasBeenBidded":true,"auctionId":42}]}"""
    val result = scraper.parseJson(json, "Hero")
    assert(result.isRight)
    val auctions = result.toOption.get
    assertEquals(auctions.length, 1)
    assertEquals(auctions.head.end, Instant.ofEpochSecond(epochSec))
    assertEquals(auctions.head.auctionId, Some(42L))
  }

  test("parseJson returns Left on invalid JSON") {
    val result = scraper.parseJson("not-json", "Hero")
    assert(result.isLeft)
  }

  test("parseJson returns Left when page field is missing") {
    val result = scraper.parseJson("""{"other":[]}""", "Hero")
    assert(result.isLeft)
  }

  test("parseJson returns Left when page field is not an array") {
    val result = scraper.parseJson("""{"page":"wrong"}""", "Hero")
    assert(result.isLeft)
  }

  test("parseJson returns multiple auctions when several match") {
    val json =
      """{"page":[
        |  {"nickname":"Hero","auctionEnd":1000,"hasBeenBidded":true,"auctionId":1},
        |  {"nickname":"Hero","auctionEnd":2000,"hasBeenBidded":true,"auctionId":2},
        |  {"nickname":"Other","auctionEnd":3000,"hasBeenBidded":true,"auctionId":3}
        |]}""".stripMargin
    val result = scraper.parseJson(json, "Hero")
    assert(result.isRight)
    assertEquals(result.toOption.get.length, 2)
  }

  // ---- multipleCharacterSales ----

  test("multipleCharacterSales returns Right(Nil) when page is empty") {
    val s = new BazaarScraper[IO](noopClient)
    val result = s.multipleCharacterSales(List("Hero")).unsafeRunSync()
    assertEquals(result.saleDates, Right(Nil))
  }

  test("multipleCharacterSales returns Left on HTTP error") {
    val errorClient: BazaarScraperClientAlg[IO] = new BazaarScraperClientAlg[IO] {
      def searchCharacter(name: String) = IO.raiseError(new RuntimeException("network error"))
      def cooldownRemainingSeconds      = IO.pure(0L)
    }
    val s = new BazaarScraper[IO](errorClient)
    val result = s.multipleCharacterSales(List("Hero")).unsafeRunSync()
    assert(result.saleDates.isLeft)
  }

  test("multipleCharacterSales returns sale date when auction matches") {
    val epochSec = 1700000000L
    val json = s"""{"page":[{"nickname":"Hero","auctionEnd":$epochSec,"hasBeenBidded":true,"auctionId":1}]}"""
    val s = new BazaarScraper[IO](clientReturning(json))
    val result = s.multipleCharacterSales(List("Hero")).unsafeRunSync()
    assert(result.saleDates.isRight)
    assert(result.saleDates.toOption.get.nonEmpty)
  }
}
