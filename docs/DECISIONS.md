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
- **Battery strategy (revised 2026-07-27)**: raw IMU is explicitly capped at
  200 Hz; the live fusion filter consumes 50 Hz. Screen off removes map/UI work.
  The writer keeps lossless low-rate GPS/meta/events separate from a bounded IMU
  backlog, preventing temporary storage stalls from growing memory without limit.
## 2026-07-12 — Live telemetry uses fusion-core at reduced input rate

Real-time values shown during recording are Rust fusion results, never direct
Android GPS-derived metrics. Raw IMU is recorded at the configured acquisition
rate (currently 200 Hz), while the live pipeline downsamples it to 50 Hz and publishes UI
snapshots at display/GPS rate. Map rendering is foreground-UI-only; background
recording keeps raw capture and low-cost fusion state without rendering. Final
activity results are always recomputed canonically from the raw on-device file.

## 2026-07-12 — Recorder-first field-test policy

- Product work is frozen around segments/social until the local recorder and
  Strava export are dependable; `docs/ROADMAP.md` is now recorder-first.
- Manual pause/resume is part of the raw contract via explicit `event` lines.
  Sensor samples are absent during a manual pause, and analysis must not bridge it.
- `STILL` is not a recording pause. High-rate IMU remains captured while stationary
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
- An accepted exact-zero GPS fix is an immediate horizontal stop anchor: the preceding
  displacement describes arrival and must not carry velocity beyond that fix. Hold the
  fused position while subsequent fixes remain inside the root-sum-square accuracy of
  the anchor, then re-seat position and velocity together when Earth-relative movement
  clears the gate. If repeated zero reports are disproved by continued movement, keep a
  rearm anchor so transport cannot alternate STILL/MOVING every other sample; trust zero
  again only after its coordinates stabilize.

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
- Until trusted segment matching exists, every accepted moving GPS fix is authoritative
  for rendered horizontal position. Do not treat a large accuracy radius as permission
  for velocity/bearing prediction to cut a bend: without another spatial reference,
  that is invented geometry. Fusion may still hold stationary position, reject bad
  fixes and maintain velocity/vertical/dynamics state.
- In post-ride GPS/Compare diagnostics, keep the raw line neutral and color individual
  GPS fixes continuously by their stored horizontal accuracy: good/leaf-green at 5 m
  or better, yellow around 10 m, gold through the accepted 20 m boundary and error-red
  only above 20 m. Always show a numeric
  legend and preserve point casing/layer order so quality is not communicated by color
  alone and GPS remains distinguishable from the primary-orange fusion line.

## 2026-07-14 — Finalized horizontal fusion is delayed and GPS-bounded

- Preserve the exact causal recording result as `fused_track`; expose delayed post-ride
  geometry separately as `finalized_track`. Live UI must not pretend that future GPS
  evidence is already available. A later provisional-live tail may replace only its
  buffered final interval when the next fix arrives.
- Finalized horizontal samples target 5 Hz, but accepted GPS fixes remain immutable
  anchors. Neighboring GPS geometry defines safe tangents; GPS speed and gravity-axis
  gyro may redistribute samples and turn timing only inside each anchor interval. IMU
  must never introduce an unbounded XY displacement.
- Constrain each interpolated interval to forward progress and an accuracy-aware
  corridor capped at 6 m. Do not interpolate across manual pause sections or sensor/GPS
  gaps longer than 2.5 seconds. Use later displacement evidence to restore moving GPS
  fixes that conservative causal `STILL` temporarily held at the stop anchor.
- In Activity Detail, distinguish computed 5 Hz samples from measured GPS anchors:
  computed points are smaller, light-centered, below the accuracy-colored GPS layer and
  visible only at useful detailed zoom. Scale GPS dots with zoom as well so overview
  mode communicates the line rather than collapsing into a chain of overlapping dots.
- Diagnostic line and point layers must preserve the same coordinates. Disable
  MapLibre GeoJSON line simplification for raw and fusion sources; otherwise maximum
  zoom can reveal a false visual offset even though both layers originate from the same
  track. Keep 5 Hz fusion points hidden below zoom 18.

## 2026-07-14 — Strava export is one tap after one-time connection

- The rider connects Strava once through its mobile OAuth flow. Each saved activity
  then exposes one `Export to Strava` action with queued, exporting, uploaded and
  retryable-failure states. Offline taps enqueue network-constrained WorkManager work.
- Strava requires the application client secret for authorization-code exchange and
  token refresh, so never embed that secret in the APK. A minimal Go backend broker
  owns the secret and latest refresh token and performs or proxies the asynchronous
  Strava upload. Core recording and local activities remain backend-optional.
- Only the processed canonical GPX/FIT artifact may pass through the broker; raw GPS,
  IMU and barometer recordings remain on the phone. Use a stable external id derived
  from recording id plus algorithm version, persist the returned Strava upload/activity
  ids and prevent accidental duplicate submissions.
- Ship canonical finalized GPX first, including separate pause sections, ride title,
  description and `MountainBikeRide` sport type. Move to FIT later for richer pause,
  device and sport metadata without changing the one-button UX.

## 2026-07-14 — GPX exposes processed and raw GPS artifacts

- Activity Detail offers two local Share targets: `Processed · 5 Hz` is the normal
  ride artifact, while `Raw GPS` preserves the original recorded fixes for inspection
  and interoperability. The two filenames are explicit so they cannot be confused.
- Processed GPX coordinates and timestamps come directly from Rust
  `finalized_track`; Android must not resample or smooth them again. Both exports use
  Rust replay `section_id` boundaries to create separate GPX `<trkseg>` elements, so
  a pause never becomes a straight line in another application.
- Raw GPX keeps recorded GPS elevation. Processed GPX may include `<ele>` only when the
  canonical Rust contract supplies it; Android must never invent vertical fusion. The
  first export omitted elevation, and the later `gps-bounded-0.2` Rust artifact made it
  vertically canonical.

## 2026-07-14 — Canonical activities are rebuildable caches, raw remains truth

- The immutable `recordings/<id>.jsonl.gz` file remains the only source of truth.
  Canonical output lives separately under `activity-artifacts/` as a gzip-compressed
  JSON cache. Creating, reading, invalidating or deleting that cache never edits raw;
  an explicit user discard removes both.
- A cache is reusable only when its schema version, Rust algorithm version, raw byte
  size and raw modification time all match. Missing, corrupt, stale and old-algorithm
  artifacts are rebuilt locally from raw and atomically replace the old file. This
  makes an algorithm upgrade a cache invalidation rather than a data migration. The
  fingerprint is checked again after computation; a recording that resumed or grew
  concurrently cannot publish a stale result under the newer fingerprint.
- Rust `finalize_recording` parses raw once and owns the complete derived result:
  metrics, raw GPS diagnostic view, GPS-bounded 5 Hz track, pause section ids and
  finalized elevation. Android owns persistence and representation conversion only.
- Explicit Finish publishes the save workspace immediately, then canonicalizes on an
  IO scope. If the process dies before completion, Activity Detail or GPX export lazily
  performs the same rebuild. Existing recordings are migrated lazily instead of all at
  app startup, avoiding a large CPU/battery spike.
- Canonical vertical v0 preserves relative barometer movement and anchors it to
  median-filtered, accuracy-gated GPS altitude. GPS-only recordings use section-aware
  altitude interpolation. Ascent/descent hysteresis resets at pause boundaries. The
  canonical algorithm version is currently `gps-bounded-0.4`.

## 2026-07-19 — Short GPS jumps require accuracy and Doppler consistency

- Raw GPS fixes remain immutable and visible in diagnostics. A processed horizontal
  anchor still needs accuracy ≤20 m, and a short 0.2–5 s move is additionally rejected
  when its chord exceeds the sum of both reported accuracy radii plus the distance
  allowed by the larger endpoint Doppler speed and a 3 m/s acceleration margin.
- Missing, exact-zero and sub-1.5 m/s reported speeds never veto earth-relative
  displacement. OnePlus field recordings proved that a calm phone on a moving vehicle
  can report zero, so coordinate motion must remain able to release STILL. Manual pause
  boundaries reset the kinematic anchor; a rejected fix does not advance it, allowing
  the next consistent fix to recover normally.
- Maximum speed prefers Android's Doppler speed. Coordinate-derived maxima are used
  only across consecutive fixes without reported speed; average moving speed is the
  conservative floor. This avoids interpreting an allowed correction inside GPS
  uncertainty as instantaneous rider velocity.

## 2026-07-20 — GPS-only elevation is a net metric, not accumulated gain

- A barometric canonical track keeps accumulated ascent/descent. Without a
  barometer, short smoothing cannot distinguish actual repeated climbing from
  low-frequency GPS-altitude drift, so accumulated GPS gain/drop is not presented as
  trustworthy.
- GPS-only canonical metrics sum robust net altitude changes independently per
  continuous recording section. Each section uses the median of up to five accepted
  altitude fixes at each endpoint, applies the existing 2 m deadband and never bridges
  a pause. The processed track still carries section-aware interpolated GPS elevation;
  immutable raw remains available for future DEM or multi-run segment recalculation.
- Android labels the downward metric `Net drop` and the quality source `GPS net`.
  GPS-only uncertainty is a conservative display heuristic of `max(7 m, p90 horizontal
  accuracy × 2)`; it describes the reported net metric and must not feed timing,
  segment matching or corrections.

## 2026-07-27 — Interrupted recordings remain visible and repeatedly resumable

- A killed foreground recorder is expected to be restartable more than once.
  START_STICKY includes the asynchronous repair/claim window; returning
  START_NOT_STICKY before that claim completes makes the next process kill terminal.
- Every interrupted index entry and orphan raw file remains visible. Readable files
  may be continued or saved; undecodable bytes are surfaced as `Raw only` and may be
  retained/exported instead of becoming invisible disk orphans.
- Continue appends a new RFC 1952 gzip member and writes pause/resume boundary events,
  preserving raw history without drawing or analyzing across process downtime.
- Raw accelerometer/gyro acquisition is capped at 200 Hz. A 4,096-line bounded IMU
  queue contains short storage stalls; GPS, meta, barometer and lifecycle events use
  a separate lossless queue. Any IMU overflow is recorded as a diagnostic event.
