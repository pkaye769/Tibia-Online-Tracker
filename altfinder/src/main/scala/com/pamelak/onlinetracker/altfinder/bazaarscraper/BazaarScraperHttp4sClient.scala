package com.pamelak.onlinetracker.altfinder.bazaarscraper

import cats.effect.Async
import cats.effect.IO
import cats.effect.kernel.Concurrent
import cats.effect.kernel.Resource
import cats.implicits.*
import com.pamelak.onlinetracker.altfinder.bazaarscraper.BazaarScraperClientAlg
import io.circe.generic.auto.*
import org.http4s.Status
import org.http4s.Method
import org.http4s.Request
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.client.middleware.GZip
import org.http4s.client.middleware.Retry
import org.http4s.client.middleware.RetryPolicy
import org.http4s.implicits.uri

import scala.concurrent.duration.*

object BazaarScraperHttp4sClient {
  private val retryPolicy: RetryPolicy[IO] = (_, result, unsuccessfulAttempts) => {
    if unsuccessfulAttempts > 2 then None else if result.exists(_.status == Status.Ok) then None else 1.second.some
  }

  val clientResource: Resource[IO, Client[IO]] = BlazeClientBuilder[IO].withRequestTimeout(5.seconds).resource
    .map(GZip()(_)).map(Retry[IO](retryPolicy)(_))
}

class BazaarScraperHttp4sClient[F[_]: Async](client: Client[F]) extends BazaarScraperClientAlg[F] {
  private val apiRoot = uri"https://www.exevopan.com"
  private val bazaarWorld = sys.env.get("BAZAAR_WORLD").orElse(sys.env.get("WORLD")).getOrElse("Nefera")
  private val retryAfterRegex = "(?i)retry-after\\D*(\\d+)".r
  @volatile private var rateLimitedUntilEpochSec: Long = 0L
  private val defaultCooldownSeconds = sys.env.get("BAZAAR_RATE_LIMIT_COOLDOWN_SECONDS").flatMap(_.toLongOption).getOrElse(1800L)

  private def nowEpochSec: Long = System.currentTimeMillis() / 1000L

  private def parseRetryAfterSeconds(text: String): Option[Long] =
    retryAfterRegex.findFirstMatchIn(text).flatMap(m => m.group(1).toLongOption)

  private def activateCooldown(seconds: Long): Unit = {
    val cooldown = math.max(60L, seconds)
    rateLimitedUntilEpochSec = math.max(rateLimitedUntilEpochSec, nowEpochSec + cooldown)
  }

  private val emptyResultJson = """{"page":[]}"""

  override def cooldownRemainingSeconds: F[Long] =
    Async[F].pure(math.max(0L, rateLimitedUntilEpochSec - nowEpochSec))

  def searchCharacter(name: String): F[String] = {
    if (nowEpochSec < rateLimitedUntilEpochSec) {
      Async[F].pure(emptyResultJson)
    } else {
    // nicknameFilter for exevopan is a "contains" rather than exact match, so here we grab a lot of results to be safe
    // and handling pagination is too much effort
    val target = (apiRoot / "api/auctions").withQueryParams(Map(
      ("nicknameFilter", name),
      ("serverSet", bazaarWorld),
      ("descending", "true"),
      ("history", "true"),
      ("pageSize", "100")
    ))
      val req = Request[F](Method.GET, target)
      client.run(req).use { res =>
        res.as[String].flatMap { body =>
          val lower = body.toLowerCase
          val retryAfterHeaderSeconds =
            res.headers.headers
              .find(h => h.name.toString.equalsIgnoreCase("Retry-After"))
              .flatMap(h => h.value.toLongOption)
          val retryAfterBodySeconds = parseRetryAfterSeconds(body)
          val retryAfterSeconds = retryAfterHeaderSeconds.orElse(retryAfterBodySeconds).getOrElse(defaultCooldownSeconds)

          if (res.status.code == 429 || (lower.contains("cloudflare") && lower.contains("rate limit"))) {
            Async[F].delay(activateCooldown(retryAfterSeconds)) *>
              Async[F].pure(emptyResultJson)
          } else if (!res.status.isSuccess) {
            Async[F].pure(emptyResultJson)
          } else {
            Async[F].pure(body)
          }
        }
      }
    }
  }

}
