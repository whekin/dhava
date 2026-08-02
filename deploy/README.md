# Nakvali private-alpha deployment

This stack deploys the Go API and PostGIS only. Immutable raw recordings remain
on the phone, so production does not run MinIO or expose the legacy raw-upload
routes. The Rust verification worker is also intentionally absent until the
server has a real segment verification queue.

## Create the Coolify resource

1. Connect `whekin/nakvali` through the Coolify GitHub App and create a Docker
   Compose application.
2. Use branch `main`, base directory `/`, and Compose location
   `/deploy/docker-compose.yml`.
3. Assign the API service the domain `https://api.example.com:8080` (replace the
   hostname). Do not add a host `ports` mapping; Coolify's proxy is the only
   public entry point.
4. Enable automatic deployments from GitHub after the first manual deployment
   is healthy.

The API container waits for PostGIS, applies every pending migration, and only
then starts listening. Its readiness check includes a database ping.

The active PostgreSQL volume is `db-data-v2`. It intentionally replaced the initial
private-alpha `db-data` volume after the product/database rename: PostgreSQL applies
`POSTGRES_USER`, `POSTGRES_DB` and `POSTGRES_PASSWORD` only to an empty data directory.
Do not rename this volume for ordinary deployments or password rotation; change the
database role password in-place once the backend contains real user data.

For local development, use the second Compose file that binds the API to
localhost while keeping the production file unchanged:

```sh
just stack-up
curl -H 'X-Nakvali-Access-Key: nakvali-local' http://127.0.0.1:8080/api/v1/me
just stack-down
```

The recipes use private local defaults and
`deploy/docker-compose.local.yml`. Coolify uses only
`deploy/docker-compose.yml`, where the API remains available exclusively through
its proxy. Run `just deploy-check` to validate the shared build-context assumption.

## Environment

Set these as Coolify secrets/variables before the first deployment:

- `POSTGRES_PASSWORD` — required, long and URL-safe because it is embedded in a
  PostgreSQL connection URL. `openssl rand -hex 32` is suitable.
- `API_ACCESS_KEY` — required and independent from the database password.
  `openssl rand -hex 32` is suitable.
- `FIREBASE_PROJECT_ID` — `nakvali-app` to enable Firebase ID-token verification.
- `FIREBASE_SERVICE_ACCOUNT_JSON` — the complete service-account JSON compacted to
  one line (`jq -c .`). Create it in Coolify's **Normal view** as a locked
  **Literal**, **Runtime-only** variable with **Multiline disabled** and Build Variable
  disabled. Native Compose mounts it read-only at
  `/run/secrets/firebase-service-account.json`; it is not passed into the API process
  environment or embedded in the image. Never commit or paste its contents into logs
  or chat.
- `PUBLIC_BASE_URL` — required HTTPS origin without a trailing slash, for
  example `https://api.example.com`.
- `LOG_LEVEL` — optional; defaults to `info`.
- `STRAVA_CLIENT_ID` and `STRAVA_CLIENT_SECRET` — optional until the Strava app
  is registered.
- `STRAVA_APP_REDIRECT_URL` — optional; defaults to
  `nakvali://strava/connected`.

Do not create `GOOGLE_APPLICATION_CREDENTIALS` in Coolify. The backend passes the
fixed runtime-secret path directly to the Firebase Admin SDK. If an older deployment
left this variable in Coolify, remove it after updating the Compose source.

Keep `RAW_UPLOADS_ENABLED=false`; it is fixed in the production Compose file.
Never paste the access key, Strava secret, database password, or Coolify token
into chat, Git, screenshots, or deployment logs.

For optional local Firebase verification, put the downloaded private JSON at the
repository-local, gitignored path
`deploy/secrets/firebase-service-account.json` and keep mode `600`:

```sh
chmod 600 deploy/secrets/firebase-service-account.json
just stack-up
```

The local stack helper reads that file into the Compose secret and automatically uses
Firebase project `nakvali-app`. Without the file, it supplies an inert `{}` secret and
leaves Firebase verification disabled, so normal credential-free backend development
still works. An explicitly exported `FIREBASE_SERVICE_ACCOUNT_JSON` still overrides
the file when needed.

For the owner's Android build, put this in the untracked
`~/.gradle/gradle.properties`:

```properties
nakvaliApiBaseUrl=https://api.example.com
nakvaliApiAccessKey=the-same-private-alpha-access-key
```

The shared key only prevents casual access to an owner-only alpha deployment.
It is recoverable from an APK and is not a substitute for user authentication.

## First-deploy checks

Replace the hostname and use the access key only in your local terminal:

```sh
curl --fail https://api.example.com/healthz
curl --fail https://api.example.com/readyz
curl -i https://api.example.com/api/v1/me
curl -i -H "X-Nakvali-Access-Key: $NAKVALI_API_ACCESS_KEY" \
  https://api.example.com/api/v1/me
```

Expected results are `200`, `200`, `401`, then `401`: the final request passes the
private-alpha perimeter but intentionally has no Firebase bearer token. Complete the
end-to-end check from the Android Profile screen; a valid signed-in request returns
`200` and creates or refreshes the local user. Also verify that the database volume is
persistent and configure encrypted daily database backups before relying on Strava:
OAuth tokens are stored in Postgres. Test a restore, not only backup creation.

When Strava credentials are added, register the hostname from
`PUBLIC_BASE_URL` as its Authorization Callback Domain. The full callback is
`$PUBLIC_BASE_URL/api/v1/strava/oauth/callback`.

## Codex access to Coolify

Coolify exposes a Streamable HTTP MCP endpoint at
`https://coolify.example.com/mcp`. Enable the API/MCP in Coolify and create a
team-scoped read token for routine inspection. Keep the token in the shell
environment, outside this repository, then register it once:

```sh
export COOLIFY_MCP_TOKEN=replace-locally
codex mcp add coolify \
  --url https://coolify.example.com/mcp \
  --bearer-token-env-var COOLIFY_MCP_TOKEN
```

That lets Codex inspect resources, deployment state, and redacted logs without
SSH. Let the GitHub App own normal deployments. If manual deployment automation
is later useful, create a separate least-privilege Coolify API token with only
the deployment permission; do not broaden the read token or use a root token.
