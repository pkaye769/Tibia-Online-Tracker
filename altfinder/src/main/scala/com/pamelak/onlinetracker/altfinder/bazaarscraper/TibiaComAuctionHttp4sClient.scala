package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.Concurrent
import cats.implicits.*
import org.http4s.EntityDecoder.text
import org.http4s.client.Client
import org.http4s.implicits.uri
import org.jsoup.Jsoup

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import scala.jdk.CollectionConverters.*

class TibiaComAuctionHttp4sClient[F[_]: Concurrent](client: Client[F]) extends TibiaComAuctionClientAlg[F] {
  private val base = uri"https://www.tibia.com/charactertrade/"

  private val dateFormatter = new DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("MMM dd yyyy, HH:mm:ss z")
    .toFormatter(Locale.ENGLISH)

  override def getAuctionEnd(auctionId: Long): F[Option[Instant]] = {
    val target = base.withQueryParams(Map(
      ("subtopic", "currentcharactertrades"),
      ("page", "details"),
      ("auctionid", auctionId.toString)
    ))

    client.expect[String](target).map(parseAuctionEnd).handleError(_ => None)
  }

  private def parseAuctionEnd(html: String): Option[Instant] = {
    val doc = Jsoup.parse(html)
    val rows = doc.select("table.TableContent tr").asScala.toList
    rows.iterator.flatMap { row =>
      val cells = row.select("td").asScala.toList
      if (cells.length >= 2 && cells.head.text().trim.equalsIgnoreCase("Auction End")) {
        parseDate(cells(1).text().trim)
      } else None
    }.toSeq.headOption
  }

  private def parseDate(text: String): Option[Instant] = {
    try {
      Some(ZonedDateTime.parse(text, dateFormatter).toInstant)
    } catch {
      case _: Exception => None
    }
  }
}
