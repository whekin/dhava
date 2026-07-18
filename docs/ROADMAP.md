# Roadmap — recorder first

Dhava is currently a high-quality, offline-first MTB recorder that will export
activities to Strava. Segments, leaderboards and social features are frozen until
the recorder is trustworthy enough to replace a dedicated bike computer.

## Phase 0 — Foundation ✅

Monorepo, Android multi-module app, Rust fusion-core shared through UniFFI, Go API
skeleton and deployment scaffolding.

## Phase 1 — Field-ready recording (in progress)

- Foreground GPS + full-rate IMU + barometer raw capture with crash recovery ✅
- Five-second-bounded sensor warm-up, live fused telemetry and map ✅
- Manual pause/resume with explicit raw events and guarded Finish ✅
- Screen-off/background survival, wake lock and battery exemption ✅
- Real settings: keep-screen-awake, sensor diagnostics, offline-only save ✅
- Automated lifecycle tests: prepare → record → pause → resume → finish
- Storage/free-space guard and a 1–2 hour physical-device field test

## Phase 2 — Complete local activity

- Canonical on-device fusion immediately after Finish ✅
- Active time, fused distance/speed/track and GPS/barometer elevation ✅
- Surface elevation quality, GPS quality and uncertainty indicators ✅
- Reliable activity detail, rename/edit/delete and raw diagnostics export ✅
- Local storage management and offline map/cache behavior ✅

## Phase 3 — Export

- GPX export through Android Share: raw GPS and processed 5 Hz tracks ✅
- Canonical finalized horizontal GPX with explicit pause sections ✅
- Add Rust-finalized elevation to the processed GPX ✅
- One-time `Connect with Strava`, then one-tap per-activity export with offline queue,
  retry and duplicate protection
- Minimal Go OAuth/upload broker for Strava credentials; the recorder itself remains
  fully usable without Dhava backend connectivity
- FIT export with pause semantics and sport metadata
- Upgrade Strava uploads from GPX to FIT once canonical pause and device metadata are
  stable

## Phase 4 — Recorder polish

- Configurable recording fields, optional auto-pause, accidental-touch lock
- Audio/haptic preferences and actionable recording notification
- Tested stationary sampling reduction without losing motion onset evidence

## Frozen future work

Segments and gates, uncertainty/anti-cheat evidence, leaderboards, live deltas,
social features and server verification remain part of the long-term vision but
are deliberately out of the current product path.
