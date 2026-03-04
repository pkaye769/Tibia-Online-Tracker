package com.pamelak.onlinetracker.altfinder.repo

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, OffsetDateTime, ZoneId}

object Model {
  case class OnlineSegment(characterId: Long, start: Long, end: Long)

  case class OnlineDateSegment(characterName: String, start: OffsetDateTime, end: OffsetDateTime) {
    override def toString: String = s"${characterName.padTo(32, ' ')} ${start.atLocal} - ${end.atLocal}"

    def onlineDiscordFormat = s"${start.toDiscord} - ${end.toDiscord}"

    extension (d: OffsetDateTime)

      private def atLocal: String =
        // d.atZoneSameInstant(ZoneId.of("Brazil/East")).toLocalDateTime .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        d.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

      private def toDiscord: String = {
        val unixTimestamp = d.toEpochSecond()
        s"<t:$unixTimestamp:d> <t:$unixTimestamp:t>"
      }
  }

  case class LastSearch(
      characters: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Option[Int],
      includeClashes: Boolean
  )

  case class WatchConfig(
      guildId: String,
      channelId: String,
      characterName: String,
      distance: Int,
      includeClashes: Boolean,
      confidenceThreshold: Int,
      windowDays: Int
  )

  case class WatchEntry(
      id: Long,
      guildId: String,
      channelId: String,
      characterName: String,
      distance: Int,
      includeClashes: Boolean,
      confidenceThreshold: Int,
      windowDays: Int,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime,
      lastCheckedAt: Option[OffsetDateTime],
      lastAlertAt: Option[OffsetDateTime]
  )

  case class GuildTrackConfig(
      guildId: String,
      tibiaGuildName: String
  )

  case class GuildTrackEntry(
      id: Long,
      guildId: String,
      tibiaGuildName: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )

  case class ResearchRunWrite(
      runType: String,
      searchedCharacters: List[String],
      targetCharacters: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Int,
      includeClashes: Boolean,
      totalLogins: Int,
      matchCount: Int,
      summary: String
  )

  case class ResearchRun(
      id: Long,
      runType: String,
      searchedCharacters: List[String],
      targetCharacters: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Int,
      includeClashes: Boolean,
      totalLogins: Int,
      matchCount: Int,
      summary: String,
      createdAt: OffsetDateTime
  )
}
