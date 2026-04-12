package com.pamelak.onlinetracker.altfinder

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.implicits.*

object HttpServer extends IOApp {

  val routes: HttpApp[IO] = HttpRoutes
    .of[IO] {
      case GET -> Root / "api" / "altfinder" / "health" =>
        Ok("OK")
      case GET -> Root / "api" / "altfinder" / "alts" =>
        Ok("""{"status":"ok","data":[]}""")
    }
    .orNotFound

  def run(args: List[String]): IO[ExitCode] = {
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)

    BlazeServerBuilder[IO]
      .bindHttp(port, "0.0.0.0")
      .withHttpApp(routes)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
