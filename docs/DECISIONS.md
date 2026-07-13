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

## 2026-07-12 — Recorder-first field-test policy

- Product work is frozen around segments/social until the local recorder and
  Strava export are dependable; `docs/ROADMAP.md` is now recorder-first.
- Manual pause/resume is part of the raw contract via explicit `event` lines.
  Sensor samples are absent during a manual pause, and analysis must not bridge it.
- `STILL` is not a recording pause. Full-rate IMU remains captured while stationary
  during the calibration/field-test period because it is required to validate
  stationarity, preserve motion-onset evidence and tune device-specific noise.
  Adaptive stationary sampling is allowed later only with heartbeat plus pre-roll.

## 2026-07-13 — Dhava design system wraps Material 3

- Keep Material 3 Expressive as the behavior/accessibility foundation, but expose
  Dhava product components and tokens from `:core:ui` for visually significant UI.
  Feature screens may still use low-level Compose primitives and appropriate Material
  controls, but ride controls, panels, metrics, headers, status pills, spacing, shapes
  and palette must remain coherent through the shared layer.
- The visual direction is a dark-first downhill field instrument: map-led recording,
  vivid dirt-orange action/state accent, tabular live numerals, glove-sized controls,
  restrained flat activity surfaces and complete explicit recording states.
- Hide top-level navigation during preparation, active recording, pause/save workspaces
  and pushed detail screens so chrome never competes with the ride or data-loss actions.
- Do not adopt the experimental Compose Styles API solely for theming. Revisit only if
  its stable API materially reduces custom-component maintenance.

## 2026-07-13 — STILL is earth-relative; transport is classified after recording

- `STILL` means stationary relative to the Earth, not merely low IMU variance. A
  smoothly moving bus can have calm accelerometer/gyroscope data and must remain
  `MOVING` when GPS displacement corroborates motion. While genuinely still, live
  fusion holds a stable map position instead of drawing GPS-noise patterns.
- Never stop recording or discard raw samples solely because a ride appears to be in
  motorized transport. Shuttle/bus speeds can overlap riding, walking and trail
  transitions, while raw data must remain available for recomputation. A later Rust
  post-processing classifier may label and exclude motorized intervals from processed
  activity statistics and segments, with uncertainty; Kotlin must not own this rule.
- Until horizontal inertial prediction is validated against vibration-heavy downhill
  recordings, live horizontal output stays GPS-bounded and does not integrate phone
  acceleration. Vertical/orientation processing remains available. This is a safety
  constraint against plausible-looking but unbounded live zigzags, not a permanent
  abandonment of GPS+IMU fusion.

## 2026-07-14 — Recorder screen pre-warms foreground GPS

- While the visible Record screen is idle and precise-location permission exists,
  request 1 Hz high-accuracy location updates. This both centers the working map and
  warms GNSS before Start, reducing time to a clean recording anchor.
- The preview request is strictly lifecycle- and state-scoped: remove it on screen-off,
  tab/app departure, and immediately when Preparing begins. The foreground recording
  service is the sole location owner during preparation and recording; the map must not
  run a second hidden location engine.

## 2026-07-14 — Map presentation is shared across feature screens

- Base-map colors, the vector-style URI and MapLibre chrome configuration live in
  `:core:map`. Recorder and Activity Detail may own their feature-specific overlays,
  cameras and controls, but must consume the shared presentation layer so the two map
  surfaces do not drift visually.
- Hide the decorative MapLibre wordmark on both surfaces. Keep the interactive
  attribution control enabled, legible and tucked into the lower-left map corner above
  feature overlays so OpenStreetMap and map-style credits remain accessible.
- In Activity Detail, render raw GPS as a thin neutral line with one visible point per
  fix in `GPS` and `Compare`. In Compare, layer the raw line below the continuous
  primary-orange fusion path, then layer raw GPS points above fusion so each actual
  measurement remains inspectable. Apply shared high-contrast text and halo colors to
  every base-map symbol layer because upstream style layer names are not a reliable
  label classifier.
- Manual pause boundaries are part of the Rust diagnostic replay contract via
  `DiagnosticTrackPoint.section_id`. Android may group points into map geometries by
  that identifier but must never infer pause timing from timestamp gaps. Every section
  is a separate line, while GPS points remain visible; if replay is unavailable, prefer
  isolated fixes over a false bridge.
- Completed tracks mark start with a green play glyph and finish with a primary-orange
  checkered flag, both inside dark-cased circular badges. Use raw endpoints in GPS mode
  and fused endpoints in Fusion/Compare when available.

## 2026-07-14 — GPS owns horizontal position; IMU supports dynamics and timing

- Off a known segment, GPS is the absolute horizontal reference. Phone IMU must not
  freely dead-reckon an XY path: uncontrolled mounting, yaw ambiguity and downhill
  vibration make the resulting position drift structurally unsafe. IMU remains useful
  for movement state, motion onset, short gate-crossing interpolation, airtime and
  other dynamics; barometer/vertical inertial evidence remains separate.
- On a trusted segment, constrain the ride to a versioned multi-pass centerline using
  continuity-, direction- and uncertainty-aware map matching rather than nearest-point
  snapping. Build the centerline robustly from several quality-gated rides and retain
  a spatial uncertainty corridor. A ride may contribute only to a later geometry
  version, never the version used to match or score itself.
- Until segment matching exists, live horizontal smoothing must stay within a tight
  fresh-GPS accuracy envelope and prefer recovery to a plausible-looking loop.
- In post-ride GPS/Compare diagnostics, keep the raw line neutral and color individual
  GPS fixes continuously by their stored horizontal accuracy: good/leaf-green at 5 m
  or better, amber around 10 m and error-red at 20 m or worse. Always show a numeric
  legend and preserve point casing/layer order so quality is not communicated by color
  alone and GPS remains distinguishable from the primary-orange fusion line.
