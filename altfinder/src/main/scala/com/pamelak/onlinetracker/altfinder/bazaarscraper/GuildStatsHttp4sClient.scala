package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.Concurrent
import cats.implicits.*
import org.http4s.EntityDecoder.text
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.jsoup.Jsoup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Fetches character bazaar/transfer history from guildstats.eu.
  *
  * guildstats.eu exposes per-character tab pages at:
  *   https://guildstats.eu/include/character/tab.php?nick=<name>&tab=<tab>
  *
  * The `bazaar` tab contains an HTML table listing past auctions for the character
  * with dates rendered in "YYYY-MM-DD HH:mm:ss" (Europe/Berlin) format.
  * The `transfers` tab contains world-transfer events in the same date format.
  *
  * Both tabs are scraped and their dates are merged and returned as UTC Instants.
  * All errors are caught and an empty list is returned so the caller never fails.
  */
class GuildStatsHttp4sClient[F[_]: Concurrent](client: Client[F])
    extends CharacterSaleDateClientAlg[F] {

  private val zone      = ZoneId.of("Europe/Berlin")
  private val baseUri   = uri"https://guildstats.eu"

  /** Date format used in guildstats.eu tab HTML responses: "2024-01-15 10:00:00" */
  private[bazaarscraper] val dateFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

  override def getSaleDates(name: String): F[List[Instant]] = {
    fetchTabDates(name, "bazaar")
      .map2(fetchTabDates(name, "transfers"))(_ ++ _)
      .map(_.distinct)
      .handleError(_ => Nil)
  }

  private def fetchTabDates(name: String, tab: String): F[List[Instant]] = {
    val target = (baseUri / "include" / "character" / "tab.php")
      .withQueryParams(Map("nick" -> name, "tab" -> tab))
    client
      .expect[String](target)
      .map(parseTabHtml)
      .handleError(_ => Nil)
  }

  /** Parse dates from a guildstats.eu character tab HTML fragment.
    *
    * The tab response is a partial HTML snippet containing a table.
    * Each `<td>` that holds a date string of the form "YYYY-MM-DD HH:mm:ss"
    * is returned as a UTC Instant using the Europe/Berlin zone for conversion.
    */
  private[bazaarscraper] def parseTabHtml(html: String): List[Instant] = {
    val doc  = Jsoup.parseBodyFragment(html)
    val cells = doc.select("td").asScala.toList
    cells.flatMap(cell => parseDate(cell.text().trim)).distinct
  }

  private[bazaarscraper] def parseDate(text: String): Option[Instant] = {
    // Extract the leading "YYYY-MM-DD HH:mm:ss" portion so that extra trailing
    // text (e.g. world name) does not break the parse.
    val candidate = if (text.length >= 19) text.take(19) else text
    Try(LocalDateTime.parse(candidate, dateFormatter))
      .map(_.atZone(zone).toInstant)
      .toOption
  }
}
