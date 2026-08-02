# Nakvali Backend

Go HTTP API for Nakvali's private alpha: health/readiness, Strava OAuth and
processed GPX export, plus explicitly opt-in legacy GPS/IMU ingest routes.

## Run

    make run          # or: make build && ./bin/api

## Environment variables

- `PORT` — HTTP port (default `8080`)
- `DATABASE_URL` — PostgreSQL connection string (optional; `/readyz` reports 503 without it)
- `LOG_LEVEL` — `debug` | `info` | `warn` | `error` (default `info`)
- `API_ACCESS_KEY` — shared private-alpha perimeter key expected in
  `X-Nakvali-Access-Key`. Leaving it empty is convenient for local development;
  the production Compose file requires it. This is not user authentication.
- `FIREBASE_PROJECT_ID` — enables Firebase ID-token verification for `/api/v1/me`.
  Outside Google infrastructure, `GOOGLE_APPLICATION_CREDENTIALS` must point to a
  readable mounted service-account JSON file. The API never accepts client-provided
  UID/email fields without first verifying the Firebase bearer token.
- `RAW_UPLOADS_ENABLED` — exposes the legacy activity/raw upload endpoints when
  `true` (default `false`). Product deployments keep immutable raw sensor data
  on-device and must leave this disabled.
- `S3_ENDPOINT` — S3/MinIO endpoint, scheme optional (e.g. `http://localhost:9000`; no scheme implies HTTPS). Unset → filesystem blob storage
- `S3_BUCKET` — bucket for raw recordings (default `nakvali`; created at startup if missing)
- `S3_ACCESS_KEY` / `S3_SECRET_KEY` — S3 credentials
- `BLOB_DIR` — base directory for the filesystem blob store (default `./data/blobs`; used when `S3_ENDPOINT` is unset)
- `PUBLIC_BASE_URL` — public HTTPS origin of the API, without a trailing slash
  (for example `https://api.nakvali.app`); used to build the Strava callback
- `STRAVA_CLIENT_ID` / `STRAVA_CLIENT_SECRET` — credentials from the
  [Strava API dashboard](https://www.strava.com/settings/api). The secret is
  backend-only and must never be put in Gradle properties or the APK.
- `STRAVA_APP_REDIRECT_URL` — post-OAuth Android deep link
  (default `nakvali://strava/connected`)

Strava export is enabled only when PostgreSQL and all three of
`PUBLIC_BASE_URL`, `STRAVA_CLIENT_ID`, and `STRAVA_CLIENT_SECRET` are configured.
Register the hostname from `PUBLIC_BASE_URL` as the Strava application's
Authorization Callback Domain. For phone testing before deployment, expose the
local API through a stable HTTPS tunnel and use that tunnel hostname.

For a USB-attached Android test, no tunnel is required: Strava explicitly
allows `127.0.0.1` callbacks. Set the app's Authorization Callback Domain to
`127.0.0.1`, run the API with
`PUBLIC_BASE_URL=http://127.0.0.1:8080`, build Android with
`-PnakvaliApiBaseUrl=http://127.0.0.1:8080`, and forward the phone port:

    adb reverse tcp:8080 tcp:8080

The browser callback reaches the Mac through ADB, then the API redirects to the
registered `nakvali://strava/connected` app link.

Private-alpha Android builds targeting a protected backend also need the same
access key outside the repository:

    ./gradlew :app:assembleDebug \
      -PnakvaliApiBaseUrl=https://api.example.com \
      -PnakvaliApiAccessKey=replace-with-the-private-alpha-key

Prefer putting those two properties in the developer's untracked
`~/.gradle/gradle.properties`. Use a URL-safe key so it can be represented as a
Gradle string property without extra escaping. The key is compiled into the APK,
so rotate it if that private build is shared and replace this perimeter with
real account authentication before public distribution.

## Migrations

SQL files in `migrations/` use the [golang-migrate](https://github.com/golang-migrate/migrate) format:
`migrate -path migrations -database "$DATABASE_URL" up`. Requires PostgreSQL with PostGIS.

The production API image runs this command before starting the HTTP server. A
migration failure prevents the API from becoming ready; repeated starts are
safe because already-applied migrations are skipped.
