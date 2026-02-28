# Tibia Online Tracker

Tracks Tibia online login/logout history and finds likely alts from adjacency patterns.

## Features

- Tracker service stores online sessions into PostgreSQL
- Altfinder API + Discord slash commands
- Hidden-character scoring from login/logout adjacency data
- Optional watch alerts for suspicious matches

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

UI page is served at:

- `/`
- `/altfinder`

## Standalone Web Version (Render Static Site)

You can deploy a separate frontend from the `web` folder.

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

## Discord Commands

- `/alts`
- `/alt`
- `/alts-last`
- `/history`
- `/compare`
- `/world`
- `/guild`
- `/watch`

## Troubleshooting

- `Address already in use`: free the port or set `ALTFINDER_API_PORT`
- No slash commands: ensure bot invite includes `applications.commands`, restart bot
- `Total logins: 0`: tracker has not captured history for that character yet

