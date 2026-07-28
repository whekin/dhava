# Dhava Backend

Go HTTP API for Dhava (downhill MTB ride recording: segments, leaderboards, GPS/IMU ingest).

## Run

    make run          # or: make build && ./bin/api

## Environment variables

- `PORT` — HTTP port (default `8080`)
- `DATABASE_URL` — PostgreSQL connection string (optional; `/readyz` reports 503 without it)
- `LOG_LEVEL` — `debug` | `info` | `warn` | `error` (default `info`)
- `S3_ENDPOINT` — S3/MinIO endpoint, scheme optional (e.g. `http://localhost:9000`; no scheme implies HTTPS). Unset → filesystem blob storage
- `S3_BUCKET` — bucket for raw recordings (default `dhava`; created at startup if missing)
- `S3_ACCESS_KEY` / `S3_SECRET_KEY` — S3 credentials
- `BLOB_DIR` — base directory for the filesystem blob store (default `./data/blobs`; used when `S3_ENDPOINT` is unset)
- `PUBLIC_BASE_URL` — public HTTPS origin of the API, without a trailing slash
  (for example `https://api.dhava.app`); used to build the Strava callback
- `STRAVA_CLIENT_ID` / `STRAVA_CLIENT_SECRET` — credentials from the
  [Strava API dashboard](https://www.strava.com/settings/api). The secret is
  backend-only and must never be put in Gradle properties or the APK.
- `STRAVA_APP_REDIRECT_URL` — post-OAuth Android deep link
  (default `dhava://strava/connected`)

Strava export is enabled only when PostgreSQL and all three of
`PUBLIC_BASE_URL`, `STRAVA_CLIENT_ID`, and `STRAVA_CLIENT_SECRET` are configured.
Register the hostname from `PUBLIC_BASE_URL` as the Strava application's
Authorization Callback Domain. For phone testing before deployment, expose the
local API through a stable HTTPS tunnel and use that tunnel hostname.

For a USB-attached Android test, no tunnel is required: Strava explicitly
allows `127.0.0.1` callbacks. Set the app's Authorization Callback Domain to
`127.0.0.1`, run the API with
`PUBLIC_BASE_URL=http://127.0.0.1:8080`, build Android with
`-PdhavaApiBaseUrl=http://127.0.0.1:8080`, and forward the phone port:

    adb reverse tcp:8080 tcp:8080

The browser callback reaches the Mac through ADB, then the API redirects to the
registered `dhava://strava/connected` app link.

## Migrations

SQL files in `migrations/` use the [golang-migrate](https://github.com/golang-migrate/migrate) format:
`migrate -path migrations -database "$DATABASE_URL" up`. Requires PostgreSQL with PostGIS.
