package com.pamelak.onlinetracker.altfinder.api

import cats.effect.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.ResearchRunWrite
import com.pamelak.onlinetracker.altfinder.repo.Model.WatchConfig
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import com.pamelak.onlinetracker.altfinder.service.AltFinderService.CharacterAdjacencies
import com.pamelak.onlinetracker.altfinder.tibiadata.TibiaDataClientAlg
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.circe.syntax.*

import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import io.circe.Json
import scala.collection.mutable
import scala.util.Try

final class AltFinderApi[F[_]: Async](
    service: AltFinderService[F],
    repo: AltFinderRepoAlg[F],
    tibiaDataClient: TibiaDataClientAlg[F],
    bazaarClient: com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraperClientAlg[F]
) {
  private val dsl = new Http4sDsl[F] {}
  import dsl.*

  private val berlinZone = ZoneId.of("Europe/Berlin")

  final case class Health(status: String)
  final case class TrackerStatusResponse(
      onlineHistoryRows: Long,
      latestWorldSave: Option[String],
      latestWorldSaveAgeSeconds: Option[Long],
      bazaarCooldownSeconds: Long,
      queryCacheSize: Int,
      queryCacheTtlSeconds: Int,
      statusLatencyMs: Long
  )
  final case class ErrorResponse(error: String, details: List[String])
  final case class TradeSummary(title: String, message: String)
  final case class AltMatch(
      name: String,
      adjacencies: Int,
      clashes: Int,
      logins: Int,
      sessionSimilarity: Int,
      confidence: Int,
      hiddenScore: Int,
      hiddenLikely: Boolean,
      evidencePassed: Boolean,
      explanation: String,
      recentTradeDates: List[String],
      formatted: String
  )
  final case class TradeCharacterResult(
      characterName: String,
      checkedNames: List[String],
      recentTradeDates: List[String],
      hadError: Boolean
  )
  final case class TradesResponse(
      lookbackDays: Int,
      results: List[TradeCharacterResult]
  )
  final case class ClashMatch(
      name: String,
      adjacencies: Int,
      clashes: Int,
      logins: Int,
      formatted: String
  )
  final case class ClashesResponse(
      searchedCharacters: List[String],
      checkedCharacters: List[String],
      searchedFrom: Option[String],
      searchedTo: Option[String],
      adjacencyDistanceMinutes: Int,
      totalClashes: Int,
      clashes: List[ClashMatch],
      formattedText: String
  )
  final case class GuildSummary(name: String, world: String, members: Int, online: Int, onlineCharacters: List[String])
  final case class ResearchRunResponse(
      id: Long,
      runType: String,
      searchedCharacters: List[String],
      targetCharacters: List[String],
      searchedFrom: Option[String],
      searchedTo: Option[String],
      distanceMinutes: Int,
      includeClashes: Boolean,
      totalLogins: Int,
      matchCount: Int,
      summary: String,
      createdAt: String
  )
  final case class WatchRow(
      characterName: String,
      distance: Int,
      includeClashes: Boolean,
      confidenceThreshold: Int,
      windowDays: Int,
      lastCheckedAt: Option[String],
      lastAlertAt: Option[String]
  )
  final case class WatchListResponse(guildId: String, watches: List[WatchRow])
  final case class WatchDeleteResponse(removed: Boolean)
  final case class WatchBatchItem(characterName: String, matches: Int, lines: List[String])
  final case class WatchBatchResponse(guildId: String, total: Int, items: List[WatchBatchItem])

  final case class AltsResponse(
      searchedCharacters: List[String],
      searchedFrom: Option[String],
      searchedTo: Option[String],
      totalLogins: Int,
      dateRange: String,
      adjacencyDistanceMinutes: Int,
      includeClashes: Boolean,
      tradeSummary: Option[TradeSummary],
      candidateTradeErrors: Int,
      possibleMatches: List[AltMatch],
      formattedText: String
  )

  given Encoder[Health] = deriveEncoder
  given Encoder[TrackerStatusResponse] = deriveEncoder
  given Encoder[ErrorResponse] = deriveEncoder
  given Encoder[TradeSummary] = deriveEncoder
  given Encoder[AltMatch] = deriveEncoder
  given Encoder[AltsResponse] = deriveEncoder
  given Encoder[TradeCharacterResult] = deriveEncoder
  given Encoder[TradesResponse] = deriveEncoder
  given Encoder[ClashMatch] = deriveEncoder
  given Encoder[ClashesResponse] = deriveEncoder
  given Encoder[GuildSummary] = deriveEncoder
  given Encoder[ResearchRunResponse] = deriveEncoder
  given Encoder[WatchRow] = deriveEncoder
  given Encoder[WatchListResponse] = deriveEncoder
  given Encoder[WatchDeleteResponse] = deriveEncoder
  given Encoder[WatchBatchItem] = deriveEncoder
  given Encoder[WatchBatchResponse] = deriveEncoder

  private val queryCacheTtlSeconds = sys.env.get("QUERY_CACHE_TTL_SECONDS").flatMap(_.toIntOption).getOrElse(60).max(5)
  private val queryCache = mutable.Map.empty[String, (Long, Json)]

  private def nowMs: Long = System.currentTimeMillis()
  private def purgeExpiredCache(): Unit = {
    val now = nowMs
    queryCache.filterInPlace { case (_, (expiresAt, _)) => expiresAt > now }
  }

  private def cachedJson(key: String)(compute: => F[Json]): F[Json] = {
    Async[F].delay {
      purgeExpiredCache()
      queryCache.get(key).filter(_._1 > nowMs).map(_._2)
    }.flatMap {
      case Some(value) => Async[F].pure(value)
      case None =>
        compute.flatTap { json =>
          Async[F].delay {
            purgeExpiredCache()
            queryCache.update(key, (nowMs + queryCacheTtlSeconds.toLong * 1000L, json))
          }
        }
    }
  }

  def routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      Ok(uiHtml).map(_.withContentType(`Content-Type`(MediaType.text.html)))

    case GET -> Root / "altfinder" =>
      Ok(uiHtml).map(_.withContentType(`Content-Type`(MediaType.text.html)))

    case GET -> Root / "api" / "altfinder" / "health" =>
      Ok(Health("ok").asJson)

    case GET -> Root / "api" / "altfinder" / "status" =>
      val startMs = nowMs
      (service.getTrackerStatus, bazaarClient.cooldownRemainingSeconds).mapN { case (status, cooldownSeconds) =>
        val latestSave = status.latestWorldSave
        val latestAge = latestSave.map(ts => math.max(0L, java.time.Duration.between(ts, OffsetDateTime.now()).getSeconds))
        TrackerStatusResponse(
          onlineHistoryRows = status.onlineHistoryRows,
          latestWorldSave = latestSave.map(_.toString),
          latestWorldSaveAgeSeconds = latestAge,
          bazaarCooldownSeconds = cooldownSeconds,
          queryCacheSize = queryCache.size,
          queryCacheTtlSeconds = queryCacheTtlSeconds,
          statusLatencyMs = math.max(0L, nowMs - startMs)
        )
      }.flatMap(s => Ok(s.asJson))
        .handleErrorWith { _ =>
          Ok(TrackerStatusResponse(
            onlineHistoryRows = -1,
            latestWorldSave = None,
            latestWorldSaveAgeSeconds = None,
            bazaarCooldownSeconds = -1,
            queryCacheSize = queryCache.size,
            queryCacheTtlSeconds = queryCacheTtlSeconds,
            statusLatencyMs = math.max(0L, nowMs - startMs)
          ).asJson)
        }

    case req @ GET -> Root / "api" / "altfinder" / route if route == "alts" || route == "alt" =>
      val params = req.uri.query.params
      val errors = collection.mutable.ListBuffer.empty[String]

      val characterRaw = params.get("characters").map(_.trim).filter(_.nonEmpty)
      val characters = characterRaw.map(_.split(",").map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
      if (characters.isEmpty) errors += "Missing required query param: characters"

      val from = parseDateParam(params, "from", errors)
      val to = parseDateParam(params, "to", errors)
      val distanceOpt = parseIntParam(params, "distance", errors)
      val includeClashes = parseBoolParam(params, "includeClashes", errors).getOrElse(false)
      val format = params.get("format").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("detailed")
      if (!Set("classic", "detailed").contains(format)) {
        errors += "Invalid format. Use classic or detailed."
      }

      if (errors.nonEmpty) {
        BadRequest(ErrorResponse("Invalid request", errors.toList).asJson)
      } else {
        val cacheKey = s"alts|${characters.mkString(",")}|$from|$to|$distanceOpt|$includeClashes|$format"
        cachedJson(cacheKey) {
          service.findAndPrintAlts(characters, from, to, distanceOpt, includeClashes).flatMap { results =>
          val dateMessage = buildDateRange(results.searchedFrom, results.searchedTo)
          val tradeSummary = buildTradeSummary(results.sales, from)
          val formattedMatches = results.adjacencies.take(20).map { adj =>
            val formatted = if (format == "classic") formatClassic(adj) else formatDetailed(adj)
            AltMatch(
              name = adj.characterName.getOrElse("Unknown"),
              adjacencies = adj.adjacencies,
              clashes = adj.clashes,
              logins = adj.logins,
              sessionSimilarity = adj.sessionSimilarity,
              confidence = adj.confidence,
              hiddenScore = adj.hiddenScore,
              hiddenLikely = adj.hiddenLikely,
              evidencePassed = adj.evidencePassed,
              explanation = explainAdj(adj),
              recentTradeDates = adj.recentTradeDates.map(_.toString),
              formatted = formatted
            )
          }

          val response = AltsResponse(
            searchedCharacters = results.searchedCharacters,
            searchedFrom = results.searchedFrom.map(_.toLocalDate.toString),
            searchedTo = results.searchedTo.map(_.toLocalDate.toString),
            totalLogins = results.mainLogins,
            dateRange = dateMessage,
            adjacencyDistanceMinutes = distanceOpt.getOrElse(0),
            includeClashes = includeClashes,
            tradeSummary = tradeSummary,
            candidateTradeErrors = results.candidateTradeErrors,
            possibleMatches = formattedMatches,
            formattedText = buildFormattedText(
              results = results,
              tradeSummary = tradeSummary,
              distance = distanceOpt.getOrElse(0),
              includeClashes = includeClashes,
              format = format
            )
          )
          val summaryText = response.formattedText.take(4000)
          repo.saveResearchRun(
            ResearchRunWrite(
              runType = "alts",
              searchedCharacters = results.searchedCharacters,
              targetCharacters = Nil,
              from = results.searchedFrom,
              to = results.searchedTo,
              distance = distanceOpt.getOrElse(0),
              includeClashes = includeClashes,
              totalLogins = results.mainLogins,
              matchCount = formattedMatches.length,
              summary = summaryText
            )
          ).attempt *> Async[F].pure(response.asJson)
        }.recoverWith { case ex =>
          Async[F].pure(ErrorResponse(s"Search failed: ${ex.getMessage}", Nil).asJson)
        }
        }.flatMap(json => Ok(json)).handleErrorWith { case ex =>
          InternalServerError(ErrorResponse(s"Internal server error: ${ex.getMessage}", Nil).asJson)
        }
      }

    case req @ GET -> Root / "api" / "altfinder" / "trades" =>
      val params = req.uri.query.params
      val errors = collection.mutable.ListBuffer.empty[String]

      val characterRaw = params.get("characters").map(_.trim).filter(_.nonEmpty)
      val characters = characterRaw.map(_.split(",").map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
      if (characters.isEmpty) errors += "Missing required query param: characters"

      val lookbackDays = params.get("lookbackDays").map(_.trim).filter(_.nonEmpty) match
        case None => 30
        case Some(raw) =>
          raw.toIntOption match
            case Some(v) if v >= 1 && v <= 365 => v
            case _ =>
              errors += "lookbackDays must be an integer from 1 to 365."
              30

      if (errors.nonEmpty) {
        BadRequest(ErrorResponse("Invalid request", errors.toList).asJson)
      } else {
        val cacheKey = s"trades|${characters.mkString(",")}|$lookbackDays"
        cachedJson(cacheKey) {
          service.checkTradedCharacters(characters, lookbackDays).map { rows =>
          val response = TradesResponse(
            lookbackDays = lookbackDays,
            results = rows.map { row =>
              TradeCharacterResult(
                characterName = row.characterName,
                checkedNames = row.checkedNames,
                recentTradeDates = row.recentTradeDates.map(_.toString),
                hadError = row.hadError
              )
            }
          )
          response.asJson
        }
        }.flatMap(json => Ok(json))
      }

    case req @ GET -> Root / "api" / "altfinder" / "clashes" =>
      val params = req.uri.query.params
      val errors = collection.mutable.ListBuffer.empty[String]

      val charactersRaw = params.get("characters").map(_.trim).filter(_.nonEmpty)
      val targetsRaw = params.get("targets").map(_.trim).filter(_.nonEmpty)
      val characters = charactersRaw.map(_.split(",").map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
      val targets = targetsRaw.map(_.split(",").map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
      if (characters.isEmpty) errors += "Missing required query param: characters"
      if (targets.isEmpty) errors += "Missing required query param: targets"
      val from = parseDateParam(params, "from", errors)
      val to = parseDateParam(params, "to", errors)
      val distance = parseIntParam(params, "distance", errors).getOrElse(0)

      if (errors.nonEmpty) {
        BadRequest(ErrorResponse("Invalid request", errors.toList).asJson)
      } else {
        val cacheKey = s"clashes|${characters.mkString(",")}|${targets.mkString(",")}|$from|$to|$distance"
        cachedJson(cacheKey) {
          service.findClashes(characters, targets, from, to, distance).flatMap { results =>
          val matches = results.clashes.map { c =>
            val name = c.characterName.getOrElse("Unknown")
            val formatted = s"$name: ${c.adjacencies} / ${c.clashes} / ${c.logins}"
            ClashMatch(name, c.adjacencies, c.clashes, c.logins, formatted)
          }
          val body =
            if (matches.isEmpty) "No clashes found."
            else matches.map(_.formatted).mkString("\n")
          val formattedText =
            List(
              "Searched characters",
              results.searchedCharacters.mkString(", "),
              "Checked against",
              results.checkedCharacters.mkString(", "),
              "Adjacency distance",
              appendMinutes(distance),
              "Total clashes",
              matches.length.toString,
              "",
              "Clash matches",
              body
            ).mkString("\n")
          val response = ClashesResponse(
              searchedCharacters = results.searchedCharacters,
              checkedCharacters = results.checkedCharacters,
              searchedFrom = results.searchedFrom.map(_.toLocalDate.toString),
              searchedTo = results.searchedTo.map(_.toLocalDate.toString),
              adjacencyDistanceMinutes = distance,
              totalClashes = matches.length,
              clashes = matches,
              formattedText = formattedText
            )
          repo.saveResearchRun(
            ResearchRunWrite(
              runType = "clashes",
              searchedCharacters = results.searchedCharacters,
              targetCharacters = results.checkedCharacters,
              from = results.searchedFrom,
              to = results.searchedTo,
              distance = distance,
              includeClashes = true,
              totalLogins = 0,
              matchCount = matches.length,
              summary = response.formattedText.take(4000)
            )
          ).attempt *> Async[F].pure(response.asJson)
          }
        }.flatMap(json => Ok(json))
      }

    case req @ GET -> Root / "api" / "altfinder" / "guild" =>
      val params = req.uri.query.params
      params.get("name").map(_.trim).filter(_.nonEmpty) match
        case None =>
          BadRequest(ErrorResponse("Invalid request", List("Missing required query param: name")).asJson)
        case Some(name) =>
          tibiaDataClient.getGuild(name).flatMap { json =>
            val (gName, world, members, online, onlineCharacters) = parseGuild(json, name)
            Ok(GuildSummary(gName, world, members, online, onlineCharacters).asJson)
          }.handleErrorWith { e =>
            BadRequest(ErrorResponse("Guild lookup failed", List(Option(e.getMessage).getOrElse("unknown error"))).asJson)
          }

    case req @ GET -> Root / "api" / "altfinder" / "research" =>
      val params = req.uri.query.params
      val limit = params.get("limit").flatMap(_.toIntOption).getOrElse(50).max(1).min(200)
      repo.listResearchRuns(limit).flatMap { rows =>
        Ok(rows.map { r =>
          ResearchRunResponse(
            id = r.id,
            runType = r.runType,
            searchedCharacters = r.searchedCharacters,
            targetCharacters = r.targetCharacters,
            searchedFrom = r.from.map(_.toLocalDate.toString),
            searchedTo = r.to.map(_.toLocalDate.toString),
            distanceMinutes = r.distance,
            includeClashes = r.includeClashes,
            totalLogins = r.totalLogins,
            matchCount = r.matchCount,
            summary = r.summary,
            createdAt = r.createdAt.toString
          )
        }.asJson)
      }

    case req @ GET -> Root / "api" / "altfinder" / "watchlist" =>
      val params = req.uri.query.params
      params.get("guildId").map(_.trim).filter(_.nonEmpty) match
        case None =>
          BadRequest(ErrorResponse("Invalid request", List("Missing required query param: guildId")).asJson)
        case Some(guildId) =>
          repo.listWatches(guildId).flatMap { rows =>
            val response = WatchListResponse(
              guildId,
              rows.map(w =>
                WatchRow(
                  w.characterName,
                  w.distance,
                  w.includeClashes,
                  w.confidenceThreshold,
                  w.windowDays,
                  w.lastCheckedAt.map(_.toString),
                  w.lastAlertAt.map(_.toString)
                )
              )
            )
            Ok(response.asJson)
          }

    case req @ GET -> Root / "api" / "altfinder" / "watchlist" / "add" =>
      val params = req.uri.query.params
      val guildId = params.get("guildId").map(_.trim).getOrElse("")
      val channelId = params.get("channelId").map(_.trim).getOrElse("")
      val characterName = params.get("character").map(_.trim).getOrElse("")
      val distance = params.get("distance").flatMap(_.toIntOption).getOrElse(0).max(0)
      val includeClashes = params.get("includeClashes").exists(_.equalsIgnoreCase("true"))
      val threshold = params.get("threshold").flatMap(_.toIntOption).getOrElse(80).max(0).min(100)
      val windowDays = params.get("windowDays").flatMap(_.toIntOption).getOrElse(30).max(1).min(365)
      val errors = List(
        Option.when(guildId.isEmpty)("guildId is required"),
        Option.when(channelId.isEmpty)("channelId is required"),
        Option.when(characterName.isEmpty)("character is required")
      ).flatten
      if (errors.nonEmpty) BadRequest(ErrorResponse("Invalid request", errors).asJson)
      else {
        val cfg = WatchConfig(guildId, channelId, characterName, distance, includeClashes, threshold, windowDays)
        repo.upsertWatch(cfg) *> Ok(Map("status" -> "ok").asJson)
      }

    case req @ GET -> Root / "api" / "altfinder" / "watchlist" / "remove" =>
      val params = req.uri.query.params
      (params.get("guildId").map(_.trim).filter(_.nonEmpty), params.get("character").map(_.trim).filter(_.nonEmpty)) match
        case (Some(guildId), Some(character)) =>
          repo.removeWatch(guildId, character).flatMap(removed => Ok(WatchDeleteResponse(removed).asJson))
        case _ =>
          BadRequest(ErrorResponse("Invalid request", List("guildId and character are required")).asJson)

    case req @ GET -> Root / "api" / "altfinder" / "watchlist" / "run" =>
      val params = req.uri.query.params
      val guildId = params.get("guildId").map(_.trim).filter(_.nonEmpty).getOrElse("")
      val limit = params.get("limit").flatMap(_.toIntOption).getOrElse(10).max(1).min(50)
      if (guildId.isEmpty) {
        BadRequest(ErrorResponse("Invalid request", List("guildId is required")).asJson)
      } else {
        repo.listWatches(guildId).flatMap { watches =>
          val selected = watches.take(limit)
          selected.traverse { w =>
            val from = Some(OffsetDateTime.now(ZoneId.of("Europe/Berlin")).minusDays(w.windowDays.toLong))
            service.findAndPrintAlts(List(w.characterName), from, None, Some(w.distance), w.includeClashes).map { results =>
              val lines = results.adjacencies
                .filter(_.confidence >= w.confidenceThreshold)
                .take(10)
                .map(formatClassic)
              WatchBatchItem(w.characterName, lines.length, lines)
            }
          }.flatMap { items =>
            Ok(WatchBatchResponse(guildId = guildId, total = items.length, items = items).asJson)
          }
        }
      }

  }

  private def parseDateParam(
      params: Map[String, String],
      key: String,
      errors: collection.mutable.ListBuffer[String]
  ): Option[OffsetDateTime] = {
    params.get(key).map(_.trim).filter(_.nonEmpty) match
      case None => None
      case Some(raw) =>
        parseDate(raw) match
          case Left(err) =>
            errors += err
            None
          case Right(value) => Some(value)
  }

  private def parseDate(raw: String): Either[String, OffsetDateTime] = {
    Try(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)).toEither.left
      .map(_ => s"Date needs to be in YYYY-MM-DD format. Could not parse date: $raw")
      .map(d => ZonedDateTime.of(d, LocalTime.of(10, 0), berlinZone).toOffsetDateTime)
  }

  private def parseIntParam(
      params: Map[String, String],
      key: String,
      errors: collection.mutable.ListBuffer[String]
  ): Option[Int] = {
    params.get(key).map(_.trim).filter(_.nonEmpty) match
      case None => None
      case Some(raw) =>
        raw.toIntOption match
          case Some(value) if value >= 0 => Some(value)
          case _ =>
            errors += s"$key must be a non-negative integer."
            None
  }

  private def parseBoolParam(
      params: Map[String, String],
      key: String,
      errors: collection.mutable.ListBuffer[String]
  ): Option[Boolean] = {
    params.get(key).map(_.trim).filter(_.nonEmpty) match
      case None => None
      case Some(raw) =>
        raw.toLowerCase match
          case "true" => Some(true)
          case "false" => Some(false)
          case _ =>
            errors += s"$key must be true or false."
            None
  }

  private def buildDateRange(from: Option[OffsetDateTime], to: Option[OffsetDateTime]): String = {
    (from, to) match
      case (None, None) => "Max range"
      case (None, Some(t)) => s"Until ${t.toLocalDate}"
      case (Some(f), None) => s"From ${f.toLocalDate}"
      case (Some(f), Some(t)) => s"From ${f.toLocalDate} until ${t.toLocalDate}"
  }

  private def buildTradeSummary(
      sales: BazaarScraper.CharacterSalesList,
      from: Option[OffsetDateTime]
  ): Option[TradeSummary] = {
    val bazaarScraperError = "Error accessing bazaar sources"
    (sales.allSales, sales.numberOfErrors) match
      case (Nil, 0) => None
      case (Nil, _) => Some(TradeSummary("Couldn't check if traded", bazaarScraperError))
      case (_, _) =>
        val salesList = sales.characterSales.flatMap { s =>
          s.saleDates match
            case Right(Nil) => None
            case Right(dates) => Some(s"${s.name}: ${dates.map(_.toLocalDate).mkString(", ")}")
            case Left(_) => Some(s"${s.name}: $bazaarScraperError")
        }
        val dateMessage = from match
          case None => "Setting the from date to be the date of the latest sale."
          case Some(_) => "Using from date provided. Results may be inaccurate."
        val message = s"The following characters have been traded:\n${salesList.mkString("\n")}\n$dateMessage"
        Some(TradeSummary("Traded character detected", message))
  }

  private def parseGuild(json: Json, fallbackName: String): (String, String, Int, Int, List[String]) = {
    val guildCursor = json.hcursor.downField("guild")
    val name = guildCursor.get[String]("name").getOrElse(fallbackName)
    val world = guildCursor.get[String]("world").getOrElse("Unknown")

    val membersDirect = guildCursor.downField("members").as[List[Json]].toOption
    val membersNested = guildCursor.downField("members").downField("members").as[List[Json]].toOption
    val membersList = membersDirect.orElse(membersNested).getOrElse(Nil)

    val total = guildCursor.get[Int]("members_total").toOption
      .orElse(guildCursor.downField("members").get[Int]("members_total").toOption)
      .getOrElse(membersList.length)

    val online = guildCursor.get[Int]("members_online").toOption
      .orElse(guildCursor.downField("members").get[Int]("members_online").toOption)
      .getOrElse {
        membersList.count { m =>
          m.hcursor.get[String]("status").toOption.exists(_.equalsIgnoreCase("online"))
        }
      }

    val onlineCharacters = membersList.flatMap { m =>
      val status = m.hcursor.get[String]("status").toOption.getOrElse("")
      if (status.equalsIgnoreCase("online")) m.hcursor.get[String]("name").toOption else None
    }

    (name, world, total, online, onlineCharacters)
  }

  private def formatClassic(adj: CharacterAdjacencies): String = {
    val name = adj.characterName.getOrElse("Unknown")
    val clashText = if (adj.clashes < 0) "yes" else adj.clashes.toString
    s"$name: ${adj.adjacencies} / $clashText / ${adj.logins}"
  }

  private def formatDetailed(adj: CharacterAdjacencies): String = {
    val name = adj.characterName.getOrElse("Unknown")
    val clashText = if (adj.clashes < 0) "yes" else adj.clashes.toString
    val tradeText =
      if (adj.recentTradeDates.nonEmpty) adj.recentTradeDates.map(_.toString).mkString(", ") else "none"
    val hiddenText = if (adj.hiddenLikely) s"yes (${adj.hiddenScore})" else s"no (${adj.hiddenScore})"
    val evidenceText = if (adj.evidencePassed) "pass" else "low"
    s"$name | conf ${adj.confidence} | hidden $hiddenText | evidence $evidenceText | adj ${adj.adjacencies} | clashes $clashText | logins ${adj.logins} | session ${adj.sessionSimilarity} | traded $tradeText"
  }

  private def explainAdj(adj: CharacterAdjacencies): String = {
    val clashText = if (adj.clashes < 0) "clashes unknown (filtered)" else s"clashes=${adj.clashes}"
    val evidenceText = if (adj.evidencePassed) "evidence=pass" else "evidence=low"
    s"adj=${adj.adjacencies}, $clashText, logins=${adj.logins}, sessionSimilarity=${adj.sessionSimilarity}, $evidenceText"
  }

  private def appendMinutes(i: Int) = if (i == 1) s"$i minute" else s"$i minutes"

  private def buildFormattedText(
      results: AltFinderService.AltsResults,
      tradeSummary: Option[TradeSummary],
      distance: Int,
      includeClashes: Boolean,
      format: String
  ): String = {
    val header = List(
      "Searched characters",
      results.searchedCharacters.mkString(", ")
    )

    val tradeLines = tradeSummary match
      case None => Nil
      case Some(summary) => List(summary.title, summary.message)

    val candidateWarning =
      if (results.candidateTradeErrors > 0)
        List("Candidate trade checks", s"Errors checking ${results.candidateTradeErrors} candidate(s).")
      else Nil

    val body = List(
      "Total logins",
      results.mainLogins.toString,
      "Date range",
      buildDateRange(results.searchedFrom, results.searchedTo),
      "",
      "Adjacency distance",
      appendMinutes(distance),
      "Include clashes",
      includeClashes.toString,
      "",
      "Possible matches"
    )

    val matchLines = results.adjacencies.take(20).map { adj =>
      if (format == "classic") formatClassic(adj) else formatDetailed(adj)
    }

    (header ++ tradeLines ++ candidateWarning ++ body ++ matchLines).mkString("\n")
  }

  private val uiHtml: String =
    """<!doctype html>
      |<html lang="en">
      |<head>
      |  <meta charset="utf-8" />
      |  <meta name="viewport" content="width=device-width, initial-scale=1" />
      |  <title>Alt Finder Console</title>
      |  <style>
      |    :root {
      |      --bg: #f4efe4;
      |      --ink: #101417;
      |      --muted: #55646f;
      |      --card: #fff9eecc;
      |      --line: #dbcaa6;
      |      --accent: #d95d39;
      |      --accent-2: #2f6e69;
      |      --ok: #1f7a42;
      |      --warn: #8c2f39;
      |      --shadow: 0 20px 45px #7f735840;
      |    }
      |    * { box-sizing: border-box; }
      |    body {
      |      margin: 0;
      |      font-family: "Palatino Linotype", "Book Antiqua", "Times New Roman", serif;
      |      color: var(--ink);
      |      background:
      |        radial-gradient(circle at 14% 8%, #f7d58a 0%, transparent 35%),
      |        radial-gradient(circle at 92% 22%, #9dd6d0 0%, transparent 33%),
      |        linear-gradient(165deg, #efe5d0 0%, #f6f1e8 45%, #ecdfc5 100%);
      |      min-height: 100vh;
      |    }
      |    .noise {
      |      position: fixed;
      |      inset: 0;
      |      pointer-events: none;
      |      opacity: 0.15;
      |      background-image: radial-gradient(#6f5f44 0.4px, transparent 0.4px);
      |      background-size: 4px 4px;
      |    }
      |    .wrap { max-width: 1120px; margin: 0 auto; padding: 28px 18px 34px; }
      |    .hero {
      |      margin-bottom: 18px;
      |      display: flex;
      |      align-items: end;
      |      justify-content: space-between;
      |      gap: 12px;
      |      opacity: 0;
      |      transform: translateY(12px);
      |      animation: rise 0.55s ease forwards;
      |    }
      |    h1 {
      |      margin: 0;
      |      font-size: clamp(1.6rem, 1.9vw + 1rem, 2.4rem);
      |      letter-spacing: 0.03em;
      |      text-transform: uppercase;
      |    }
      |    .sub {
      |      margin-top: 6px;
      |      color: var(--muted);
      |      max-width: 760px;
      |      font-size: 0.98rem;
      |    }
      |    .badge {
      |      border: 1px solid var(--line);
      |      background: #ffffffa0;
      |      border-radius: 999px;
      |      padding: 7px 12px;
      |      font-size: 0.82rem;
      |      font-weight: 700;
      |      white-space: nowrap;
      |    }
      |    .grid {
      |      display: grid;
      |      gap: 14px;
      |      grid-template-columns: 1.1fr 1fr;
      |      align-items: start;
      |    }
      |    .card {
      |      border: 1px solid var(--line);
      |      background: var(--card);
      |      border-radius: 16px;
      |      padding: 16px;
      |      box-shadow: var(--shadow);
      |      backdrop-filter: blur(5px);
      |      opacity: 0;
      |      transform: translateY(14px);
      |      animation: rise 0.6s ease forwards;
      |    }
      |    .card:nth-child(2) { animation-delay: 0.08s; }
      |    .card:nth-child(3) { animation-delay: 0.14s; }
      |    .stack { display: grid; gap: 12px; }
      |    h2 { margin: 0 0 8px; font-size: 1.05rem; letter-spacing: 0.04em; text-transform: uppercase; }
      |    .form-grid { display: grid; gap: 10px; grid-template-columns: repeat(4, minmax(0, 1fr)); }
      |    .full { grid-column: 1 / -1; }
      |    label { font-size: 0.78rem; color: var(--muted); display: block; margin-bottom: 4px; letter-spacing: 0.03em; }
      |    input, select {
      |      width: 100%;
      |      padding: 10px 11px;
      |      border-radius: 10px;
      |      border: 1px solid #c8b890;
      |      background: #fffef9;
      |      color: var(--ink);
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |      font-size: 0.95rem;
      |    }
      |    .actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 10px; }
      |    button {
      |      border: 1px solid transparent;
      |      border-radius: 11px;
      |      padding: 10px 14px;
      |      cursor: pointer;
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |      font-weight: 700;
      |      transition: transform 0.15s ease, filter 0.15s ease;
      |    }
      |    button:hover { transform: translateY(-1px); filter: brightness(0.98); }
      |    .btn-primary {
      |      color: #fff;
      |      background: linear-gradient(105deg, var(--accent), #c6512f);
      |    }
      |    .btn-ghost {
      |      color: var(--accent-2);
      |      border-color: #9ec5bd;
      |      background: #edf7f5;
      |    }
      |    .meta {
      |      color: var(--muted);
      |      font-size: 0.84rem;
      |      min-height: 20px;
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |    }
      |    .error {
      |      color: var(--warn);
      |      min-height: 20px;
      |      font-weight: 700;
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |      font-size: 0.9rem;
      |    }
      |    .kpis {
      |      display: grid;
      |      gap: 8px;
      |      grid-template-columns: repeat(2, minmax(0, 1fr));
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |    }
      |    .kpi {
      |      border: 1px dashed #ccb788;
      |      border-radius: 10px;
      |      padding: 9px 10px;
      |      background: #fffefbcc;
      |    }
      |    .kpi b { display: block; font-size: 0.76rem; color: var(--muted); font-weight: 600; letter-spacing: 0.03em; }
      |    .kpi span { font-size: 1.08rem; font-weight: 700; }
      |    .status-ok { color: var(--ok); }
      |    .table-wrap {
      |      margin-top: 8px;
      |      overflow: auto;
      |      border: 1px solid #d9c8a2;
      |      border-radius: 12px;
      |      background: #fffdf7;
      |      max-height: 400px;
      |    }
      |    table {
      |      width: 100%;
      |      border-collapse: collapse;
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |      font-size: 0.9rem;
      |    }
      |    th, td { padding: 9px 10px; border-bottom: 1px solid #ebdec4; text-align: left; white-space: nowrap; }
      |    th { position: sticky; top: 0; background: #f7ecd4; font-size: 0.78rem; letter-spacing: 0.03em; text-transform: uppercase; }
      |    tr:nth-child(even) td { background: #fff9eb; }
      |    .muted { color: var(--muted); }
      |    .score-pill {
      |      display: inline-block;
      |      border-radius: 999px;
      |      padding: 3px 8px;
      |      background: #efe5cc;
      |      border: 1px solid #d3bf90;
      |      font-weight: 700;
      |    }
      |    pre {
      |      margin: 0;
      |      white-space: pre-wrap;
      |      border: 1px solid #d3c39d;
      |      border-radius: 12px;
      |      background: #fffdf8;
      |      padding: 12px;
      |      font-family: "Consolas", "Courier New", monospace;
      |      font-size: 0.82rem;
      |      max-height: 280px;
      |      overflow: auto;
      |    }
      |    @keyframes rise {
      |      to { opacity: 1; transform: translateY(0); }
      |    }
      |    @media (max-width: 950px) {
      |      .grid { grid-template-columns: 1fr; }
      |      .hero { align-items: start; flex-direction: column; }
      |    }
      |    @media (max-width: 760px) {
      |      .form-grid { grid-template-columns: 1fr 1fr; }
      |    }
      |    @media (max-width: 520px) {
      |      .form-grid { grid-template-columns: 1fr; }
      |      .kpis { grid-template-columns: 1fr; }
      |    }
      |  </style>
      |</head>
      |<body>
      |  <div class="noise"></div>
      |  <div class="wrap">
      |    <div class="hero">
      |      <div>
      |        <h1>Tibia Alt Finder Console</h1>
      |        <div class="sub">Search suspected alt networks from tracked login and logout adjacency data.</div>
      |      </div>
      |      <div class="badge" id="healthBadge">API health: ...</div>
      |    </div>
      |    <div class="grid">
      |      <section class="card">
      |        <h2>Search</h2>
      |        <div class="form-grid">
      |          <div class="full">
      |            <label for="characters">Characters (comma separated)</label>
      |            <input id="characters" placeholder="Deli Tokes, Another Name" />
      |          </div>
      |          <div>
      |            <label for="distance">Distance minutes</label>
      |            <input id="distance" type="number" min="0" value="0" />
      |          </div>
      |          <div>
      |            <label for="clashes">Include clashes</label>
      |            <select id="clashes">
      |              <option value="false" selected>false</option>
      |              <option value="true">true</option>
      |            </select>
      |          </div>
      |          <div>
      |            <label for="fromDate">From (optional)</label>
      |            <input id="fromDate" type="date" />
      |          </div>
      |          <div>
      |            <label for="toDate">To (optional)</label>
      |            <input id="toDate" type="date" />
      |          </div>
      |        </div>
      |        <div class="actions">
      |          <button class="btn-primary" id="runBtn">Run Search</button>
      |          <button class="btn-ghost" id="clearBtn">Clear</button>
      |          <span class="meta" id="status"></span>
      |        </div>
      |        <div class="error" id="error"></div>
      |        <div class="kpis">
      |          <div class="kpi"><b>Total Logins</b><span id="kpiLogins">-</span></div>
      |          <div class="kpi"><b>Matches</b><span id="kpiMatches">-</span></div>
      |          <div class="kpi"><b>Date Range</b><span id="kpiRange">-</span></div>
      |          <div class="kpi"><b>Last World Save</b><span id="kpiSave">-</span></div>
      |        </div>
      |      </section>
      |      <section class="stack">
      |        <div class="card">
      |          <h2>Possible Matches</h2>
      |          <div class="table-wrap">
      |            <table>
      |              <thead>
      |                <tr>
      |                  <th>Name</th>
      |                  <th>Confidence</th>
      |                  <th>Adj</th>
      |                  <th>Clashes</th>
      |                  <th>Logins</th>
      |                  <th>Hidden</th>
      |                  <th>Trades</th>
      |                </tr>
      |              </thead>
      |              <tbody id="resultsBody">
      |                <tr><td colspan="7" class="muted">No search yet.</td></tr>
      |              </tbody>
      |            </table>
      |          </div>
      |        </div>
      |        <div class="card">
      |          <h2>Raw Summary</h2>
      |          <pre id="output">No search yet.</pre>
      |        </div>
      |      </section>
      |    </div>
      |    <div style="margin-top:14px">
      |      <section class="card">
      |        <h2>Guild Search</h2>
      |        <div class="form-grid">
      |          <div>
      |            <label for="guildName">Guild name</label>
      |            <input id="guildName" placeholder="Guild Name" />
      |          </div>
      |        </div>
      |        <div class="actions">
      |          <button class="btn-ghost" id="guildBtn">Search</button>
      |          <span class="meta" id="guildStatus"></span>
      |        </div>
      |        <pre id="guildOutput" style="margin-top:8px">No search yet.</pre>
      |      </section>
      |    </div>
      |    <div style="margin-top:14px">
      |      <section class="card">
      |        <h2>Research History</h2>
      |        <div class="actions">
      |          <button class="btn-ghost" id="historyBtn">Load History</button>
      |          <span class="meta" id="historyStatus"></span>
      |        </div>
      |        <div class="table-wrap">
      |          <table>
      |            <thead><tr><th>Type</th><th>Characters</th><th>Date Range</th><th>Distance</th><th>Matches</th><th>Run At</th></tr></thead>
      |            <tbody id="historyBody"><tr><td colspan="6" class="muted">Click Load History.</td></tr></tbody>
      |          </table>
      |        </div>
      |      </section>
      |    </div>
      |  </div>
      |  <script>
      |    const $ = (id) => document.getElementById(id);
      |    const ui = {
      |      status: $("status"),
      |      error: $("error"),
      |      output: $("output"),
      |      healthBadge: $("healthBadge"),
      |      resultsBody: $("resultsBody"),
      |      kpiLogins: $("kpiLogins"),
      |      kpiMatches: $("kpiMatches"),
      |      kpiRange: $("kpiRange"),
      |      kpiSave: $("kpiSave")
      |    };
      |
      |    function setStatus(message) {
      |      ui.status.textContent = message || "";
      |    }
      |
      |    function setError(message) {
      |      ui.error.textContent = message || "";
      |    }
      |
      |    function fillKpis(data, trackerStatus) {
      |      ui.kpiLogins.textContent = String(data.totalLogins ?? "-");
      |      ui.kpiMatches.textContent = String((data.possibleMatches || []).length);
      |      ui.kpiRange.textContent = data.dateRange || "-";
      |      ui.kpiSave.textContent = trackerStatus && trackerStatus.latestWorldSave ? trackerStatus.latestWorldSave : "-";
      |    }
      |
      |    function renderRows(matches) {
      |      if (!matches || matches.length === 0) {
      |        ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted">No matches found.</td></tr>';
      |        return;
      |      }
      |      const rows = matches.map((m) => {
      |        const tradeDates = (m.recentTradeDates || []).length > 0 ? m.recentTradeDates.join(", ") : "none";
      |        const hidden = m.hiddenLikely ? ("yes (" + m.hiddenScore + ")") : ("no (" + m.hiddenScore + ")");
      |        return (
      |          "<tr>" +
      |            "<td>" + escapeHtml(m.name || "Unknown") + "</td>" +
      |            "<td><span class=\"score-pill\">" + escapeHtml(String(m.confidence ?? "-")) + "</span></td>" +
      |            "<td>" + escapeHtml(String(m.adjacencies ?? "-")) + "</td>" +
      |            "<td>" + escapeHtml(String(m.clashes ?? "-")) + "</td>" +
      |            "<td>" + escapeHtml(String(m.logins ?? "-")) + "</td>" +
      |            "<td>" + escapeHtml(hidden) + "</td>" +
      |            "<td>" + escapeHtml(tradeDates) + "</td>" +
      |          "</tr>"
      |        );
      |      }).join("");
      |      ui.resultsBody.innerHTML = rows;
      |    }
      |
      |    function escapeHtml(value) {
      |      return String(value)
      |        .replaceAll("&", "&amp;")
      |        .replaceAll("<", "&lt;")
      |        .replaceAll(">", "&gt;")
      |        .replaceAll("\"", "&quot;")
      |        .replaceAll("'", "&#39;");
      |    }
      |
      |    async function loadStatus() {
      |      try {
      |        const healthRes = await fetch("/api/altfinder/health");
      |        const healthRaw = await healthRes.text();
      |        const health = healthRaw ? JSON.parse(healthRaw) : {};
      |        ui.healthBadge.textContent = "API health: " + (health.status || "unknown");
      |        if (health.status === "ok") ui.healthBadge.classList.add("status-ok");
      |      } catch (_) {
      |        ui.healthBadge.textContent = "API health: unavailable";
      |      }
      |      try {
      |        const statusRes = await fetch("/api/altfinder/status");
      |        if (statusRes.ok) {
      |          const statusRaw = await statusRes.text();
      |          const trackerStatus = statusRaw ? JSON.parse(statusRaw) : {};
      |          if (!ui.kpiSave.textContent || ui.kpiSave.textContent === "-") {
      |            ui.kpiSave.textContent = trackerStatus.latestWorldSave || "-";
      |          }
      |        }
      |      } catch (_) {}
      |    }
      |
      |    async function runSearch() {
      |      setError("");
      |      setStatus("Searching...");
      |      ui.output.textContent = "Loading...";
      |      const timeoutMs = 60000;
      |
      |      const q = new URLSearchParams();
      |      const chars = $("characters").value.trim();
      |      const distance = $("distance").value.trim();
      |      const includeClashes = $("clashes").value;
      |
      |      if (!chars) {
      |        setError("Characters is required.");
      |        setStatus("");
      |        ui.output.textContent = "No search yet.";
      |        return;
      |      }
      |
      |      q.set("characters", chars);
      |      if (distance) q.set("distance", distance);
      |      q.set("includeClashes", includeClashes);
      |      q.set("format", "detailed");
      |      const fromDate = $("fromDate").value.trim();
      |      const toDate = $("toDate").value.trim();
      |      if (fromDate) q.set("from", fromDate);
      |      if (toDate) q.set("to", toDate);
      |
      |      try {
      |        const controller = new AbortController();
      |        const timer = setTimeout(() => controller.abort(), timeoutMs);
      |        const res = await fetch("/api/altfinder/alts?" + q.toString(), { signal: controller.signal });
      |        clearTimeout(timer);
      |        const raw = await res.text();
      |        let data;
      |        try {
      |          data = raw ? JSON.parse(raw) : {};
      |        } catch (_) {
      |          if (/response timed out/i.test(raw)) {
      |            throw new Error("Backend timed out. Narrow date range, reduce characters, or retry.");
      |          }
      |          throw new Error("Non-JSON response from backend: " + String(raw).slice(0, 200));
      |        }
      |        let trackerStatus = {};
      |        try {
      |          const trackerRes = await fetch("/api/altfinder/status");
      |          if (trackerRes.ok) trackerStatus = await trackerRes.json();
      |        } catch (_) {}
      |        if (!res.ok) {
      |          throw new Error(data.error ? (data.error + " | " + (data.details || []).join("; ")) : "Request failed");
      |        }
      |        fillKpis(data, trackerStatus);
      |        renderRows(data.possibleMatches || []);
      |        ui.output.textContent = data.formattedText || JSON.stringify(data, null, 2);
      |        setStatus("Done.");
      |      } catch (e) {
      |        if (e && e.name === "AbortError") {
      |          setError("Backend timed out. Narrow date range, reduce characters, or retry.");
      |        } else {
      |          setError(e.message || String(e));
      |        }
      |        ui.output.textContent = "Search failed.";
      |        ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted">Search failed.</td></tr>';
      |        setStatus("");
      |      }
      |    }
      |
      |    function escHtml(v) {
      |      return String(v).replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;").replaceAll('"',"&quot;").replaceAll("'","&#39;");
      |    }
      |
      |    async function runGuildSearch() {
      |      const name = ($("guildName").value || "").trim();
      |      if (!name) { $("guildStatus").textContent = "Enter a guild name."; return; }
      |      $("guildStatus").textContent = "Searching...";
      |      try {
      |        const res = await fetch("/api/altfinder/guild?name=" + encodeURIComponent(name));
      |        const data = res.ok ? await res.json() : {};
      |        const online = (data.onlineCharacters || []).join(", ") || "none";
      |        $("guildOutput").textContent = [
      |          "Guild: " + (data.name || name),
      |          "World: " + (data.world || "-"),
      |          "Members: " + (data.members || 0),
      |          "Online: " + (data.online || 0),
      |          "Online characters: " + online
      |        ].join("\n");
      |        $("guildStatus").textContent = "Done.";
      |      } catch (e) {
      |        $("guildOutput").textContent = e.message || String(e);
      |        $("guildStatus").textContent = "";
      |      }
      |    }
      |
      |    async function loadHistory() {
      |      $("historyStatus").textContent = "Loading...";
      |      try {
      |        const res = await fetch("/api/altfinder/research?limit=25");
      |        const rows = res.ok ? await res.json() : [];
      |        if (!Array.isArray(rows) || rows.length === 0) {
      |          $("historyBody").innerHTML = '<tr><td colspan="6" class="muted">No research runs yet.</td></tr>';
      |        } else {
      |          $("historyBody").innerHTML = rows.map((r) => {
      |            const chars = (r.searchedCharacters || []).slice(0, 4).join(", ") + ((r.searchedCharacters || []).length > 4 ? "…" : "");
      |            const range = [r.searchedFrom, r.searchedTo].filter(Boolean).join(" – ") || "Max";
      |            const runAt = (r.createdAt || "").slice(0, 16).replace("T", " ");
      |            return "<tr>" +
      |              "<td>" + escHtml(r.runType || "-") + "</td>" +
      |              "<td>" + escHtml(chars || "-") + "</td>" +
      |              "<td>" + escHtml(range) + "</td>" +
      |              "<td>" + escHtml(String(r.distanceMinutes ?? "-")) + "m</td>" +
      |              "<td>" + escHtml(String(r.matchCount ?? "-")) + "</td>" +
      |              "<td>" + escHtml(runAt || "-") + "</td>" +
      |              "</tr>";
      |          }).join("");
      |        }
      |        $("historyStatus").textContent = "Done.";
      |      } catch (e) {
      |        $("historyBody").innerHTML = '<tr><td colspan="6" class="muted">' + (e.message || String(e)) + '</td></tr>';
      |        $("historyStatus").textContent = "";
      |      }
      |    }
      |
      |    $("runBtn").addEventListener("click", runSearch);
      |    $("clearBtn").addEventListener("click", () => {
      |      $("characters").value = "";
      |      $("distance").value = "0";
      |      $("clashes").value = "false";
      |      setStatus("");
      |      setError("");
      |      ui.output.textContent = "No search yet.";
      |      ui.resultsBody.innerHTML = '<tr><td colspan="7" class="muted">No search yet.</td></tr>';
      |      ui.kpiLogins.textContent = "-";
      |      ui.kpiMatches.textContent = "-";
      |      ui.kpiRange.textContent = "-";
      |      ui.kpiSave.textContent = "-";
      |      $("fromDate").value = "";
      |      $("toDate").value = "";
      |    });
      |    $("characters").addEventListener("keydown", (e) => {
      |      if (e.key === "Enter") runSearch();
      |    });
      |    $("guildBtn").addEventListener("click", runGuildSearch);
      |    $("historyBtn").addEventListener("click", loadHistory);
      |    loadStatus();
      |  </script>
      |</body>
      |</html>
      |""".stripMargin
}


