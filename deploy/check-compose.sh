#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-compose-check-password}
export API_ACCESS_KEY=${API_ACCESS_KEY:-compose-check-access-key}
export PUBLIC_BASE_URL=${PUBLIC_BASE_URL:-https://api.example.com}
# The real value is a locked compact-JSON Coolify variable. Compose requires the
# secret source even when Firebase is disabled for this configuration-only check.
export FIREBASE_SERVICE_ACCOUNT_JSON=${FIREBASE_SERVICE_ACCOUNT_JSON:-{}}

rendered=$(docker compose \
    --project-directory "$repo_root" \
    -f "$repo_root/deploy/docker-compose.yml" \
    config)
expected="context: $repo_root/backend"

if ! printf '%s\n' "$rendered" | grep -F "$expected" >/dev/null; then
    echo "Compose API build context is not repository-root/backend" >&2
    exit 1
fi

for expected in \
    "source: firebase_service_account" \
    "target: firebase-service-account.json" \
    "environment: FIREBASE_SERVICE_ACCOUNT_JSON"
do
    if ! printf '%s\n' "$rendered" | grep -F "$expected" >/dev/null; then
        echo "Compose Firebase runtime secret is missing: $expected" >&2
        exit 1
    fi
done

echo "Compose build context and Firebase runtime secret OK"
