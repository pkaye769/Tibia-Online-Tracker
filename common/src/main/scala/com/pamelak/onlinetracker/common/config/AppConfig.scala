package com.pamelak.onlinetracker.common.config

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

final case class DatabaseConfig(host: String, port: Int, user: String, database: String, password: String)

final case class BotConfig(token: String)

final case class Config(database: DatabaseConfig, bot: BotConfig)

object AppConfig {
  val databaseConfig: ConfigValue[Effect, DatabaseConfig] = (
    env("DB_HOST").as[String],
    env("DB_PORT").as[Int],
    env("DB_USER").as[String],
    env("DB_NAME").as[String],
    env("DB_PASSWORD").as[String]
  ).parMapN(DatabaseConfig.apply)

  val botConfig: ConfigValue[Effect, BotConfig] = env("TOKEN").as[String].default("").map(BotConfig.apply)

  val config: ConfigValue[Effect, Config] = (databaseConfig, botConfig).parMapN(Config.apply)

  private def readDotEnv(): Map[String, String] =
    def load(path: java.nio.file.Path): Map[String, String] =
      Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .flatMap { line =>
          line.split("=", 2) match
            case Array(key, value) => Some(key.trim -> value.trim)
            case _ => None
        }
        .toMap

    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(cwd)(current => Option(current.getParent).orNull)
      .takeWhile(_ != null)
      .map(_.resolve(".env"))
      .find(Files.exists(_))
      .fold(Map.empty[String, String])(load)

  private def envWithDotEnv(name: String): Option[String] =
    sys.env.get(name).orElse(readDotEnv().get(name))

  private def requiredEnv(name: String): String =
    envWithDotEnv(name).getOrElse(throw new RuntimeException(s"Missing environment variable $name"))

  private def requiredInt(name: String): Int =
    requiredEnv(name).toIntOption.getOrElse(throw new RuntimeException(s"Invalid integer value for $name"))

  private def parseDatabaseUrl(url: String): DatabaseConfig = {
    val uri = new java.net.URI(url.replaceFirst("^jdbc:", ""))
    val scheme = uri.getScheme
    if (scheme != "postgres" && scheme != "postgresql")
      throw new RuntimeException(s"Unsupported DATABASE_URL scheme: $scheme")
    val decode = (s: String) => java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8)
    val userInfo = Option(uri.getUserInfo).getOrElse("")
    val parts = userInfo.split(":", 2)
    val user = parts.headOption.map(decode).getOrElse("")
    val password = if (parts.length > 1) decode(parts(1)) else ""
    val host = Option(uri.getHost).getOrElse(
      throw new RuntimeException(s"DATABASE_URL is missing a host")
    )
    val port = if (uri.getPort == -1) 5432 else uri.getPort
    val database = Option(uri.getPath).map(_.stripPrefix("/")).getOrElse(
      throw new RuntimeException(s"DATABASE_URL is missing a database path")
    )
    DatabaseConfig(host, port, user, database, password)
  }

  def loadDatabaseConfigIO: IO[DatabaseConfig] = IO.blocking {
    envWithDotEnv("DATABASE_URL") match {
      case Some(url) if url.nonEmpty => parseDatabaseUrl(url)
      case _ =>
        DatabaseConfig(
          host = requiredEnv("DB_HOST"),
          port = requiredInt("DB_PORT"),
          user = requiredEnv("DB_USER"),
          database = requiredEnv("DB_NAME"),
          password = requiredEnv("DB_PASSWORD")
        )
    }
  }

  def loadConfigIO: IO[Config] = loadDatabaseConfigIO.map { dbCfg =>
    Config(dbCfg, BotConfig(envWithDotEnv("TOKEN").getOrElse("")))
  }
}
