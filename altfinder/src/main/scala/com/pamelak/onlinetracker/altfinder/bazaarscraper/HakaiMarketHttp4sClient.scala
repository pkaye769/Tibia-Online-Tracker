package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.Concurrent
import cats.implicits.*
import io.circe.parser.parse
import org.http4s.EntityDecoder.text
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.jsoup.Jsoup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Fetches sold character data from hakaimarket.com.
  *
  * hakaimarket.com is a third-party Tibia character marketplace.  Sold-character
  * data is available at:
  *   https://hakaimarket.com/api/sold?name=<name>   (JSON – tried first)
  *   https://hakaimarket.com/sold?name=<name>        (HTML fallback)
  *
  * JSON response shape (array at root or under a "data"/"results" key):
  *   [{"name":"...", "soldAt": <epoch-seconds-or-ISO>}, ...]
  *
  * HTML fallback: any `<td>` containing a recognisable date string is collected.
  *
  * All errors are caught and an empty list is returned so the caller never fails.
  */
class HakaiMarketHttp4sClient[F[_]: Concurrent](client: Client[F])
    extends CharacterSaleDateClientAlg[F] {

  private val zone    = ZoneId.of("Europe/Berlin")
  private val baseUri = uri"https://hakaimarket.com"

  /** ISO-8601 extended format used in JSON responses: "2024-01-15T10:00:00Z" */
  private[bazaarscraper] val isoFormatter =
    DateTimeFormatter.ISO_DATE_TIME

  /** Human-readable date format potentially used in HTML: "Jan 15 2024 10:00:00" */
  private[bazaarscraper] val humanFormatter =
    DateTimeFormatter.ofPattern("MMM dd yyyy HH:mm:ss", Locale.ENGLISH)

  override def getSaleDates(name: String): F[List[Instant]] = {
    fetchJsonDates(name)
      .flatMap { jsonDates =>
        if (jsonDates.nonEmpty) Concurrent[F].pure(jsonDates)
        else fetchHtmlDates(name)
      }
      .map(_.distinct)
      .handleError(_ => Nil)
  }

  // ── JSON path ────────────────────────────────────────────────────────────

  private def fetchJsonDates(name: String): F[List[Instant]] = {
    val target = (baseUri / "api" / "sold").withQueryParam("name", name)
    client
      .expect[String](target)
      .map(parseJsonResponse(_, name))
      .handleError(_ => Nil)
  }

  /** Parse a JSON response that is either:
    *  - an array of objects with a "soldAt" field (epoch-seconds or ISO string)
    *  - an object with a "data" or "results" key containing such an array
    */
  private[bazaarscraper] def parseJsonResponse(body: String, name: String): List[Instant] = {
    parse(body).toOption match {
      case None => Nil
      case Some(json) =>
        val arrayJson = json.asArray
          .orElse(json.hcursor.get[io.circe.Json]("data").toOption.flatMap(_.asArray))
          .orElse(json.hcursor.get[io.circe.Json]("results").toOption.flatMap(_.asArray))
        arrayJson match {
          case None => Nil
          case Some(items) =>
            items.toList.flatMap { item =>
              val c           = item.hcursor
              val nameMatches = c.get[String]("name").toOption
                .exists(_.equalsIgnoreCase(name))
              if (!nameMatches) None
              else {
                c.get[Long]("soldAt").toOption.map(Instant.ofEpochSecond)
                  .orElse(c.get[String]("soldAt").toOption.flatMap(parseIsoOrHuman))
                  .orElse(c.get[Long]("sold_at").toOption.map(Instant.ofEpochSecond))
                  .orElse(c.get[String]("sold_at").toOption.flatMap(parseIsoOrHuman))
                  .orElse(c.get[Long]("auctionEnd").toOption.map(Instant.ofEpochSecond))
                  .orElse(c.get[String]("auctionEnd").toOption.flatMap(parseIsoOrHuman))
              }
            }
        }
    }
  }

  // ── HTML fallback path ────────────────────────────────────────────────────

  private def fetchHtmlDates(name: String): F[List[Instant]] = {
    val target = (baseUri / "sold").withQueryParam("name", name)
    client
      .expect[String](target)
      .map(parseHtmlResponse(_, name))
      .handleError(_ => Nil)
  }

  /** Scrape sale dates from the HTML listing page for a character.
    *
    * Looks for `<tr>` rows whose text contains the (case-insensitive) character
    * name; within each such row any `<td>` with a parseable date is collected.
    * If no name-matched rows are found, falls back to collecting all parseable
    * dates from the page (so that a page already scoped to one character works).
    */
  private[bazaarscraper] def parseHtmlResponse(html: String, name: String): List[Instant] = {
    val doc  = Jsoup.parse(html)
    val rows = doc.select("tr").asScala.toList

    val matchedRows = rows.filter(_.text().toLowerCase.contains(name.toLowerCase))
    val sourceRows  = if (matchedRows.nonEmpty) matchedRows else rows

    sourceRows.flatMap { row =>
      row.select("td").asScala.toList.flatMap(cell => parseIsoOrHuman(cell.text().trim))
    }.distinct
  }

  // ── Shared date helpers ───────────────────────────────────────────────────

  private[bazaarscraper] def parseIsoOrHuman(text: String): Option[Instant] =
    parseIso(text).orElse(parseHuman(text))

  private def parseIso(text: String): Option[Instant] =
    Try(ZonedDateTime.parse(text, isoFormatter).toInstant)
      .orElse(Try(LocalDateTime.parse(text, isoFormatter).atZone(zone).toInstant))
      .toOption

  private def parseHuman(text: String): Option[Instant] = {
    val candidate = if (text.length >= 20) text.take(20) else text
    Try(LocalDateTime.parse(candidate, humanFormatter))
      .map(_.atZone(zone).toInstant)
      .toOption
  }
}
