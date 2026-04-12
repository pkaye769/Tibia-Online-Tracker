package com.pamelak.onlinetracker.altfinder.repo

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.Model.*
import skunk.*
import skunk.codec.all.bool
import skunk.codec.all.int4
import skunk.codec.all.int8
import skunk.codec.all.timestamptz
import skunk.codec.all.varchar
import skunk.data.Completion
import skunk.implicits.sql
import skunk.implicits.toIdOps

import java.time.OffsetDateTime

class AltFinderSkunkRepo(sessionPool: Resource[IO, Session[IO]])
    extends AltFinderRepoAlg[IO] with AltFinderCodecs {

  private def withSession[A](f: Session[IO] => IO[A]): IO[A] = sessionPool.use(f)

  private def prepareToList[A, B](session: Session[IO])(q: Query[A, B], args: A): IO[List[B]] =
    session.stream(q, args, 65536).compile.toList

  override def ensureSchema: IO[Unit] = withSession { session =>

  val createOnlineHistoryTable = sql"""
    CREATE TABLE IF NOT EXISTS online_history (
      character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
      login_time   BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE,
      logout_time  BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE
    )
  """.command

  val createLastSearch = sql"""
    CREATE TABLE IF NOT EXISTS altfinder_last_search (
      id BIGINT PRIMARY KEY CHECK (id = 1),
      characters TEXT NOT NULL,
      from_date TIMESTAMPTZ NULL,
      to_date TIMESTAMPTZ NULL,
      distance_minutes INTEGER NULL,
      include_clashes BOOLEAN NOT NULL DEFAULT false,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  """.command

  val createWatchTable = sql"""
    CREATE TABLE IF NOT EXISTS altfinder_watch (
      id BIGSERIAL PRIMARY KEY,
      guild_id TEXT NOT NULL,
      channel_id TEXT NOT NULL,
      character_name TEXT NOT NULL,
      distance_minutes INTEGER NOT NULL DEFAULT 0,
      include_clashes BOOLEAN NOT NULL DEFAULT false,
      confidence_threshold INTEGER NOT NULL DEFAULT 80,
      window_days INTEGER NOT NULL DEFAULT 30,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_checked_at TIMESTAMPTZ NULL,
      last_alert_at TIMESTAMPTZ NULL,
      UNIQUE (guild_id, character_name)
    )
  """.command

  val createWatchGuildIdx = sql"""
    CREATE INDEX IF NOT EXISTS altfinder_watch_guild_idx
    ON altfinder_watch(guild_id)
  """.command

  val createGuildTrackTable = sql"""
    CREATE TABLE IF NOT EXISTS altfinder_guild_track (
      id BIGSERIAL PRIMARY KEY,
      guild_id TEXT NOT NULL,
      tibia_guild_name TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (guild_id, tibia_guild_name)
    )
  """.command

  val createGuildTrackGuildIdx = sql"""
    CREATE INDEX IF NOT EXISTS altfinder_guild_track_guild_idx
    ON altfinder_guild_track(guild_id)
  """.command

  val createResearchRunTable = sql"""
    CREATE TABLE IF NOT EXISTS altfinder_research_run (
      id BIGSERIAL PRIMARY KEY,
      run_type TEXT NOT NULL,
      searched_characters TEXT NOT NULL,
      target_characters TEXT NOT NULL DEFAULT '',
      from_date TIMESTAMPTZ NULL,
      to_date TIMESTAMPTZ NULL,
      distance_minutes INTEGER NOT NULL DEFAULT 0,
      include_clashes BOOLEAN NOT NULL DEFAULT false,
      total_logins INTEGER NOT NULL DEFAULT 0,
      match_count INTEGER NOT NULL DEFAULT 0,
      summary TEXT NOT NULL DEFAULT '',
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  """.command

  val createResearchRunCreatedIdx = sql"""
    CREATE INDEX IF NOT EXISTS altfinder_research_run_created_idx
    ON altfinder_research_run(created_at DESC)
  """.command

  for {
    _ <- session.execute(createOnlineHistoryTable, Void)
    _ <- session.execute(createLastSearch, Void)
    _ <- session.execute(createWatchTable, Void)
    _ <- session.execute(createWatchGuildIdx, Void)
    _ <- session.execute(createGuildTrackTable, Void)
    _ <- session.execute(createGuildTrackGuildIdx, Void)
    _ <- session.execute(createResearchRunTable, Void)
    _ <- session.execute(createResearchRunCreatedIdx, Void)
  } yield ()
  }

  override def getOnlineTimes(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineSegment]] = withSession { session =>
    val cl = characterNames.map(_.toLowerCase)

    // Return epoch seconds so that the service can compare durations in real time units.
    val baseFragment = sql"""
        SELECT o.character_id,
               EXTRACT(EPOCH FROM w_login.time)::bigint,
               EXTRACT(EPOCH FROM w_logout.time)::bigint
        FROM online_history o
        JOIN character c        ON o.character_id = c.id
        JOIN world_save_time w_login  ON o.login_time  = w_login.id
        JOIN world_save_time w_logout ON o.logout_time = w_logout.id
      """
    val charFragment   = sql"WHERE LOWER(c.name) IN (${varchar.values.list(characterNames.length)})"
    val fromToFragment = sql"AND w_login.time >= $timestamptz AND w_login.time <= $timestamptz"
    val fromFragment   = sql"AND w_login.time >= $timestamptz"
    val toFragment     = sql"AND w_login.time <= $timestamptz"

    (from, to) match {
      case (Some(f), Some(t)) =>
        val q = sql"$baseFragment $charFragment $fromToFragment".query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, (f, t)))
      case (Some(f), None) =>
        val q = sql"$baseFragment $charFragment $fromFragment".query(onlineSegmentDecoder)
        prepareToList(session)(q, cl ~ f)
      case (None, Some(t)) =>
        val q = sql"$baseFragment $charFragment $toFragment".query(onlineSegmentDecoder)
        prepareToList(session)(q, cl ~ t)
      case (None, None) =>
        val q = sql"$baseFragment $charFragment".query(onlineSegmentDecoder)
        prepareToList(session)(q, cl)
    }
  }

  override def getPossibleMatches(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime],
      distance: Option[Int]
  ): IO[List[OnlineSegment]] = withSession { session =>
    val cl = characterNames.map(_.toLowerCase)
    // Convert distance from minutes to seconds for real-time comparison.
    val distanceSecs = distance.map(_ * 60L).getOrElse(0L)

    // Pre-compute epoch seconds and login timestamp for every online_history row.
    val innerSubquery = sql"""
        SELECT oh.character_id,
               EXTRACT(EPOCH FROM ws_l.time)::bigint  AS login_sec,
               EXTRACT(EPOCH FROM ws_lo.time)::bigint AS logout_sec,
               ws_l.time                              AS login_ts
        FROM online_history oh
        JOIN world_save_time ws_l  ON oh.login_time  = ws_l.id
        JOIN world_save_time ws_lo ON oh.logout_time = ws_lo.id
      """

    // Adjacency: session of o1 ends within distanceSecs before eph starts, or vice-versa.
    val adjacencyFragment = sql"""
        (EXTRACT(EPOCH FROM ws1_login.time)::bigint - eph.logout_sec BETWEEN 0 AND #${distanceSecs.toString}
         OR eph.login_sec - EXTRACT(EPOCH FROM ws1_logout.time)::bigint BETWEEN 0 AND #${distanceSecs.toString})
      """

    // Outer query: return epoch seconds for the matched characters' sessions.
    // The inner SELECT finds all character IDs adjacent to the searched characters.
    val outerBase = sql"""
        SELECT o.character_id,
               EXTRACT(EPOCH FROM w_login.time)::bigint,
               EXTRACT(EPOCH FROM w_logout.time)::bigint
        FROM online_history o
        JOIN world_save_time w_login  ON o.login_time  = w_login.id
        JOIN world_save_time w_logout ON o.logout_time = w_logout.id
        WHERE o.character_id IN (
          SELECT DISTINCT eph.character_id
          FROM online_history o1
          JOIN world_save_time ws1_login  ON o1.login_time  = ws1_login.id
          JOIN world_save_time ws1_logout ON o1.logout_time = ws1_logout.id
          JOIN character c ON o1.character_id = c.id
          JOIN ($innerSubquery) eph ON $adjacencyFragment
          WHERE LOWER(c.name) IN (${varchar.values.list(characterNames.length)})
      """

    val innerFromToFilter = sql"AND eph.login_ts >= $timestamptz AND eph.login_ts <= $timestamptz"
    val innerFromFilter   = sql"AND eph.login_ts >= $timestamptz"
    val innerToFilter     = sql"AND eph.login_ts <= $timestamptz"
    val outerFromToFilter = sql"AND w_login.time >= $timestamptz AND w_login.time <= $timestamptz"
    val outerFromFilter   = sql"AND w_login.time >= $timestamptz"
    val outerToFilter     = sql"AND w_login.time <= $timestamptz"

    (from, to) match {
      case (Some(f), Some(t)) =>
        val q = sql"$outerBase $innerFromToFilter) $outerFromToFilter".query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, (f, t), (f, t)))
      case (Some(f), None) =>
        val q = sql"$outerBase $innerFromFilter) $outerFromFilter".query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, f, f))
      case (None, Some(t)) =>
        val q = sql"$outerBase $innerToFilter) $outerToFilter".query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, t, t))
      case (None, None) =>
        val q = sql"$outerBase)".query(onlineSegmentDecoder)
        prepareToList(session)(q, cl)
    }
  }

  override def getCharacterName(characterId: Long): IO[String] = withSession { session =>
    val q: Query[Long, String] = sql"""
        SELECT name FROM character
        WHERE id = $int8
      """.query(varchar)
    session.unique(q, characterId)
  }

  override def getCharacterHistories(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineDateSegment]] = withSession { session =>
    val cl = characterNames.map(_.toLowerCase)

    val baseFragment = sql"""
        SELECT c.name, w1.time, w2.time
        FROM online_history o
        JOIN character c ON o.character_id = c.id
        JOIN world_save_time w1 ON o.login_time = w1.id
        JOIN world_save_time w2 ON o.logout_time = w2.id
        WHERE lower(c.name) IN (${varchar.values.list(characterNames.length)})
      """
    val fromToFragment = sql"AND w2.time >= $timestamptz AND w1.time <= $timestamptz"
    val fromFragment = sql"AND w2.time >= $timestamptz"
    val toFragment = sql"AND w1.time <= $timestamptz"
    val orderFragment = sql"ORDER BY w1.id"

    (from, to) match {
      case (Some(f), Some(t)) =>
        val q = sql"$baseFragment $fromToFragment $orderFragment".query(onlineDateSegmentDecoder)
        prepareToList(session)(q, (cl, (f, t)))
      case (Some(f), None) =>
        val q = sql"$baseFragment $fromFragment $orderFragment".query(onlineDateSegmentDecoder)
        prepareToList(session)(q, cl ~ f)
      case (None, Some(t)) =>
        val q = sql"$baseFragment $toFragment $orderFragment".query(onlineDateSegmentDecoder)
        prepareToList(session)(q, cl ~ t)
      case (None, None) =>
        val q = sql"$baseFragment $orderFragment".query(onlineDateSegmentDecoder)
        prepareToList(session)(q, cl)
    }
  }

  override def getPastCharacterNames(characterName: String): IO[List[String]] = withSession { session =>
    val q = sql"""
      SELECT cnh.name FROM character c JOIN character_name_history cnh ON c.id = cnh.character_id
      WHERE lower(c.name) = $varchar
    """.query(varchar)
    prepareToList(session)(q, characterName.toLowerCase)
  }

  override def saveLastSearch(search: LastSearch): IO[Unit] = withSession { session =>
    val q = sql"""
      INSERT INTO altfinder_last_search
        (id, characters, from_date, to_date, distance_minutes, include_clashes, updated_at)
      VALUES
        (1, $varchar, ${timestamptz.opt}, ${timestamptz.opt}, ${int4.opt}, $bool, NOW())
      ON CONFLICT (id) DO UPDATE
      SET characters = EXCLUDED.characters,
          from_date = EXCLUDED.from_date,
          to_date = EXCLUDED.to_date,
          distance_minutes = EXCLUDED.distance_minutes,
          include_clashes = EXCLUDED.include_clashes,
          updated_at = NOW()
    """.command
    val characters = search.characters.mkString(", ")
    session.execute(q, (characters, search.from, search.to, search.distance, search.includeClashes)).void
  }

  override def getLastSearch: IO[Option[LastSearch]] = withSession { session =>
    val q = sql"""
      SELECT characters, from_date, to_date, distance_minutes, include_clashes
      FROM altfinder_last_search
      WHERE id = 1
    """.query(lastSearchDecoder)
    session.option(q, Void).map(_.map { case (chars, from, to, distance, includeClashes) =>
      val list = chars.split(",").map(_.trim).filter(_.nonEmpty).toList
      LastSearch(list, from, to, distance, includeClashes)
    })
  }

  override def upsertWatch(config: WatchConfig): IO[Unit] = withSession { session =>
    val q = sql"""
      INSERT INTO altfinder_watch
        (guild_id, channel_id, character_name, distance_minutes, include_clashes, confidence_threshold, window_days, created_at, updated_at)
      VALUES
        ($varchar, $varchar, $varchar, $int4, $bool, $int4, $int4, NOW(), NOW())
      ON CONFLICT (guild_id, character_name) DO UPDATE
      SET channel_id = EXCLUDED.channel_id,
          distance_minutes = EXCLUDED.distance_minutes,
          include_clashes = EXCLUDED.include_clashes,
          confidence_threshold = EXCLUDED.confidence_threshold,
          window_days = EXCLUDED.window_days,
          updated_at = NOW()
    """.command
    session.execute(
      q,
      (
        config.guildId,
        config.channelId,
        config.characterName,
        config.distance,
        config.includeClashes,
        config.confidenceThreshold,
        config.windowDays
      )
    ).void
  }

  override def removeWatch(guildId: String, characterName: String): IO[Boolean] = withSession { session =>
    val q = sql"""
      DELETE FROM altfinder_watch
      WHERE guild_id = $varchar AND lower(character_name) = $varchar
    """.command
    session.execute(q, (guildId, characterName.toLowerCase)).map {
      case Completion.Delete(count) => count > 0
      case _ => false
    }
  }

  override def listWatches(guildId: String): IO[List[WatchEntry]] = withSession { session =>
    val q = sql"""
      SELECT id, guild_id, channel_id, character_name, distance_minutes, include_clashes, confidence_threshold,
             window_days, created_at, updated_at, last_checked_at, last_alert_at
      FROM altfinder_watch
      WHERE guild_id = $varchar
      ORDER BY updated_at DESC
    """.query(watchEntryDecoder)
    prepareToList(session)(q, guildId).map(_.map {
      case (id, gid, cid, name, distance, includeClashes, threshold, windowDays, createdAt, updatedAt, lastChecked, lastAlert) =>
        WatchEntry(id, gid, cid, name, distance, includeClashes, threshold, windowDays, createdAt, updatedAt, lastChecked, lastAlert)
    })
  }

  override def listAllWatches: IO[List[WatchEntry]] = withSession { session =>
    val q = sql"""
      SELECT id, guild_id, channel_id, character_name, distance_minutes, include_clashes, confidence_threshold,
             window_days, created_at, updated_at, last_checked_at, last_alert_at
      FROM altfinder_watch
      ORDER BY updated_at DESC
    """.query(watchEntryDecoder)
    prepareToList(session)(q, Void).map(_.map {
      case (id, gid, cid, name, distance, includeClashes, threshold, windowDays, createdAt, updatedAt, lastChecked, lastAlert) =>
        WatchEntry(id, gid, cid, name, distance, includeClashes, threshold, windowDays, createdAt, updatedAt, lastChecked, lastAlert)
    })
  }

  override def updateWatchCheck(id: Long, checkedAt: OffsetDateTime, alertedAt: Option[OffsetDateTime]): IO[Unit] =
    withSession { session =>
      alertedAt match {
        case Some(alerted) =>
          val q = sql"""
            UPDATE altfinder_watch
            SET last_checked_at = $timestamptz,
                last_alert_at = $timestamptz,
                updated_at = NOW()
            WHERE id = $int8
          """.command
          session.execute(q, (checkedAt, alerted, id)).void

        case None =>
          val q = sql"""
            UPDATE altfinder_watch
            SET last_checked_at = $timestamptz,
                updated_at = NOW()
            WHERE id = $int8
          """.command
          session.execute(q, (checkedAt, id)).void
      }
    }

  override def upsertTrackedGuild(config: GuildTrackConfig): IO[Unit] = withSession { session =>
    val q = sql"""
      INSERT INTO altfinder_guild_track
        (guild_id, tibia_guild_name, created_at, updated_at)
      VALUES
        ($varchar, $varchar, NOW(), NOW())
      ON CONFLICT (guild_id, tibia_guild_name) DO UPDATE
      SET updated_at = NOW()
    """.command
    session.execute(q, (config.guildId, config.tibiaGuildName)).void
  }

  override def removeTrackedGuild(guildId: String, tibiaGuildName: String): IO[Boolean] = withSession { session =>
    val q = sql"""
      DELETE FROM altfinder_guild_track
      WHERE guild_id = $varchar AND lower(tibia_guild_name) = $varchar
    """.command
    session.execute(q, (guildId, tibiaGuildName.toLowerCase)).map {
      case Completion.Delete(count) => count > 0
      case _ => false
    }
  }

  override def listTrackedGuilds(guildId: String): IO[List[GuildTrackEntry]] = withSession { session =>
    val q = sql"""
      SELECT id, guild_id, tibia_guild_name, created_at, updated_at
      FROM altfinder_guild_track
      WHERE guild_id = $varchar
      ORDER BY updated_at DESC
    """.query(guildTrackEntryDecoder)
    prepareToList(session)(q, guildId).map(_.map {
      case (id, gid, name, createdAt, updatedAt) =>
        GuildTrackEntry(id, gid, name, createdAt, updatedAt)
    })
  }

  override def saveResearchRun(run: ResearchRunWrite): IO[Unit] = withSession { session =>
    val q = sql"""
      INSERT INTO altfinder_research_run
        (run_type, searched_characters, target_characters, from_date, to_date, distance_minutes, include_clashes, total_logins, match_count, summary, created_at)
      VALUES
        ($varchar, $varchar, $varchar, ${timestamptz.opt}, ${timestamptz.opt}, $int4, $bool, $int4, $int4, $varchar, NOW())
    """.command
    session.execute(
      q,
      (
        run.runType,
        run.searchedCharacters.mkString(", "),
        run.targetCharacters.mkString(", "),
        run.from,
        run.to,
        run.distance,
        run.includeClashes,
        run.totalLogins,
        run.matchCount,
        run.summary
      )
    ).void
  }

  override def listResearchRuns(limit: Int): IO[List[ResearchRun]] = withSession { session =>
    val q = sql"""
      SELECT id, run_type, searched_characters, target_characters, from_date, to_date, distance_minutes,
             include_clashes, total_logins, match_count, summary, created_at
      FROM altfinder_research_run
      ORDER BY created_at DESC
      LIMIT $int4
    """.query(researchRunDecoder)
    prepareToList(session)(q, limit.max(1).min(200)).map(_.map {
      case (id, runType, searchedChars, targetChars, from, to, distance, includeClashes, totalLogins, matchCount, summary, createdAt) =>
        ResearchRun(
          id,
          runType,
          searchedChars.split(",").map(_.trim).filter(_.nonEmpty).toList,
          targetChars.split(",").map(_.trim).filter(_.nonEmpty).toList,
          from,
          to,
          distance,
          includeClashes,
          totalLogins,
          matchCount,
          summary,
          createdAt
        )
    })
  }

  override def countOnlineHistoryRows: IO[Long] = withSession { session =>
    val q = sql"""
      SELECT COUNT(*) FROM online_history
    """.query(int8)
    session.unique(q, Void)
  }

  override def latestWorldSaveTime: IO[Option[OffsetDateTime]] = withSession { session =>
    val q = sql"""
      SELECT MAX(time) FROM world_save_time
    """.query(timestamptz.opt)
    session.unique(q, Void)
  }
}
