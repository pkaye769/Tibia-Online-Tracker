package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import java.time.Instant
import java.time.ZoneId

class GuildStatsClientSpec extends munit.FunSuite {

  private val client = new GuildStatsHttp4sClient[IO](null) // HTTP client not used in unit tests

  private val zone = ZoneId.of("Europe/Berlin")

  // ── parseDate ────────────────────────────────────────────────────────────

  test("parseDate parses a valid datetime string") {
    val result = client.parseDate("2024-01-15 10:00:00")
    assert(result.isDefined, "expected Some but got None")
    val expected = java.time.LocalDateTime
      .of(2024, 1, 15, 10, 0, 0)
      .atZone(zone)
      .toInstant
    assertEquals(result.get, expected)
  }

  test("parseDate returns None for empty string") {
    assertEquals(client.parseDate(""), None)
  }

  test("parseDate returns None for non-date text") {
    assertEquals(client.parseDate("Nefera"), None)
    assertEquals(client.parseDate("Sold"), None)
  }

  test("parseDate accepts trailing text by truncating to 19 chars") {
    // e.g. "2024-01-15 10:00:00 CET" – the timezone suffix should be ignored
    val result = client.parseDate("2024-01-15 10:00:00 CET")
    assert(result.isDefined)
  }

  // ── parseTabHtml ─────────────────────────────────────────────────────────

  private def buildRow(date: String): String =
    s"<tr><td>Sold</td><td>$date</td></tr>"

  test("parseTabHtml returns empty list for empty HTML") {
    assertEquals(client.parseTabHtml(""), Nil)
  }

  test("parseTabHtml returns empty list when no date cells present") {
    val html = "<table><tr><td>No dates here</td></tr></table>"
    assertEquals(client.parseTabHtml(html), Nil)
  }

  test("parseTabHtml extracts one date from a single row") {
    val html = s"<table>${buildRow("2024-01-15 10:00:00")}</table>"
    val result = client.parseTabHtml(html)
    assertEquals(result.length, 1)
    val expected = java.time.LocalDateTime
      .of(2024, 1, 15, 10, 0, 0)
      .atZone(zone)
      .toInstant
    assertEquals(result.head, expected)
  }

  test("parseTabHtml extracts multiple distinct dates") {
    val html = s"""<table>
      ${buildRow("2024-01-15 10:00:00")}
      ${buildRow("2024-03-20 10:00:00")}
    </table>"""
    val result = client.parseTabHtml(html)
    assertEquals(result.length, 2)
  }

  test("parseTabHtml deduplicates identical dates") {
    val html = s"""<table>
      ${buildRow("2024-01-15 10:00:00")}
      ${buildRow("2024-01-15 10:00:00")}
    </table>"""
    val result = client.parseTabHtml(html)
    assertEquals(result.length, 1)
  }

  test("parseTabHtml skips non-date cells without error") {
    val html = s"""<table>
      <tr><td>World: Nefera</td></tr>
      ${buildRow("2024-01-15 10:00:00")}
      <tr><td>Character: Hero</td></tr>
    </table>"""
    val result = client.parseTabHtml(html)
    assertEquals(result.length, 1)
  }
}
