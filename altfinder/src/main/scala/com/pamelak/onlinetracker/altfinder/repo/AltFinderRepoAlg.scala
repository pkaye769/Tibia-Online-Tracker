package com.pamelak.onlinetracker.altfinder.repo

import com.pamelak.onlinetracker.altfinder.repo.Model.*

import java.time.OffsetDateTime

trait AltFinderRepoAlg[F[_]] {
  def ensureSchema: F[Unit]

  def getOnlineTimes(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): F[List[OnlineSegment]]

  def getPossibleMatches(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Option[Int]
  ): F[List[OnlineSegment]]

  def getCharacterName(characterId: Long): F[String]

  def getCharacterHistories(
      characterName: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): F[List[OnlineDateSegment]]

  def getPastCharacterNames(characterName: String): F[List[String]]

  def saveLastSearch(search: LastSearch): F[Unit]
  def getLastSearch: F[Option[LastSearch]]

  def upsertWatch(config: WatchConfig): F[Unit]
  def removeWatch(guildId: String, characterName: String): F[Boolean]
  def listWatches(guildId: String): F[List[WatchEntry]]
  def listAllWatches: F[List[WatchEntry]]
  def updateWatchCheck(id: Long, checkedAt: OffsetDateTime, alertedAt: Option[OffsetDateTime]): F[Unit]
  def upsertTrackedGuild(config: GuildTrackConfig): F[Unit]
  def removeTrackedGuild(guildId: String, tibiaGuildName: String): F[Boolean]
  def listTrackedGuilds(guildId: String): F[List[GuildTrackEntry]]
  def saveResearchRun(run: ResearchRunWrite): F[Unit]
  def listResearchRuns(limit: Int): F[List[ResearchRun]]
  def countTotalLogins(characterNames: List[String]): F[Int]
  def countOnlineHistoryRows: F[Long]
  def latestWorldSaveTime: F[Option[OffsetDateTime]]
  def getCurrentlyOnlineNames: F[List[String]]
  def getWorldTransfers(world: String, lookbackDays: Int): F[List[WorldTransfer]]

  def upsertLevelEvent(config: LevelEventConfig): F[Unit]
  def removeLevelEvent(guildId: String, tibiaGuildName: String): F[Boolean]
  def listLevelEvents(guildId: String): F[List[LevelEventEntry]]
  def listAllLevelEvents: F[List[LevelEventEntry]]
  def insertLevelSnapshot(snapshot: LevelSnapshotWrite): F[Unit]
  def getLevelSnapshots(guildId: String, tibiaGuildName: String): F[List[LevelSnapshotRow]]
  def getLatestLevelsByGuild(guildId: String, tibiaGuildName: String): F[Map[String, Int]]
  def clearLevelSnapshots(guildId: String, tibiaGuildName: String): F[Unit]
}
