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
