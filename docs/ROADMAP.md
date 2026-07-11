# Roadmap

## Phase 0 — Foundation ✅ (2026-07-11)
Monorepo, Android multi-module Compose skeleton (M3 Expressive), Go API skeleton
(chi/pgx/PostGIS migrations), Rust fusion workspace (gate-crossing detection with
tests), docker-compose for Coolify.

## Phase 1 — Recording (in progress)
The riskiest part — start collecting real ride data ASAP.
- Android foreground service: GPS (FusedLocation) + IMU (accel/gyro/mag, 100 Hz+) +
  barometer → local per-activity files (gzip JSONL, see proto/raw-recording-format.md)
- Record screen with live data (speed, duration, GPS accuracy, sample counts)
- Backend: create activity, upload raw recording (→ object storage), finish activity
- Upload from phone (opportunistic, manual retry OK for now)

## Phase 2 — Segments & timing
- Data model: segments, gates, combo segments, segmented trails
- fusion-core: gate crossings on real data (GPS-only first, Kalman later)
- Segment creation from recordings (incl. slow registration ride), server recompute
- fusion-worker: pipeline raw → results

## Phase 3 — Leaderboards & Strava
- Leaderboards: bike class filter (default), gender, resets on trail change + archive
- Combo time stat
- Strava OAuth, import own activities, export

## Phase 4 — Live & social
- Offline segment/leaderboard cache, live timing, on-trail KOM notification
- Kudos, comments, feed, memories

## v1.5
Airtime, ± uncertainty on results, trail conditions + per-condition boards,
auth (device tokens → accounts), power-save geofencing.

## v2
Race mode, e-bike boards, snowboarding (lift exclusion), Wear OS, web app (view/social).

## v3
Betting (virtual points), trailbuilder crypto tips.
