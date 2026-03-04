package com.pamelak.onlinetracker.altfinder.repo

import com.pamelak.onlinetracker.altfinder.repo.Model.*
import skunk.codec.all.{bool, int4, int8, text, timestamptz, varchar}
import skunk.implicits.{sql, toIdOps}
import skunk.{Decoder, Encoder, ~}

import java.time.OffsetDateTime

trait AltFinderCodecs {
  val onlineSegmentDecoder: Decoder[OnlineSegment] =
    (int8 ~ int8 ~ int8).map {
      case id ~ start ~ end => OnlineSegment(id, start, end)
    }

  val onlineDateSegmentDecoder: Decoder[OnlineDateSegment] =
    (varchar ~ timestamptz ~ timestamptz).map {
      case name ~ start ~ end => OnlineDateSegment(name, start, end)
    }

  val lastSearchDecoder: Decoder[(String, Option[OffsetDateTime], Option[OffsetDateTime], Option[Int], Boolean)] =
    (text ~ timestamptz.opt ~ timestamptz.opt ~ int4.opt ~ bool).map {
      case chars ~ from ~ to ~ distance ~ includeClashes => (chars, from, to, distance, includeClashes)
    }

  val watchEntryDecoder
      : Decoder[(Long, String, String, String, Int, Boolean, Int, Int, OffsetDateTime, OffsetDateTime, Option[OffsetDateTime], Option[OffsetDateTime])] =
    (int8 ~ text ~ text ~ text ~ int4 ~ bool ~ int4 ~ int4 ~ timestamptz ~ timestamptz ~ timestamptz.opt ~ timestamptz.opt)
      .map {
        case id ~ guildId ~ channelId ~ name ~ distance ~ includeClashes ~ threshold ~ windowDays ~ createdAt ~ updatedAt ~ lastChecked ~ lastAlert =>
          (id, guildId, channelId, name, distance, includeClashes, threshold, windowDays, createdAt, updatedAt, lastChecked, lastAlert)
      }

  val guildTrackEntryDecoder: Decoder[(Long, String, String, OffsetDateTime, OffsetDateTime)] =
    (int8 ~ text ~ text ~ timestamptz ~ timestamptz).map {
      case id ~ guildId ~ tibiaGuildName ~ createdAt ~ updatedAt =>
        (id, guildId, tibiaGuildName, createdAt, updatedAt)
    }

  val researchRunDecoder
      : Decoder[(Long, String, String, String, Option[OffsetDateTime], Option[OffsetDateTime], Int, Boolean, Int, Int, String, OffsetDateTime)] =
    (int8 ~ text ~ text ~ text ~ timestamptz.opt ~ timestamptz.opt ~ int4 ~ bool ~ int4 ~ int4 ~ text ~ timestamptz).map {
      case id ~ runType ~ searchedCharacters ~ targetCharacters ~ from ~ to ~ distance ~ includeClashes ~ totalLogins ~ matchCount ~ summary ~ createdAt =>
        (id, runType, searchedCharacters, targetCharacters, from, to, distance, includeClashes, totalLogins, matchCount, summary, createdAt)
    }
}
