#!/bin/sh
set -eu

action=${1:-}
case "$action" in
    up|down) ;;
    *)
        echo "usage: $0 up|down" >&2
        exit 2
        ;;
esac

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
credential_file="$repo_root/deploy/secrets/firebase-service-account.json"
firebase_json=${FIREBASE_SERVICE_ACCOUNT_JSON:-}

if [ -z "$firebase_json" ] && [ -f "$credential_file" ]; then
    firebase_json=$(cat "$credential_file")
fi

firebase_json=${firebase_json:-{}}
firebase_project_id=${FIREBASE_PROJECT_ID:-}
if [ "$firebase_json" != "{}" ] && [ -z "$firebase_project_id" ]; then
    firebase_project_id=nakvali-app
fi

export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-nakvali-local}
export API_ACCESS_KEY=${API_ACCESS_KEY:-nakvali-local}
export PUBLIC_BASE_URL=${PUBLIC_BASE_URL:-http://127.0.0.1:8080}
export FIREBASE_PROJECT_ID=$firebase_project_id
export FIREBASE_SERVICE_ACCOUNT_JSON=$firebase_json

if [ "$action" = "up" ]; then
    exec docker compose \
        --project-directory "$repo_root" \
        -f "$repo_root/deploy/docker-compose.yml" \
        -f "$repo_root/deploy/docker-compose.local.yml" \
        up --build
fi

exec docker compose \
    --project-directory "$repo_root" \
    -f "$repo_root/deploy/docker-compose.yml" \
    -f "$repo_root/deploy/docker-compose.local.yml" \
    down
