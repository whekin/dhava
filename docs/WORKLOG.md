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

## 2026-07-11 — Phase 1.1: save flow, background upload, ARM images

Owner requirements: fully offline recording + save; upload in background when the
user saves; title/description/bike entered at save time. Also: the Coolify VPS is
ARM — deploy compose now uses multi-arch `imresamu/postgis` everywhere.

**Android:**
- Save sheet after Stop: title (prefilled by time of day), description, bike picker
  as horizontal selectable cards + inline "Add bike" dialog (Full-sus/Hardtail/
  E-bike/Other); bikes persist in `bikes.json`, last-used preselected. Discard with
  confirm deletes file + entry.
- Upload rewritten to WorkManager: unique work per recording (KEEP), NetworkType.
  CONNECTED, exponential backoff, 5 attempts then terminal `failed` with manual
  Retry. Server id persisted after create → retries skip create (idempotent).
  Repository re-enqueues `pending_upload` entries on init.
- Recording status lifecycle persisted in the index: recorded → pending_upload →
  uploaded / failed; unsaved recordings reopen the save sheet ("Finish saving"),
  surviving process death. 13 unit tests green, assembleDebug green.

**Backend:**
- Migration 0003: activities.title/description/bike/bike_type (nullable, CHECK on
  bike_type enum). finish accepts optional metadata (caps: 200/5000/100, Unicode
  rune counts); missing == empty == NULL. 33 tests + e2e smoke on imresamu/postgis
  (migrations up/down round-trip verified). openapi.yaml + raw-recording-format.md
  updated.

**Open:** same as above (device test pending) + WorkManager job not yet observed
end-to-end on device with real airplane-mode toggling.

## 2026-07-11 — First real device test + crash recovery (Phase 1.2)

**Field test** (OnePlus 9 Pro, Android 16): 4 short recordings saved fine. One
13-min ride was KILLED mid-recording by OxygenOS (ApplicationExitInfo reason=13
"o-kill", importance=125 — FGS alive when killed; OnePlus = aggressive OEM killer).
File survived as truncated gzip, but was invisible in-app (index entry was only
created at Stop) → user believed the ride lost.

**Data quality findings (great news):**
- IMU: 501 Hz, zero gaps >50 ms. GPS: 1.06 Hz, median accuracy 3.8 m (one 60 m
  outlier). No barometer on this device (sensor absent) — handled gracefully.
- Airtime concept validated on raw data: bunny hops visible as ~200 ms low-|a|
  windows; stair-drop landing peaked at 19.8 g. Simple thresholding already works.

**Fixes shipped:**
- Index entry now created at Start (status `recording`) — active-recording marker.
- Startup recovery: repairs truncated gzip (decompress until error, atomic rewrite),
  recovers orphan files and stuck `recording` entries → status `recorded` +
  `recovered: true`, "Recovered after crash" + Finish saving in UI. Never deletes
  unrecoverable files (raw-forever principle). 7 new unit tests.
- START_STICKY resume: null-intent restart repairs the file and CONTINUES recording,
  appending a new gzip member (RFC 1952 multi-member; readers handle it).
- Writer: GZIPOutputStream(syncFlush=true) — the ~2 s loss bound is now actually
  guaranteed (before, flush() didn't force the deflater).
- PARTIAL_WAKE_LOCK during recording; battery-optimization exemption dialog on Start
  (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), never blocks recording.

**Open:**
- Backend still not deployed to Coolify — uploads from the phone can't complete
  outside the dev Wi-Fi (APK bakes the Mac's LAN IP). Deploy = next step.
- Re-test on device: recovery of the real 12 MB orphan, kill-resilience on a long
  ride with exemption granted, airplane-mode upload queue.
