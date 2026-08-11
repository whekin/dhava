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
- **Package root** `com.nakvali`, module deps: features → core, never feature → feature.
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

## 2026-07-13 — Nakvali design system wraps Material 3

- Keep Material 3 Expressive as the behavior/accessibility foundation, but expose
  Nakvali product components and tokens from `:core:ui` for visually significant UI.
  Feature screens may still use low-level Compose primitives and appropriate Material
  controls, but ride controls, panels, metrics, headers, status pills, spacing, shapes
  and palette must remain coherent through the shared layer.
- The visual direction is a dark-first downhill field instrument: map-led recording,
  vivid trail-green action/state accent, tabular live numerals, glove-sized controls,
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
  primary-green fusion path, then layer raw GPS points above fusion so each actual
  measurement remains inspectable. Apply shared high-contrast text and halo colors to
  every base-map symbol layer because upstream style layer names are not a reliable
  label classifier.
- Manual pause boundaries are part of the Rust diagnostic replay contract via
  `DiagnosticTrackPoint.section_id`. Android may group points into map geometries by
  that identifier but must never infer pause timing from timestamp gaps. Every section
  is a separate line, while GPS points remain visible; if replay is unavailable, prefer
  isolated fixes over a false bridge.
- Completed tracks mark start with a sage play glyph and finish with a vivid primary-green
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
  GPS fixes continuously by their stored horizontal accuracy: good/cool-mint at 5 m
  or better, yellow around 10 m, gold through the accepted 20 m boundary and error-red
  only above 20 m. Always show a numeric
  legend and preserve point casing/layer order so quality is not communicated by color
  alone and GPS remains distinguishable from the primary-green fusion line.

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
  canonical algorithm version is currently `gps-bounded-0.5`.

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

## 2026-07-28 — Recorder health is a durable local sidecar

- Operational telemetry must never modify immutable raw sensor input or invalidate
  Rust-derived artifacts. Store it as an append-only
  `recordings/<id>.health.jsonl` sidecar, keep it local, and remove it only with the
  same explicit activity deletion that removes raw.
- Write a heartbeat once per wall-clock minute, including while paused. Persist PSS,
  RSS, Java/native heap, process CPU/uptime, thermal and battery state, raw size,
  per-process sensor counts, GPS age, writer queue depths and cumulative IMU drops.
  Flush and fsync each tiny checkpoint so a process kill cannot erase the evidence.
- On recovery, attach the latest public `ApplicationExitInfo` after the ride start,
  deduplicated by system timestamp, then append a restart checkpoint with the exact
  recording gap. Diagnostics are best-effort: any collection or I/O failure is logged
  and must never block raw repair, sticky restart or explicit Stop.
- Activity Detail exposes the health JSONL separately from raw recording and GPX
  exports. This preserves clear artifact semantics while making field evidence
  shareable without ADB.

## 2026-07-28 — Strava connection is anonymous-device authenticated

- Nakvali does not yet require a product account just to export a local ride. Android
  generates a random 256-bit installation credential and sends it as a bearer token;
  the backend stores only its SHA-256 hash. This credential authorizes only that
  installation's Strava connection and exports, not any future Nakvali social API.
- Strava's client secret, rotating refresh token and short-lived access token stay in
  the Go broker. OAuth returns first to its public HTTPS callback, which consumes a
  ten-minute random state and redirects only a success/denied/failure result through
  `nakvali://strava/connected`. No OAuth token enters the APK or deep link.
- Each export sends only Rust's canonical processed GPX and uses
  `nakvali-<recording-id>-<algorithm-version>.gpx` as its stable external id. A unique
  database row persists Strava upload/activity ids; retries poll an accepted upload
  instead of resubmitting it. If an ambiguous network retry reaches Strava twice, its
  documented duplicate response is resolved back to the existing activity id.
- Strava upload completion is asynchronous. Network-constrained WorkManager owns the
  durable phone-side queue; the broker persists server-side progress, refreshes tokens
  within Strava's one-hour threshold, updates the completed activity to
  `MountainBikeRide`/`EMountainBikeRide`, and returns the final activity id for
  `View on Strava`.

## 2026-07-28 — Activity states are conservative Rust-derived artifacts

- Post-ride activity classification belongs exclusively to `fusion-core` and runs
  after canonical 5 Hz position, speed and elevation have been finalized. Every
  finalized point carries one `ActivityState` — `Unknown`, `Still`, `Downhill`,
  `Transit` or `LikelyMotorized` — plus confidence. Android may map those values to
  visual styling, but must not reproduce or amend the classification rules.
- `LikelyMotorized` is intentionally tentative. Speed alone never proves transport;
  the first classifier requires independent sustained uphill or unusually smooth IMU
  evidence and does not cross manual-pause sections or GPS gaps. Classification is a
  visual diagnostic until it has been calibrated against labelled field recordings:
  it does not yet remove distance/time, exclude segment attempts, auto-pause, or
  discard any raw sample.
- A manual pause is not an `ActivityState`. It remains an explicit raw
  `pause`/`resume` boundary represented by `section_id`, and renderers must still
  split geometry at that boundary regardless of neighboring activity labels.

## 2026-07-28 — Stationary persistence reduces disk work, not evidence collection

- Accelerometer/gyroscope acquisition remains capped at 200 Hz and Rust live fusion
  continues receiving 50 Hz. Only rows persisted to raw are reduced, to 20 Hz, while
  Rust reports confirmed earth-relative `STILL`; this preserves enough stationary
  evidence for deterministic replay while reducing long-stop serialization, queue
  pressure and storage.
- Keep a rolling two-second full-rate stationary IMU pre-roll. Flush it in timestamp
  order when movement resumes and before manual pause or Finish, so canonical
  recomputation retains motion onset and terminal evidence. Adaptive persistence never
  creates a pause section, and GPS/barometer collection continues normally.
- GPS remains high-accuracy at approximately 1 Hz during `STILL`. It is the external
  earth-relative evidence that releases a calm phone riding in a bus or shuttle, so
  lowering GPS cadence at this stage would make transport/stationarity failures harder
  to detect. Physical long-ride validation remains required before treating the
  persistence policy as field-proven.
- The full-rate pre-roll is process-local. A hard process kill while `STILL` can
  therefore lose up to two additional seconds of stationary IMU (GPS, barometer and
  the older 20 Hz evidence remain durable); a kill in the roughly 250 ms motion-release
  window can also lose that onset. This bounded trade-off is accepted for the first
  field iteration. A tiny crash-safe circular sidecar is the upgrade path if field
  evidence shows that boundary loss matters.

## 2026-07-28 — Android UniFFI bindings use the Android-aware cleaner

- Nakvali supports Android API 26, so generated Kotlin bindings must not directly
  reference the API 33 `java.lang.ref.Cleaner` path selected by UniFFI's
  platform-neutral default.
- Keep `android_cleaner = true` in `fusion-core/uniffi.toml`. Generated bindings use
  `android.system.SystemCleaner` on API 34+ and the packaged JNA cleaner on older
  Android versions. `androidx.annotation` remains a compile-only dependency for the
  generated SDK guard; JNA remains a runtime AAR dependency.
- Regenerate the committed Kotlin binding through `fusion/scripts/build-android.sh`
  after changing the UDL, UniFFI version or bindgen configuration. App-level lint is
  the release gate for verifying the guard against Android's minSdk.

## 2026-07-28 — Local draft segments are gate-timed, corridor-verified Rust results

- Segments are unfrozen as a fully local feature. No backend is involved: authoring,
  matching, results and their invalidation all happen on the phone. Server-side shared
  segments, KOM and verification remain future work.
- A segment is authored from one ride as a **draft**: its centerline is that ride's
  finalized sub-track, `trusted = false`. A draft times runs but is never treated as
  authoritative geometry and never corrects GPS. Multi-pass centerlines, uncertainty
  corridors and map-matched GPS correction come in a later version; every attempt
  already stores `matched_geometry_version` so the rule "a ride may only contribute to
  a later geometry version" stays enforceable.
- Selection is expressed as two **indexes into the finalized track**, never as free map
  coordinates. An index is unambiguous, always lies on the recorded line and cannot
  pick a point from a different pass. The editor's default selection is Rust's longest
  continuous `Downhill` run, reusing the existing conservative ActivityState pass.
- Gate crossing alone is not evidence of an attempt: a start and a finish line can both
  be crossed by a different trail. A candidate must additionally stay inside the segment
  corridor, make monotone forward progress (bounded backtracking) and cover the segment
  by binned centerline visitation. Gates are directed against the local centerline
  tangent, so switchbacks keep working.
- Gate width and corridor are derived in Rust from the source ride's own p90 horizontal
  accuracy (gate half-width `2×`, corridor `3×`, clamped 10–30 m and 15–40 m). A fixed
  width would either miss a crossing at 15 m error or swallow a parallel trail.
- Timing runs on the canonical finalized 5 Hz track, never on ~1 Hz raw fixes, and every
  result is reported with uncertainty: `accuracy / speed` per gate, combined as a root
  sum of squares, bounded and never rounded down to a fake zero. A run without its
  margin would imply precision the sensors do not have.
- Segment elevation is authored in Rust from the same selected canonical geometry.
  Accumulated climb and descent reuse the canonical 2 m hysteresis, while a bounded
  profile (at most 192 distance/altitude samples) is persisted with the segment for
  offline rendering. Old draft files remain readable and simply have no profile.
- Editor camera state is independent from selection state: moving a gate updates map
  layers but never reframes the camera. The slider begins focused on Rust's proposed
  segment so handles remain usable even in a recording containing several runs. One
  stateful map-scale action switches between selected range and full ride in both
  directions. Holding either handle for 700 ms enters a haptic-confirmed precision
  mode where subsequent finger movement is scaled to 10%; the active handle and status
  text change together so precision is never encoded by haptic or color alone. The
  redundant Start/Finish and point-step controls are intentionally omitted.
- Geometry v1 keeps index endpoints for compatibility. New authoring uses geometry v2:
  a gate is a continuous position on a canonical polyline edge (edge index plus a
  fraction), with interpolation, duration and endpoint geometry owned by Rust.
  Uniformly adding points to the same line is not accepted as additional accuracy: it
  changes sampling density without adding spatial evidence. The authored definition
  contains interpolated endpoints, while matching continues to interpolate real gate
  crossings.
- Segment-editor camera actions are semantic. Full ride fits every pause-split section;
  selected range fits the current segment into the map area above the collapsed sheet.
  During a gate drag, a rider's manual zoom remains authoritative: the camera does
  nothing while the endpoint remains in a safe viewport and pans without changing zoom
  only after it approaches an edge or the sheet. Above map zoom 16, handle sensitivity
  halves per zoom level (bounded at 5%); haptic precision multiplies that by another
  10%. This affects interaction sensitivity only, never the persisted geometry.
- An attempt is never silently dropped. A rejected gate pair is surfaced with a reason
  (`NoFinish`, `PausedInside`, `GapInside`, `OffCorridor`, `Backtracked`, `Incomplete`),
  and a countable attempt can carry non-fatal flags (`DefiningRide`, `LowGpsQuality`,
  `LikelyMotorized`, `HighUncertainty`). Consistent with the ride-state decision,
  tentative motorized evidence marks a run uncertain instead of deleting it.
- Persistence separates authored input from derived cache: `segments/<id>.segment.json`
  is rider-authored and never auto-rebuilt, while `segment-results/<id>.results.json.gz`
  is invalidated by the canonical algorithm version, `SEGMENT_MATCH_VERSION`, the
  segment geometry version or a changed raw fingerprint. A segment survives deletion of
  the ride that authored it, because the geometry is copied into the segment file.
- Matching is incremental and prefiltered by padded bounds taken from **raw GPS lines**,
  cached per raw fingerprint in `segment-results/track-bounds.json`. Authoring one
  segment must not force a full fusion pass over every ride ever recorded; the finalized
  track is only built for rides whose GPS hull can actually touch the segment.

## 2026-08-01 — Private-alpha backend is proxy-gated and stores no raw rides

- The first hosted backend is an owner-only alpha on Coolify, deployed from the
  GitHub App using `deploy/docker-compose.yml`. Coolify's proxy is the only public
  ingress: the API uses `expose`, never a host `ports` mapping, while PostGIS has
  a persistent private volume.
- The deployed stack contains only the Go API and PostGIS. The fusion worker is a
  non-running skeleton and MinIO would exist only to receive raw recordings, so
  neither belongs in production yet. SQL migrations run idempotently inside the
  API container before the listener starts; failure keeps readiness closed.
- Production raw-ingestion routes are opt-in and disabled. This enforces the durable
  rule that immutable GPS, IMU and barometer input stays on the device; a future
  server request may upload only a bounded verification window under a separate
  contract.
- During private alpha, every application API route requires a shared
  `X-Nakvali-Access-Key`. Health/readiness and the browser-facing Strava OAuth callback
  stay public; Strava routes additionally retain their per-installation Bearer
  credential. The shared key is compiled only into owner builds and is explicitly
  not public-user authentication: it must be rotated if an APK escapes and replaced
  with real identities before distribution.
- GitHub auto-deploy owns normal releases. Coolify MCP receives a team-scoped read
  token for resource state and redacted logs; any future manual deployment token is
  separate and limited to deployment permission. Secrets never enter Git or chat.

## 2026-08-02 — Gate centers are authored intent, independent from seed geometry

- A segment's reference centerline and its timing gates have different lifecycles.
  New geometry v3 definitions store explicit start and finish gate centers; future
  multi-pass refinement may move the centerline but never those anchors without an
  explicit rider edit. See `docs/adr/0002-authored-gates-outlive-centerline-refinement.md`.
- Gate centers may be dragged to any valid map coordinate rather than snapped to a
  source sample. Rust still derives their direction from the adjacent centerline
  tangent and owns all matching geometry.
- An imported GPX trace is preserved locally as seed evidence, not represented as a
  ride or attempt. It may create a local draft segment, and later real Nakvali rides
  may contribute to a more trusted centerline while the source file remains available
  for recomputation.

## 2026-08-02 — Local backups are user-owned verified input archives

- Backup is an offline Android Storage Access Framework operation, not a server sync
  feature. The rider chooses where the ZIP lives and can copy it to any storage
  provider without granting Nakvali a broad filesystem permission.
- Format v1 contains only irreplaceable or rider-authored input: the recording and
  bike indexes, raw GPS/IMU/barometer streams, recording health logs, authored segment
  definitions and preserved GPX seeds. Canonical tracks, segment results and maps are
  excluded because they are derived/cache data; credentials, access keys and upload
  process state are excluded because they are secrets or ephemeral state.
- A first-entry manifest records schema version, item counts, byte sizes and SHA-256
  for every payload. Restore has bounded entry/path/size rules, stages and verifies
  the entire archive, and only then merges it.
- Merge is intentionally loss-averse: current local metadata and authored files win,
  missing items are added, and a same-name raw recording with different bytes aborts
  before installation. Backup and restore are unavailable while recording, so an
  actively changing sensor stream never enters an archive.
- The format contract is documented in `docs/local-backup-format.md`.

## 2026-08-02 — Nakvali replaces the product and technical identity

- Use **Nakvali** as the customer-facing name. Georgian `ნაკვალი` means a trace,
  footprint, or track left behind, matching recorded lines, multi-ride refinement,
  and ride history without constraining the product to one gravity sport.
- The selection passed a preliminary exact web/store/domain check but still requires
  native-speaker tone validation and formal trademark clearance before public launch.
- The earlier compatibility decision to retain the prototype's internal identity is
  superseded while the app is still private and a verified local backup exists.
  Repository/module paths, Kotlin packages, API headers, deep links, backup/export
  names, deployment defaults and database defaults all use Nakvali.
- Android's new application ID is `com.nakvali.app`, with package root `com.nakvali`.
  It installs separately from the retired prototype; restore the format-v1 backup
  before removing that installation. The backup schema itself contains no brand or
  package marker, so the existing archive remains compatible.
- Firebase must register `com.nakvali.app`. Reuse the established release signing
  certificate; signing identity is independent of application ID and its key alias is
  not renamed merely for cosmetics.

## 2026-08-02 — Firebase Auth uses Credential Manager without Analytics

- Google sign-in uses Firebase Authentication as the identity provider and Android's
  Credential Manager as the interaction surface. The app depends on the main
  `firebase-auth` module rather than the retired Firebase KTX artifact.
- `google-services.json` is committed under `android/app/`: it contains public project
  configuration and OAuth client identifiers, never a service-account key or client
  secret. The Google Services Gradle plugin generates `default_web_client_id` for both
  debug and release builds.
- Firebase Analytics is not enabled merely because it appears in the console's setup
  example. Authentication does not require it, and telemetry remains a separate
  product/privacy decision.
- Android sign-in UI/session state and Go ID-token verification remain separate
  modules; adding SDK dependencies alone is not treated as completed authentication.

## 2026-08-02 — Firebase identity is optional for local riding and mapped to a local user

- Profile is Nakvali's account surface. Android uses Credential Manager for Google,
  lets Firebase persist the session, requests a current ID token only when syncing,
  and never stores or logs the token itself.
- `GET /api/v1/me` is the first Firebase-authenticated API route. The Go API verifies
  the bearer token with the official Firebase Admin SDK and trusts only the verified
  UID and selected profile claims. Firebase UID is a unique external identity; a
  Nakvali UUID remains the stable internal key for future rides, PRs and leaderboards.
- Authentication is additive to the private-alpha perimeter: `/me` currently requires
  both `X-Nakvali-Access-Key` and a Firebase bearer token. Strava keeps its anonymous
  installation bearer until an explicit migration links existing credentials; the two
  token types must never be guessed from the same route.
- Account/backend failure never blocks recording, local segment matching, exports or
  raw archives. A restored Firebase session may remain `Local only` while `/me` is
  unavailable, then retry opportunistically. This preserves offline-first behavior.
- The Firebase Admin service-account JSON enters Coolify as locked, literal,
  single-line compact JSON consumed by a native Docker Compose runtime secret.
  Multiline is deliberately disabled because Coolify's generated `.env` may expose
  raw JSON lines to Docker Compose. The backend passes only the read-only mount path
  directly to the Admin SDK; the credential is neither a build argument nor a normal
  process environment variable and never belongs in Git.

## 2026-08-09 — Calm-IMU stops require sequential GPS motion evidence

- While Rust has established `STILL`, one coordinate displacement outside the stated
  accuracy radius is not enough to release the stationary anchor. Field behavior on
  the Galaxy S25 showed that a motionless phone can produce that exact combination,
  including a false non-zero Android ground speed.
- Release requires a second accepted fix that continues away from the same stationary
  anchor. This delays live motion by at most one GPS interval; the existing bounded
  post-pass restores the causally hidden departure anchors after motion is confirmed.
- Raw GPS/IMU/barometer samples remain immutable. The rule affects only derived live
  and canonical geometry and is versioned as `gps-bounded-0.6`, so old artifacts are
  recomputed without destroying source evidence.

## 2026-08-09 — Segment authoring starts from a derived cross-ride downhill index

- Discovering a segment is a library operation, not a property of whichever Activity
  screen happened to be open. Android derives one offline candidate map from every
  finalized local ride; selecting a candidate still opens the ordinary precision
  editor, and discovery itself never persists or publishes a segment.
- Rust remains the sole owner of descent boundaries and directional overlap. A manual
  pause, recording gap or likely-motorized evidence ends a candidate. A held stationary
  wait may bridge two downhill spans only while its canonical displacement stays within
  12 m; a short non-descending trail link keeps the existing 8 s / 40 m bound.
- Discovery hides seeds with GPS accuracy p90 above 25 m and selections already covered
  at least 80% by an existing segment. Repeated passes are grouped only when directional
  overlap reaches 80% both ways, so a short trail and its future combo/extended variant
  remain distinct. Every pass counts as support even when several laps share one raw
  recording.
- A proposed downhill must be at least 300 m long. Shorter efforts are too dominated by
  gate placement and GPS uncertainty to deserve a permanent trail-segment identity;
  they remain visible inside their source Activity but do not enter discovery.
- The first version uses the best seed in each group: lowest GPS p90, then greatest
  descent/length and newest recording. This is ranking, not geometry averaging. Trusted
  multi-pass centerlines remain a separate future step and immutable raw rides stay the
  source for recalculation.

## 2026-08-09 — Segment trail context is authored metadata, not fusion geometry

- Difficulty and external trail references live in Android's durable `StoredSegment`,
  not Rust's `SegmentDefinition`. Changing either does not move gates, change a corridor,
  invalidate results or make an external catalog authoritative timing evidence.
- Difficulty is optional and uses the signage-shaped green, blue, red, black and double
  black grades. The segment library and saved detail map derive their line colour from
  that grade; an unrated segment retains Nakvali's primary green. Text labels accompany
  every colour.
- External references store provider attribution with an `http` or `https` URL. The
  first UI authors one link and recognizes common providers, while persistence is a
  list so later sources can coexist. Old segment JSON defaults to no grade and no links.
- Main/chicken line families and automatic branch classification remain a hypothesis.
  They are not encoded as metadata flags in this first trail-context version.

## 2026-08-10 — Live segment timing is provisional and shares canonical's rules

- Live matching is a streaming half of `segment::match_segment`, not a second timing
  implementation. `live_segment.rs` imports the gates, the direction tolerance, the
  corridor, the backtrack allowance and the coverage bins from `segment.rs`; the shared
  coverage binning was factored out rather than restated. A run that counts on the trail
  therefore counts after Finish for the same reasons, and stops counting for the same
  reasons too.
- A live time is provisional by construction and is labelled that way. Canonical
  matching runs the bounded post-pass over the whole ride before deciding anything;
  live fusion is causal and cannot see the fixes that follow a gate crossing. Live
  results are never persisted as attempts: the segment screens re-match the finished
  ride and that result is the one that lands in the leaderboard.
- The tracker consumes fused positions, never raw fixes, so the live map, the live clock
  and the canonical result all describe the same track. A run may not bridge a manual
  pause or a recording gap, matching canonical rejection of the same attempts.
- Every local segment is armed at Start with the personal record read from its cached
  results. Arming never triggers a re-match — the rider would wait for it — and a cache
  from an older algorithm, match or geometry version arms with no record instead of a
  time the current rules never produced. A run completed during the ride immediately
  becomes the record to beat for the next lap in the same ride.
- Gate feedback is a direct vibration, not a notification. The recording notification
  stays the only one the recorder posts; its collapsed line carries the newest run and
  the totals move to the expanded line. Riders can turn segment vibration off.

## 2026-08-10 — Live work follows attention; transport is a power decision only

- Nothing that exists to be looked at runs while no screen is up. The live track is a
  ring appended on the sensor thread and copied into an immutable snapshot only while
  the app is visible; the state cadence drops from ~4/s to 1/s in the background, where
  the notification is the only reader. Recording itself is untouched by all of this.
- Live segment matching prefilters on a padded box per segment before the corridor test
  walks a centerline. The padding covers the corridor and both gates, so the fast path
  can only skip fixes that could not have crossed a gate; a run already in progress is
  never skipped, because the fix that leaves the corridor has to be measured.
- Rate of climb is vehicle evidence on its own, at any road speed. The previous rules
  only recognized a vehicle above 25 km/h (with a climb) or 36 km/h (with smooth IMU),
  which a switchback fire road never reaches, so the middle of every shuttle lap fell
  back to plain transit. No rider sustains 0.6 m/s of climb; a shuttle does 1.5–4 m/s.
- Motorized runs are bridged across non-descending interruptions up to 90 s, and only
  when vehicle evidence exists on both sides. A traffic light, a flat kilometre or a
  GPS dropout under trees belongs to the same ride in the same vehicle; a descent
  between two lifts ends the span, so a lap is never swallowed.
- The live transport hint is a power decision and never a recorded result. It lowers
  GPS to a 5 s balanced fix and the IMU to 25 Hz by re-registering both — gating writes
  alone would leave the accelerometer waking the CPU 200 times a second — and it is
  stated on the recording panel and in the notification rather than silently coarsening
  the track. Entering needs 45 s of vehicle-rate climb with smooth motion; a descent,
  trail roughness, a 45 s stop or a manual pause leaves immediately. The ride's real
  transport spans still come from the post-ride classifier reading the raw file.
- Reduced sampling during a suspected transit is an accepted, bounded loss of raw
  fidelity: transits are secondary data in this product, and every exit condition is
  fast enough that a real run is never recorded coarsely for more than a few seconds.
  `Save power in transport` turns the whole behavior off for field tests.

## 2026-08-10 — Congestion, the platform hint, and map hierarchy

- A vehicle span absorbs congestion up to 15 minutes when the motion stays vehicle-smooth
  throughout and the interruption is stop-*and-go* rather than one long stop. Crawling in
  traffic produces neither the speed nor the rate of climb that identifies a vehicle, so
  duration alone could not separate it from the rider getting off. Waiting at the bottom
  for the next shuttle stays STILL, and rough motion between two lifts is never absorbed.
  Without IMU evidence the long bridge is never claimed.
- Android's activity recognition is an input to the live power decision only, and Rust
  owns what it means: Android forwards the transition, `LiveFusion` decides. It can bring
  power saving on sooner — a city bus in flat traffic produces no evidence of its own —
  and it can end it, but our own evidence outranks it in both directions: a descent or
  trail-like roughness vetoes entry even while the platform still reports a vehicle.
  The permission is optional and declining it changes nothing about recording.
- Transitions are written to the raw file as `activity:*` event lines: evidence kept for
  later, never an input to today's classifier. Classification must stay reproducible from
  GPS/IMU/baro alone, and a reader that ignores those lines must reach the same result.
- The activity map has one subject: the descents. Transit is thin and dimmed, a likely
  vehicle is barely there, and stops are small hollow rings rather than filled disks that
  covered the trail itself. Confirmed stillness is only drawn when it is an event in the
  rider's day: shorter than 15 s is a track stand, and stillness with vehicle evidence on
  both sides is a traffic light seen from inside a bus.

## 2026-08-10 — Ride statistics describe riding, not the day

- Headline totals exclude spans the classifier calls `LikelyMotorized`. A shuttle lap
  adds tens of kilometres and hundreds of metres of climb the rider did not produce, and
  counting them makes every figure meaningless — most of all descent, which is the
  product's subject. `RideTotals` is computed in Rust from the finalized classified
  track, using exactly the accumulators the whole-recording analysis uses.
- A pair of points counts as transport when either end is motorized, so a boundary is
  never credited to the rider, and the ride and transport streams keep separate distance
  anchors. `STILL` and `UNKNOWN` stay with the ride: a stop in the middle of a lap is
  part of that lap.
- What was excluded is stated, not hidden: the activity screen shows the transport
  distance and time under the summary, so the day still adds up. `RideAnalysis` keeps its
  whole-recording meaning and remains what export and diagnostics see.
- Artifact schema v4. Older artifacts predate the split and are rebuilt; until then the
  screen falls back to whole-recording numbers rather than showing nothing.

## 2026-08-10 — One length floor for a trail, lowered only for field tests

- The authoring floor is 300 m, the same number discovery already uses. Two floors for
  one notion — "long enough to deserve a permanent trail identity" — was the accident;
  50 m was never defensible, because at that length two gates account for most of the
  distance and the time reports where the gates landed rather than how the rider rode.
- Rust keeps ownership of the limit. Callers may request a lower one, and Rust clamps it
  into `[40 m, 300 m]`: developer mode can never raise the floor by accident, and can
  never go below the length at which two gates still separate.
- The lower floor exists for one purpose: validating gate behaviour — entry and finish
  haptics, the live clock — on a stretch next to the house instead of requiring a
  mountain. It is not a product feature, and a segment authored that way has a time
  dominated by gate placement.
- `propose_segment` uses the same floor, so the editor never opens with a default
  selection it would then refuse to save.

## 2026-08-11 — Pressure impulses are rejected; the barometer is otherwise believed

- The IMU cannot tell tarmac from singletrack, so it cannot gate elevation smoothing.
  Measured on the rider's own recordings, the typical accelerometer error reads 1.4–7.9
  m/s² on flat asphalt and 2.1–7.6 m/s² on real forest singletrack — the same range. What
  the accelerometer reports is the phone shaking in its mount at speed, not the shape of
  the ground. The IMU-gated design was written, calibrated against real data, and dropped
  on that evidence.
- What the asphalt recording actually contained was not roughness but a single impulse:
  the barometer traced a flat road to within a metre for sixty seconds, then swung twelve
  metres down and back in the last two, as the phone came out of its mount. The rule that
  follows is narrow — a sample is compared against a ±2 s running median and replaced only
  when it disagrees by more than 3 m, the most a rider can genuinely gain or lose against
  where they were two seconds ago.
- Every other sample passes through exactly as measured. A filter that rewrote all of them
  would clip the crest of every real roller: measured on a synthetic 10 m rise and fall
  over 12 s, a plain running median cost 1.3 m of it. Rejecting outliers costs nothing
  anywhere the barometer is behaving.
- Airtime suspends the test. Being airborne is the one thing that genuinely moves a rider
  faster than the rule allows, and the free-fall detector already knows when it happens.
- The baro-vs-GPS offset is filtered separately and much harder, over ±30 s of time rather
  than a count of fixes, because it is a weather field: it drifts by about a metre over
  ten minutes and never by ten metres in five seconds. Filtering it on its own physical
  timescale keeps a GPS altitude excursion out of the profile and makes the filter
  independent of the fix rate, which the power-saving profiles change.
- A digital elevation model stays deferred, but the case for it is stronger than before.
  Its role is the slow absolute anchor, not short-scale shape — at 30 m spacing and ±5–10
  m vertical it knows nothing about a two-metre lip. Adopting it means on-device tiles, a
  region choice and licensing, and is a separate decision.
- Versioned as `gps-bounded-0.7`; existing artifacts are recomputed on device from the
  untouched raw recordings.

## 2026-08-12 — A shuttle leg is one leg, and time proves it

- Two descents *inside* a Kojori shuttle climb kept being credited to the rider: 35 m over
  36 s and 80 m over 108 s. Both are the shuttle road crossing a ridge and dropping into
  the next valley, and the van resumed climbing immediately after each.
- Geometry cannot settle this. Those dips give up as much height as a short run, at a
  speed a bike reaches, on the same road. Every geometric rule tried — gross descent, net
  descent, recovery of height afterwards — either missed the dips or swallowed real runs.
- The timeline settles it, and it was the rider's own argument: you cannot get out, ride,
  and be back in the vehicle in three minutes. Measured across the day, every genuine run
  put **600 s or more** between the motorized spans on either side of it — a 105–162 s
  descent plus 300–500 s standing at the bottom waiting for the pickup. The two road dips
  took 72 s and 122 s. The populations are five times apart with nothing in between.
- So an interruption between two vehicle spans shorter than `MIN_SHUTTLE_TURNAROUND_MS`
  (240 s) cannot contain a ride, whatever its shape. The existing stop test runs first, so
  a gap that is mostly confirmed STILL is still read as the rider getting out.
- 240 s rather than the midpoint or the rider's suggested 600 s: the two errors are not
  equal. Absorbing a real run into a lift erases descent, the number the product exists to
  report; leaving a road dip only fragments a transfer. Sit close to the dips, far from
  the runs.
- Two guard tests encoded the impossible timeline — a rider getting out, descending 90 m,
  and being back in the van 60 s later, with no wait at all. Their assertions were right
  and are unchanged; their fixtures now include the wait a real turnaround has.
- Effect on the 6.4 h shuttle day: the leg became one span of 2 362 s / +904 m instead of
  three fragments, ride descent 2 413 → 2 296 m, and the ride's max speed dropped from
  20.9 m/s to 16.9 m/s — 75 km/h was the van, not the rider. Nothing else in the recording
  moved.

## 2026-08-12 — Ride distance dropped its anchor when the shuttle started

- `ride_totals` keeps a separate 1 m anchor for the ride and for transport so a lap and
  the shuttle after it cannot share one. But leaving a stream never cleared its anchor, so
  the first pair after a lift measured from wherever the rider had boarded and added the
  straight line across the entire climb to the ride's distance.
- Worth 22 km of the 54 km reported for one lift-served day. Each stream now drops the
  other's anchor as it takes over, so distance restarts from the point where riding
  actually resumed.
- Versioned as `gps-bounded-0.10`; artifacts are recomputed on device from raw.
