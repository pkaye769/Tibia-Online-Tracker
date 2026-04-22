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

  val createOnlineHistoryCharacterIdx = sql"""
    CREATE INDEX IF NOT EXISTS online_history_character_id_idx ON online_history(character_id)
  """.command

  val createOnlineHistoryLoginIdx = sql"""
    CREATE INDEX IF NOT EXISTS online_history_login_time_idx ON online_history(login_time)
  """.command

  val createOnlineHistoryLogoutIdx = sql"""
    CREATE INDEX IF NOT EXISTS online_history_logout_time_idx ON online_history(logout_time)
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

  for {
    _ <- session.execute(createWorldTable, Void)
    _ <- session.execute(createCharacterTable, Void)
    _ <- session.execute(createWorldSaveTimeTable, Void)
    _ <- session.execute(createOnlineHistoryTable, Void)
    _ <- session.execute(createLastSearch, Void)
    _ <- session.execute(createWatchTable, Void)
    _ <- session.execute(createWatchGuildIdx, Void)
    _ <- session.execute(createGuildTrackTable, Void)
    _ <- session.execute(createGuildTrackGuildIdx, Void)
    _ <- session.execute(createResearchRunTable, Void)
    _ <- session.execute(createResearchRunCreatedIdx, Void)
    _ <- session.execute(createOnlineHistoryCharacterIdx, Void)
    _ <- session.execute(createOnlineHistoryLoginIdx, Void)
    _ <- session.execute(createOnlineHistoryLogoutIdx, Void)
    _ <- session.execute(createWorldSaveTimeWorldIdx, Void)
    _ <- session.execute(createWorldSaveTimeTimeIdx, Void)
    _ <- session.execute(createCharacterNameLowerIdx, Void)
  } yield ()
  }

  override def getOnlineTimes(
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineSegment]] = withSession { session =>
    for {
      historical <- fetchHistoricalOnlineTimes(session, characterNames, from, to)
      current    <- fetchCurrentlyOnlineSessions(session, characterNames, from, to)
    } yield historical ++ current
  }

  private def fetchHistoricalOnlineTimes(
      session: Session[IO],
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineSegment]] = {
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

  // Returns the in-progress session for each of the given characters that is currently
  // recorded in currently_online (i.e. the character has not yet logged off).
  // The effective logout time is the latest world_save_time for the character's world,
  // which is the last moment the tracker confirmed they were still online.
  private def fetchCurrentlyOnlineSessions(
      session: Session[IO],
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineSegment]] = {
    if (characterNames.isEmpty) IO.pure(Nil)
    else {
      val cl = characterNames.map(_.toLowerCase)
      val baseQ = sql"""
          SELECT co.character_id,
                 EXTRACT(EPOCH FROM w_login.time)::bigint,
                 EXTRACT(EPOCH FROM latest_wst.time)::bigint
          FROM currently_online co
          JOIN character c ON co.character_id = c.id
          JOIN world_save_time w_login ON co.login_time = w_login.id
          JOIN (
            SELECT world_id, MAX(time) AS time
            FROM world_save_time
            GROUP BY world_id
          ) latest_wst ON latest_wst.world_id = co.world_id
          WHERE LOWER(c.name) IN (${varchar.values.list(characterNames.length)})
        """
      val fromToFilter = sql"AND w_login.time >= $timestamptz AND w_login.time <= $timestamptz"
      val fromFilter   = sql"AND w_login.time >= $timestamptz"
      val toFilter     = sql"AND w_login.time <= $timestamptz"

      (from, to) match {
        case (Some(f), Some(t)) =>
          prepareToList(session)(sql"$baseQ $fromToFilter".query(onlineSegmentDecoder), (cl, (f, t)))
        case (Some(f), None) =>
          prepareToList(session)(sql"$baseQ $fromFilter".query(onlineSegmentDecoder), cl ~ f)
        case (None, Some(t)) =>
          prepareToList(session)(sql"$baseQ $toFilter".query(onlineSegmentDecoder), cl ~ t)
        case (None, None) =>
          prepareToList(session)(baseQ.query(onlineSegmentDecoder), cl)
      }
    }
  }

  // Fast path for distance = 0: use FK equality joins (login_time_idx / logout_time_idx)
  // instead of computing EXTRACT(EPOCH) on every row in the table. A CTE collects the
  // searched character's sessions once; two indexed FK joins then find adjacent characters
  // in O(sessions × adjacent_count) instead of O(sessions × all_history_rows).
  private def getPossibleMatchesFastPath(
      session: Session[IO],
      characterNames: List[String],
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineSegment]] = {
    val cl = characterNames.map(_.toLowerCase)
    val n  = cl.length

    // Outer SELECT: return all sessions for the characters found in the CTE adjacency join.
    // The fragment has no parameters of its own; date-filter suffixes are appended per case.
    val outerSelect = sql"""
        SELECT o.character_id,
               EXTRACT(EPOCH FROM w_login.time)::bigint,
               EXTRACT(EPOCH FROM w_logout.time)::bigint
        FROM online_history o
        JOIN world_save_time w_login  ON o.login_time  = w_login.id
        JOIN world_save_time w_logout ON o.logout_time = w_logout.id
        WHERE o.character_id IN (
          SELECT DISTINCT sub.adj_char FROM (
            SELECT oh2.character_id AS adj_char
            FROM me_sessions
            JOIN online_history oh2 ON oh2.login_time = me_sessions.logout_time
            UNION ALL
            SELECT oh2.character_id AS adj_char
            FROM me_sessions
            JOIN online_history oh2 ON oh2.logout_time = me_sessions.login_time
          ) sub
        )"""

    val outerFromToFilter = sql"AND w_login.time >= $timestamptz AND w_login.time <= $timestamptz"
    val outerFromFilter   = sql"AND w_login.time >= $timestamptz"
    val outerToFilter     = sql"AND w_login.time <= $timestamptz"

    (from, to) match {
      case (None, None) =>
        val q = sql"""
            WITH me_sessions AS (
              SELECT oh1.login_time, oh1.logout_time
              FROM online_history oh1
              JOIN character c ON oh1.character_id = c.id
              WHERE LOWER(c.name) IN (${varchar.values.list(n)})
            )
            $outerSelect
          """.query(onlineSegmentDecoder)
        prepareToList(session)(q, cl)

      case (Some(f), None) =>
        val q = sql"""
            WITH me_sessions AS (
              SELECT oh1.login_time, oh1.logout_time
              FROM online_history oh1
              JOIN character c  ON oh1.character_id = c.id
              JOIN world_save_time ws_l ON oh1.login_time = ws_l.id
              WHERE LOWER(c.name) IN (${varchar.values.list(n)})
              AND ws_l.time >= $timestamptz
            )
            $outerSelect $outerFromFilter
          """.query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, f, f))

      case (None, Some(t)) =>
        val q = sql"""
            WITH me_sessions AS (
              SELECT oh1.login_time, oh1.logout_time
              FROM online_history oh1
              JOIN character c  ON oh1.character_id = c.id
              JOIN world_save_time ws_l ON oh1.login_time = ws_l.id
              WHERE LOWER(c.name) IN (${varchar.values.list(n)})
              AND ws_l.time <= $timestamptz
            )
            $outerSelect $outerToFilter
          """.query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, t, t))

      case (Some(f), Some(t)) =>
        val q = sql"""
            WITH me_sessions AS (
              SELECT oh1.login_time, oh1.logout_time
              FROM online_history oh1
              JOIN character c  ON oh1.character_id = c.id
              JOIN world_save_time ws_l ON oh1.login_time = ws_l.id
              WHERE LOWER(c.name) IN (${varchar.values.list(n)})
              AND ws_l.time >= $timestamptz AND ws_l.time <= $timestamptz
            )
            $outerSelect $outerFromToFilter
          """.query(onlineSegmentDecoder)
        prepareToList(session)(q, (cl, f, t, (f, t)))
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

    // For distance = 0 use the FK-based fast path: joins on login_time/logout_time FKs
    // (indexed) instead of computing EXTRACT(EPOCH) across the full history table.
    // For distance > 0 fall through to the epoch-based path below.
    if (distanceSecs == 0L) {
      for {
        historical <- getPossibleMatchesFastPath(session, characterNames, from, to)
        currentSessions <- fetchCurrentlyOnlineSessions(session, characterNames, from, to)
        adjacent <- if (currentSessions.nonEmpty) {
          val loginSecs  = currentSessions.map(_.start)
          val minSec     = loginSecs.min
          val maxSec     = loginSecs.max
          val excludeIds = currentSessions.map(_.characterId).toSet
          fetchSessionsAdjacentToEpochWindow(session, minSec, maxSec, from, to)
            .map(_.filterNot(s => excludeIds.contains(s.characterId)))
        } else IO.pure(Nil)
      } yield (historical ++ adjacent).distinct
    } else {

    // Adjacency: session of o1 ends within distanceSecs before oh_epoch starts, or vice-versa.
    // distanceSecs is a safely computed Long (distance minutes × 60) – not raw user input,
    // so literal interpolation with #${} cannot cause SQL injection.
    val adjacencyFragment = sql"""
        (EXTRACT(EPOCH FROM ws1_login.time)::bigint - oh_epoch.logout_sec BETWEEN 0 AND #${distanceSecs.toString}
         OR oh_epoch.login_sec - EXTRACT(EPOCH FROM ws1_logout.time)::bigint BETWEEN 0 AND #${distanceSecs.toString})
      """

    // Opening portion of the outer SELECT, up through and including the inner derived table's
    // FROM clause. The date filter (WHERE ws_login.time …) is injected immediately after this
    // so Postgres can apply world_save_time_time_idx before computing EXTRACT on every row.
    val outerSelectOpen = sql"""
        SELECT o.character_id,
               EXTRACT(EPOCH FROM w_login.time)::bigint,
               EXTRACT(EPOCH FROM w_logout.time)::bigint
        FROM online_history o
        JOIN world_save_time w_login  ON o.login_time  = w_login.id
        JOIN world_save_time w_logout ON o.logout_time = w_logout.id
        WHERE o.character_id IN (
          SELECT DISTINCT oh_epoch.character_id
          FROM online_history o1
          JOIN world_save_time ws1_login  ON o1.login_time  = ws1_login.id
          JOIN world_save_time ws1_logout ON o1.logout_time = ws1_logout.id
          JOIN character c ON o1.character_id = c.id
          JOIN (
            SELECT oh.character_id,
                   EXTRACT(EPOCH FROM ws_login.time)::bigint  AS login_sec,
                   EXTRACT(EPOCH FROM ws_logout.time)::bigint AS logout_sec,
                   ws_login.time                              AS login_ts
            FROM online_history oh
            JOIN world_save_time ws_login  ON oh.login_time  = ws_login.id
            JOIN world_save_time ws_logout ON oh.logout_time = ws_logout.id
      """

    // Date filters embedded inside the inner derived table so Postgres can push them down
    // through the EXTRACT and use time-based indexes on world_save_time.
    val innerFromToWhere = sql"WHERE ws_login.time >= $timestamptz AND ws_login.time <= $timestamptz"
    val innerFromWhere   = sql"WHERE ws_login.time >= $timestamptz"
    val innerToWhere     = sql"WHERE ws_login.time <= $timestamptz"

    // Closes the derived table, applies the adjacency join condition and character filter,
    // and closes the WHERE IN subquery.
    val innerJoinClose = sql"""
          ) oh_epoch ON $adjacencyFragment
          WHERE LOWER(c.name) IN (${varchar.values.list(characterNames.length)})
        )
      """

    val outerFromToFilter = sql"AND w_login.time >= $timestamptz AND w_login.time <= $timestamptz"
    val outerFromFilter   = sql"AND w_login.time >= $timestamptz"
    val outerToFilter     = sql"AND w_login.time <= $timestamptz"

    for {
      historical <- (from, to) match {
        case (Some(f), Some(t)) =>
          val q = sql"$outerSelectOpen $innerFromToWhere $innerJoinClose $outerFromToFilter".query(onlineSegmentDecoder)
          prepareToList(session)(q, ((f, t), cl, (f, t)))
        case (Some(f), None) =>
          val q = sql"$outerSelectOpen $innerFromWhere $innerJoinClose $outerFromFilter".query(onlineSegmentDecoder)
          prepareToList(session)(q, (f, cl, f))
        case (None, Some(t)) =>
          val q = sql"$outerSelectOpen $innerToWhere $innerJoinClose $outerToFilter".query(onlineSegmentDecoder)
          prepareToList(session)(q, (t, cl, t))
        case (None, None) =>
          val q = sql"$outerSelectOpen $innerJoinClose".query(onlineSegmentDecoder)
          prepareToList(session)(q, cl)
      }
      // Also include sessions for characters adjacent to any currently-online session of
      // the searched characters. This covers the case where a searched character is still
      // logged in and their alt logged off right before they logged in.
      currentSessions <- fetchCurrentlyOnlineSessions(session, characterNames, from, to)
      adjacent <- if (currentSessions.nonEmpty) {
        val loginSecs  = currentSessions.map(_.start)
        val minSec     = loginSecs.min - distanceSecs
        val maxSec     = loginSecs.max + distanceSecs
        val excludeIds = currentSessions.map(_.characterId).toSet
        fetchSessionsAdjacentToEpochWindow(session, minSec, maxSec, from, to)
          .map(_.filterNot(s => excludeIds.contains(s.characterId)))
      } else IO.pure(Nil)
    } yield (historical ++ adjacent).distinct
    } // end else (distanceSecs > 0)
  }

  // Returns all sessions of characters who had any session ending within [minSec, maxSec].
  // Used to surface candidates adjacent to a currently-online searched character's login time.
  // minSec and maxSec are safely computed epoch-second values from prior DB queries.
  private def fetchSessionsAdjacentToEpochWindow(
      session: Session[IO],
      minSec: Long,
      maxSec: Long,
      from: Option[OffsetDateTime],
      to: Option[OffsetDateTime]
  ): IO[List[OnlineSegment]] = {
    val outerBase = sql"""
        SELECT o.character_id,
               EXTRACT(EPOCH FROM w_login.time)::bigint,
               EXTRACT(EPOCH FROM w_logout.time)::bigint
        FROM online_history o
        JOIN world_save_time w_login  ON o.login_time  = w_login.id
        JOIN world_save_time w_logout ON o.logout_time = w_logout.id
        WHERE o.character_id IN (
          SELECT DISTINCT oh_inner.character_id
          FROM online_history oh_inner
          JOIN world_save_time wl ON oh_inner.logout_time = wl.id
          WHERE EXTRACT(EPOCH FROM wl.time)::bigint BETWEEN #${minSec.toString} AND #${maxSec.toString}
        )
      """
    val fromToFilter = sql"AND w_login.time >= $timestamptz AND w_login.time <= $timestamptz"
    val fromFilter   = sql"AND w_login.time >= $timestamptz"
    val toFilter     = sql"AND w_login.time <= $timestamptz"

    (from, to) match {
      case (Some(f), Some(t)) =>
        prepareToList(session)(sql"$outerBase $fromToFilter".query(onlineSegmentDecoder), (f, t))
      case (Some(f), None) =>
        prepareToList(session)(sql"$outerBase $fromFilter".query(onlineSegmentDecoder), f)
      case (None, Some(t)) =>
        prepareToList(session)(sql"$outerBase $toFilter".query(onlineSegmentDecoder), t)
      case (None, None) =>
        prepareToList(session)(outerBase.query(onlineSegmentDecoder), Void)
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

  override def getWorldTransfers(world: String, lookbackDays: Int): IO[List[WorldTransfer]] = withSession { session =>
    val fetchWorld = sql"""
      SELECT id, name
      FROM world
      WHERE LOWER(name) = LOWER($varchar)
    """.query(int8 ~ varchar)

    session.option(fetchWorld, world.trim).flatMap {
      case None => IO.pure(Nil)
      case Some(targetWorld) =>
        val targetWorldId = targetWorld._1
        val cutoff = OffsetDateTime.now().minusDays(lookbackDays.toLong)
        val historyStart = cutoff.minusDays(lookbackDays.toLong.max(7))

        val q = sql"""
          WITH sessions AS (
            SELECT c.id AS character_id, c.name AS character_name, ws.world_id, ws.time AS login_time
            FROM online_history oh
            JOIN character c ON oh.character_id = c.id
            JOIN world_save_time ws ON oh.login_time = ws.id
            WHERE ws.time >= $timestamptz
            UNION ALL
            SELECT c.id AS character_id, c.name AS character_name, co.world_id, ws.time AS login_time
            FROM currently_online co
            JOIN character c ON co.character_id = c.id
            JOIN world_save_time ws ON co.login_time = ws.id
            WHERE ws.time >= $timestamptz
          ),
          ordered AS (
            SELECT s.*,
                   LAG(s.world_id) OVER (PARTITION BY s.character_id ORDER BY s.login_time) AS prev_world_id
            FROM sessions s
          )
          SELECT s.character_name,
                 w_prev.name AS from_world,
                 w_cur.name AS to_world,
                 s.login_time
          FROM ordered s
          JOIN world w_cur ON w_cur.id = s.world_id
          JOIN world w_prev ON w_prev.id = s.prev_world_id
          WHERE s.prev_world_id IS NOT NULL
            AND s.world_id <> s.prev_world_id
            AND s.login_time >= $timestamptz
            AND (s.world_id = $int8 OR s.prev_world_id = $int8)
          ORDER BY s.login_time DESC
        """.query(worldTransferDecoder)

        prepareToList(session)(q, (historyStart, historyStart, cutoff, targetWorldId, targetWorldId))
    }
  }

  override def countTotalLogins(characterNames: List[String]): IO[Int] = withSession { session =>
    val cl = characterNames.map(_.toLowerCase)
    val q = sql"""
      SELECT COUNT(*)::int4
      FROM online_history o
      JOIN character c ON o.character_id = c.id
      WHERE LOWER(c.name) IN (${varchar.values.list(characterNames.length)})
    """.query(int4)
    session.unique(q, cl)
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

  override def getCurrentlyOnlineNames: IO[List[String]] = withSession { session =>
    val q = sql"""
      SELECT c.name
      FROM currently_online co
      JOIN character c ON co.character_id = c.id
      ORDER BY c.name
    """.query(varchar)
    session.stream(q, Void, 65536).compile.toList
  }
}
