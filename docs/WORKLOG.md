# Worklog

Append-only. Newest entry last. Format: date, what was done, decisions, open questions.

## 2026-07-11 — Phase 0: monorepo skeleton

**Done:**
- Monorepo initialized (git, README, .gitignore, this docs/ set, CLAUDE.md → AGENTS.md).
- `android/`: multi-module Compose app (`:app`, `:core:ui`, `:feature:{record,segments,feed,profile}`),
  M3 Expressive theme (dark-first, seed #D84315, displayLarge 96sp), bottom nav,
  manifest pre-lists (commented) location/sensor/foreground permissions.
  `assembleDebug` green, APK 11.5 MB.
- `backend/`: Go 1.26 + chi + pgx. `/healthz`, `/readyz` (DB ping), `/api/v1/me` stub.
  Server boots without DB (warning). Migrations 0001: postgis, users, activities,
  raw_recordings. Distroless Dockerfile, Makefile, tests green.
- `fusion/`: workspace `fusion-core` + `fusion-worker`. Real `detect_gate_crossing`
  (equirectangular projection, segment intersection, bearing tolerance, time
  interpolation), 3 tests, clippy clean. Worker is a logging stub.
- `deploy/docker-compose.yml`: api + fusion-worker + postgis/postgis:17-3.5 + minio.
- `proto/openapi.yaml` stub.

**Open questions:**
- Coolify deploy not yet exercised (compose written, not applied).
- Strava API app registration not started (needed in Phase 3).

## 2026-07-11 — Phase 1: recording (done)

**Android** (new module `:core:recording` + rewritten `:feature:record`):
- RecordingService: foreground (location type), FusedLocation 1 Hz high-accuracy,
  accel/gyro/mag at SENSOR_DELAY_FASTEST, baro at fastest; all on a HandlerThread.
- Timestamps: single epoch anchor (currentTimeMillis − elapsedRealtime) computed at
  start, applied to both SensorEvent and Location elapsed-realtime stamps.
- IMU pairing: accel is the master clock; each accel event emits one `imu` line with
  the latest stashed gyro/mag. No gyroscope → `[0,0,0]` (spec requires the field).
- Writer: unbounded Channel → single-thread dispatcher → buffered GZIPOutputStream,
  ~2 s flush; sensor callbacks never block. Files: `filesDir/recordings/<uuid>.jsonl.gz`.
- RecordingRepository singleton (StateFlow state/recordings/uploads, ~4 Hz UI updates),
  flat `recordings.json` index (Room later), ActivityUploader via OkHttp
  (create → raw → finish; server id canonical for upload, local UUID stays file key).
- Record UI: permission flow, big Start, glanceable dark recording layout
  (displayLarge elapsed, km/h, accuracy, sample counters), recordings list with
  upload states. BuildConfig API_BASE_URL default http://10.0.2.2:8080.
- 6 unit tests pin exact JSONL of every line type to the spec. assembleDebug green.

**Backend**: the three ingest endpoints per proto/raw-recording-format.md
(create 201 / raw 204 with 400/404/413/415 guards, streamed to blob store / finish 204).
`internal/blob`: Store interface, S3(minio-go) impl + FS fallback (BLOB_DIR).
Migration 0002: user_id nullable, ended_at added. 23 unit tests + full e2e smoke
against dockerized PostGIS (arm64 note: postgis/postgis has no arm64 manifest —
use imresamu/postgis locally; noted in deploy compose). openapi.yaml updated.

**Open:**
- No retry queue for uploads (WorkManager later); no auth (v1.5).
- Not yet run on a real device/emulator — next session: install, record a walk,
  upload, eyeball the JSONL.

## 2026-07-11 — Phase 1.1: save flow, background upload, ARM images

Owner requirements: fully offline recording + save; upload in background when the
user saves; title/description/bike entered at save time. Also: the Coolify VPS is
ARM — deploy compose now uses multi-arch `imresamu/postgis` everywhere.

**Android:**
- Save sheet after Stop: title (prefilled by time of day), description, bike picker
  as horizontal selectable cards + inline "Add bike" dialog (Full-sus/Hardtail/
  E-bike/Other); bikes persist in `bikes.json`, last-used preselected. Discard with
  confirm deletes file + entry.
- Upload rewritten to WorkManager: unique work per recording (KEEP), NetworkType.
  CONNECTED, exponential backoff, 5 attempts then terminal `failed` with manual
  Retry. Server id persisted after create → retries skip create (idempotent).
  Repository re-enqueues `pending_upload` entries on init.
- Recording status lifecycle persisted in the index: recorded → pending_upload →
  uploaded / failed; unsaved recordings reopen the save sheet ("Finish saving"),
  surviving process death. 13 unit tests green, assembleDebug green.

**Backend:**
- Migration 0003: activities.title/description/bike/bike_type (nullable, CHECK on
  bike_type enum). finish accepts optional metadata (caps: 200/5000/100, Unicode
  rune counts); missing == empty == NULL. 33 tests + e2e smoke on imresamu/postgis
  (migrations up/down round-trip verified). openapi.yaml + raw-recording-format.md
  updated.

**Open:** same as above (device test pending) + WorkManager job not yet observed
end-to-end on device with real airplane-mode toggling.

## 2026-07-11 — First real device test + crash recovery (Phase 1.2)

**Field test** (OnePlus 9 Pro, Android 16): 4 short recordings saved fine. One
13-min ride was KILLED mid-recording by OxygenOS (ApplicationExitInfo reason=13
"o-kill", importance=125 — FGS alive when killed; OnePlus = aggressive OEM killer).
File survived as truncated gzip, but was invisible in-app (index entry was only
created at Stop) → user believed the ride lost.

**Data quality findings (great news):**
- IMU: 501 Hz, zero gaps >50 ms. GPS: 1.06 Hz, median accuracy 3.8 m (one 60 m
  outlier). No barometer on this device (sensor absent) — handled gracefully.
- Airtime concept validated on raw data: bunny hops visible as ~200 ms low-|a|
  windows; stair-drop landing peaked at 19.8 g. Simple thresholding already works.

**Fixes shipped:**
- Index entry now created at Start (status `recording`) — active-recording marker.
- Startup recovery: repairs truncated gzip (decompress until error, atomic rewrite),
  recovers orphan files and stuck `recording` entries → status `recorded` +
  `recovered: true`, "Recovered after crash" + Finish saving in UI. Never deletes
  unrecoverable files (raw-forever principle). 7 new unit tests.
- START_STICKY resume: null-intent restart repairs the file and CONTINUES recording,
  appending a new gzip member (RFC 1952 multi-member; readers handle it).
- Writer: GZIPOutputStream(syncFlush=true) — the ~2 s loss bound is now actually
  guaranteed (before, flush() didn't force the deflater).
- PARTIAL_WAKE_LOCK during recording; battery-optimization exemption dialog on Start
  (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), never blocks recording.

**Open:**
- Backend still not deployed to Coolify — uploads from the phone can't complete
  outside the dev Wi-Fi (APK bakes the Mac's LAN IP). Deploy = next step.
- Re-test on device: recovery of the real 12 MB orphan, kill-resilience on a long
  ride with exemption granted, airplane-mode upload queue.

## 2026-07-11 — Activity detail screen with map (:feature:activity)

New `:feature:activity` module: `ActivityDetailScreen(recordingId, onBack)` —
stats header (title/start time, bike, upload-status chip; tiles for duration,
distance, avg/max speed, `—` placeholders for Descent/Airtime pending
fusion-core) over an interactive MapLibre map (11.11.0, OpenFreeMap Liberty
style — free vector tiles, no API key) with the ride polyline in the theme
accent, camera fitted to track bounds. MapView hosted via AndroidView with a
lifecycle-forwarding bridge (`rememberMapViewWithLifecycle`).

- `GpsTrackReader` in :core:recording: one streaming pass over the raw
  `.jsonl.gz`, extracts only `gps` lines (cheap substring pre-filter before
  JSON decode — IMU is ~500 Hz), tolerates multi-member gzip and truncated
  tails. Loudly documented as DISPLAY-ONLY: all real stats stay in fusion-core
  (arch principle 2); distance/speed tiles are marked TODO(fusion-core).
- Repository: new `recording(id): Flow<LocalRecording?>` accessor.
- Nav: recordings list rows now clickable → `onOpenActivity(id)` callback
  (feature stays navigation-free); :app NavHost gained `activity/{id}`.
- Verified: `:app:assembleDebug` green, :core:recording tests green; emulator
  smoke test with a seeded synthetic ride — Liberty tiles + polyline render,
  camera fit and back-navigation teardown OK.

**Open:** map tile cache/offline behavior untested; stats tiles swap to
fusion-core values once the UniFFI wiring lands.

## 2026-07-11 — fusion-core on-device via UniFFI (device-first compute lands)

Architecture pivot recorded in DECISIONS: raw stays ON DEVICE, server will get
only processed artifacts (fused track, results+uncertainty, IMU evidence pack;
raw windows on request for KOM verification). Phone = primary computer.

- Toolchain: rustup installed user-level (~/.cargo, --no-modify-path; MacPorts
  rust untouched), Android targets + cargo-ndk 4.1.2, NDK 27.1.
- fusion-core: recording.rs parser (MultiGzDecoder — multi-member gzip from
  crash-resume; tolerates truncated tails, unknown line types), analysis.rs
  with UniFFI-exported `analyze_recording(path) -> RideAnalysis` + 
  `algorithm_version()` (ALGORITHM_VERSION = "gps-naive-0.1", documented as
  pre-Kalman). v0: accuracy gate >20 m, anchored haversine, moving time,
  median+hysteresis altitude, airtime via 150 ms trailing-mean |a| < 4 m/s²
  (window edges centered so landing_peak_g catches the spike). 12 tests incl.
  REAL fixture testdata/forest-30s.jsonl.gz — detects the actual bunny hop
  (t≈+23.4 s, 164 ms, 11.9 g landing).
- Bindings: crates/uniffi-bindgen shim, uniffi.toml pins Kotlin pkg
  com.dhava.fusion; fusion/scripts/build-android.sh (cargo-ndk arm64+x86_64 →
  jniLibs, bindgen → Kotlin). Generated Kotlin + .so COMMITTED for now (app
  builds without Rust toolchain; CI takes over later).
- android :core:fusion: thin FusionCore facade (analyze from Dispatchers.IO),
  jna dep. :feature:activity now uses it: Distance/Avg/Max/Descent/Airtime
  tiles show fusion-core values ("1.2 s × 3" airtime format); Kotlin TrackStats
  deleted — GPS parsing remains for the map polyline only.

**Open:** GPS-altitude descent is garbage on wooded trails (fixture showed
9.9 m ascent / 0 descent on an actual descent) — expected; Kalman + IMU next.
Ascent/descent tiles will look wrong until then. Track from RideAnalysis
(1 Hz decimated) could replace the Kotlin polyline pass later.
## 2026-07-12 — Rust live fusion + recording map

Finished the previously uncommitted fusion foundation (`linalg`, `orientation`,
`ekf`) and wired it into fusion-core. Added `LiveFusion`, a stateful UniFFI API
that consumes reduced-rate IMU plus GPS and emits display-rate fused snapshots:
Mahony attitude, 6-state ENU EKF, accuracy/Mahalanobis GPS gates and IMU
stationarity-driven ZUPT. Android-reported GPS speed is deliberately ignored
while stationary, fixing the observed ~10 km/h chair-speed. Two live-specific
tests plus the existing math tests pass (30 fusion-core tests total including
the forest fixture).

RecordingService now preserves raw ~500 Hz sensor capture but feeds live fusion
at 50 Hz to avoid JNI/battery waste. RecordingState exposes bounded fused track
points, fused speed and STILL/MOVING. RecordScreen now renders a MapLibre live
map with a growing fused polyline/current-position marker and follows the rider;
map rendering only exists while the recording UI is composed. Generated UniFFI
Kotlin and arm64/x86_64 native libraries were refreshed.

Verified: `cargo test -p fusion-core` and strict clippy green; recording unit
tests and `:app:assembleDebug` green. Installed on the x86_64 emulator and
started a recording: native LiveFusion loaded, recording service/UI ran without
crashes. Physical-device install subsequently completed on the OnePlus 9 Pro
(LE2123, arm64); app launch produced no native/MapLibre crashes. Manual
stationary-speed and outdoor-track validation remain the next field checks.

**OnePlus stationary calibration:** first field test still showed 0.8 km/h.
Pulled the live raw recording from the device: stationary accel error median
0.056 / p95 0.244 m/s², but gyro had isolated spikes up to 0.31 rad/s despite
median 0.028. The original all-samples-below-0.12 rule could therefore never
enter ZUPT. Stationarity now gates on 700 ms window means (accel <0.45 m/s²,
gyro <0.15 rad/s), with a regression test proving sustained 0.5 rad/s rotation
is still classified moving. Rust tests/clippy green; rebuilt and reinstalled on
the OnePlus for another stationary check.

Second field check converged only to 0.2 km/h. The new raw file confirmed IMU
was calm and GPS itself continuously reported 0.27–0.36 km/h drift. Added a
Rust low-speed rest guard: fused <0.35 m/s plus reported GPS <0.5 m/s collapses
velocity via ZUPT and emits `0.0 / STILL` (well below the existing canonical
0.7 m/s moving threshold). Regression test added; 31 unit tests + forest fixture
and strict clippy green; rebuilt and reinstalled on OnePlus.

Walking regression found immediately: low-speed guard could emit `MOVING` with
0.0 km/h indoors. Device trace proved strong motion (gyro 0.5–2.9 rad/s) and
GPS walking speed up to 2.9 km/h, so a speed-only floor is invalid. Removed the
guard entirely: only confirmed IMU stationarity may zero speed. Added assertion
that sustained rotation preserves nonzero speed; tests/clippy green and corrected
native build installed on OnePlus.

Third cycle exposed `MOVING` persisting after a stop. Full 80 s device trace
showed long calm periods punctuated by brief IMU bursts; a stateless window can
flip at an unlucky GPS snapshot. Replaced direct classification with hysteretic
state transitions: 500 ms sustained calm enters STILL, 250 ms sustained motion
exits. Added a stop→move→stop regression test proving speed returns to zero;
31 tests + fixture and strict clippy green, installed on OnePlus.

Runtime/offline mismatch root-caused: the short failing recording had no usable
GPS callback, and Android only copied Rust's `stationary` flag when a GPS
snapshot arrived. IMU correctly entered STILL offline but UI remained at its
initial MOVING state indefinitely. `push_imu` now returns the live stationarity
state on every 50 Hz update; service immediately publishes STILL/0 without GPS.
On transition to MOVING it clears stale zero speed until a fresh GPS snapshot
exists (`— km/h` is honest when indoor GPS cannot estimate speed). Tests/clippy
green; rebuilt and installed on OnePlus.

Final runtime root cause: live IMU downsampling initialized
`lastLiveImuMs = Long.MIN_VALUE`; Kotlin's first `timestamp - MIN_VALUE`
overflowed negative, so the first IMU sample was rejected and the sentinel was
never advanced — every IMU sample was rejected forever. Rust diagnostics showed
`accelMean/gyroMean = Infinity`, making the issue unambiguous. Sentinel is now
handled explicitly before subtraction. Automated physical-device UI test after
install: `STILL`, `0.0 km/h`; Rust log showed accel mean 0.009, gyro mean 0.0014,
calm for 5.4 s. Test recording stopped cleanly.
## 2026-07-12 — recorder-first product shell and sensor warm-up

Reframed the Android prototype around a standalone, Strava-export-oriented
ride recorder. Top-level navigation is now Record / Activities / Settings;
the empty Segments, Feed and Profile features are no longer included in the
app build. Record opens directly onto the map/control surface, Activities is
the on-device ride archive, and Settings adds a persisted offline mode plus
sensor-diagnostics and keep-screen-awake development toggles.

Recording now has an explicit preparation phase. GPS and IMU capture start for
calibration without creating or writing a raw file; capture begins once IMU is
warm and GPS accuracy is at most 25 m, with a hard five-second deadline so the
button always produces a recording promptly. Cancelling during preparation
leaves no empty activity. Added pause/resume service actions: paused time is
excluded from the displayed timer and raw GPS/IMU/barometer samples are not
written until resume. The recording surface was restyled as a compact field
instrument with map backdrop, large telemetry, readiness feedback, and separate
pause/finish controls. Offline mode now keeps saved rides local and does not
enqueue upload work.

Verified: `:app:assembleDebug` and `:core:recording:test` green.

Follow-up visual correction after reviewing the result on the OnePlus: removed
the full-screen translucent surface that obscured the map. Record now uses the
map as the actual screen with only an opaque bottom instrument card. Idle shows
Start; active recording shows Pause only; paused recording exposes Resume and
Finish. Enabled MapLibre's native location puck, tracking camera and immediate
16.5× zoom. Built, installed and visually checked on the physical OnePlus 9 Pro.

MapLibre's tracking zoom did not apply when the first location arrived after
style activation, leaving the idle screen at globe scale. The record feature
now asks Fused Location for the last/current fix and explicitly performs one
initial 16.5× camera animation before recording starts; subsequent map gestures
remain user-controlled.

Map follow mode now yields immediately to a user pan/zoom gesture: the native
location camera switches to NONE and incoming live-track points stop moving the
camera. A floating recenter control appears and restores tracking/16.5× zoom;
opening the Record destination starts in follow mode again. Paused-state Finish
and Resume controls now share the same 88 dp touch target and visual weight.

Redesigned the broken transparent post-ride save state as a fully opaque,
full-screen workspace. It now has a clear completion heading, compact duration /
start / local-storage summary, restrained title and notes fields, bike cards,
and a full-width primary Save Activity action. Discard is subordinate and still
requires confirmation. The form scrolls, respects the IME/navigation insets,
and no longer lets the live map interfere visually with metadata entry.

Pre-field-test hardening: pause/resume now emit explicit raw `event` lines;
notification text distinguishes PAUSED from RECORDING; Finish requires confirmation;
start/pause/resume/finish controls provide haptic acknowledgement. Keep-screen-awake
now controls the Activity window flag and sensor diagnostics controls whether GPS
accuracy is shown in the live card. Updated the raw format contract, rewrote ROADMAP
around recorder-first milestones, and documented the decision to retain full-rate
stationary IMU through field calibration.

Added GPX 1.1 export from Activity Detail through Android's share sheet (timestamps,
elevation where present, escaped title, FileProvider URI) with a contract test. Start
now refuses when less than 250 MB is free and the service ignores duplicate Start
during sensor preparation. Added a Rust regression proving a manual pause cannot
bridge distance/moving time. Marked GPX complete in the recorder-first roadmap.

## 2026-07-13 — Android agent tooling and project UI guardrails

Installed Google's `android-cli`, `testing-setup`, and `edge-to-edge` skills globally
for reuse across Android projects. Added the repository-local `dhava-ui-design` skill
under `.agents/skills/` so Dhava-specific visual direction travels with the codebase.
The skill codifies the recorder as a dark-first field instrument, map-first layout,
glove-friendly controls, complete recording/save state coverage, shared Compose
tokens, accessibility, and rendered device/screenshot verification. Deliberately did
not adopt the experimental Compose Styles API (`compileSdk 37+`) or migrate to
Navigation 3 merely to enable adaptive guidance. Skill metadata and structure were
validated with the standard skill validator.

## 2026-07-13 — full Android UI refactor onto the Dhava design system

Reworked the complete current prototype rather than leaving a mixed transitional UI.
`:core:ui` now owns the dirt-orange/earth palette, tabular telemetry typography,
spacing, sizes, shapes and shared screen header, panel, metric, ride control, status,
divider and empty-state components. Material 3 Expressive remains underneath for
behavior and accessibility; the feature layer now speaks in Dhava product concepts.

Record remains map-first but has a tighter idle instrument, explicit preparing
readiness, prominent speed/state/time hierarchy, Material pause/stop/recenter icons
and 88 dp ride controls. Bottom navigation disappears during preparing, recording,
pause and save. Save became an opaque, IME-safe workspace with compact summary, bike
picker and one dominant action. Activities is now a restrained flat archive rather
than a generic card dashboard; Detail uses the map as the hero with a single overlaid
stats instrument. Settings uses grouped field-kit surfaces. Added dark Compose
previews for idle/preparing/recording, save, activities and detail, and set
`adjustResize` for keyboard correctness.

Verified `:core:recording:test` and `:app:assembleDebug`; installed the final APK on
the OnePlus 9 Pro and a freshly wiped Pixel 9 Pro AVD. Exercised the emulator journey
start → battery dialog → record → pause → guarded finish → save, plus Activities,
Detail and Settings. Visually inspected light/dark renders, save with the IME open,
and Record/Settings at system font scale 1.5. OnePlus rendered the new idle surface
before its thermal protection locked the screen; final APK installation succeeded.

Follow-up UX correction from the first review: normalized existing-bike and Add bike
cards to the same 148×112 dp footprint. Removed the confirmation dialog from the
paused Stop action; Stop now finalizes immediately into the Save workspace. Save has
both app and system Back handling, which acknowledges `Finished` without deleting the
raw file. The ride remains `RECORDED`, and Activities now exposes a working `Save`
action that reopens the workspace through a dedicated route, so postponing metadata
never creates a dead end. Verified the complete stop → save → back → Activities →
reopen-save journey and visually compared Capra / Add bike side by side on the AVD.

Second bike-picker pass replaced the equal but visually empty tiles: an existing bike
is now a compact 196×92 dp content card with bike icon and name/type hierarchy, while
Add bike is a lighter 136×92 dp outlined action. Equal height preserves rhythm without
pretending the two items have equal semantic weight. Visually verified with Capra and
Add bike together on the Save screen.

Tightened the Save workspace after review: reduced stacked screen/section/panel
padding, compacted the summary and bike cards, and kept the 56 dp primary action and
all touch targets intact. The same content now fits as one denser field workspace
without the previous oversized vertical gaps; visually verified on the AVD.

## 2026-07-13 — GPS preparation timeout increased

Extended the pre-recording GPS warm-up fallback from 5 to 10 seconds and synchronized
the Preparing countdown. A clean fix (≤15 m accuracy plus ready IMU) may still begin
early; the longer fallback prevents a cold ±70 m fix from anchoring a crooked start
merely because five seconds elapsed.

## 2026-07-13 — first full trail field dataset diagnosis

Pulled all 30 recordings (105 MB compressed) plus the local activity index from the
OnePlus after a mixed field session: riding and walking on trails, stairs, stationary
periods and a final bus control. All gzip files passed integrity checks. The ten new
long recordings contain up to ~496k IMU samples and ~989 GPS fixes each; rider-entered
titles were used as ground truth during replay. Raw remains on the phone; the pulled
copy was used only as a temporary local diagnostic dataset.

Screen-off recording failure is confirmed as a lifecycle/process problem, not an
empty writer. Entries titled `weird, didn't record`, `nothing recorded again` and
`again did not recorded` contain 5.4–12.5 MB of valid raw data but are marked
`recovered`, with 250–377 s GPS/sample gaps. `ApplicationExitInfo` shows OnePlus
`o-kill` terminating the process at foreground-service importance 125 repeatedly
(16:59, 17:21, 17:37, 18:30 and 18:44), despite battery whitelist and the partial wake
lock. A sticky null-intent restart then crashes in `resumeAfterRestart` because Android
16 forbids promoting a background-started location FGS with foreground-only location
permission. Current idle UI memory is also high (~344 MB PSS / 444 MB RSS), dominated
by MapLibre/native/graphics, while Dalvik is small; retaining the map when the screen
is off is therefore a likely contributor worth fixing before blaming the writer.

Replayed the exact live path through Rust `LiveFusion` with Android's 50 Hz IMU
downsampling and compared each emitted snapshot with the simultaneous raw GPS fix.
The severe trail zigzags are generated by fusion, not by raw GPS: `udzo 1` is 1.69 km
raw versus 28.8 km fused, `1000 lines` 1.70 km versus 17.4 km, and `Stairs` 1.29 km
versus 8.37 km. Raw steps are normally 11–16 m while fused steps reach 186–495 m.
The implementation feeds full horizontal IMU acceleration into prediction despite
comments describing it as a weak hint; after divergence, position re-seats every five
rejections but runaway velocity is not re-seated and velocity updates can reject
forever. This creates the repeating long out-and-back sawtooth.

`STILL` correctly zeroes velocity but continues accepting noisy GPS position updates,
so the live map draws small stationary patterns. Dedicated static captures measured
15.1 m raw / 6.2 m fused accumulated path within a 5.3 m / 2.9 m radius even though
98% of snapshots were classified still. The UI should hold/coalesce the displayed
position while still; canonical analysis may retain the measurements with explicit
uncertainty.

The current offline airtime detector found ten short windows (156–364 ms) across four
trail recordings, none on the stairs or bus control. The rider reported almost no
intentional airtime, so these are calibration candidates (micro-unweighting versus
false positives), not validated jumps.

**Open, in priority order:** make screen-off recording survive without a forbidden
sticky location-FGS restart (including releasing the map/native memory when UI is
hidden and deciding whether background-location permission is justified); add a
fusion fail-safe so live output can never run hundreds of meters away from fresh GPS
and recover both position and velocity; freeze/coalesce live position during confirmed
STILL; then add post-ride Activity Detail diagnostics for raw GPS / fusion / compare
and time-scrubbing rather than crowding the ride screen.

## 2026-07-13 — field failures fixed and raw/fusion diagnostics added

Reworked Rust live fusion around the field dataset. `STILL` is now earth-relative:
calm IMU may enter stationarity only when GPS does not show corroborated displacement,
so a smooth bus leaves ZUPT even if Android's/our motion state initially looks still.
Confirmed stationary state holds its GPS anchor rather than accepting every noisy fix.
Leaving ZUPT re-seats position and velocity together; prolonged velocity-gate rejection
also re-seats velocity. Long IMU gaps reset motion prediction, live horizontal inertial
integration is temporarily disabled, and a GPS-accuracy envelope is the final fail-safe
against an unbounded live track. Raw capture remains untouched.

Replayed the four representative field recordings through the exact Android-rate Rust
path. The former 5–17× distance inflation is gone: `udzo 1` is 1.687 km raw versus
1.741 km fused, `1000 lines` 1.705/1.728 km, `Stairs` 1.291/1.333 km, and the bus
control 0.701/0.704 km. Maximum fused-to-current-GPS deviation is 16–33 m rather than
hundreds of metres. A static capture now accumulates about 3 m fused path versus 15 m
raw GPS path while remaining still for 98% of snapshots. Added regressions for smooth
vehicle motion, stationary jitter, vibration spikes and velocity-filter recovery.

Hardened screen-off recording. The foreground service now handles a denied location
FGS promotion without a crash/restart loop and uses sticky restart only while an
active recording is recoverable. Recorder startup explains optional background
location access; on Android 11+ it opens the app settings because the system permission
dialog cannot grant `Allow all the time` directly, while `Record anyway` remains
available. The live MapLibre composition is removed whenever the activity is not
`STARTED`, including screen-off and save flow. On the OnePlus this reduced app memory
from roughly 302 MB to 201 MB PSS (EGL allocation removed), kept the same process and
foreground service alive, and the raw file continued growing after an immediate
screen-off during GPS preparation. A Pixel forced-kill test restarted into the same
file (4.0 KB to 9.2 KB) without an AndroidRuntime crash.

Added Activity Detail diagnostics backed entirely by `fusion-core`: Rust replays a raw
recording in timestamp order and returns raw GPS and live-fused tracks. The map now has
`GPS`, `Fusion` and `Compare` modes; compare draws neutral raw GPS beneath the primary
fused line. This deliberately lives post-ride rather than adding recorder-map clutter.
Time scrubbing and a dedicated motorized-transport classifier remain follow-ups.

Verification: 37 fusion-core unit tests plus the real forest fixture pass; the complete
fusion workspace passes strict clippy; Android recording tests and debug assembly pass.
Android arm64-v8a and x86_64 native libraries and generated UniFFI bindings were rebuilt
from the verified Rust source. The final APK was installed on the physical OnePlus;
the background-location education dialog was then exercised and visually verified on
that device. Android reports foreground precise location granted and background
location still denied, as expected until the rider chooses `Open settings` and changes
Location to `Allow all the time`.

**Next field check:** grant background location on the OnePlus if reliable OEM-kill
recovery is desired, then test immediate screen-off, rough trail, a true stationary
stop and a short bus/shuttle control. Transport must stay recorded as raw data; a later
Rust analysis stage will label/exclude it instead of treating it as `STILL` or silently
dropping it.
