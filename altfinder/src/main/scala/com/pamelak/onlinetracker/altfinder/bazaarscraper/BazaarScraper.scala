package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.Parallel
import cats.effect.Sync
import cats.implicits.*
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper.*
import io.circe.*
import io.circe.parser.*
import org.jsoup.Jsoup

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import scala.jdk.CollectionConverters.*

object BazaarScraper {
  case class BazaarScraperError(message: String)

  case class BazaarAuction(name: String, end: Instant, auctionId: Option[Long])

  case class CharacterSales(name: String, saleDates: Either[BazaarScraperError, List[ZonedDateTime]])

  case class CharacterSalesList(characterSales: List[CharacterSales]) {
    def latestSale: Option[ZonedDateTime] = characterSales.map(_.saleDates).flatMap(_.toOption).flatten.maxOption
    def allSales: List[ZonedDateTime] = characterSales.flatMap(_.saleDates.getOrElse(Nil))
    def numberOfErrors: Int = characterSales.count(_.saleDates.isLeft)
  }

  def latestSale(sales: List[CharacterSales]): Option[ZonedDateTime] = sales.map(_.saleDates).flatMap(_.toOption)
    .flatten.maxOption
}

class BazaarScraper[F[_]: Sync: Parallel](client: BazaarScraperClientAlg[F], tibiaComClient: Option[TibiaComAuctionClientAlg[F]] = None) {
  private val zone = ZoneId.of("Europe/Berlin")

  def multipleCharacterSales(names: List[String]): F[CharacterSales] = {
    names match {
      case Nil => Sync[F].pure(CharacterSales("", Right(Nil)))
      case _ =>
        for
          allSales <- names.map(singleCharacterSales).sequence
          dates = allSales.flatMap(_.saleDates.getOrElse(Nil))
        yield CharacterSales(allSales.head.name, Right(dates))
    }
  }

  def recentWorldSales(world: String, lookbackDays: Int, pageSize: Int = 300): F[Either[BazaarScraperError, CharacterSalesList]] = {
    val cutoff = ZonedDateTime.now(zone).minusDays(lookbackDays.toLong)
    client.searchWorld(world, pageSize).map { raw =>
      parseAuctions(raw).map { auctions =>
        val grouped = auctions.flatMap { a =>
          val end = instantToSSDay(a.end)
          Option.when(end.isAfter(cutoff))((a.name, end))
        }.groupBy(_._1).view.mapValues(_.map(_._2)).toMap

        val sales = grouped.toList.map { case (name, ends) =>
          val sorted = ends.distinct.sortBy(_.toInstant.toEpochMilli)(Ordering.Long.reverse)
          CharacterSales(name, Right(sorted))
        }
        CharacterSalesList(sales)
      }
    }
  }

  private def singleCharacterSales(name: String): F[CharacterSales] = {
    for
      json <- client.searchCharacter(name).map(Right(_)).handleError { case e =>
        Left(BazaarScraperError(e.getMessage()))
      }
      auctionsOrError = json.flatMap(h => parseJson(h, name))
      auctions <- auctionsOrError match
        case Left(err) => Sync[F].pure(Left(err))
        case Right(list) =>
          tibiaComClient match
            case Some(tc) =>
              list.parTraverse(verifyAuction(tc)).map(Right(_))
            case None =>
              Sync[F].pure(Right(list))
    yield CharacterSales(name, auctions.map(_.map(_.end).map(instantToSSDay)))
  }

  private def instantToSSDay(instant: Instant): ZonedDateTime = {
    val zdtAtInstant = instant.atZone(zone)
    val serverSaveSameDay = zdtAtInstant.toLocalDate.atTime(LocalTime.of(10, 0)).atZone(zone)
    val ssDay = if (serverSaveSameDay.toInstant.isBefore(instant)) serverSaveSameDay.plusDays(1) else serverSaveSameDay
    ssDay.minusDays(1) // workaround for exevo pan bug https://github.com/xandjiji/exevo-pan/issues/241
  }

  private[bazaarscraper] def parseJson(jsonString: String, name: String): Either[BazaarScraperError, List[BazaarAuction]] = {
    parseAuctions(jsonString).map(_.filter(_.name.equalsIgnoreCase(name)))
  }

  private def parseAuctions(jsonString: String): Either[BazaarScraperError, List[BazaarAuction]] = {
    parse(jsonString).leftMap(err => BazaarScraperError(s"Invalid JSON: ${err.getMessage}")).flatMap { json =>
      val pageCur = json.hcursor.downField("page")
      pageCur.as[List[Json]] match
        case Left(decErr) => Left(BazaarScraperError(s"Invalid 'page' field: ${decErr.getMessage}"))
        case Right(items) =>
          try
            val dates = items.flatMap { item =>
              val c = item.hcursor
              val nickOpt = c.get[String]("nickname").toOption
              val endOpt = c.get[Long]("auctionEnd").toOption
              val biddedOpt = c.get[Boolean]("hasBeenBidded").toOption
              val auctionIdOpt = c.get[Long]("auctionId").toOption
              (nickOpt, endOpt, biddedOpt) match
                case (Some(nick), Some(epoch), Some(bidded)) if bidded =>
                  Some(BazaarAuction(nick, Instant.ofEpochSecond(epoch), auctionIdOpt))
                case _ => None
            }
            Right(dates)
          catch
            case e: Exception => Left(BazaarScraperError(s"Unexpected error while converting dates: ${e.getMessage}"))
    }
  }

  private def verifyAuction(client: TibiaComAuctionClientAlg[F])(auction: BazaarAuction): F[BazaarAuction] = {
    auction.auctionId match
      case Some(id) =>
        client.getAuctionEnd(id).map {
          case Some(end) => auction.copy(end = end)
          case None => auction
        }
      case None => Sync[F].pure(auction)
  }
}
