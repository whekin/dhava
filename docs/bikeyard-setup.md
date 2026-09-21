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
7. Set `PUBLIC_BASE_URL=https://nakvali.whekin.dev` (origin only), redeploy, and
   verify trusted HTTPS. Keep `/healthz` and `/readyz` checks on the old API domain
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
uploads. This session does not implement PKCE/token exchange or upload.

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

Expected domain state: `verified`. The current foundation opens Nakvali and says
that BIKEYARD is coming in a future update; it does **not** claim to connect an
account. Real OAuth handling replaces this branch after registration. Browser
fallback is similarly explicit. Code/state must never be logged or echoed.

## References

- [Coolify domains and path routes](https://coolify.io/docs/core/networking/domains)
- [Android website associations](https://developer.android.com/training/app-links/configure-assetlinks)
- [BIKEYARD API research](research/bikeyard-integration.md)

The owner confirmed the Coolify dashboard is `https://coolify.whekin.dev`.
Its HTTPS login page is accessible; an authenticated session is required to
inspect or modify the existing resource. Coolify manages the site certificate
after an HTTPS service domain is saved and deployed.
