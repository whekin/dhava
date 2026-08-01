#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-compose-check-password}
export API_ACCESS_KEY=${API_ACCESS_KEY:-compose-check-access-key}
export PUBLIC_BASE_URL=${PUBLIC_BASE_URL:-https://api.example.com}

rendered=$(docker compose \
    --project-directory "$repo_root" \
    -f "$repo_root/deploy/docker-compose.yml" \
    config)
expected="context: $repo_root/backend"

if ! printf '%s\n' "$rendered" | grep -F "$expected" >/dev/null; then
    echo "Compose API build context is not repository-root/backend" >&2
    exit 1
fi

echo "Compose build context OK: $repo_root/backend"
