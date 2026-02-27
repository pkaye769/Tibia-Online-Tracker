package com.pamelak.onlinetracker.altfinder.api

import cats.effect.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraper
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import com.pamelak.onlinetracker.altfinder.service.AltFinderService.CharacterAdjacencies
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
import scala.util.Try

final class AltFinderApi[F[_]: Async](service: AltFinderService[F]) {
  private val dsl = new Http4sDsl[F] {}
  import dsl.*

  private val berlinZone = ZoneId.of("Europe/Berlin")

  final case class Health(status: String)
  final case class TrackerStatusResponse(onlineHistoryRows: Long, latestWorldSave: Option[String])
  final case class ErrorResponse(error: String, details: List[String])
  final case class TradeSummary(title: String, message: String)
  final case class AltMatch(
      name: String,
      adjacencies: Int,
      clashes: Int,
      logins: Int,
      confidence: Int,
      hiddenScore: Int,
      hiddenLikely: Boolean,
      recentTradeDates: List[String],
      formatted: String
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

  def routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      Ok(uiHtml).map(_.withContentType(`Content-Type`(MediaType.text.html)))

    case GET -> Root / "altfinder" =>
      Ok(uiHtml).map(_.withContentType(`Content-Type`(MediaType.text.html)))

    case GET -> Root / "api" / "altfinder" / "health" =>
      Ok(Health("ok").asJson)

    case GET -> Root / "api" / "altfinder" / "status" =>
      service.getTrackerStatus.map { status =>
        TrackerStatusResponse(
          onlineHistoryRows = status.onlineHistoryRows,
          latestWorldSave = status.latestWorldSave.map(_.toString)
        )
      }.flatMap(s => Ok(s.asJson))

    case req @ GET -> Root / "api" / "altfinder" / "alts" =>
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
        service.findAndPrintAlts(characters, from, to, distanceOpt, includeClashes).map { results =>
          val dateMessage = buildDateRange(results.searchedFrom, results.searchedTo)
          val tradeSummary = buildTradeSummary(results.sales, from)
          val formattedMatches = results.adjacencies.take(20).map { adj =>
            val formatted = if (format == "classic") formatClassic(adj) else formatDetailed(adj)
            AltMatch(
              name = adj.characterName.getOrElse("Unknown"),
              adjacencies = adj.adjacencies,
              clashes = adj.clashes,
              logins = adj.logins,
              confidence = adj.confidence,
              hiddenScore = adj.hiddenScore,
              hiddenLikely = adj.hiddenLikely,
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

          Ok(response.asJson)
        }.flatten
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
    s"$name | conf ${adj.confidence} | hidden $hiddenText | adj ${adj.adjacencies} | clashes $clashText | logins ${adj.logins} | traded $tradeText"
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
      |  <title>Tibia Alt Finder</title>
      |  <style>
      |    :root {
      |      --bg1: #0f172a;
      |      --bg2: #111827;
      |      --panel: #0b1220cc;
      |      --line: #334155;
      |      --text: #e2e8f0;
      |      --muted: #94a3b8;
      |      --accent: #22c55e;
      |      --accent2: #06b6d4;
      |      --danger: #ef4444;
      |    }
      |    * { box-sizing: border-box; }
      |    body {
      |      margin: 0;
      |      font-family: "Trebuchet MS", "Segoe UI", sans-serif;
      |      color: var(--text);
      |      background: radial-gradient(circle at 10% 10%, #1d4ed8 0%, transparent 40%),
      |                  radial-gradient(circle at 90% 20%, #0ea5e9 0%, transparent 35%),
      |                  linear-gradient(140deg, var(--bg1), var(--bg2));
      |      min-height: 100vh;
      |    }
      |    .wrap { max-width: 980px; margin: 0 auto; padding: 24px; }
      |    .card {
      |      border: 1px solid var(--line);
      |      background: var(--panel);
      |      backdrop-filter: blur(6px);
      |      border-radius: 14px;
      |      padding: 16px;
      |      box-shadow: 0 12px 40px #00000055;
      |    }
      |    h1 { margin: 0 0 12px; font-size: 1.6rem; letter-spacing: 0.4px; }
      |    .sub { color: var(--muted); margin-bottom: 16px; }
      |    .grid { display: grid; gap: 12px; grid-template-columns: repeat(4, minmax(0, 1fr)); }
      |    .full { grid-column: 1 / -1; }
      |    label { font-size: 0.85rem; color: var(--muted); display: block; margin-bottom: 6px; }
      |    input, select {
      |      width: 100%; padding: 10px 11px; border-radius: 10px;
      |      border: 1px solid var(--line); background: #0f172a; color: var(--text);
      |    }
      |    .actions { display: flex; gap: 10px; align-items: center; margin-top: 8px; }
      |    button {
      |      border: none; border-radius: 10px; padding: 10px 14px; cursor: pointer;
      |      color: #052e16; font-weight: 700; background: linear-gradient(90deg, var(--accent), var(--accent2));
      |    }
      |    .error { color: var(--danger); font-weight: 600; min-height: 20px; }
      |    .status { color: var(--muted); min-height: 20px; }
      |    pre {
      |      margin: 0; white-space: pre-wrap; border: 1px solid var(--line);
      |      border-radius: 10px; background: #020617; padding: 12px; max-height: 480px; overflow: auto;
      |    }
      |    @media (max-width: 820px) {
      |      .grid { grid-template-columns: 1fr 1fr; }
      |    }
      |    @media (max-width: 560px) {
      |      .grid { grid-template-columns: 1fr; }
      |    }
      |  </style>
      |</head>
      |<body>
      |  <div class="wrap">
      |    <div class="card">
      |      <h1>Tibia Alt Finder</h1>
      |      <div class="sub">Search from your tracked login/logout history.</div>
      |      <div class="grid">
      |        <div class="full">
      |          <label for="characters">Characters (comma separated)</label>
      |          <input id="characters" placeholder="deli tokes, another name" />
      |        </div>
      |        <div>
      |          <label for="from">From (YYYY-MM-DD)</label>
      |          <input id="from" placeholder="2026-02-01" />
      |        </div>
      |        <div>
      |          <label for="to">To (YYYY-MM-DD)</label>
      |          <input id="to" placeholder="2026-02-27" />
      |        </div>
      |        <div>
      |          <label for="distance">Adjacency distance (minutes)</label>
      |          <input id="distance" type="number" min="0" value="0" />
      |        </div>
      |        <div>
      |          <label for="clashes">Include clashes</label>
      |          <select id="clashes">
      |            <option value="false" selected>false</option>
      |            <option value="true">true</option>
      |          </select>
      |        </div>
      |      </div>
      |      <div class="actions">
      |        <button id="runBtn">Run Search</button>
      |        <span class="status" id="status"></span>
      |      </div>
      |      <div class="error" id="error"></div>
      |      <pre id="output">No search yet.</pre>
      |    </div>
      |  </div>
      |  <script>
      |    const $ = (id) => document.getElementById(id);
      |    const statusEl = $("status");
      |    const errorEl = $("error");
      |    const outputEl = $("output");
      |
      |    async function runSearch() {
      |      errorEl.textContent = "";
      |      statusEl.textContent = "Searching...";
      |      outputEl.textContent = "Loading...";
      |
      |      const q = new URLSearchParams();
      |      const chars = $("characters").value.trim();
      |      const from = $("from").value.trim();
      |      const to = $("to").value.trim();
      |      const distance = $("distance").value.trim();
      |      const includeClashes = $("clashes").value;
      |
      |      if (!chars) {
      |        errorEl.textContent = "Characters is required.";
      |        statusEl.textContent = "";
      |        outputEl.textContent = "No search yet.";
      |        return;
      |      }
      |
      |      q.set("characters", chars);
      |      if (from) q.set("from", from);
      |      if (to) q.set("to", to);
      |      if (distance) q.set("distance", distance);
      |      q.set("includeClashes", includeClashes);
      |      q.set("format", "detailed");
      |
      |      try {
      |        const res = await fetch("/api/altfinder/alts?" + q.toString());
      |        const data = await res.json();
      |        if (!res.ok) {
      |          throw new Error(data.error ? (data.error + " | " + (data.details || []).join("; ")) : "Request failed");
      |        }
      |        outputEl.textContent = data.formattedText || JSON.stringify(data, null, 2);
      |        statusEl.textContent = "Done.";
      |      } catch (e) {
      |        errorEl.textContent = e.message || String(e);
      |        outputEl.textContent = "Search failed.";
      |        statusEl.textContent = "";
      |      }
      |    }
      |
      |    $("runBtn").addEventListener("click", runSearch);
      |    $("characters").addEventListener("keydown", (e) => {
      |      if (e.key === "Enter") runSearch();
      |    });
      |  </script>
      |</body>
      |</html>
      |""".stripMargin
}


