# Nakvali — Agent Guide

Nakvali is the public brand; the repository and internal identifiers remain `dhava`.
Downhill-first ride tracking app. Android (Kotlin/Compose) + Go API + Rust fusion core.
Read `docs/VISION.md` for the product idea, `docs/ROADMAP.md` for phases,
`docs/WORKLOG.md` for what has been done and why.

## Session protocol

- Before starting: read `docs/WORKLOG.md` (last entries) to pick up context.
- After significant work: append an entry to `docs/WORKLOG.md` (date, what, decisions, open questions).
- Architecture decisions worth remembering go to `docs/DECISIONS.md`.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `whekin/dhava`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five standard triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

Nakvali uses a single-context domain documentation layout. See `docs/agents/domain.md`.

## Layout

| Path       | Stack | Build / verify |
|------------|-------|----------------|
| `android/` | Kotlin 2.2, Compose, material3 1.5.0-alpha (Expressive), AGP 8.12, multi-module | `cd android && ./gradlew assembleDebug` (JDK: Android Studio JBR) |
| `backend/` | Go 1.26, chi, pgx, PostGIS | `cd backend && make vet test build` |
| `fusion/`  | Rust (edition 2024), workspace: `fusion-core` (lib), `fusion-worker` (bin) | `cd fusion && cargo test && cargo clippy` |
| `proto/`   | OpenAPI spec + raw recording format | contract-first: update spec with API changes |
| `deploy/`  | docker-compose (api + postgis) for Coolify | `cd deploy && docker compose --project-directory .. -f docker-compose.yml up` |

## Architecture principles (do not violate)

1. **Raw sensor data is kept forever — on the device.** The phone records raw GPS +
   IMU + baro and keeps it; recomputation on algorithm upgrades happens on-device.
   The server stores only processed artifacts: corrected track (1–5 Hz fused,
   GPX on export), segment results (+uncertainty, +algorithm version), compact
   IMU evidence pack for anti-cheat. Raw windows (segment run ±10 s) are uploaded
   only on server request (KOM verification/disputes).
2. **Fusion logic lives in Rust only** (`fusion-core`), running primarily ON-DEVICE
   via UniFFI; the server-side worker uses the same crate for selective verification.
   Never reimplement timing/gate logic in Kotlin or Go — live and canonical results
   must never diverge.
3. **Offline-first mobile.** Recording, live timing, and segment cache must work with zero connectivity; sync is opportunistic.
4. **Segment-first, not route-first.** The product tracks trail runs (segments), transits are secondary/gray.
5. **Contract-first API**: `proto/openapi.yaml` and `proto/raw-recording-format.md` are the source of truth between components.

## Conventions

- Code, comments, commit messages: English. Chat with the user: Russian.
- Android modules: `:core:*` shared, `:feature:*` one per screen/domain; features depend on core, never on each other.
- Go: stdlib + chi + pgx, slog for logging, no frameworks. Migrations via golang-migrate files in `backend/migrations/`.
- Rust: minimal deps, no geo mega-crates; the math is small and ours.
- Android package root: `com.dhava`.
