# Roadmap — recorder first

Dhava is currently a high-quality, offline-first MTB recorder that will export
activities to Strava. Segments, leaderboards and social features are frozen until
the recorder is trustworthy enough to replace a dedicated bike computer.

## Phase 0 — Foundation ✅

Monorepo, Android multi-module app, Rust fusion-core shared through UniFFI, Go API
skeleton and deployment scaffolding.

## Phase 1 — Field-ready recording (in progress)

- Foreground GPS + high-rate IMU + barometer raw capture with crash recovery ✅
- Five-second-bounded sensor warm-up, live fused telemetry and map ✅
- Manual pause/resume with explicit raw events and guarded Finish ✅
- Screen-off/background survival, wake lock and battery exemption ✅
- Real settings: keep-screen-awake, sensor diagnostics, offline-only save ✅
- Durable memory/thermal/writer heartbeat and process-exit diagnostics ✅
- Automated lifecycle tests: prepare → record → pause → resume → finish
- Storage/free-space guard and a 1–2 hour physical-device field test ✅

## Phase 2 — Complete local activity

- Canonical on-device fusion immediately after Finish ✅
- Active time, fused distance/speed/track and GPS/barometer elevation ✅
- Surface elevation quality, GPS quality and uncertainty indicators ✅
- Reliable activity detail, rename/edit/delete and raw diagnostics export ✅
- Local storage management and offline map/cache behavior ✅
- Versioned local backup/restore for raw rides and authored data ✅

## Phase 3 — Export

- GPX export through Android Share: raw GPS and processed 5 Hz tracks ✅
- Canonical finalized horizontal GPX with explicit pause sections ✅
- Add Rust-finalized elevation to the processed GPX ✅
- One-time `Connect with Strava`, then one-tap per-activity export with offline queue,
  retry and duplicate protection — implemented locally; live OAuth/upload verification
  awaits a registered Strava app and public backend callback
- Minimal Go OAuth/upload broker for Strava credentials; implemented and tested
  locally, while the recorder itself remains fully usable without backend connectivity
- Coolify private-alpha deployment contract: proxy-only API, PostGIS persistence,
  startup migrations, readiness healthcheck, shared alpha perimeter and raw uploads
  disabled — prepared and locally verified; first live deployment pending
- FIT export with pause semantics and sport metadata
- Upgrade Strava uploads from GPX to FIT once canonical pause and device metadata are
  stable

## Phase 4 — Recorder polish

- Configurable recording fields, optional auto-pause, accidental-touch lock
- Actionable recording notification ✅
- Audio/haptic preferences
- Rust-only post-ride ActivityState map visualization — implemented locally;
  labelled field calibration pending
- Adaptive stationary IMU disk persistence with two-second full-rate pre-roll —
  implemented locally; long physical-device validation pending

## Phase 5 — Local segments (in progress)

- Rust gate model: directed start/finish gates, corridor, coverage and progress
  checks, per-gate timing uncertainty, rejection reasons ✅
- Longest-descent proposal reusing the ActivityState pass ✅
- Index-based v1 start/finish editor launched from Activity Detail ✅
- Selection-focused initial scale, reversible full-ride scale and haptic
  hold-to-precision at 10× slower movement, preserving map zoom and pan ✅
- Continuous gate placement between canonical points, authored in Rust as
  geometry v2; no fake resolution from merely resampling the same polyline ✅
- Map-aware continuous editing: explicit ride/segment camera fits, zoom-scaled
  drag sensitivity and edge-only endpoint following ✅
- Map-led segment editor with an always-visible selection slider and expandable
  details sheet ✅
- Rust-authored elevation profile with accumulated climb and descent ✅
- Segments screen: segment map, best/latest with `± margin`, run count,
  uncertain runs and not-counted runs with their reason ✅
- Map-led segment library: one muted map of every local segment, tap to select,
  opening a segment a separate action, list in a persistent sheet, camera and
  selection retained, explicit `Fit area` and `My location` ✅
- Countable-only personal records: an uncertain run is never presented as a PR,
  and a faster uncountable run is shown with why it does not count ✅
- Draft-only geometry: no GPS correction, no centerline averaging yet ✅
- Trusted multi-pass centerline: averaged geometry from three or more quality
  rides, uncertainty corridor, GPS correction inside reported accuracy, and
  geometry updates that never let a ride influence the version that scored it
- Field validation of gate/corridor thresholds against repeated real runs of the
  same trail

## Frozen future work

Shared/server-side segments, leaderboards, KOM verification, uncertainty and
anti-cheat evidence upload, live deltas and social features remain part of the
long-term vision but are deliberately out of the current product path.
