# Dhava Backend

Go HTTP API for Dhava (downhill MTB ride recording: segments, leaderboards, GPS/IMU ingest).

## Run

    make run          # or: make build && ./bin/api

## Environment variables

- `PORT` — HTTP port (default `8080`)
- `DATABASE_URL` — PostgreSQL connection string (optional; `/readyz` reports 503 without it)
- `LOG_LEVEL` — `debug` | `info` | `warn` | `error` (default `info`)

## Migrations

SQL files in `migrations/` use the [golang-migrate](https://github.com/golang-migrate/migrate) format:
`migrate -path migrations -database "$DATABASE_URL" up`. Requires PostgreSQL with PostGIS.
