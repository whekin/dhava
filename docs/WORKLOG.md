# Worklog

Append-only. Newest entry last. Format: date, what was done, decisions, open questions.

## 2026-07-11 — Phase 0: monorepo skeleton

**Done:**
- Monorepo initialized (git, README, .gitignore, this docs/ set, CLAUDE.md → AGENTS.md).
- `android/`: multi-module Compose app (`:app`, `:core:ui`, `:feature:{record,segments,feed,profile}`),
  M3 Expressive theme (dark-first, seed #D84315, displayLarge 96sp), bottom nav,
  manifest pre-lists (commented) location/sensor/foreground permissions.
  `assembleDebug` green, APK 11.5 MB.
- `backend/`: Go 1.26 + chi + pgx. `/healthz`, `/readyz` (DB ping), `/api/v1/me` stub.
  Server boots without DB (warning). Migrations 0001: postgis, users, activities,
  raw_recordings. Distroless Dockerfile, Makefile, tests green.
- `fusion/`: workspace `fusion-core` + `fusion-worker`. Real `detect_gate_crossing`
  (equirectangular projection, segment intersection, bearing tolerance, time
  interpolation), 3 tests, clippy clean. Worker is a logging stub.
- `deploy/docker-compose.yml`: api + fusion-worker + postgis/postgis:17-3.5 + minio.
- `proto/openapi.yaml` stub.

**Open questions:**
- Coolify deploy not yet exercised (compose written, not applied).
- Strava API app registration not started (needed in Phase 3).

## 2026-07-11 — Phase 1: recording (done)

**Android** (new module `:core:recording` + rewritten `:feature:record`):
- RecordingService: foreground (location type), FusedLocation 1 Hz high-accuracy,
  accel/gyro/mag at SENSOR_DELAY_FASTEST, baro at fastest; all on a HandlerThread.
- Timestamps: single epoch anchor (currentTimeMillis − elapsedRealtime) computed at
  start, applied to both SensorEvent and Location elapsed-realtime stamps.
- IMU pairing: accel is the master clock; each accel event emits one `imu` line with
  the latest stashed gyro/mag. No gyroscope → `[0,0,0]` (spec requires the field).
- Writer: unbounded Channel → single-thread dispatcher → buffered GZIPOutputStream,
  ~2 s flush; sensor callbacks never block. Files: `filesDir/recordings/<uuid>.jsonl.gz`.
- RecordingRepository singleton (StateFlow state/recordings/uploads, ~4 Hz UI updates),
  flat `recordings.json` index (Room later), ActivityUploader via OkHttp
  (create → raw → finish; server id canonical for upload, local UUID stays file key).
- Record UI: permission flow, big Start, glanceable dark recording layout
  (displayLarge elapsed, km/h, accuracy, sample counters), recordings list with
  upload states. BuildConfig API_BASE_URL default http://10.0.2.2:8080.
- 6 unit tests pin exact JSONL of every line type to the spec. assembleDebug green.

**Backend**: the three ingest endpoints per proto/raw-recording-format.md
(create 201 / raw 204 with 400/404/413/415 guards, streamed to blob store / finish 204).
`internal/blob`: Store interface, S3(minio-go) impl + FS fallback (BLOB_DIR).
Migration 0002: user_id nullable, ended_at added. 23 unit tests + full e2e smoke
against dockerized PostGIS (arm64 note: postgis/postgis has no arm64 manifest —
use imresamu/postgis locally; noted in deploy compose). openapi.yaml updated.

**Open:**
- No retry queue for uploads (WorkManager later); no auth (v1.5).
- Not yet run on a real device/emulator — next session: install, record a walk,
  upload, eyeball the JSONL.
