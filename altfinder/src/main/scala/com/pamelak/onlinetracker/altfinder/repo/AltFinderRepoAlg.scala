package com.pamelak.onlinetracker.altfinder.repo

import com.pamelak.onlinetracker.altfinder.repo.Model.*

import java.time.OffsetDateTime

trait AltFinderRepoAlg[F[_]] {
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
  def countOnlineHistoryRows: F[Long]
  def latestWorldSaveTime: F[Option[OffsetDateTime]]
}
