# BIKEYARD integration options

Researched 2026-09-21. Scope: feasibility and architecture only; no application
registration, authorization, upload, or implementation was performed.

## Sources and confidence

The official [developer documentation](https://yard.bike/developers/docs) is a
client-rendered site. Its public guide JSON and
[OpenAPI contract](https://yard.bike/developer-api/docs/openapi.json) were fetched
directly after the web reader could not render it. Facts below describe the
published contract, not verified live behavior. Relevant guides are linked below.

## Capabilities

| Capability | Published API | Nakvali relevance |
| --- | --- | --- |
| Upload a ride | `POST /v1/uploads`, scope `rides:write` | Primary integration: send the processed recording |
| Connected rider identity | `GET /v1/me`, `profile:read`; optional `profile:email` | Name/avatar and BIKEYARD account association |
| Read rides/results | `/v1/me/rides`, `/v1/rides/{ride_id}`; `rides:read`, or `rides:read_all` for private rides | Optional upload verification and result links |
| Read social data | Followers/following (`social:read`), ride likes/comments (`rides:read`) | Available, but outside recorder-only proposal |
| Read trail data | Regions, trails, detail, leaderboard; `trails:read` | Available, but avoid recreating BIKEYARD's discovery/community product |

The contract does not expose ride edits/deletion, writing comments/likes, or a
GPS track download. [Rides](https://yard.bike/developers/docs/rides) explicitly
exclude ride GPS tracks. [Trails](https://yard.bike/developers/docs/trails)
explicitly exclude trail geometry, bounds, center, start, and finish, with no
plan to expose them. Therefore these endpoints cannot populate Nakvali's local
timing gates or offline trail geometry. Link to a trail's returned `url` instead.

## Upload contract

[Uploading rides](https://yard.bike/developers/docs/uploads) and the OpenAPI
contract document:

- Live API: `https://open.yard.bike`; test API: `https://sandbox.yard.bike`.
- Multipart file upload accepts GPX, TCX, or FIT up to 20 MB. Only `file` is
  required. Nakvali already exports processed TCX and GPX; no raw sensors need
  leave the phone.
- Optional fields: `name` (120 characters), `description` (2000), `bike_type`
  (`mtb`, `emtb`, `ebike`, `gravel_road`, `trial_bmx`), `visibility` (`public` or
  `private`), `trail_condition`, and `external_id` (128 printable characters,
  no spaces).
- Visibility defaults to **public**. Proposed Nakvali behavior: make the
  choice explicit and always send it. Do not infer a trail condition: it applies
  to every trail covered by the uploaded ride.
- Always send a stable recording-based `external_id`. It is scoped to app and
  rider. Replays return `200` and `Idempotent-Replayed: true`; initial creation
  returns `201` with upload ID and ride ID. `GET /v1/uploads/{upload_id}` can
  retrieve the receipt with `rides:write` alone.
- Duplicate detection may return `409 duplicate_ride`, including identical
  tracks uploaded by another rider. `duplicate_of` identifies the existing ride
  only if owned by the current rider. Although the guide recommends treating
  409 as sync success, Nakvali should not claim an upload exists in this rider's
  account when `duplicate_of` is absent; show a duplicate outcome separately.
- The saved ride subsequently receives BIKEYARD trail matching, leaderboard
  processing, weather, and feed processing. `trails_status=pending` is not final.
  Reading private processed results additionally requires `rides:read_all`.

**Unverified compatibility:** accepting TCX does not promise that BIKEYARD
preserves TCX cumulative distance, lap boundaries, pauses, disconnected tracks,
transport exclusions, or Nakvali's fused altitude. Its documented upload fields
cannot override computed totals. Test a TCX with shuttle gaps and riding-only
Rust distance in the sandbox and confirm parser semantics with BIKEYARD before
promising matching totals. BIKEYARD computes its own trail results; they should
not be described as Nakvali's canonical Rust timing.

## OAuth and “Continue with BIKEYARD”

[Authentication](https://yard.bike/developers/docs/authentication) provides OAuth
2.0 authorization code with PKCE S256. Public mobile/browser clients have no
secret and require PKCE. Confidential server clients receive a secret. Client
type is immutable after creation.

Authorization starts at `https://yard.bike/oauth/authorize`. The callback must
match the registered URI exactly; the registration screen requires HTTPS except
localhost HTTP for development. For Android, propose a verified HTTPS App Link
under a controlled domain. Do not assume a custom URL scheme is accepted.
Always generate and verify `state`; retain the PKCE verifier through the flow.

Codes expire after five minutes and are single-use. Access tokens last six
hours; refresh tokens last 90 days and rotate on every use. Reusing an old
refresh token revokes its authorization. Serialize refreshes and persist the
replacement refresh token atomically before using its access token. Disconnect
should revoke the refresh token through `/oauth/revoke`.

`GET /v1/me` returns a UUID `id`, username, names, avatar/profile URLs, and stats.
Email is nullable without `profile:email`; no `email_verified` field is documented.
Use the provider rider ID for account mapping, never email or username alone.

**Inference:** a “Continue with BIKEYARD” experience is feasible using OAuth and
the authenticated profile endpoint. However, the contract documents no OpenID
Connect `openid` scope, ID token, JWKS, or OIDC identity guarantee. It is not a
drop-in replacement for Nakvali's Firebase bearer verification.

Two distinct designs:

1. **Recorder with connected BIKEYARD identity:** Android public OAuth client;
   authenticated `/v1/me` supplies the connected rider; direct device upload.
   Local recording and archive remain usable as a guest/offline. No Nakvali
   backend is necessary for this export flow.
2. **BIKEYARD login to the existing Nakvali backend:** backend-controlled OAuth
   account mapping and Nakvali session issuance (or a deliberately implemented
   Firebase custom-auth bridge). Existing Firebase UID-backed users need an
   explicit linking/migration design. Never accept a client-provided rider ID
   as proof of identity or pass BIKEYARD tokens to existing Firebase verification.

Suggested initial scopes: `profile:read rides:write`. Email, social access, ride
history, and trail access are unnecessary for simple recording and upload.
Request read scopes only if adding processed-result verification, especially
private-ride verification.

## Proposed product and implementation boundary

This is a proposal, not an accepted architecture decision or feature removal:

- Nakvali: reliable offline recording, Rust fusion, local raw data/archive,
  activity review, export, and explicit/background upload after user opt-in.
- BIKEYARD: trail discovery, community, leaderboards, weather enrichment, and
  published ride pages.
- Existing Nakvali local segment/timing functionality is a separate product
  decision; this research does not authorize removing it.

Prefer Android public PKCE plus the existing WorkManager upload approach over
building a new Nakvali upload broker solely for BIKEYARD. Keep persisted upload
states and recording IDs, explicit visibility, retry/backoff, reconnect handling,
and a returned ride link. Treat the existing Strava flow as an implementation
reference, not a reason to preserve its separate broker architecture.

For the registration form choose **Mobile or browser application** for this
proposal, supply a real controlled HTTPS redirect URI and public website/privacy
links. Suggested description (proposed behavior, not a claim of existing code):

> Nakvali records cycling activities offline and processes GPS and sensor data
> on your device. Connect BIKEYARD to upload activities you choose to your
> BIKEYARD account. Raw sensor recordings remain on your device.

## Limits, sandbox, and obligations

[Rate limits](https://yard.bike/developers/docs/rate-limits): live application
600 requests/minute and 100,000/day; rider token 120/minute; uploads 60/hour per
rider per app; token requests 30/minute per client and IP. Honor `Retry-After`
on 429 and use jittered backoff.

[Sandbox](https://yard.bike/developers/docs/sandbox) uses test client IDs/tokens
and provides four synthetic riders per app plus sample GPX/TCX/FIT files. The
same OAuth page selects sandbox riders; token exchange uses the sandbox host.
This supports checking real OAuth, refresh rotation, retries, duplicate handling,
private/public uploads, and TCX semantics before involving a real account.

[API terms](https://yard.bike/developers/docs/terms), version 2026-09-16, require
accurate app registration and privacy disclosure, rider-requested uploads of
rides they recorded, respecting privacy, and deletion of received rider data
within 30 days after disconnect/deletion request, subject to stated exceptions.
They prohibit reconstructing/redistributing a substantial trail catalogue and
require provided trail/weather attribution. Do not interpret deletion of
BIKEYARD-derived profile/token data as authority to delete independently recorded
local raw recordings.

## Questions for BIKEYARD's owner before implementation

1. Does the TCX importer preserve `DistanceMeters`, lap/track breaks, paused
   periods, transport exclusions, and fused altitude, or recompute them? Can we
   validate a shuttle-containing reference recording together?
2. Is third-party account login an intended supported use of `/v1/me`? Are rider
   IDs stable across username/email changes, and are OIDC or revocation webhooks
   planned?
3. Confirm Android HTTPS App Links, accepted callback domains, and any required
   app review before live use.
4. What is the intended duplicate UX when an identical track belongs to someone
   else and no current-rider `duplicate_of` is returned?
5. Can updated processed exports replace an earlier upload in future? No update
   or delete endpoint is currently documented, and reusing `external_id` replays
   the original receipt.
