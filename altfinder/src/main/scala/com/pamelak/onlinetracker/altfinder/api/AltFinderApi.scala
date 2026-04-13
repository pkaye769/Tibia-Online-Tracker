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
import scala.concurrent.duration.*
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

  final case class CharacterInfoResponse(
      name: String,
      level: Int,
      vocation: String,
      world: String,
      sex: String,
      guild: Option[String],
      guildRank: Option[String],
      formerNames: List[String],
      lastLogin: Option[String],
      recentTradeDates: List[String],
      tradedCheckError: Boolean,
      tibiaComUrl: String,
      exevopanUrl: String
  )

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
  given Encoder[CharacterInfoResponse] = deriveEncoder

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
        Async[F].timeoutTo(
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
          },
          25.seconds,
          Async[F].pure(ErrorResponse("response timed out", Nil).asJson)
        ).flatMap(json => Ok(json)).handleErrorWith { case ex =>
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
        Async[F].timeoutTo(
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
          },
          25.seconds,
          Async[F].pure(ErrorResponse("response timed out", Nil).asJson)
        ).flatMap(json => Ok(json))
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
        Async[F].timeoutTo(
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
          },
          25.seconds,
          Async[F].pure(ErrorResponse("response timed out", Nil).asJson)
        ).flatMap(json => Ok(json))
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

    case req @ GET -> Root / "api" / "altfinder" / "character" =>
      val params = req.uri.query.params
      params.get("name").map(_.trim).filter(_.nonEmpty) match
        case None =>
          BadRequest(ErrorResponse("Invalid request", List("Missing required query param: name")).asJson)
        case Some(name) =>
          val cacheKey = s"char|$name"
          cachedJson(cacheKey) {
            // trade lookback window matches the default used by the alts search
            val tradeLookbackDays = 30
            val tibiaDataF = tibiaDataClient.getCharacter(name).map(Right(_)).handleError(e => Left(e.getMessage))
            val tradesF = service.checkTradedCharacters(List(name), tradeLookbackDays)
              .map(Right(_)).handleError(e => Left(e.getMessage))
            (tibiaDataF, tradesF).mapN { case (tibiaResult, tradesResult) =>
              val charJson = tibiaResult.toOption
              val charCursor = charJson.map(_.hcursor.downField("character").downField("character"))
              val charName = charCursor.flatMap(_.get[String]("name").toOption).getOrElse(name)
              val level = charCursor.flatMap(_.get[Double]("level").toOption).map(_.toInt).getOrElse(0)
              val vocation = charCursor.flatMap(_.get[String]("vocation").toOption).getOrElse("-")
              val world = charCursor.flatMap(_.get[String]("world").toOption).getOrElse("-")
              val sex = charCursor.flatMap(_.get[String]("sex").toOption).getOrElse("-")
              val guildName = charCursor.flatMap(
                _.downField("guild").get[String]("name").toOption
              )
              val guildRank = charCursor.flatMap(
                _.downField("guild").get[String]("rank").toOption
              )
              val formerNames = charCursor.flatMap(
                _.get[List[String]]("former_names").toOption
              ).getOrElse(Nil)
              val lastLogin = charCursor.flatMap(_.get[String]("last_login").toOption)
              val trades = tradesResult.toOption.getOrElse(Nil)
              val tradeStatus = trades.headOption
              val recentTradeDates = tradeStatus.map(_.recentTradeDates.map(_.toString)).getOrElse(Nil)
              val tradedCheckError = tradeStatus.exists(_.hadError)
              // URLEncoder uses '+' for spaces; replace with %20 for query-param-safe URLs.
              // StandardCharsets.UTF_8 avoids the deprecated String charset overload.
              val encodedName = java.net.URLEncoder.encode(charName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
              val tibiaComUrl = s"https://www.tibia.com/community/?subtopic=characters&name=$encodedName"
              val exevopanUrl = s"https://www.exevopan.com/?name=$encodedName"
              CharacterInfoResponse(
                name = charName,
                level = level,
                vocation = vocation,
                world = world,
                sex = sex,
                guild = guildName,
                guildRank = guildRank,
                formerNames = formerNames,
                lastLogin = lastLogin,
                recentTradeDates = recentTradeDates,
                tradedCheckError = tradedCheckError,
                tibiaComUrl = tibiaComUrl,
                exevopanUrl = exevopanUrl
              ).asJson
            }
          }.flatMap(json => Ok(json)).handleErrorWith { e =>
            BadRequest(ErrorResponse("Character lookup failed", List(Option(e.getMessage).getOrElse("unknown error"))).asJson)
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
      |  <meta charset="utf-8"/>
      |  <meta name="viewport" content="width=device-width, initial-scale=1"/>
      |  <title>Tibia Alt Finder</title>
      |  <style>
      |*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
      |body{
      |  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans",Helvetica,Arial,sans-serif;
      |  background:#0d1117;color:#e6edf3;font-size:14px;line-height:1.5;min-height:100vh;
      |}
      |.page{
      |  display:grid;grid-template-columns:1fr 340px;gap:16px;
      |  max-width:1280px;margin:0 auto;padding:24px 16px 40px;
      |}
      |.main-col,.side-col{display:flex;flex-direction:column;gap:12px}
      |/* Header */
      |header h1{font-size:1.75rem;font-weight:700;color:#fff}
      |header .subtitle{color:#8b949e;margin-top:4px;font-size:.875rem}
      |.api-badge{
      |  display:inline-flex;align-items:center;gap:6px;margin-top:10px;
      |  padding:3px 12px;border-radius:999px;border:1px solid #30363d;
      |  font-size:.8rem;color:#8b949e;background:transparent;cursor:default;
      |}
      |.api-badge.ok {border-color:#238636;color:#3fb950}
      |.api-badge.bad{border-color:#da3633;color:#f85149}
      |/* Cards */
      |.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px}
      |.card-title{font-size:1rem;font-weight:700;color:#fff;margin-bottom:12px}
      |/* Form */
      |label{display:block;font-size:.75rem;color:#8b949e;margin-bottom:4px}
      |input,select{
      |  width:100%;background:#0d1117;border:1px solid #30363d;border-radius:6px;
      |  color:#e6edf3;padding:7px 10px;font:inherit;font-size:.875rem;
      |}
      |input:focus,select:focus{outline:none;border-color:#388bfd;box-shadow:0 0 0 3px rgba(56,139,253,.1)}
      |select option{background:#161b22}
      |.form-group{margin-bottom:12px}
      |.row-3{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:12px}
      |.row-2{display:grid;grid-template-columns:1fr 1fr;gap:10px}
      |/* Checkbox row */
      |.check-row{
      |  display:flex;align-items:center;gap:8px;margin-bottom:12px;cursor:pointer;
      |  color:#8b949e;font-size:.875rem;
      |}
      |.check-row input[type=checkbox]{width:16px;height:16px;cursor:pointer;accent-color:#238636;flex-shrink:0}
      |/* Buttons */
      |.actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:12px}
      |button{
      |  font:inherit;font-size:.875rem;font-weight:600;padding:6px 16px;
      |  border-radius:6px;cursor:pointer;border:1px solid transparent;
      |  white-space:nowrap;transition:background .1s,border-color .1s;
      |}
      |button:disabled{opacity:.5;cursor:not-allowed}
      |.btn-green{background:#238636;color:#fff;border-color:#2ea043}
      |.btn-green:hover:not(:disabled){background:#2ea043}
      |.btn-ghost{background:transparent;color:#e6edf3;border-color:#30363d}
      |.btn-ghost:hover:not(:disabled){background:#21262d}
      |/* Error box */
      |.error-box{
      |  margin-top:10px;padding:10px 12px;
      |  background:rgba(56,139,253,.1);border:1px solid rgba(56,139,253,.4);
      |  border-radius:6px;font-size:.8rem;color:#79c0ff;
      |  display:none;word-break:break-word;
      |}
      |.error-box.visible{display:block}
      |.error-box a{color:#79c0ff;text-decoration:underline;font-size:.8rem;display:block;margin-top:6px}
      |/* Pre */
      |pre{
      |  background:#0d1117;border:1px solid #30363d;border-radius:6px;padding:12px;
      |  font-family:"SFMono-Regular",Consolas,"Liberation Mono",Menlo,monospace;
      |  font-size:.78rem;color:#8b949e;white-space:pre-wrap;word-break:break-word;
      |  max-height:320px;overflow:auto;
      |}
      |/* Matches table */
      |.table-wrap{overflow:auto;max-height:350px;border:1px solid #30363d;border-radius:6px}
      |table{width:100%;border-collapse:collapse;font-size:.8rem}
      |th,td{padding:8px 10px;text-align:left;border-bottom:1px solid #21262d;white-space:nowrap}
      |th{background:#1c2128;color:#8b949e;font-size:.7rem;letter-spacing:.04em;text-transform:uppercase;position:sticky;top:0}
      |tr:hover td{background:#1c2128;cursor:pointer}
      |.cell-muted{color:#8b949e;font-style:italic}
      |td.name-cell{font-weight:600;color:#79c0ff;cursor:pointer}
      |.conf-pill{display:inline-block;padding:1px 7px;border-radius:999px;font-size:.75rem;font-weight:700}
      |.conf-hi {background:#1a3a2a;color:#3fb950}
      |.conf-mid{background:#3a3020;color:#d29922}
      |.conf-lo {background:#3a2020;color:#f85149}
      |.trade-badge{
      |  display:inline-block;padding:1px 5px;border-radius:3px;
      |  background:#3a2810;border:1px solid #d29922;color:#d29922;
      |  font-size:.68rem;font-weight:700;margin-left:4px;vertical-align:middle;
      |  text-transform:uppercase;letter-spacing:.03em;
      |}
      |/* Sidebar saved display */
      |.saved-display{
      |  background:#0d1117;border:1px solid #30363d;border-radius:6px;
      |  padding:10px;min-height:72px;font-size:.8rem;color:#8b949e;
      |  margin-top:8px;word-break:break-word;white-space:pre-wrap;
      |}
      |/* Character detail panel */
      |.char-panel{display:none;margin-top:12px}
      |.char-panel.open{display:block}
      |.char-panel-inner{background:#0d1117;border:1px solid #388bfd;border-radius:6px;padding:12px}
      |.char-panel-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}
      |.char-panel-header h4{font-size:.95rem;font-weight:700;color:#79c0ff;margin:0}
      |.char-panel-close{background:none;border:none;color:#8b949e;font-size:1rem;cursor:pointer;padding:2px 6px;border-radius:4px}
      |.char-panel-close:hover{background:#21262d}
      |.char-fields{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:8px}
      |.char-field-lbl{font-size:.68rem;color:#8b949e;text-transform:uppercase;letter-spacing:.04em;margin-bottom:2px}
      |.char-field-val{font-size:.85rem;font-weight:600;color:#e6edf3;word-break:break-word}
      |.trade-alert-box{
      |  background:rgba(210,153,34,.15);border:1px solid #d29922;border-radius:6px;
      |  padding:8px 10px;font-size:.8rem;color:#e3b341;margin-bottom:8px;
      |}
      |.char-links{display:flex;gap:8px;flex-wrap:wrap}
      |.char-link{
      |  display:inline-block;padding:4px 12px;border-radius:6px;font-size:.78rem;font-weight:600;
      |  text-decoration:none;border:1px solid #30363d;color:#79c0ff;background:transparent;
      |}
      |.char-link:hover{background:#1c2128}
      |/* Responsive */
      |@media(max-width:900px){.page{grid-template-columns:1fr}}
      |@media(max-width:540px){.row-3{grid-template-columns:1fr}.char-fields{grid-template-columns:1fr 1fr}}
      |  </style>
      |</head>
      |<body>
      |<div class="page">
      |
      |  <!-- ── Main column ─────────────────────────────────────────────────── -->
      |  <div class="main-col">
      |    <header>
      |      <h1>Tibia Alt Finder</h1>
      |      <p class="subtitle">Fast scan for likely alts using adjacent logins, clashes, and login counts.</p>
      |      <div id="apiBadge" class="api-badge">API: checking&#8230;</div>
      |    </header>
      |
      |    <!-- Search card -->
      |    <div class="card">
      |      <div class="form-group">
      |        <label for="backendUrl">Backend URL</label>
      |        <input id="backendUrl" type="text" value="https://tibia-alt-finder-api.onrender.com"/>
      |      </div>
      |      <div class="row-3">
      |        <div>
      |          <label for="mode">Mode</label>
      |          <select id="mode">
      |            <option value="alts">alts</option>
      |            <option value="clashes">clashes</option>
      |          </select>
      |        </div>
      |        <div>
      |          <label for="distance">Adjacency (minutes)</label>
      |          <input id="distance" type="number" value="0" min="0"/>
      |        </div>
      |        <div>
      |          <label for="includeClashes">Include clashes</label>
      |          <select id="includeClashes">
      |            <option value="false">false</option>
      |            <option value="true">true</option>
      |          </select>
      |        </div>
      |      </div>
      |      <label class="check-row" for="strictMode">
      |        <input id="strictMode" type="checkbox"/>
      |        <span>Strict mode (distance 0, clashes excluded)</span>
      |      </label>
      |      <div class="form-group">
      |        <label for="characters">Characters (comma separated)</label>
      |        <input id="characters" type="text" placeholder="deli tokes"/>
      |      </div>
      |      <div class="actions">
      |        <button id="runBtn" class="btn-green">Run</button>
      |        <button id="clearBtn" class="btn-ghost">Clear</button>
      |      </div>
      |      <div id="errorBox" class="error-box">
      |        <span id="errorMsg"></span>
      |        <a id="backendLink" href="#" target="_blank" rel="noopener">Open backend-hosted board</a>
      |      </div>
      |    </div>
      |
      |    <!-- Summary -->
      |    <div class="card">
      |      <div class="card-title">Summary</div>
      |      <pre id="summary">No search yet.</pre>
      |    </div>
      |
      |    <!-- Possible Matches -->
      |    <div class="card">
      |      <div class="card-title">Possible Matches</div>
      |      <div id="matchesArea"><pre>No search yet.</pre></div>
      |      <div id="charPanel" class="char-panel">
      |        <div class="char-panel-inner">
      |          <div class="char-panel-header">
      |            <h4 id="charPanelName"></h4>
      |            <button id="charPanelClose" class="char-panel-close">&#x2715;</button>
      |          </div>
      |          <div id="charPanelContent"></div>
      |        </div>
      |      </div>
      |    </div>
      |  </div>
      |
      |  <!-- ── Sidebar ──────────────────────────────────────────────────────── -->
      |  <div class="side-col">
      |
      |    <!-- Saved Characters -->
      |    <div class="card">
      |      <div class="card-title">Saved Characters</div>
      |      <div class="form-group">
      |        <label for="savedCharInput">Character</label>
      |        <input id="savedCharInput" type="text" placeholder="Deli Tokes"/>
      |      </div>
      |      <div class="form-group">
      |        <label for="savedCharList">Saved list</label>
      |        <select id="savedCharList"></select>
      |      </div>
      |      <div class="actions">
      |        <button id="addCharBtn" class="btn-green">Add</button>
      |        <button id="useCharBtn" class="btn-ghost">Use in Search</button>
      |        <button id="removeCharBtn" class="btn-ghost">Remove</button>
      |      </div>
      |      <div id="savedCharDisplay" class="saved-display">No saved characters yet.</div>
      |    </div>
      |
      |    <!-- Saved Guilds -->
      |    <div class="card">
      |      <div class="card-title">Saved Guilds</div>
      |      <div class="form-group">
      |        <label for="savedGuildInput">Guild name (press Enter to save)</label>
      |        <input id="savedGuildInput" type="text" placeholder="Guild Name"/>
      |      </div>
      |      <div class="form-group">
      |        <label for="savedGuildList">Saved list</label>
      |        <select id="savedGuildList"></select>
      |      </div>
      |      <div class="actions">
      |        <button id="refreshGuildBtn" class="btn-green">Refresh</button>
      |        <button id="loadGuildBtn" class="btn-ghost">Load</button>
      |        <button id="removeGuildBtn" class="btn-ghost">Remove</button>
      |      </div>
      |      <div id="savedGuildDisplay" class="saved-display">No saved guilds yet.</div>
      |    </div>
      |
      |    <!-- Watched Characters -->
      |    <div class="card">
      |      <div class="card-title">Watched Characters</div>
      |      <div class="row-2" style="margin-bottom:10px">
      |        <div>
      |          <label for="watchGuildId">Guild ID</label>
      |          <input id="watchGuildId" type="text" placeholder="Discord Guild ID"/>
      |        </div>
      |        <div>
      |          <label for="watchChannelId">Channel ID</label>
      |          <input id="watchChannelId" type="text" placeholder="Discord Channel ID"/>
      |        </div>
      |      </div>
      |      <div class="form-group">
      |        <label for="watchCharInput">Character to watch</label>
      |        <input id="watchCharInput" type="text" placeholder="Character name"/>
      |      </div>
      |      <div class="actions">
      |        <button id="addWatchBtn" class="btn-green">Add Watch</button>
      |        <button id="loadWatchBtn" class="btn-ghost">Load List</button>
      |      </div>
      |      <div id="watchListArea" class="saved-display">Enter Guild ID and click Load List.</div>
      |    </div>
      |
      |  </div>
      |</div>
      |<script>
      |'use strict';
      |
      |const STORAGE_BACKEND   = 'altfinder_backend_url';
      |const STORAGE_DISTANCE  = 'altfinder_distance';
      |const STORAGE_CHARS     = 'altfinder_saved_chars';
      |const STORAGE_GUILDS    = 'altfinder_saved_guilds';
      |const TIMEOUT_SEARCH    = 90000;
      |const TIMEOUT_STATUS    = 10000;
      |const TIMEOUT_CHAR      = 20000;
      |
      |const el = {
      |  backendUrl:       document.getElementById('backendUrl'),
      |  mode:             document.getElementById('mode'),
      |  distance:         document.getElementById('distance'),
      |  includeClashes:   document.getElementById('includeClashes'),
      |  strictMode:       document.getElementById('strictMode'),
      |  characters:       document.getElementById('characters'),
      |  runBtn:           document.getElementById('runBtn'),
      |  clearBtn:         document.getElementById('clearBtn'),
      |  errorBox:         document.getElementById('errorBox'),
      |  errorMsg:         document.getElementById('errorMsg'),
      |  backendLink:      document.getElementById('backendLink'),
      |  summary:          document.getElementById('summary'),
      |  matchesArea:      document.getElementById('matchesArea'),
      |  apiBadge:         document.getElementById('apiBadge'),
      |  savedCharInput:   document.getElementById('savedCharInput'),
      |  savedCharList:    document.getElementById('savedCharList'),
      |  addCharBtn:       document.getElementById('addCharBtn'),
      |  useCharBtn:       document.getElementById('useCharBtn'),
      |  removeCharBtn:    document.getElementById('removeCharBtn'),
      |  savedCharDisplay: document.getElementById('savedCharDisplay'),
      |  savedGuildInput:  document.getElementById('savedGuildInput'),
      |  savedGuildList:   document.getElementById('savedGuildList'),
      |  refreshGuildBtn:  document.getElementById('refreshGuildBtn'),
      |  loadGuildBtn:     document.getElementById('loadGuildBtn'),
      |  removeGuildBtn:   document.getElementById('removeGuildBtn'),
      |  savedGuildDisplay:document.getElementById('savedGuildDisplay'),
      |  watchGuildId:     document.getElementById('watchGuildId'),
      |  watchChannelId:   document.getElementById('watchChannelId'),
      |  watchCharInput:   document.getElementById('watchCharInput'),
      |  addWatchBtn:      document.getElementById('addWatchBtn'),
      |  loadWatchBtn:     document.getElementById('loadWatchBtn'),
      |  watchListArea:    document.getElementById('watchListArea'),
      |  charPanel:        document.getElementById('charPanel'),
      |  charPanelName:    document.getElementById('charPanelName'),
      |  charPanelContent: document.getElementById('charPanelContent'),
      |  charPanelClose:   document.getElementById('charPanelClose'),
      |};
      |
      |let savedChars  = [];
      |let savedGuilds = [];
      |let guildOnlineMap = {};
      |
      |// ── Persistence ───────────────────────────────────────────────────────────────
      |function loadStorage() {
      |  const url = localStorage.getItem(STORAGE_BACKEND);
      |  if (url) el.backendUrl.value = url;
      |  const dist = localStorage.getItem(STORAGE_DISTANCE);
      |  if (dist !== null) el.distance.value = dist;
      |  try { savedChars  = JSON.parse(localStorage.getItem(STORAGE_CHARS)  || '[]'); } catch(_) {}
      |  try { savedGuilds = JSON.parse(localStorage.getItem(STORAGE_GUILDS) || '[]'); } catch(_) {}
      |  renderSavedChars();
      |  renderSavedGuilds();
      |}
      |
      |function persist() {
      |  localStorage.setItem(STORAGE_BACKEND,  el.backendUrl.value.trim());
      |  localStorage.setItem(STORAGE_DISTANCE, el.distance.value);
      |  localStorage.setItem(STORAGE_CHARS,    JSON.stringify(savedChars));
      |  localStorage.setItem(STORAGE_GUILDS,   JSON.stringify(savedGuilds));
      |}
      |
      |// ── Helpers ───────────────────────────────────────────────────────────────────
      |function baseUrl() { return el.backendUrl.value.trim().replace(/\/+$/, ''); }
      |// Return a safe http/https origin; falls back to '#' to prevent javascript: injection.
      |// The backend URL is intentionally user-configurable — the link lets users navigate
      |// to the board they explicitly set up, so pointing at any http/https host is by design.
      |function safeBoardUrl() {
      |  try {
      |    const u = new URL(baseUrl() + '/');
      |    if (u.protocol === 'http:' || u.protocol === 'https:') return u.href;
      |  } catch(_) {}
      |  return '#';
      |}
      |function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
      |function esc(v) {
      |  return String(v)
      |    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
      |    .replace(/>/g,'&gt;').replace(/"/g,'&quot;')
      |    .replace(/'/g,'&#39;');
      |}
      |
      |// ── Error ─────────────────────────────────────────────────────────────────────
      |function showError(msg) {
      |  el.errorMsg.textContent = msg || '';
      |  el.errorBox.classList.toggle('visible', !!msg);
      |  el.backendLink.href = safeBoardUrl();
      |}
      |function clearError() { el.errorBox.classList.remove('visible'); }
      |
      |// ── API fetch ─────────────────────────────────────────────────────────────────
      |async function fetchJson(path, timeoutMs, maxAttempts) {
      |  if (maxAttempts === undefined) maxAttempts = 2;
      |  if (timeoutMs === undefined) timeoutMs = 15000;
      |  const url = baseUrl() + path;
      |  let lastErr = null, res = null;
      |  for (let i = 1; i <= maxAttempts; i++) {
      |    const ctrl = new AbortController();
      |    const t = setTimeout(() => ctrl.abort(), timeoutMs);
      |    try {
      |      res = await fetch(url, { signal: ctrl.signal });
      |      clearTimeout(t);
      |      if (res.status < 500) break;
      |      lastErr = new Error('HTTP ' + res.status);
      |    } catch(e) { clearTimeout(t); lastErr = e; }
      |    if (i < maxAttempts) await sleep(1200);
      |  }
      |  if (!res) {
      |    const detail = lastErr && lastErr.name === 'AbortError'
      |      ? 'Request timed out.'
      |      : 'Browser could not establish a network connection. Check Backend URL, Render deploy health, VPN/firewall/proxy rules, and CORS/network access.';
      |    throw new Error('Could not reach API at ' + url + ' after ' + maxAttempts + ' attempts. ' + detail);
      |  }
      |  const text = await res.text();
      |  if (/response timed out/i.test(text)) throw new Error('Backend timed out. Try narrowing your search.');
      |  const ct = res.headers.get('content-type') || '';
      |  if (!ct.includes('application/json')) throw new Error('Non-JSON response (HTTP ' + res.status + '): ' + text.slice(0,200));
      |  const body = JSON.parse(text);
      |  if (!res.ok) throw new Error(body.error || body.message || 'HTTP ' + res.status);
      |  return body;
      |}
      |
      |// ── Health check ──────────────────────────────────────────────────────────────
      |async function checkHealth() {
      |  try {
      |    const d = await fetchJson('/api/altfinder/health', TIMEOUT_STATUS, 1);
      |    el.apiBadge.textContent = 'API: ' + (d.status || 'ok');
      |    el.apiBadge.className = 'api-badge ok';
      |  } catch(_) {
      |    el.apiBadge.textContent = 'API: unavailable';
      |    el.apiBadge.className = 'api-badge bad';
      |  }
      |}
      |
      |// ── Strict mode ───────────────────────────────────────────────────────────────
      |el.strictMode.addEventListener('change', function() {
      |  if (this.checked) {
      |    el.distance.value = '0'; el.includeClashes.value = 'false';
      |    el.distance.disabled = true; el.includeClashes.disabled = true;
      |  } else {
      |    el.distance.disabled = false; el.includeClashes.disabled = false;
      |  }
      |});
      |
      |// ── Render matches ────────────────────────────────────────────────────────────
      |function renderMatches(matches) {
      |  if (!matches || matches.length === 0) {
      |    el.matchesArea.innerHTML = '<pre>No matches found.</pre>';
      |    return;
      |  }
      |  const rows = matches.map(function(m) {
      |    const conf = Number(m.confidence || 0);
      |    const cls = conf >= 70 ? 'conf-hi' : conf >= 40 ? 'conf-mid' : 'conf-lo';
      |    const hidden = m.hiddenLikely ? 'yes (' + m.hiddenScore + ')' : 'no (' + m.hiddenScore + ')';
      |    const traded = (m.recentTradeDates || []).length > 0;
      |    const tradeCell = traded
      |      ? esc(m.recentTradeDates[0]) + '<span class="trade-badge">TRADED</span>'
      |      : '<span style="color:#8b949e">none</span>';
      |    return '<tr>'
      |      + '<td class="name-cell" data-name="' + esc(m.name||'') + '">' + esc(m.name||'Unknown') + '</td>'
      |      + '<td><span class="conf-pill ' + cls + '">' + conf + '</span></td>'
      |      + '<td>' + esc(String(m.adjacencies != null ? m.adjacencies : '-')) + '</td>'
      |      + '<td>' + esc(String(m.clashes != null ? m.clashes : '-')) + '</td>'
      |      + '<td>' + esc(String(m.logins != null ? m.logins : '-')) + '</td>'
      |      + '<td>' + esc(hidden) + '</td>'
      |      + '<td>' + tradeCell + '</td>'
      |      + '</tr>';
      |  }).join('');
      |  el.matchesArea.innerHTML = '<div class="table-wrap"><table>'
      |    + '<thead><tr><th>Name</th><th>Confidence</th><th>Adj</th><th>Clashes</th>'
      |    + '<th>Logins</th><th>Hidden</th><th>Trades</th></tr></thead>'
      |    + '<tbody>' + rows + '</tbody></table></div>';
      |}
      |
      |// ── Run search ────────────────────────────────────────────────────────────────
      |// ── Summary renderer ─────────────────────────────────────────────────────────────────────────────────
      |function renderSummary(text) {
      |  const NAME_AFTER = new Set(['Searched characters', 'Checked against']);
      |  const LABEL_LINES = new Set([
      |    'Searched characters', 'Checked against',
      |    'Total logins', 'Date range', 'Adjacency distance', 'Include clashes',
      |    'Total clashes', 'Possible matches', 'Clash matches',
      |    'Traded character detected', "Couldn't check if traded", 'Candidate trade checks',
      |  ]);
      |  const lines = text.split('\n');
      |  let highlightNext = false;
      |  const html = lines.map(line => {
      |    const escaped = line.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
      |    const trimmed = line.trim();
      |    if (trimmed === '') { highlightNext = false; return escaped; }
      |    if (NAME_AFTER.has(trimmed)) { highlightNext = true; return escaped; }
      |    if (highlightNext && trimmed !== '') { highlightNext = false; return `<span class="summary-chars">${escaped}</span>`; }
      |    if (LABEL_LINES.has(trimmed)) return escaped;
      |    return `<span style="color:#e6edf3">${escaped}</span>`;
      |  }).join('\n');
      |  el.summary.innerHTML = html;
      |}
      |
      |async function runSearch() {
      |  clearError();
      |  hideCharPanel();
      |  const chars = el.characters.value.trim();
      |  if (!chars) { showError('Enter at least one character name.'); return; }
      |  const distance  = Number(el.distance.value || 0);
      |  const clashes   = el.includeClashes.value;
      |  const params = new URLSearchParams({
      |    characters: chars, distance: String(distance),
      |    includeClashes: clashes, format: 'detailed',
      |  });
      |  persist();
      |  el.runBtn.disabled = true; el.runBtn.textContent = 'Running\u2026';
      |  el.summary.textContent = 'Loading\u2026';
      |  el.matchesArea.innerHTML = '<pre>Loading\u2026</pre>';
      |  try {
      |    const data = await fetchJson('/api/altfinder/alts?' + params, TIMEOUT_SEARCH, 2);
      |    renderSummary(data.formattedText || JSON.stringify(data, null, 2));
      |    renderMatches(data.possibleMatches || []);
      |  } catch(err) {
      |    showError(err.message || String(err));
      |    el.summary.textContent = 'Search failed.';
      |    el.matchesArea.innerHTML = '<pre>Search failed.</pre>';
      |  } finally {
      |    el.runBtn.disabled = false; el.runBtn.textContent = 'Run';
      |  }
      |}
      |
      |function clearAll() {
      |  clearError(); hideCharPanel();
      |  el.characters.value = '';
      |  el.summary.textContent = 'No search yet.';
      |  el.matchesArea.innerHTML = '<pre>No search yet.</pre>';
      |}
      |
      |// ── Saved Characters ──────────────────────────────────────────────────────────
      |function renderSavedChars() {
      |  el.savedCharList.innerHTML = savedChars.length === 0
      |    ? '<option value="">No saved characters</option>'
      |    : savedChars.map(function(c){ return '<option value="' + esc(c) + '">' + esc(c) + '</option>'; }).join('');
      |  el.savedCharDisplay.textContent = savedChars.length === 0 ? 'No saved characters yet.' : savedChars.join('\n');
      |}
      |
      |el.addCharBtn.addEventListener('click', function() {
      |  const name = el.savedCharInput.value.trim();
      |  if (!name || savedChars.includes(name)) return;
      |  savedChars.push(name); persist(); renderSavedChars(); el.savedCharInput.value = '';
      |});
      |el.savedCharInput.addEventListener('keydown', function(e) { if (e.key === 'Enter') el.addCharBtn.click(); });
      |el.useCharBtn.addEventListener('click', function() {
      |  const sel = el.savedCharList.value; if (sel) el.characters.value = sel;
      |});
      |el.removeCharBtn.addEventListener('click', function() {
      |  const sel = el.savedCharList.value;
      |  savedChars = savedChars.filter(function(c){ return c !== sel; });
      |  persist(); renderSavedChars();
      |});
      |
      |// ── Saved Guilds ──────────────────────────────────────────────────────────────
      |function renderSavedGuilds() {
      |  el.savedGuildList.innerHTML = savedGuilds.length === 0
      |    ? '<option value="">No saved guilds</option>'
      |    : savedGuilds.map(function(g){ return '<option value="' + esc(g) + '">' + esc(g) + '</option>'; }).join('');
      |  el.savedGuildDisplay.textContent = savedGuilds.length === 0 ? 'No saved guilds yet.' : savedGuilds.join('\n');
      |}
      |
      |el.savedGuildInput.addEventListener('keydown', function(e) {
      |  if (e.key !== 'Enter') return;
      |  const name = el.savedGuildInput.value.trim();
      |  if (!name || savedGuilds.includes(name)) return;
      |  savedGuilds.push(name); persist(); renderSavedGuilds(); el.savedGuildInput.value = '';
      |});
      |el.refreshGuildBtn.addEventListener('click', async function() {
      |  const sel = el.savedGuildList.value;
      |  if (!sel) { el.savedGuildDisplay.textContent = 'Select a guild first.'; return; }
      |  el.savedGuildDisplay.textContent = 'Refreshing\u2026';
      |  try {
      |    const data = await fetchJson('/api/altfinder/guild?name=' + encodeURIComponent(sel), TIMEOUT_STATUS, 1);
      |    const online = data.onlineCharacters || [];
      |    guildOnlineMap[sel] = online;
      |    el.savedGuildDisplay.textContent = (data.name || sel) + ' (' + (data.world||'?') + ') \u2014 '
      |      + (data.online||0) + '/' + (data.members||0) + ' online\n'
      |      + (online.length ? online.join(', ') : 'No one online');
      |  } catch(err) { el.savedGuildDisplay.textContent = 'Error: ' + (err.message || String(err)); }
      |});
      |el.loadGuildBtn.addEventListener('click', function() {
      |  const sel = el.savedGuildList.value;
      |  const online = guildOnlineMap[sel];
      |  if (!online || online.length === 0) { el.savedGuildDisplay.textContent = 'Refresh the guild first to get online members.'; return; }
      |  el.characters.value = online.join(', ');
      |});
      |el.removeGuildBtn.addEventListener('click', function() {
      |  const sel = el.savedGuildList.value;
      |  savedGuilds = savedGuilds.filter(function(g){ return g !== sel; });
      |  delete guildOnlineMap[sel]; persist(); renderSavedGuilds();
      |});
      |
      |// ── Watchlist ─────────────────────────────────────────────────────────────────
      |async function loadWatchlist() {
      |  const guildId = el.watchGuildId.value.trim();
      |  if (!guildId) { el.watchListArea.textContent = 'Enter a Discord Guild ID.'; return; }
      |  el.watchListArea.textContent = 'Loading\u2026';
      |  try {
      |    const data = await fetchJson('/api/altfinder/watchlist?guildId=' + encodeURIComponent(guildId), TIMEOUT_STATUS, 1);
      |    const watches = data.watches || [];
      |    el.watchListArea.textContent = watches.length === 0
      |      ? 'No watches configured for this guild.'
      |      : watches.map(function(w){ return w.characterName + ' (dist ' + w.distance + ', threshold ' + w.confidenceThreshold + '%)'; }).join('\n');
      |  } catch(err) { el.watchListArea.textContent = 'Error: ' + (err.message || String(err)); }
      |}
      |
      |async function addWatch() {
      |  const guildId   = el.watchGuildId.value.trim();
      |  const channelId = el.watchChannelId.value.trim();
      |  const charName  = el.watchCharInput.value.trim();
      |  if (!guildId || !channelId || !charName) {
      |    el.watchListArea.textContent = 'Guild ID, Channel ID and character name are all required.'; return;
      |  }
      |  el.watchListArea.textContent = 'Adding\u2026';
      |  try {
      |    await fetchJson('/api/altfinder/watchlist/add?guildId=' + encodeURIComponent(guildId)
      |      + '&channelId=' + encodeURIComponent(channelId)
      |      + '&character=' + encodeURIComponent(charName), TIMEOUT_STATUS, 1);
      |    el.watchCharInput.value = '';
      |    await loadWatchlist();
      |  } catch(err) { el.watchListArea.textContent = 'Error: ' + (err.message || String(err)); }
      |}
      |
      |el.addWatchBtn.addEventListener('click', addWatch);
      |el.loadWatchBtn.addEventListener('click', loadWatchlist);
      |el.watchGuildId.addEventListener('keydown', function(e){ if (e.key === 'Enter') loadWatchlist(); });
      |
      |// ── Character detail panel ────────────────────────────────────────────────────
      |function hideCharPanel() { el.charPanel.classList.remove('open'); }
      |el.charPanelClose.addEventListener('click', hideCharPanel);
      |el.matchesArea.addEventListener('click', function(e) {
      |  const td = e.target.closest('[data-name]'); if (td) openCharPanel(td.dataset.name);
      |});
      |
      |async function openCharPanel(name) {
      |  el.charPanelName.textContent = name;
      |  el.charPanelContent.innerHTML = '<div style="color:#8b949e;font-style:italic">Fetching from TibiaData &amp; Exevopan\u2026</div>';
      |  el.charPanel.classList.add('open');
      |  el.charPanel.scrollIntoView({ behavior:'smooth', block:'nearest' });
      |  try {
      |    const d = await fetchJson('/api/altfinder/character?name=' + encodeURIComponent(name), TIMEOUT_CHAR, 2);
      |    const guild = d.guild ? d.guild + (d.guildRank ? ' (' + d.guildRank + ')' : '') : '-';
      |    const former = (d.formerNames || []).join(', ') || '-';
      |    const lastLogin = (d.lastLogin || '-').slice(0, 16).replace('T', ' ');
      |    const traded = (d.recentTradeDates || []).length > 0;
      |    const tradeHtml = traded
      |      ? '<div class="trade-alert-box">\u26a0 RECENTLY TRADED \u2014 ' + esc((d.recentTradeDates||[]).join(', ')) + '</div>'
      |      : '';
      |    const tradeErr = d.tradedCheckError
      |      ? '<div class="trade-alert-box" style="border-color:#f85149;color:#ffa198">\u26a0 Exevopan trade check failed (may be rate-limited).</div>'
      |      : '';
      |    function f(lbl, val) {
      |      return '<div><div class="char-field-lbl">' + esc(lbl) + '</div><div class="char-field-val">' + esc(val||'-') + '</div></div>';
      |    }
      |    el.charPanelName.textContent = d.name || name;
      |    el.charPanelContent.innerHTML = '<div class="char-fields">'
      |      + f('Level', String(d.level||'-')) + f('Vocation', d.vocation) + f('World', d.world)
      |      + f('Sex', d.sex) + f('Guild', guild) + f('Last Login', lastLogin)
      |      + f('Former Names', former) + '</div>'
      |      + tradeHtml + tradeErr
      |      + '<div class="char-links">'
      |      + '<a class="char-link" href="' + esc(d.tibiaComUrl) + '" target="_blank" rel="noopener">Tibia.com \u2197</a>'
      |      + '<a class="char-link" href="' + esc(d.exevopanUrl) + '" target="_blank" rel="noopener">Exevopan \u2197</a>'
      |      + '</div>';
      |  } catch(err) {
      |    el.charPanelContent.innerHTML = '<div style="color:#f85149;font-size:.85rem">Failed: ' + esc(err.message||String(err)) + '</div>';
      |  }
      |}
      |
      |// ── Event wiring ──────────────────────────────────────────────────────────────
      |el.runBtn.addEventListener('click', runSearch);
      |el.clearBtn.addEventListener('click', clearAll);
      |el.characters.addEventListener('keydown', function(e){ if (e.key === 'Enter') runSearch(); });
      |el.backendUrl.addEventListener('change', function() {
      |  localStorage.setItem(STORAGE_BACKEND, el.backendUrl.value.trim());
      |  checkHealth();
      |});
      |
      |// ── Init ──────────────────────────────────────────────────────────────────────
      |loadStorage();
      |checkHealth();
      |</script>
      |</body>
      |</html>
      |""".stripMargin
}


