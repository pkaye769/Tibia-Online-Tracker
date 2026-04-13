package com.pamelak.onlinetracker.altfinder.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.pamelak.onlinetracker.altfinder.bazaarscraper.{BazaarScraper, BazaarScraperClientAlg}

class BazaarScraperSpec extends munit.FunSuite {

  private val auctionEndEpoch = 1_700_000_000L // arbitrary fixed timestamp
  private val auctionJson =
    s"""{"page":[{"nickname":"Alice","auctionEnd":$auctionEndEpoch,"hasBeenBidded":true,"auctionId":1}]}"""

  private def clientAlwaysReturns(response: IO[String]): BazaarScraperClientAlg[IO] =
    new BazaarScraperClientAlg[IO] {
      def searchCharacter(name: String): IO[String] = response
      def cooldownRemainingSeconds: IO[Long]         = IO.pure(0L)
    }

  private def clientPerName(f: String => IO[String]): BazaarScraperClientAlg[IO] =
    new BazaarScraperClientAlg[IO] {
      def searchCharacter(name: String): IO[String] = f(name)
      def cooldownRemainingSeconds: IO[Long]         = IO.pure(0L)
    }

  test("multipleCharacterSales returns Right with dates when all lookups succeed") {
    val scraper = new BazaarScraper[IO](clientAlwaysReturns(IO.pure(auctionJson)))
    val result  = scraper.multipleCharacterSales(List("Alice")).unsafeRunSync()
    assert(result.saleDates.isRight)
    assert(result.saleDates.toOption.get.nonEmpty)
  }

  test("multipleCharacterSales returns Right(empty) when no auctions are found") {
    val scraper = new BazaarScraper[IO](clientAlwaysReturns(IO.pure("""{"page":[]}""")))
    val result  = scraper.multipleCharacterSales(List("Alice")).unsafeRunSync()
    assertEquals(result.saleDates, Right(Nil))
  }

  test("multipleCharacterSales returns Right(empty) when all lookups fail (e.g. network error)") {
    val scraper = new BazaarScraper[IO](clientAlwaysReturns(IO.raiseError(new RuntimeException("network error"))))
    val result  = scraper.multipleCharacterSales(List("Alice", "OldAlice")).unsafeRunSync()
    // Errors must NOT be propagated — callers receive empty dates, not Left
    assertEquals(result.saleDates, Right(Nil))
  }

  test("multipleCharacterSales returns Right with partial dates when only some lookups fail") {
    // "Alice" succeeds, "OldAlice" fails with a network error
    val client = clientPerName {
      case "Alice"    => IO.pure(auctionJson)
      case _          => IO.raiseError(new RuntimeException("network error"))
    }
    val scraper = new BazaarScraper[IO](client)
    val result  = scraper.multipleCharacterSales(List("Alice", "OldAlice")).unsafeRunSync()
    assert(result.saleDates.isRight, "partial failure must not produce Left")
    assert(result.saleDates.toOption.get.nonEmpty, "dates from the successful lookup must be preserved")
  }
}
