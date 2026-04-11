package com.pamelak.onlinetracker.common.config

import skunk.SSL

class AppConfigSpec extends munit.FunSuite {

  // ---- parseDatabaseUrl ----

  test("parseDatabaseUrl parses host, port, user, password, database") {
    val cfg = AppConfig.parseDatabaseUrl("postgres://alice:secret@db.example.com:5432/mydb")
    assertEquals(cfg.host, "db.example.com")
    assertEquals(cfg.port, 5432)
    assertEquals(cfg.user, "alice")
    assertEquals(cfg.password, "secret")
    assertEquals(cfg.database, "mydb")
    assertEquals(cfg.sslMode, None)
  }

  test("parseDatabaseUrl strips jdbc: prefix") {
    val cfg = AppConfig.parseDatabaseUrl("jdbc:postgres://alice:secret@db.example.com:5432/mydb")
    assertEquals(cfg.host, "db.example.com")
    assertEquals(cfg.port, 5432)
    assertEquals(cfg.user, "alice")
    assertEquals(cfg.password, "secret")
    assertEquals(cfg.database, "mydb")
  }

  test("parseDatabaseUrl accepts postgresql scheme") {
    val cfg = AppConfig.parseDatabaseUrl("postgresql://alice:secret@db.example.com:5432/mydb")
    assertEquals(cfg.host, "db.example.com")
  }

  test("parseDatabaseUrl defaults port to 5432 when omitted") {
    val cfg = AppConfig.parseDatabaseUrl("postgres://alice:secret@db.example.com/mydb")
    assertEquals(cfg.port, 5432)
  }

  test("parseDatabaseUrl parses sslmode query param") {
    val cfg = AppConfig.parseDatabaseUrl("postgres://alice:secret@db.example.com:5432/mydb?sslmode=require")
    assertEquals(cfg.sslMode, Some("require"))
  }

  test("parseDatabaseUrl parses sslmode=disable") {
    val cfg = AppConfig.parseDatabaseUrl("postgres://alice:secret@db.example.com:5432/mydb?sslmode=disable")
    assertEquals(cfg.sslMode, Some("disable"))
  }

  test("parseDatabaseUrl rejects unknown scheme") {
    intercept[RuntimeException] {
      AppConfig.parseDatabaseUrl("mysql://alice:secret@db.example.com:5432/mydb")
    }
  }

  // ---- resolveSSL ----

  private def dbCfg(host: String, sslMode: Option[String] = None) =
    DatabaseConfig(host, 5432, "user", "db", "pass", sslMode)

  test("resolveSSL returns SSL.None for localhost with no env override") {
    assertEquals(AppConfig.resolveSSL(dbCfg("localhost"), None), SSL.None)
  }

  test("resolveSSL returns SSL.None for 127.0.0.1 with no env override") {
    assertEquals(AppConfig.resolveSSL(dbCfg("127.0.0.1"), None), SSL.None)
  }

  test("resolveSSL returns SSL.Trusted for remote host with no env override") {
    assertEquals(AppConfig.resolveSSL(dbCfg("db.example.com"), None), SSL.Trusted)
  }

  test("resolveSSL returns SSL.None when DB_SSL=disable") {
    assertEquals(AppConfig.resolveSSL(dbCfg("db.example.com"), Some("disable")), SSL.None)
  }

  test("resolveSSL returns SSL.None when DB_SSL=false") {
    assertEquals(AppConfig.resolveSSL(dbCfg("db.example.com"), Some("false")), SSL.None)
  }

  test("resolveSSL returns SSL.Trusted when DB_SSL=require") {
    assertEquals(AppConfig.resolveSSL(dbCfg("localhost"), Some("require")), SSL.Trusted)
  }

  test("resolveSSL returns SSL.Trusted when DB_SSL=true") {
    assertEquals(AppConfig.resolveSSL(dbCfg("localhost"), Some("true")), SSL.Trusted)
  }

  test("resolveSSL returns SSL.System when DB_SSL=verify-full") {
    assertEquals(AppConfig.resolveSSL(dbCfg("db.example.com"), Some("verify-full")), SSL.System)
  }

  test("resolveSSL respects sslmode in URL when no env override") {
    assertEquals(AppConfig.resolveSSL(dbCfg("db.example.com", Some("disable")), None), SSL.None)
    assertEquals(AppConfig.resolveSSL(dbCfg("db.example.com", Some("require")), None), SSL.Trusted)
  }
}
