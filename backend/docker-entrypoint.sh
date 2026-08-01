#!/bin/sh
set -eu

: "${DATABASE_URL:?DATABASE_URL is required}"

migrate -path /migrations -database "$DATABASE_URL" up
exec /api
