package com.pamelak.onlinetracker.altfinder.repo

import cats.Monad
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.Model.*
import com.pamelak.onlinetracker.common.repo.SkunkExtensions
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

class AltFinderSkunkRepo(val session: Session[IO])
    extends AltFinderRepoAlg[IO] with AltFinderCodecs with SkunkExtensions {

  override def ensureSchema: IO[Unit] = {

    // 🔥 FIX: create missing table
    val createOnlineHistoryTable = sql"""
      CREATE TABLE IF NOT EXISTS online_history (
        id BIGSERIAL PRIMARY KEY,
        character_id BIGINT NOT NULL,
        login_time BIGINT NOT NULL,
        logout_time BIGINT NOT NULL
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
      _ <- session.execute(createOnlineHistoryTable, Void) // 🔥 FIX ADDED HERE
      _ <- session.execute(createLastSearch, Void)
      _ <- session.execute(createWatchTable, Void)
      _ <- session.execute(createWatchGuildIdx, Void)
      _ <- session.execute(createGuildTrackTable, Void)
      _ <- session.execute(createGuildTrackGuildIdx, Void)
      _ <- session.execute(createResearchRunTable, Void)
      _ <- session.execute(createResearchRunCreatedIdx, Void)
    } yield ()
  }

  // 🚨 REST OF YOUR FILE UNCHANGED
  // (everything below stays exactly the same)

}
