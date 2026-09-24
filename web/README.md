# Nakvali website

Static Astro site for `https://nakvali.whekin.dev`, served by nginx behind the
existing Coolify proxy. No browser JavaScript, remote fonts, tracking, runtime
Node server, or extra reverse-proxy layer. The terrain is original synthetic SVG
artwork, not a public copy of a rider's coordinates or a fake app screenshot.

## Local preview

```sh
cd web
npm ci
npm run dev
npm run check
```

Node >=22.12 is required; Docker builds use Node 24. Open the URL printed by Astro.
The owner-approved public support/privacy address defaults to `whekins@gmail.com`.
Override `NAKVALI_CONTACT_EMAIL` when changing it; the Docker build refuses an
explicitly empty contact. The value is public
build-time content, not a runtime secret. Never put credentials in it.

The CTA points to the signed, keyless `v0.1.0-test2` prerelease APK at GitHub
Releases. Bump the version and verify the published asset before updating this
link for a later release. See `docs/release-build.md`.

Archivo's OFL license is included at `/licenses/archivo.txt` alongside the
self-hosted font files. The source package and version are
pinned in package-lock.json.

## Deploy

Use the `web` service in `deploy/docker-compose.yml`; see
`docs/bikeyard-setup.md` for exact Coolify values and the domain migration.
`web` serves `/`, `/privacy`, `/terms`, the callback landing page and
`/.well-known/assetlinks.json`. Go continues to own `/api/v1/*`.

The callback is only a registration foundation: it never exchanges a code,
claims a successful connection or runs JavaScript. nginx suppresses web access
logs and sets no-store/no-referrer on the exact callback route. Confirm the
outer Coolify proxy also does not log OAuth query strings before enabling OAuth.
Unknown API paths reaching the web container return 404, not a successful page.

The Android association trusts only the existing release certificate verified
with `:app:signingReport` on 2026-09-21. Do not add a debug certificate to the
public website. A Play-distributed app would need the Play app-signing certificate
added separately. Android's manifest limits handling to the exact callback path.

## Verification

`npm run check` checks Astro/TypeScript, builds all pages and verifies static
output, canonical asset association and absence of client scripts. nginx route
behavior is checked with `npm run check:http -- http://127.0.0.1:4322` against
the running Docker image (including JSON content
type, 404s, callback no-store, and direct API routing through Coolify).

The Privacy and Terms pages describe the current early-stage implementation,
including optional BIKEYARD ride uploads and separately enabled sensor metrics.
Revisit retention practices and support contact before expanding distribution.
