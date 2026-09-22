# BIKEYARD registration and website deployment

Prepared 2026-09-21. Product: Nakvali. Current public GitHub repository verified
through GitHub: `https://github.com/whekin/dhava` (not `whekin/nakvali`). There are
no public releases yet. This guide does not itself create a BIKEYARD application.

## Website and API layout

Keep the existing Coolify Docker Compose resource and database volume. The stack
adds a static `web` service; the existing integrated proxy terminates TLS and
routes by path:

| Public path | Container |
| --- | --- |
| `/`, `/privacy`, `/terms` | `web:80` |
| `/oauth/bikeyard/callback` | `web:80` (Android App Link fallback) |
| `/.well-known/assetlinks.json` | `web:80` |
| `/api/v1/*` | `api:8080`, preserving the entire path |

No new public ports, standalone proxy, account system, or database is needed.
Only the new site container changes for site builds; Compose may still coordinate
the whole stack on deployment. The existing `db-data-v2` volume must stay intact.

## Coolify steps

1. Verify the DNS record for `nakvali.whekin.dev` points at the existing Coolify
   resource server. At preparation time DNS returned `152.53.136.162`, but HTTPS
   returned an untrusted self-signed certificate; the domain was not ready.
2. Review the site and legal text. The owner-approved `NAKVALI_CONTACT_EMAIL`
   defaults to `whekins@gmail.com`; override it in Coolify only if changing it. It is passed as a **build argument**, not a secret or
   runtime-only variable; changing it requires rebuilding the web image.
3. Deploy the updated Compose source from Git. Preserve all existing credentials,
   Firebase setup, database configuration and volumes.
4. Set **Domains for web** to `https://nakvali.whekin.dev`.
5. Add `https://nakvali.whekin.dev:8080/api/v1` to **Domains for api**, retaining
   the old API origin as a second domain during migration. The `:8080` selects
   the internal container port, not a public port.
6. Disable **Strip Prefixes** for the API route. Go expects `/api/v1/me`; do not
   rewrite it to `/me`. Inspect generated proxy labels after saving: the new
   router must match the host plus `/api/v1` path prefix, point to port 8080,
   have no StripPrefix middleware, and take precedence over the web root route.
   If this Coolify version does not expose the setting for Compose, remove the
   generated strip middleware from that router using custom labels; inspect the
   actual generated router names instead of pasting guessed labels.
7. Redeploy and verify trusted HTTPS. `PUBLIC_BASE_URL` is no longer needed by
   the Go backend after removing the Strava broker. Keep `/healthz` and `/readyz` checks on the old API domain
   or use internal container healthchecks; the web's `/healthz` checks only web.
8. After the new API route works, build Android with
   `-PnakvaliApiBaseUrl=https://nakvali.whekin.dev`. Do not put `/api/v1` into this
   property; Android already appends it. Preserve the current alpha perimeter
   key locally; never paste it into the site or public release notes.

Publishing a broadly downloadable APK with the current private-alpha key needs
its own release decision: it is embedded in the APK. Registration of BIKEYARD
and launching the site do not require publishing that APK.

## Verify before creating the BIKEYARD app

```sh
curl --fail --head https://nakvali.whekin.dev/
curl --fail --head https://nakvali.whekin.dev/privacy
curl --fail --head https://nakvali.whekin.dev/terms
curl --fail -i https://nakvali.whekin.dev/.well-known/assetlinks.json
curl --fail -I https://nakvali.whekin.dev/oauth/bikeyard/callback
curl -i https://nakvali.whekin.dev/api/v1/me
```

Expect successful pages, JSON with `Content-Type: application/json` and no
redirect, a callback with `Cache-Control: no-store` and no-referrer, and `401`
from the actual API for the unauthenticated `/me` request. HTML or a web 404 for
`/api/v1/me` means routing is wrong. Confirm a signed-in Android Profile request
still succeeds. Do not test by sharing a real OAuth callback URL in logs/chat.

Check the Coolify proxy's access-log policy before enabling OAuth: nginx inside
`web` suppresses its own access logs, but cannot configure the outer proxy.

## Exact BIKEYARD form values

| Field | Value |
| --- | --- |
| Name | `Nakvali` |
| Website | `https://nakvali.whekin.dev` |
| Privacy policy | `https://nakvali.whekin.dev/privacy` |
| Terms of service | `https://nakvali.whekin.dev/terms` |
| Application type | **Mobile or browser application** |
| Redirect URIs | `https://nakvali.whekin.dev/oauth/bikeyard/callback` |

Description (describes the integration being registered):

> Nakvali records cycling activities offline and processes GPS and sensor data
> on your device. Connect your BIKEYARD account to upload selected rides or enable
> automatic uploads after saving. Raw sensor recordings remain on your device.

Review and accept BIKEYARD's terms yourself, then create the application. Share
only the public Client ID for implementation; a mobile client has no secret.
Initial scopes will be `profile:read rides:write`. Use the sandbox before live
uploads. The registered clients and implemented Android flow are described below.

## Android verification

The domain association contains the existing release certificate, not the debug
key. Test a signed release build on an emulator or a selected test device after
the site is live (do not uninstall an existing app or clear its data):

```sh
adb -s SERIAL shell pm verify-app-links --re-verify com.nakvali.app
adb -s SERIAL shell pm get-app-links com.nakvali.app
adb -s SERIAL shell am start -W -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d 'https://nakvali.whekin.dev/oauth/bikeyard/callback'
```

Expected domain state: `verified`. A bare callback without a pending authorization
and matching state simply opens Nakvali; it must never claim to connect an account.
A real browser return is validated and exchanged using the saved PKCE verifier.
The published fallback page remains informational. Code/state must never be logged
or echoed.

## References

- [Coolify domains and path routes](https://coolify.io/docs/core/networking/domains)
- [Android website associations](https://developer.android.com/training/app-links/configure-assetlinks)
- [BIKEYARD API research](research/bikeyard-integration.md)

The owner confirmed the Coolify dashboard is `https://coolify.whekin.dev`.
Its HTTPS login page is accessible; an authenticated session is required to
inspect or modify the existing resource. Coolify manages the site certificate
after an HTTPS service domain is saved and deployed.

## Registered clients and Android implementation (2026-09-21)

Production client: `yb_live_3dnewziryr55f5hvrgd2`. This identifier is public,
not a credential. Android requests only `profile:read rides:write`; a sample with
`rides:read` cannot authorize uploads. The callback stays exactly
`https://nakvali.whekin.dev/oauth/bikeyard/callback`.

Profile → BIKEYARD connects directly to the live account. Sandbox selection was
removed at the owner's request after a failure on BIKEYARD's sandbox consent page;
the owner confirmed live authorization works. Existing live credentials, upload
receipts and auto-upload preferences survive the update. Legacy sandbox state is
recognized only for migration: its tokens, pending authorization, consent and jobs
are discarded locally, never redirected into a live account. New connections
default to private visibility with automatic uploads off.

Connection uses the system browser, a fresh random state/verifier, S256, strict
callback matching and a consumed-once pending flow. A release-signed APK verifies
the published App Link. Debug builds are deliberately not trusted by the public
domain; use a release build for the real browser return. No debug certificate is
published.

The direct Android client lives under `core/recording/bikeyard`. AES-GCM with an
Android Keystore key encrypts state stored under `noBackupFilesDir`; it contains
tokens, profile and account-bound upload receipts. Refresh rotation is serialized,
marked in-flight before the request, and atomically persisted on success. An
ambiguous refresh failure or process death requires reconnection instead of
reusing a possibly spent token. Disconnect cancels queued work and clears local
connection data; failed remote revocation is shown with instructions to revoke
Nakvali from BIKEYARD connected apps. Already published rides are unaffected.

Manual upload appears in Activity → Export. It sends the whole riding-only TCX,
with a confirmation naming the account, environment and visibility. WorkManager
waits for a connection and retries boundedly. The first prepared file and metadata
remain fixed across retries, with a stable `nakvali-<recording-id>` external ID.
An edit/reprocessing does not silently create or replace another remote ride.
Duplicate receipts are considered uploaded only when BIKEYARD confirms this
rider's `duplicate_of`; a foreign duplicate is a separate review state. Upload
receipts marked processing are polled. `Retry-After` is respected.

Automatic uploads default off; visibility defaults private. Opting in explicitly
disables the global Offline mode and records consent on each subsequently saved
ride. Startup recovers a save-to-enqueue interruption; existing saves are never
scanned into the queue. Importing a backup removes its automatic-upload markers.
Turning automatic uploads off cancels queued automatic work; manual uploads remain
explicitly available. Environment/account changes cannot move an existing job to
another rider. Disabling Offline mode alone does not enable automatic uploads.

The upload response does not contain a browser URL, and obtaining ride details
would need additional read scopes. For now the success action opens BIKEYARD,
not an invented per-ride URL. No `rides:read_all`, email, social or trail permissions
are requested. BIKEYARD's TCX accounting and trail processing are independent of
Nakvali's canonical Rust figures and still need comparison using a representative
shuttle-containing recording before promising equal totals.

The published website still describes the current public release as forthcoming;
update its feature copy and privacy notice when shipping the integration. No live
upload or public APK publication is part of local implementation verification.

## TCX size

The exporter now emits compact XML for both file export and BIKEYARD upload.
Only indentation and inter-element line breaks are removed; sample count, 5 Hz
timestamps, coordinate/elevation values, odometer and lap boundaries are unchanged.
A deterministic 54,000-point, three-lap fixture decreased from 21,158,873 to
14,840,566 bytes (29.9%). The test parses the output and verifies all points/laps.
Names retain their original internal whitespace and XML escaping.

The 20,000,000-byte upload guard remains. Files still above the limit are reported
with their actual size; no sampling reduction or automatic splitting is performed.
Already prepared live upload snapshots are never rewritten, preserving retry
idempotency. Failed preparation of an oversized file can be retried with the new
serializer. FIT or provider-supported compressed TCX are future options; gzip
upload support has not been established and must not be assumed.


## File export formats and retired Strava integration

FIT/FIT.GZ, TCX/TCX.GZ and GPX/GPX.GZ are available through the activity's file
export controls, with explicit 1/5 Hz sampling. See [activity export](activity-export.md)
for precision and compatibility. BIKEYARD's automatic/manual API delivery remains
TCX at 5 Hz until its new compression support and FIT timestamp handling are
verified; file options do not silently change that queue.

Strava's UI, OAuth callback, Android uploader/worker, Go broker/store access,
configuration variables and OpenAPI endpoints have been removed. Startup cancels
legacy WorkManager jobs by their class tag and clears the old installation
credential preference. Old recording indexes still decode, ignoring retired
Strava fields. Applied SQL migration history is retained; no destructive table or
remote-account deletion accompanies code removal. Deploy the backend changes to
retire the server routes; source removal alone does not modify an already-running
server. No new activity or account identity depends on Strava.

## 2026-09-22: compressed asynchronous delivery

The current provider contract now supports `.tcx.gz`/`.fit.gz`/`.gpx.gz` with a
20 MB transmitted-file limit and 128 MB uncompressed limit. Android now sends
`nakvali.tcx.gz` as `application/gzip` in multipart with `Prefer: respond-async`.
The existing immutable plain TCX snapshot is retained, including for jobs queued
before this update; each POST compresses those exact bytes without rebuilding the
track or changing its external ID. Temporary gzip output is removed after sending.
A 202 processing receipt is persisted and polled through GET `/v1/uploads/{id}`;
completion/own duplicate/foreign duplicate/failure remain distinct. A process
restart resumes polling rather than uploading a second ride. Polling does not
need a local snapshot once a receipt ID is known. WorkManager retains bounded
retry/backoff and the existing Retry-After handling for rate-limit responses.

FIT upload is still a separate compatibility decision. The immediate delivery
change is TCX.GZ, preserving millisecond timestamps and existing receiver behavior.
Sensor metrics are proposed in [bikeyard-sensor-metrics.md](bikeyard-sensor-metrics.md)
and are not sent without a receiver contract and validated event semantics.
