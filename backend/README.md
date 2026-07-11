# Dhava Backend

Go HTTP API for Dhava (downhill MTB ride recording: segments, leaderboards, GPS/IMU ingest).

## Run

    make run          # or: make build && ./bin/api

## Environment variables

- `PORT` — HTTP port (default `8080`)
- `DATABASE_URL` — PostgreSQL connection string (optional; `/readyz` reports 503 without it)
- `LOG_LEVEL` — `debug` | `info` | `warn` | `error` (default `info`)
- `S3_ENDPOINT` — S3/MinIO endpoint, scheme optional (e.g. `http://localhost:9000`; no scheme implies HTTPS). Unset → filesystem blob storage
- `S3_BUCKET` — bucket for raw recordings (default `dhava`; created at startup if missing)
- `S3_ACCESS_KEY` / `S3_SECRET_KEY` — S3 credentials
- `BLOB_DIR` — base directory for the filesystem blob store (default `./data/blobs`; used when `S3_ENDPOINT` is unset)

## Migrations

SQL files in `migrations/` use the [golang-migrate](https://github.com/golang-migrate/migrate) format:
`migrate -path migrations -database "$DATABASE_URL" up`. Requires PostgreSQL with PostGIS.
