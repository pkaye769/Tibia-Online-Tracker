CREATE SCHEMA IF NOT EXISTS "pkaye" AUTHORIZATION "pkaye";
SET search_path TO "pkaye", public;
CREATE TABLE IF NOT EXISTS world (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS character (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR NOT NULL UNIQUE,
  created TIMESTAMPTZ NOT NULL,
  current_name_since TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS world_save_time (
  id BIGSERIAL PRIMARY KEY,
  world_id BIGINT NOT NULL REFERENCES world(id) ON DELETE CASCADE,
  sequence_id BIGINT NOT NULL,
  time TIMESTAMPTZ NOT NULL,
  UNIQUE (world_id, sequence_id),
  UNIQUE (world_id, time)
);

CREATE TABLE IF NOT EXISTS character_name_history (
  id BIGSERIAL PRIMARY KEY,
  character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
  name VARCHAR NOT NULL,
  from_date TIMESTAMPTZ NOT NULL,
  until_date TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS currently_online (
  character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
  world_id BIGINT NOT NULL REFERENCES world(id) ON DELETE CASCADE,
  login_time BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE,
  PRIMARY KEY (character_id, world_id)
);

CREATE TABLE IF NOT EXISTS online_history (
  character_id BIGINT NOT NULL REFERENCES character(id) ON DELETE CASCADE,
  login_time BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE,
  logout_time BIGINT NOT NULL REFERENCES world_save_time(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS online_history_character_id_idx ON online_history(character_id);
CREATE INDEX IF NOT EXISTS online_history_login_time_idx ON online_history(login_time);
CREATE INDEX IF NOT EXISTS online_history_logout_time_idx ON online_history(logout_time);
CREATE INDEX IF NOT EXISTS character_name_history_character_id_idx ON character_name_history(character_id);
CREATE INDEX IF NOT EXISTS world_save_time_world_id_idx ON world_save_time(world_id);

CREATE TABLE IF NOT EXISTS altfinder_last_search (
  id BIGINT PRIMARY KEY CHECK (id = 1),
  characters TEXT NOT NULL,
  from_date TIMESTAMPTZ NULL,
  to_date TIMESTAMPTZ NULL,
  distance_minutes INTEGER NULL,
  include_clashes BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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
);

CREATE INDEX IF NOT EXISTS altfinder_watch_guild_idx ON altfinder_watch(guild_id);

-- Seed initial world (safe to re-run)
INSERT INTO world (name)
VALUES ('Nefera')
ON CONFLICT (name) DO NOTHING;

