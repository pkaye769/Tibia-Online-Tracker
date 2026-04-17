package com.pamelak.onlinetracker.tracker.repo

import cats.Monad
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import com.pamelak.onlinetracker.common.repo.SkunkExtensions
import com.pamelak.onlinetracker.tracker.repo.Model.*
import skunk.*
import skunk.codec.all.int8
import skunk.codec.all.timestamptz
import skunk.codec.all.varchar
import skunk.implicits.sql
import skunk.implicits.toIdOps

import java.time.OffsetDateTime

class OnlineTrackerSkunkRepo(val session: Session[IO])
    extends OnlineTrackerRepoAlg[IO] with OnlineTrackerCodecs with SkunkExtensions {

  override def ensureSchema: IO[Unit] = {
    val createWorldTable = sql"""
      CREATE TABLE IF NOT EXISTS world (
        id   BIGSERIAL PRIMARY KEY,
        name VARCHAR   NOT NULL UNIQUE
      )
    """.command

    val createCharacterTable = sql"""
      CREATE TABLE IF NOT EXISTS character (
        id                 BIGSERIAL PRIMARY KEY,
        name               VARCHAR   NOT NULL UNIQUE,
        created            TIMESTAMPTZ NOT NULL,
        current_name_since TIMESTAMPTZ NOT NULL
      )
    """.command

    val createCharacterNameHistoryTable = sql"""
      CREATE TABLE IF NOT EXISTS character_name_history (
        id           BIGSERIAL PRIMARY KEY,
        character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
        name         VARCHAR   NOT NULL,
        from_date    TIMESTAMPTZ NOT NULL,
        until_date   TIMESTAMPTZ NOT NULL
      )
    """.command

    val createWorldSaveTimeTable = sql"""
      CREATE TABLE IF NOT EXISTS world_save_time (
        id          BIGSERIAL PRIMARY KEY,
        world_id    BIGINT NOT NULL REFERENCES world(id) ON DELETE CASCADE,
        sequence_id BIGINT NOT NULL,
        time        TIMESTAMPTZ NOT NULL,
        UNIQUE (world_id, sequence_id),
        UNIQUE (world_id, time)
      )
    """.command

    val createCurrentlyOnlineTable = sql"""
      CREATE TABLE IF NOT EXISTS currently_online (
        character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
        world_id     BIGINT NOT NULL REFERENCES world(id) ON DELETE CASCADE,
        login_time   BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE,
        PRIMARY KEY (character_id, world_id)
      )
    """.command

    val createOnlineHistoryTable = sql"""
      CREATE TABLE IF NOT EXISTS online_history (
        character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
        login_time   BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE,
        logout_time  BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE
      )
    """.command

    val createOnlineHistoryCharacterIdx = sql"""
      CREATE INDEX IF NOT EXISTS online_history_character_id_idx ON online_history(character_id)
    """.command

    val createOnlineHistoryLoginIdx = sql"""
      CREATE INDEX IF NOT EXISTS online_history_login_time_idx ON online_history(login_time)
    """.command

    val createOnlineHistoryLogoutIdx = sql"""
      CREATE INDEX IF NOT EXISTS online_history_logout_time_idx ON online_history(logout_time)
    """.command

    val createCharacterNameHistoryIdx = sql"""
      CREATE INDEX IF NOT EXISTS character_name_history_character_id_idx ON character_name_history(character_id)
    """.command

    val createWorldSaveTimeWorldIdx = sql"""
      CREATE INDEX IF NOT EXISTS world_save_time_world_id_idx ON world_save_time(world_id)
    """.command

    val createWorldSaveTimeTimeIdx = sql"""
      CREATE INDEX IF NOT EXISTS world_save_time_time_idx ON world_save_time(time)
    """.command

    val createCharacterNameLowerIdx = sql"""
      CREATE INDEX IF NOT EXISTS character_name_lower_idx ON character(lower(name))
    """.command

    val seedWorld = sql"""
      INSERT INTO world (name) VALUES ('Nefera') ON CONFLICT (name) DO NOTHING
    """.command

    for {
      _ <- session.execute(createWorldTable, Void)
      _ <- session.execute(createCharacterTable, Void)
      _ <- session.execute(createCharacterNameHistoryTable, Void)
      _ <- session.execute(createWorldSaveTimeTable, Void)
      _ <- session.execute(createCurrentlyOnlineTable, Void)
      _ <- session.execute(createOnlineHistoryTable, Void)
      _ <- session.execute(createOnlineHistoryCharacterIdx, Void)
      _ <- session.execute(createOnlineHistoryLoginIdx, Void)
      _ <- session.execute(createOnlineHistoryLogoutIdx, Void)
      _ <- session.execute(createCharacterNameHistoryIdx, Void)
      _ <- session.execute(createWorldSaveTimeWorldIdx, Void)
      _ <- session.execute(createWorldSaveTimeTimeIdx, Void)
      _ <- session.execute(createCharacterNameLowerIdx, Void)
      _ <- session.execute(seedWorld, Void)
    } yield ()
  }

  override def getWorld(name: String): IO[WorldRow] = {
    val q: Query[String, WorldRow] = sql"""
        SELECT id, name
        FROM world
        WHERE name = $varchar
      """.query(worldDecoder)
    session.unique(q, name)
  }

  override def getOrCreateWorld(name: String): IO[WorldRow] = {
    val selectQ: Query[String, WorldRow] = sql"""
        SELECT id, name
        FROM world
        WHERE name = $varchar
      """.query(worldDecoder)
    val insertC: Command[String] = sql"""
        INSERT INTO world(name)
        VALUES ($varchar)
        ON CONFLICT (name) DO NOTHING
      """.command

    session.option(selectQ, name).flatMap {
      case Some(world) => IO.pure(world)
      case None =>
        session.prepare(insertC).flatMap(_.execute(name)).flatMap(_ => session.unique(selectQ, name))
    }
  }

  override def getLatestSaveTime(worldId: Long): IO[Option[OffsetDateTime]] = {
    val q: Query[Long, Option[OffsetDateTime]] = sql"""
        SELECT MAX(time)
        FROM world_save_time
        WHERE world_id = $int8
      """.query(timestamptz.opt)
    session.unique(q, worldId)
  }

  def getAllOnline(worldId: Long): IO[List[OnlineNameTime]] = {
    val q: Query[Long, OnlineNameTime] = sql"""
        SELECT character.name, currently_online.login_time
        FROM currently_online
        JOIN character ON currently_online.character_id = character.id
        WHERE currently_online.world_id = $int8
      """.query(onlineNameTimeDecoder)
    prepareToList(q, worldId)
  }

  override def getMaxSequenceId(worldId: Long): IO[Option[Long]] = {
    val q: Query[Long, Option[Long]] = sql"""
        SELECT MAX(sequence_id)
        FROM world_save_time
        WHERE world_id = $int8
      """.query(int8.opt)
    session.unique(q, worldId)
  }

  override def insertWorldSaveTime(w: WorldSaveTimeRow): IO[Long] = {
    val q: Query[WorldSaveTimeRow, Long] = sql"""
        INSERT INTO world_save_time(world_id, sequence_id, time)
        VALUES $worldSaveTimeEncoder
        RETURNING id
      """.query(int8)
    session.unique(q, w)
  }

  override def insertCharacterNameHistory(characterNameHistory: CharacterNameHistoryRow): IO[Unit] = {
    val c: Command[CharacterNameHistoryRow] = sql"""
        INSERT INTO character_name_history(character_id, name, from_date, until_date)
        VALUES $characterNameHistoryEncoder
      """.command
    session.prepare(c).flatMap(_.execute(characterNameHistory)).void
  }

  override def updateCharacterName(id: Long, newName: String, time: OffsetDateTime): IO[Unit] = {
    val c: Command[(String, OffsetDateTime, Long)] = sql"""
        UPDATE character SET
          name = $varchar,
          current_name_since = $timestamptz
        WHERE id = $int8
      """.command
    session.prepare(c).flatMap(_.execute((newName, time, id))).void
  }

  override def insertCharacter(character: CharacterRow): IO[Unit] = {
    val c: Command[CharacterRow] = sql"""
        INSERT INTO character(name, created, current_name_since)
        VALUES $characterEncoder
        ON CONFLICT DO NOTHING
      """.command
    session.prepare(c).flatMap(_.execute(character)).void
  }

  override def getCharacter(name: String): IO[Option[CharacterRow]] = {
    val q: Query[String, CharacterRow] = sql"""
        SELECT id, name, created, current_name_since FROM character
        WHERE name = $varchar
      """.query(characterDecoder)
    session.option(q, name)
  }

  override def insertOnline(online: OnlineNameTime, worldId: Long): IO[Unit] = {
    val c: Command[(String, Long, Long)] = sql"""
        INSERT INTO currently_online(character_id, world_id, login_time)
        VALUES (
          (SELECT id FROM character WHERE name = $varchar),
          $int8,
          $int8)
      """.command
    session.prepare(c).flatMap(_.execute((online.name, worldId, online.loginTime))).void
  }

  override def deleteOnline(name: String, worldId: Long): IO[Unit] = {
    val c: Command[String ~ Long] = sql"""
        DELETE FROM currently_online O
        USING character C
        WHERE O.character_id = C.id
        AND C.name = $varchar
        AND O.world_id = $int8
      """.command
    session.prepare(c).flatMap(_.execute(name ~ worldId)).void
  }

  override def insertOnlineHistory(name: String, loginTime: Long, logoutTime: Long): IO[Unit] = {
    val c: Command[(String, Long, Long)] = sql"""
        INSERT INTO online_history(character_id, login_time, logout_time)
        VALUES (
          (SELECT id FROM character WHERE name = $varchar),
          $int8,
          $int8)
      """.command
    session.prepare(c).flatMap(_.execute((name, loginTime, logoutTime))).void
  }

}
