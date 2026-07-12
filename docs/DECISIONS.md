# Decisions

Short log of architecture/product decisions. Newest last.

## 2026-07-11

- **Monorepo** (`android/`, `backend/`, `fusion/`, `proto/`, `deploy/`).
- **Go for API, Rust for fusion** — not either/or. Fusion must be Rust because the
  same crate ships on-device (UniFFI) and server-side; CRUD/social is faster in Go.
- **Raw-first pipeline**: phone uploads raw GPS+IMU+baro; server computes canonical
  results; raw kept forever so results are recomputable as algorithms improve.
- **material3 1.5.0-alpha09** pinned outside the Compose BOM — `MaterialExpressiveTheme`
  is `internal` in 1.4.0 stable. Accepted alpha-API churn for a greenfield app.
- **Gradle 9.0 / AGP 8.12 / Kotlin 2.2** — proven-on-this-machine baseline copied from
  a neighboring project.
- **Raw recording format = gzip JSONL** (`proto/raw-recording-format.md`), field names
  match `fusion-core` serde types. Chosen over protobuf for debuggability; gzip closes
  most of the size gap. Revisit if upload sizes hurt.
- **No auth in Phase 1** — endpoints open, device-token auth comes in v1.5. Fine while
  the API is not public.
- **Package root** `com.dhava`, module deps: features → core, never feature → feature.
- **PostGIS image: `imresamu/postgis`** (multi-arch) — official `postgis/postgis` has
  no arm64 manifest; dev Mac and the Coolify VPS are both ARM.
- **Upload model**: recording and saving are fully offline; upload is a background
  WorkManager job enqueued at save time (network constraint, exponential backoff).
  Activity metadata (title, description, bike) is entered at save and sent with
  `finish`; the raw file never changes after recording stops.
- **Device-first compute** (VPS is 4-core/8GB ARM; also better UX): the phone runs
  fusion-core (UniFFI) and computes results in realtime; upload = results + raw.
  Server accepts device results as primary (same crate, same version → identical
  output), recomputes selectively: KOM/top claims (anti-cheat, raw is on hand) and
  batch recompute on algorithm upgrades. Server compute cost per ride is <1 s/core
  anyway — the scarce resource is DISK: raw at ~60 MB/h/rider goes MinIO now,
  Cloudflare R2 when it grows.
- **REVISED (owner call, same day): raw stays on the device.** Server stores only:
  corrected 1–5 Hz fused track (GPX on export), segment results with uncertainty +
  algorithm version, per-second IMU evidence pack (variance/peaks/energy — a few KB)
  for physics-based anti-cheat, Play Integrity attestation. Raw windows (segment
  run ±10 s) uploaded only on server request for KOM verification. Trade-off
  accepted: history recompute happens on-device; lost phone = lost raw (results
  survive). Optional raw cloud backup may come later. Phase 1 raw-upload endpoints
  stay for now (dev convenience) but are no longer the product path.
- **Battery strategy**: recording is always full-rate (raw file, cheap IO); the
  live fusion filter consumes ~100 Hz (decimated at filter input only). Screen off →
  no UI compute + hardware sensor batching (1–2 s FIFO flushes, CPU sleeps); only a
  cheap gate-proximity geofence stays on to wake live mode near segment starts.
## 2026-07-12 — Live telemetry uses fusion-core at reduced input rate

Real-time values shown during recording are Rust fusion results, never direct
Android GPS-derived metrics. Raw sensors remain recorded at hardware rate, while
the live pipeline may downsample IMU input (currently 50 Hz) and publish UI
snapshots at display/GPS rate. Map rendering is foreground-UI-only; background
recording keeps raw capture and low-cost fusion state without rendering. Final
activity results are always recomputed canonically from the raw on-device file.
