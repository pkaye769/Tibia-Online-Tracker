package com.pamelak.onlinetracker.altfinder.tibiadata

import cats.effect.kernel.{Concurrent, Resource}
import cats.effect.{IO, Sync}
import cats.implicits.*
import io.circe.Json
import org.http4s.Status
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.client.middleware.{GZip, Retry, RetryPolicy}
import org.http4s.implicits.uri

import scala.concurrent.duration.*

object TibiaDataHttp4sClient {
  private val retryPolicy: RetryPolicy[IO] = (_, result, unsuccessfulAttempts) => {
    if unsuccessfulAttempts > 2 then None else if result.exists(_.status == Status.Ok) then None else 1.second.some
  }

  val clientResource: Resource[IO, Client[IO]] = BlazeClientBuilder[IO].withRequestTimeout(10.seconds).resource
    .map(GZip()(_)).map(Retry[IO](retryPolicy)(_))
}

class TibiaDataHttp4sClient[F[_]: Sync](client: Client[F])(using Concurrent[F])
    extends TibiaDataClientAlg[F] {

  private val apiRoot = uri"https://api.tibiadata.com/v4"

  def getWorld(world: String): F[Json] = {
    val target = apiRoot / "world" / world
    client.expect(target)(jsonOf[F, Json])
  }

  def getGuild(name: String): F[Json] = {
    val target = apiRoot / "guild" / name
    client.expect(target)(jsonOf[F, Json])
  }

  def getCharacter(name: String): F[Json] = {
    val target = apiRoot / "character" / name
    client.expect(target)(jsonOf[F, Json])
  }
}
