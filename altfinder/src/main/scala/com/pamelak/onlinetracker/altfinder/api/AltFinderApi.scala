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

  final case class OnlineNamesResponse(count: Int, names: List[String])

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
  given Encoder[OnlineNamesResponse] = deriveEncoder

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

    case GET -> Root / "api" / "altfinder" / "online" =>
      val cacheKey = "online"
      cachedJson(cacheKey) {
        repo.getCurrentlyOnlineNames.map { names =>
          OnlineNamesResponse(count = names.length, names = names).asJson
        }
      }.flatMap(json => Ok(json)).handleErrorWith { e =>
        InternalServerError(ErrorResponse("Could not fetch online names", List(Option(e.getMessage).getOrElse("unknown error"))).asJson)
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

  private[api] def parseDate(raw: String): Either[String, OffsetDateTime] = {
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

  private[api] def buildDateRange(from: Option[OffsetDateTime], to: Option[OffsetDateTime]): String = {
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

  private[api] def formatClassic(adj: CharacterAdjacencies): String = {
    val name = adj.characterName.getOrElse("Unknown")
    val clashText = if (adj.clashes < 0) "yes" else adj.clashes.toString
    s"$name: ${adj.adjacencies} / $clashText / ${adj.logins}"
  }

  private[api] def formatDetailed(adj: CharacterAdjacencies): String = {
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

  private[api] def appendMinutes(i: Int) = if (i == 1) s"$i minute" else s"$i minutes"

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

  private val uiHtml: String = {
    def readRes(name: String): String = {
      val stream = Option(Thread.currentThread.getContextClassLoader.getResourceAsStream(name))
        .getOrElse(throw new RuntimeException(s"Web resource not found on classpath (expected in docs/ directory): $name"))
      val source = scala.io.Source.fromInputStream(stream)
      try source.mkString finally { source.close(); stream.close() }
    }
    val html = readRes("index.html")
    val js   = readRes("app.js")
    val css  = readRes("styles.css")
    html
      .replace("<link rel=\"stylesheet\" href=\"./styles.css\"/>", s"<style>\n$css\n</style>")
      .replace("<script src=\"./app.js\"></script>", s"<script>\n$js\n</script>")
  }
}


