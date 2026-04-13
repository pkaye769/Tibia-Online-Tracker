# Tibia Online Tracker

Tracks Tibia online login/logout history and finds likely alts from adjacency patterns.

## Features

- Tracker service stores online sessions into PostgreSQL
- Altfinder API + Discord slash commands
- Hidden-character scoring from login/logout adjacency data
- Session-duration similarity scoring from online-time behavior
- Evidence gating to reduce low-signal matches
- Optional watch alerts for suspicious matches
- Auto clash-spike watch alerts
- Persistent tracked Tibia guild list per Discord server
- Direct traded-character checks for one or more names
- Query result caching with short TTL
- Health dashboard metrics (world save age, bazaar cooldown, cache)
- CSV export, presets, and ignore/allow filtering in web board

## Requirements

- Java 17
- sbt 1.8+
- PostgreSQL
- Discord bot token

## Setup

1. Clone repo
2. Create `.env` in project root (do not commit secrets)
3. Create database schema from `db/schema.sql`

Example `.env`:

```env
DB_HOST=localhost
DB_PORT=5432
DB_USER=your_user
DB_NAME=tibia_tracker
DB_PASSWORD=your_password

ALTFINDER_TOKEN=your_discord_bot_token
DISCORD_GUILD_ID=your_server_id

TRACKER_WORLDS=Nefera
TRACKER_INTERVAL_SECONDS=15

BAZAAR_WORLD=Nefera
ALTFINDER_API_PORT=8081

HIDDEN_LIKELY_MIN_SCORE=70
HIDDEN_LIKELY_MIN_ADJACENCIES=3
HIDDEN_LIKELY_MAX_CLASH_RATIO=0.25
MIN_EVIDENCE_LOGINS=8
MIN_EVIDENCE_ADJACENCIES=2
INCLUDE_LOW_EVIDENCE_MATCHES=false
QUERY_CACHE_TTL_SECONDS=60
BAZAAR_RATE_LIMIT_COOLDOWN_SECONDS=1800
```

## Run

Run tracker:

```bat
run-tracker.bat
```

Run altfinder:

```bat
run-altfinder.bat
```

Run both:

```bat
start-all.bat
```

## API

- `GET /api/altfinder/health`
- `GET /api/altfinder/status`
- `GET /api/altfinder/alts?characters=name1,name2&distance=0&includeClashes=false`
- `GET /api/altfinder/trades?characters=name1,name2&lookbackDays=30`
- `GET /api/altfinder/clashes?characters=name1,name2&targets=name3,name4&distance=0`
- `GET /api/altfinder/research?limit=25`
- `GET /api/altfinder/guild?name=<guild>` (includes online member names)

UI page is served at:

- `/`
- `/altfinder`

## Standalone Web Version (Render Static Site)

You can deploy a separate frontend from the `web` folder.

If you use Render, deploy the root `render.yaml` blueprint so the backend API exists as a public web service.

### Render Static Site settings

1. New `Static Site` in Render from this repo
2. Root Directory: `web`
3. Build Command: *(leave empty)*
4. Publish Directory: `.`

After deploy, open the static site URL and set **API Base URL** to your backend service URL, for example:

`https://your-altfinder-service.onrender.com`

The page will call:

- `/api/altfinder/health`
- `/api/altfinder/status`
- `/api/altfinder/alts`

## Render Blueprint (recommended)

This repo includes `render.yaml` that provisions:

- `tibia-alt-finder-api` (`web`) - public API + Discord bot process
- `tibia-online-tracker-worker` (`worker`) - tracker poller
- `tibia-scout-board` (`static`) - standalone Scout Board frontend
- `tibia-tracker-db` (PostgreSQL)

After first deploy:

1. Run `db/schema.sql` in the Render Postgres database.
2. Set `TOKEN` on `tibia-alt-finder-api` (this is the Discord bot token env var used in Render).
3. Optional: set `DISCORD_GUILD_ID` to your server ID for fast command sync.
4. Open the `tibia-scout-board` URL and set Backend URL to the `tibia-alt-finder-api` URL.

If you see `404` at `/api/altfinder/health`, the URL is not pointing to the altfinder web service.

## Discord Commands

- `/alts`
- `/alt`
- `/alts-last`
- `/history`
- `/compare`
- `/clashes`
- `/world`
- `/guild`
- `/watch`
- `/guildtrack`
- `/trades`

## Changelog

- [#19 fix: add server-side timeouts to /alts, /trades, /clashes to prevent Render proxy connection drops](https://github.com/pkaye769/Tibia-Online-Tracker/pull/19)

## Troubleshooting

- `Address already in use`: free the port or set `ALTFINDER_API_PORT`
- No slash commands: ensure bot invite includes `applications.commands`, restart bot
- `Total logins: 0`: tracker has not captured history for that character yet

