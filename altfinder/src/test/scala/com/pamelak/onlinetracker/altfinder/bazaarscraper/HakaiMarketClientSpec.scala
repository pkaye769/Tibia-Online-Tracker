package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import java.time.Instant
import java.time.ZoneId

class HakaiMarketClientSpec extends munit.FunSuite {

  private val clientImpl = new HakaiMarketHttp4sClient[IO](null) // HTTP client not used in unit tests

  private val zone = ZoneId.of("Europe/Berlin")

  // ── parseIsoOrHuman ───────────────────────────────────────────────────────

  test("parseIsoOrHuman parses ISO-8601 UTC timestamp") {
    val result = clientImpl.parseIsoOrHuman("2024-01-15T10:00:00Z")
    assert(result.isDefined)
    assertEquals(result.get, Instant.parse("2024-01-15T10:00:00Z"))
  }

  test("parseIsoOrHuman parses human-readable date (MMM dd yyyy HH:mm:ss)") {
    val result = clientImpl.parseIsoOrHuman("Jan 15 2024 10:00:00")
    assert(result.isDefined)
    val expected = java.time.LocalDateTime
      .of(2024, 1, 15, 10, 0, 0)
      .atZone(zone)
      .toInstant
    assertEquals(result.get, expected)
  }

  test("parseIsoOrHuman returns None for non-date text") {
    assertEquals(clientImpl.parseIsoOrHuman("Hero"), None)
    assertEquals(clientImpl.parseIsoOrHuman(""), None)
    assertEquals(clientImpl.parseIsoOrHuman("12345"), None)
  }

  // ── parseJsonResponse ─────────────────────────────────────────────────────

  test("parseJsonResponse returns empty list for malformed JSON") {
    assertEquals(clientImpl.parseJsonResponse("not-json", "Hero"), Nil)
  }

  test("parseJsonResponse returns empty list when character name does not match") {
    val json = """[{"name":"OtherChar","soldAt":1705312000}]"""
    assertEquals(clientImpl.parseJsonResponse(json, "Hero"), Nil)
  }

  test("parseJsonResponse parses epoch-second soldAt field") {
    val epoch = 1705312000L
    val json  = s"""[{"name":"Hero","soldAt":$epoch}]"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.ofEpochSecond(epoch)))
  }

  test("parseJsonResponse parses ISO string soldAt field") {
    val json = """[{"name":"Hero","soldAt":"2024-01-15T10:00:00Z"}]"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.parse("2024-01-15T10:00:00Z")))
  }

  test("parseJsonResponse is case-insensitive for character name") {
    val epoch = 1705312000L
    val json  = s"""[{"name":"HERO","soldAt":$epoch}]"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.ofEpochSecond(epoch)))
  }

  test("parseJsonResponse reads from data wrapper key") {
    val epoch = 1705312000L
    val json  = s"""{"data":[{"name":"Hero","soldAt":$epoch}]}"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.ofEpochSecond(epoch)))
  }

  test("parseJsonResponse reads from results wrapper key") {
    val epoch = 1705312000L
    val json  = s"""{"results":[{"name":"Hero","soldAt":$epoch}]}"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.ofEpochSecond(epoch)))
  }

  test("parseJsonResponse falls back to sold_at snake_case field") {
    val epoch = 1705312000L
    val json  = s"""[{"name":"Hero","sold_at":$epoch}]"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.ofEpochSecond(epoch)))
  }

  test("parseJsonResponse falls back to auctionEnd field") {
    val epoch = 1705312000L
    val json  = s"""[{"name":"Hero","auctionEnd":$epoch}]"""
    val result = clientImpl.parseJsonResponse(json, "Hero")
    assertEquals(result, List(Instant.ofEpochSecond(epoch)))
  }

  test("parseJsonResponse returns empty list for empty array") {
    assertEquals(clientImpl.parseJsonResponse("[]", "Hero"), Nil)
  }

  // ── parseHtmlResponse ─────────────────────────────────────────────────────

  test("parseHtmlResponse returns empty list for empty HTML") {
    assertEquals(clientImpl.parseHtmlResponse("", "Hero"), Nil)
  }

  test("parseHtmlResponse returns empty list when no dates present") {
    val html = "<table><tr><td>Hero</td><td>Nefera</td></tr></table>"
    assertEquals(clientImpl.parseHtmlResponse(html, "Hero"), Nil)
  }

  test("parseHtmlResponse extracts ISO date from a row containing the character name") {
    val html =
      """<table>
        |  <tr><td>Hero</td><td>2024-01-15T10:00:00Z</td></tr>
        |  <tr><td>OtherChar</td><td>2024-03-20T10:00:00Z</td></tr>
        |</table>""".stripMargin
    val result = clientImpl.parseHtmlResponse(html, "Hero")
    assertEquals(result, List(Instant.parse("2024-01-15T10:00:00Z")))
  }

  test("parseHtmlResponse is case-insensitive for character name matching") {
    val html =
      """<table>
        |  <tr><td>HERO</td><td>2024-01-15T10:00:00Z</td></tr>
        |</table>""".stripMargin
    val result = clientImpl.parseHtmlResponse(html, "hero")
    assertEquals(result.length, 1)
  }

  test("parseHtmlResponse collects all dates when no name-matched rows (scoped page)") {
    // A page that is already scoped to one character may not repeat the name in each row
    val html =
      """<table>
        |  <tr><td>2024-01-15T10:00:00Z</td></tr>
        |  <tr><td>2024-03-20T10:00:00Z</td></tr>
        |</table>""".stripMargin
    val result = clientImpl.parseHtmlResponse(html, "Hero")
    assertEquals(result.length, 2)
  }

  test("parseHtmlResponse deduplicates identical dates") {
    val html =
      """<table>
        |  <tr><td>Hero</td><td>2024-01-15T10:00:00Z</td></tr>
        |  <tr><td>Hero</td><td>2024-01-15T10:00:00Z</td></tr>
        |</table>""".stripMargin
    val result = clientImpl.parseHtmlResponse(html, "Hero")
    assertEquals(result.length, 1)
  }
}
