# Dhava

Downhill-first ride tracking. Segments done right: smart start/finish gates,
combo segments, honest timing (±uncertainty), leaderboards that reset when
trails change.

## Monorepo layout

| Path       | What                                                        |
|------------|-------------------------------------------------------------|
| `android/` | Android app — Kotlin, Jetpack Compose, Material 3 Expressive |
| `backend/` | API server — Go, chi, PostgreSQL + PostGIS                   |
| `fusion/`  | Sensor-fusion core + worker — Rust (Kalman, gate detection, airtime). Shared between server and mobile (UniFFI) |
| `proto/`   | API contracts (OpenAPI)                                      |
| `deploy/`  | docker-compose for Coolify, ops notes                        |

## Architecture (v1)

Phone records raw GPS + IMU (100 Hz+) + barometer → uploads raw data →
Go API stores it (Postgres + object storage) → Rust worker computes canonical
results (gate crossings, segment times) → API serves leaderboards.
Live timing on the phone uses the same Rust core compiled for Android,
so live and canonical results never diverge. Raw data is kept forever:
when fusion improves, old rides get recomputed.

## Dev quickstart

- Android: open `android/` in Android Studio, run `app`.
- Backend: `cd backend && make run` (needs `DATABASE_URL`).
- Fusion: `cd fusion && cargo test`.
- Full stack: `cd deploy && docker compose up`.
