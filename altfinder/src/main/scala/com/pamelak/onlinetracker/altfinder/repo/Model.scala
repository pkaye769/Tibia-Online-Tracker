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

  case class WorldTransfer(
      characterName: String,
      fromWorld: String,
      toWorld: String,
      transferTime: OffsetDateTime
  )

  case class LevelEventConfig(guildId: String, tibiaGuildName: String)

  case class LevelEventEntry(
      id: Long,
      guildId: String,
      tibiaGuildName: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )

  case class LevelSnapshotWrite(guildId: String, tibiaGuildName: String, characterName: String, level: Int)

  case class LevelSnapshotRow(
      id: Long,
      guildId: String,
      tibiaGuildName: String,
      characterName: String,
      level: Int,
      recordedAt: OffsetDateTime
  )

  case class LevelCharData(name: String, startLevel: Int, endLevel: Int, gained: Int)

  case class LevelBracket(id: Int, displayName: String, minLevel: Int, maxLevel: Option[Int])

  object LevelBracket {
    val all: List[LevelBracket] = List(
      LevelBracket(1, "1-199", 1, Some(199)),
      LevelBracket(2, "200-399", 200, Some(399)),
      LevelBracket(3, "400-599", 400, Some(599)),
      LevelBracket(4, "600-799", 600, Some(799)),
      LevelBracket(5, "800-999", 800, Some(999)),
      LevelBracket(6, "1000+", 1000, None)
    )

    def forLevel(level: Int): LevelBracket =
      all.find(b => level >= b.minLevel && b.maxLevel.forall(level <= _)).getOrElse(all.last)
  }
}
