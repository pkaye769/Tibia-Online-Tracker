package com.pamelak.onlinetracker.tracker.tibiadata

import com.pamelak.onlinetracker.tracker.tibiadata.response.*
import io.circe.generic.auto.*
import io.circe.parser.decode

class TibiaDataDecodersSpec extends munit.FunSuite with TibiaDataDecoders {

  // ---- decodeAccountInformation ----

  test("decodeAccountInformation returns None for an empty JSON object {}") {
    val result = decode[Option[AccountInformation]]("{}")(using decodeAccountInformation)
    assertEquals(result, Right(None))
  }

  test("decodeAccountInformation returns None for a JSON null") {
    val result = decode[Option[AccountInformation]]("null")(using decodeAccountInformation)
    assertEquals(result, Right(None))
  }

  test("decodeAccountInformation returns Some when all fields are present") {
    val json   = """{"position":null,"created":"2020-01-01T00:00:00Z","loyalty_title":null}"""
    val result = decode[Option[AccountInformation]](json)(using decodeAccountInformation)
    assertEquals(result, Right(Some(AccountInformation(None, "2020-01-01T00:00:00Z", None))))
  }

  test("decodeAccountInformation returns Some with optional fields populated") {
    val json   = """{"position":"CipSoft Member","created":"2010-05-01","loyalty_title":"Ancestral Legend"}"""
    val result = decode[Option[AccountInformation]](json)(using decodeAccountInformation)
    assertEquals(result, Right(Some(AccountInformation(Some("CipSoft Member"), "2010-05-01", Some("Ancestral Legend")))))
  }

  test("decodeAccountInformation returns Left when required field is missing") {
    // `created` is a required (non-optional) field; omitting it should produce a decode error
    val json   = """{"position":null,"loyalty_title":null}"""
    val result = decode[Option[AccountInformation]](json)(using decodeAccountInformation)
    assert(result.isLeft)
  }

  // ---- decodeGuild ----

  test("decodeGuild returns None for an empty JSON object {}") {
    val result = decode[Option[Guild]]("{}")(using decodeGuild)
    assertEquals(result, Right(None))
  }

  test("decodeGuild returns None for a JSON null") {
    val result = decode[Option[Guild]]("null")(using decodeGuild)
    assertEquals(result, Right(None))
  }

  test("decodeGuild returns Some for a fully populated object") {
    val json   = """{"name":"The Brotherhood","rank":"Leader"}"""
    val result = decode[Option[Guild]](json)(using decodeGuild)
    assertEquals(result, Right(Some(Guild("The Brotherhood", "Leader"))))
  }

  test("decodeGuild returns Left when a required field is missing") {
    val json   = """{"name":"The Brotherhood"}"""
    val result = decode[Option[Guild]](json)(using decodeGuild)
    assert(result.isLeft)
  }

  test("decodeGuild non-empty object with wrong type for field returns Left") {
    val json   = """{"name":123,"rank":"Leader"}"""
    val result = decode[Option[Guild]](json)(using decodeGuild)
    assert(result.isLeft)
  }
}
