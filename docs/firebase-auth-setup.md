# Firebase Authentication setup

Nakvali uses Firebase Authentication as the identity provider for the Android app and
the existing Go API. Postgres remains the product database; Firestore, Realtime
Database, and Firebase Storage are not required for authentication.

## Stable identifiers

- Public product name: **Nakvali**.
- Android application ID registered with Firebase: **`com.nakvali.app`**.
- Firebase project display name: **Nakvali**.
- Firebase project ID: `nakvali-app`.

`com.nakvali.app` is intentionally a new Android identity while the app is still in
private development. It installs separately from the retired prototype. Restore the
verified local backup into Nakvali before removing the old installation. The same
release certificate remains valid and is reused; application ID and signing identity
are independent.

## Console checklist

1. Create one Firebase project for production. Google Analytics is optional and is
   not needed for Authentication.
2. Add an Android app with package name `com.nakvali.app` and nickname
   `Nakvali Android`.
3. Add both current signing-certificate pairs from `./gradlew :app:signingReport`:

   | Build | SHA-1 | SHA-256 |
   |---|---|---|
   | Debug | `04:91:F7:33:E2:39:FD:15:21:03:1F:15:1D:0A:E4:AD:E2:4B:D0:E3` | `C6:13:65:83:C1:C5:5F:34:9F:28:DF:78:41:D8:8E:C3:71:77:AB:61:BA:EA:65:20:C3:15:D9:ED:8F:27:09:E2` |
   | Release | `D9:09:62:3E:46:05:D2:C2:09:B6:F2:8F:63:4E:AB:60:7D:82:56:EF` | `F8:DC:85:B0:5D:D7:72:46:44:53:80:09:B3:CC:BE:26:3C:96:55:1A:D3:A7:A5:8A:80:9B:14:6E:A4:BF:45:CA` |

4. Open **Authentication → Sign-in method**, enable **Google**, choose the public
   support email, and save.
5. Download the updated `google-services.json` after Google sign-in is enabled and
   place it at `android/app/google-services.json`. The file contains project IDs, not
   a server secret; the service-account private key must never be placed there.
6. Under **Project settings → Service accounts**, create credentials for the Go API.
   Store the JSON as a Coolify secret file and point
   `GOOGLE_APPLICATION_CREDENTIALS` at its mounted path. Never commit or paste this
   private key into Gradle properties, Compose files, logs, or chat.
7. When Google Play App Signing is enabled, add the Play **app-signing** SHA-1 and
   SHA-256 certificates as well as the local upload/release certificate, then download
   the refreshed `google-services.json` again.

## Implemented application flow

The Android foundation is configured: the Google services Gradle plugin, Firebase
Android BoM, `firebase-auth`, Credential Manager, the Play Services credential bridge,
the Google ID library and the refreshed `android/app/google-services.json` are present.
Both debug and release builds generate `default_web_client_id` from that config; no
client secret is embedded in source. Firebase Analytics is deliberately not included.

The Profile tab owns the explicit signed-out, signing-in, signed-in, syncing,
local-only, retryable-error and sign-out states. Credential Manager obtains the
Google ID token, Firebase exchanges it for a Firebase session, and Firebase itself
persists that session. Nakvali never writes the Firebase ID token to app storage.

After Firebase sign-in, Android obtains a Firebase ID token and sends it to the API as
`Authorization: Bearer <token>` over HTTPS. The Go API verifies that token with the
Firebase Admin SDK and uses only the verified Firebase `uid` as the external identity.
Email and display name are profile attributes, not authorization keys.

`GET /api/v1/me` verifies a fresh Firebase ID token with the official Go Admin SDK and
upserts a local user keyed by verified Firebase `uid`. The local UUID remains the
stable key for Nakvali product data. Only verified profile claims (email, display
name, avatar URL, email-verification state) are copied; raw tokens and arbitrary
claims are neither persisted nor logged.

If `/me` is unavailable, the Firebase session stays signed in and Profile reports
`Local only`; recording, local segments and raw archives continue to work. A 401 gets
one forced token refresh before becoming a retryable state. The anonymous installation
credential used by the current Strava prototype remains separate until its deliberate
account-migration step.

The private-alpha `X-Nakvali-Access-Key` remains an additional deployment perimeter,
but it is not user authentication and must not be required by a public release.

## Production handoff

Set `FIREBASE_PROJECT_ID=nakvali-app` in Coolify. Mount the service-account JSON as a
read-only secret file and set `GOOGLE_APPLICATION_CREDENTIALS` to that container path.
After redeploying, choose an account from Profile on a test build: the expected final
state is `Synced`, and Postgres should contain one `users.firebase_uid` row. Do not
paste the service-account JSON or a Firebase ID token into logs, Git, or chat.

## References

- [Add Firebase to Android](https://firebase.google.com/docs/android/setup)
- [Authenticate with Google on Android](https://firebase.google.com/docs/auth/android/google-signin)
- [Verify Firebase ID tokens on a custom backend](https://firebase.google.com/docs/auth/admin/verify-id-tokens)
- [Set up the Firebase Admin SDK](https://firebase.google.com/docs/admin/setup)
