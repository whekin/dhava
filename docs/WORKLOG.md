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

## 2026-07-14 — themed live map and pre-start GPS warm-up

Restyled the recorder map as part of the Dhava field instrument rather than leaving the
generic OpenFreeMap Liberty palette untouched. Land, vegetation, water, buildings,
roads, trails, boundaries and labels are now mapped into coordinated dark/light Dhava
colors. The live track uses a dark rounded casing beneath a 5.5 px primary-orange line.
The rider position is a two-ring primary marker with a real geographic accuracy area,
and camera padding keeps it centered in the unobstructed map above the changing idle,
preparing and recording panels. The interactive MapLibre attribution control moves
above those overlays, while the decorative SDK wordmark is hidden so map data and
license credits remain accessible without adding visual clutter.

Replaced the map SDK's implicit location engine and one-shot cached lookup with an
explicit high-accuracy preview request (1 s target, 500 ms minimum). It runs only while
the Record screen is visible and state is `Idle`, uses a recent cached fix for immediate
orientation, then continuously refines the position. It is removed on screen-off/app
departure and as soon as the recording service enters Preparing, preventing duplicate
GPS subscriptions. The idle panel now reports `GPS warming`, `GPS refining · ±N m` or
`GPS ready · ±N m` so the rider can see pre-start readiness.

Verified light and dark rendering on the AVD and dark rendering on the physical
OnePlus. `dumpsys location` showed an active `HIGH_ACCURACY` request at 1 s attributed
to Dhava while idle, and `ProviderRequest[OFF]` after leaving the screen. On the OnePlus
the visible preview refined from ±100 m through ±24 m to ±7–9 m without starting a
recording. MapLibre loaded the customized vector style without runtime property errors;
recording tests and the final debug assembly pass. The final APK was installed and
opened on the OnePlus.

Moved the shared base-map palette, style URI and MapLibre chrome policy into the new
`:core:map` module and applied them to both map surfaces: the live recorder and
Activity Detail diagnostics. Activity Detail now gives the fused path the same rounded
dark casing used by the live track, reserves camera space for its bottom statistics
card, hides the decorative MapLibre wordmark and keeps the interactive attribution
control tucked into the lower-left map corner above the card. The recorder uses the
same explicit 12 dp edge placement above its ride panel rather than retaining
MapLibre's large wordmark-sized left margin. Rebuilt, installed and visually verified
both screens on the OnePlus using the saved bus recording; no user data was modified.

Improved dense-city diagnostics after reviewing the bus track. Raw GPS is now drawn as
a thin neutral line plus a small outlined dot for every GPS fix in `GPS` and `Compare`;
the dots are absent from `Fusion`. In compare mode the raw line stays beneath the solid
orange fused path, while the individual GPS fixes render above fusion so deviations and
input sampling remain visible. The final point radius is 3.25 px with a 1 px casing.
Expanded shared label theming from a layer-name subset to every MapLibre symbol layer
and increased the crisp contrasting halo, fixing road and POI text that previously
blended into themed city buildings. Verified GPS, Fusion and Compare on the physical
OnePlus, including the final point size and layer ordering; recording tests and the
debug build pass, and the updated APK is installed.

Added semantic start/finish markers and pause-safe diagnostic geometry. Activity Detail
now draws a green play badge at the first visible fix and a primary-orange checkered
flag at the last, both with dark map casing so their meaning survives forest and city
backgrounds without relying on color alone. Marker coordinates follow the selected
mode: raw endpoints in `GPS`, fused endpoints in `Fusion` and `Compare` when available.

Extended Rust `DiagnosticTrackPoint` with `section_id`, assigned by chronological raw
`pause` events. Both raw and exact-live-replayed fusion points cross UniFFI with the
same section boundary, and Android renders each section as a separate MultiLineString;
individual GPS fixes remain visible across the gap. If Rust replay is unavailable,
the map deliberately shows isolated GPS fixes instead of guessing a continuous line.
Regenerated the arm64-v8a/x86_64 native libraries and Kotlin bindings. Added a Rust
pause/resume regression with a large coordinate jump and an Android renderer section
test. All 38 fusion-core tests, the real forest fixture, workspace strict clippy,
Activity map test, recording tests and debug assembly pass. Visually verified the
markers on the real paused `udzo 1` recording on the OnePlus; its 3.8 s pause occurred
while stationary, so the real geographic gap is under one metre. The final APK is
installed on the device.

## 2026-07-14 — Stop, pause and sharp-turn fusion loops bounded

Traced the large Compare-mode loops in `udzo 1` through the raw recording and the
exact Rust live replay. Android commonly emitted an exact `speed_mps: 0` without a
bearing at the stop; `velocity_en` rejected that pair, leaving the pre-stop velocity
inside the EKF. The velocity gate could then reject the abrupt real stop, and a long
manual-pause/sensor gap cleared motion classification without clearing horizontal
velocity. Tight corners exposed a separate overshoot bounded only by the former loose
2.5-sigma/12 m live GPS envelope.

Zero speed is now a valid directionless `[0, 0]` measurement. Corroborated geographic
displacement still overrides a false zero from a smooth bus, while an uncorroborated
zero clears stale horizontal velocity without waiting for the normal gate. A sensor
gap resets horizontal velocity immediately; derived velocity is not calculated across
that discontinuity, and the first accepted GPS fix re-seats position and velocity
together. Tightened the live GPS envelope to 1.5 times reported accuracy with a 6 m
floor so corner prediction cannot make the wide former triangles.

Added regressions for directionless zero speed, abrupt stop recovery, pause/gap
re-seating and preserving vertical state during a horizontal reset. On the exact
`udzo 1` replay, fused path length changed from 1741.1 m to 1695.7 m against 1686.6 m
raw, and maximum fused-to-current-fix offset fell from about 20.5 m to 13.0 m. A still
window around the manual pause remains within 0.6 m of its GPS fixes. All 42 core unit
tests, the real forest fixture, workspace strict clippy, Android recording/activity
tests and debug assembly pass. Rebuilt both Android native libraries and installed and
launched the verified APK on the physical OnePlus.

## 2026-07-14 — GPS accuracy becomes visible along the ride

Activity Detail now carries each raw fix's stored `accuracy_m` into its GeoJSON feature
and colors GPS dots with a continuous data-driven MapLibre expression. The scale uses
Dhava tertiary/leaf-green at 5 m or better, amber at 10 m and the theme error color at
20 m or worse; missing or invalid estimates stay neutral. The thin raw GPS line remains
neutral and dots retain their dark casing above the primary-orange fusion path, so
Compare communicates signal quality without losing source identity.

Added a compact `GPS ACCURACY` map legend with numeric `≤5 m`, `10 m`, and `20+ m`
anchors and an accessibility description. It appears in GPS and Compare, hides in
Fusion, and sits below the mode control without covering the track statistics. Added
dark/light previews plus a unit regression proving per-fix accuracy survives GeoJSON
conversion and invalid values get the explicit unknown sentinel.

Visually verified the full forest `udzo 1` ride on the physical OnePlus in dark mode:
421 of 581 fixes are green at ≤5 m, 158 form visible green-to-amber degradation bands
at 5–10 m, and two are above 10 m. Verified legend contrast and layout in light mode on
the Pixel 9 Pro AVD, plus the Fusion/GPS/Compare visibility transitions on the OnePlus.
Activity tests, feature lint and the complete debug assembly pass. The final APK is
installed and launched on the OnePlus.

## 2026-07-14 — Accuracy-aware stop anchors remove fusion loops

Revisited every stationary interval in the saved `udzo 1` ride after the first
stop-loop fix still left large triangular excursions in Compare. The remaining bug
was temporal: the first exact-zero GPS fix at an arrival was overridden by derived
velocity from the previous moving fix. That displacement described the approach to
the point, not velocity after it, so fusion carried approach speed beyond an already
reported stop.

Live Rust fusion now treats an accepted exact-zero fix as an immediate horizontal
position/velocity anchor. Subsequent GPS jitter and false non-zero device speed are
ignored while fixes remain within the root-sum-square uncertainty of the anchor. Real
earth-relative displacement beyond that accuracy-aware gate releases position and
velocity atomically. A separate rearm anchor remembers when a zero-speed claim was
disproved, preventing a bus that repeatedly reports zero from alternating between
STILL and MOVING every other fix; zero becomes trustworthy again once coordinates
stabilize. Invalid or >20 m fixes are rejected before they can mutate fusion state.

Added regressions for arrival at a stop, stationary jitter plus the OnePlus's observed
2.8 m/s false speed, repeated zero reports on a moving vehicle, re-arming at its later
stop and bad-accuracy rejection. On exact `udzo 1` replay, each of the five detected
stationary runs has 0.00 m of internal fused path; total fusion length is 1683.3 m
against 1686.6 m raw. This removed motion inside the stop windows, but later close
visual review on the physical OnePlus showed that moving approach/exit triangles still
remained outside those windows; the next entry corrects that incomplete conclusion.

Refined GPS accuracy presentation so accepted fixes no longer approach the same orange
as fusion: ≤5 m is green, 10 m yellow and 15–20 m gold. Only raw fixes strictly above
the fusion acceptance limit of 20 m turn red. Updated the four-anchor numeric legend
and accessibility description. Rebuilt both native Android libraries, ran all Rust
tests and strict clippy plus Activity unit tests/debug assembly, installed the APK and
visually verified `udzo 1` on the OnePlus.

## 2026-07-14 — Moving GPS fixes become authoritative off-segment

Corrected the remaining orange triangle shown on the approach to the `udzo 1` stop.
The problematic interval had a nearly constant 9.94 m reported accuracy and reached
11.8 m/s before the exact-zero fix. The stop anchor worked once zero arrived, but the
velocity/bearing EKF had already extrapolated through the preceding bend and used the
large accuracy radius as permission to cut outside the raw GPS polyline. Measuring
only the internal path of `stationary` runs had hidden this failure.

Until trusted segment map matching exists, every accepted moving GPS fix is now the
authoritative rendered horizontal position. Rust still keeps velocity, vertical and
stationarity state, holds a true stop anchor, rejects >20 m fixes and respects pause
sections, but it no longer invents off-segment XY smoothing from speed/bearing. Added a
sharp 90-degree turn regression at 10 m accuracy and strengthened the rough-vibration
regression to require exact recovery to each accepted fix.

On exact `udzo 1` replay, maximum moving fusion-to-current-fix offset is now 0.000 m.
Total fused length is 1669.8 m versus 1686.6 m raw; the difference comes from removing
stationary GPS drift rather than cutting moving bends. All 45 Rust tests, the forest
fixture and strict clippy pass, as do Activity unit tests and debug assembly. Rebuilt
both Android native libraries, installed the APK, zoomed to the reported bend on the
OnePlus and verified that the orange path stays entirely beneath the GPS fixes.

## 2026-07-14 — Finalized replay adds GPS-bounded 5 Hz detail

Added a distinct delayed `finalized_track` to Rust diagnostic replay while retaining
`fused_track` as the exact causal live result. Every accepted GPS fix remains an exact
horizontal anchor. Between same-section anchors no more than 2.5 seconds apart, the
post-pass emits 200 ms samples using GPS-derived Hermite tangents and endpoint GPS
speed. Gravity-axis angular rate from the Mahony orientation filter can shift the
timing of curvature inside the interval, but never creates a free inertial XY path.
Forward progress cannot reverse and lateral curvature is clamped to the fixes' combined
reported accuracy with a hard 6 m ceiling. Manual pause sections are never bridged.

The finalized pass also uses later evidence to repair a causal stop-release artifact:
when displacement clears the root-sum-square accuracy gate, a monotonic non-zero-speed
tail previously held by live `STILL` is restored to its actual GPS anchors. On `udzo 1`,
this recovers all falsely held 2.17–3.60 m/s departure fixes. The finalized result has
1,711 points versus 581 raw fixes, is 1,674.8 m long versus 1,686.6 m raw, and stays
within 1.71 m of every causal live anchor; the small remaining held sample had only
0.74 m/s reported speed and a 0.71 m coordinate offset.

Activity Detail now consumes `finalized_track` for Fusion and Compare, falling back to
the exact live replay for old/empty results. It renders finalized samples as a separate
small light-centered layer underneath the larger accuracy-colored GPS fixes. Fusion
samples appear only at detailed zoom and grow with zoom; GPS dots also scale down at
ride overview so the orange line remains readable. Regenerated UniFFI bindings and
both Android native libraries. All 48 core unit tests, the real forest fixture, strict
clippy, Activity tests and debug assembly pass. The final APK is installed on the
OnePlus; overview presentation was visually checked there without modifying ride data.

## 2026-07-14 — Diagnostic lines preserve every rendered sample

Fixed a maximum-zoom mismatch where fusion points were very close to, but not exactly
centered on, the orange line. The data was identical; MapLibre simplified each
LineString source with its default GeoJSON tolerance while the separate point source
retained every 5 Hz coordinate. Raw and fusion diagnostic line sources now explicitly
use zero simplification tolerance, so their rendered polylines pass through the exact
same vertices as their point layers at every zoom.

Raised the fusion-point visibility threshold from zoom 16.5 to 18 and kept their radius
zoom-dependent. Ride overview and medium-distance views therefore show a clean fusion
line; individual computed samples appear only when the map has enough space to inspect
them. Added a regression for the zero-tolerance source option. Activity tests and the
complete debug assembly pass, and the APK was updated on the OnePlus without forcing
Dhava over the app currently in the foreground.

## 2026-07-14 — One-tap Strava export specified for later

Documented the deferred Strava export path without starting implementation. After a
one-time mobile OAuth connection, Activity Detail will provide one-tap export with an
offline WorkManager queue, retries, persisted status and duplicate protection. The
first upload artifact will be the canonical finalized GPX with explicit pause sections
and `MountainBikeRide`; FIT remains a compatible later upgrade.

Because Strava requires a client secret for code exchange and refresh, direct export
uses a minimal Go OAuth/upload broker rather than shipping the secret in Android. The
broker receives only the processed GPX/FIT artifact and never the device's raw sensor
recording. Recorder and local activity functionality remain fully backend-optional.

## 2026-07-14 — Raw and processed 5 Hz GPX export

Replaced Activity Detail's single ambiguous GPX share action with a compact two-option
menu. `Processed · 5 Hz` exports Rust's GPS-bounded `finalized_track`; `Raw GPS`
exports the original recorded fixes and retains their GPS elevation. The processed
option stays disabled while replay is being prepared or if finalization is unavailable,
and export failures now surface to the rider instead of silently doing nothing.

Generalized the core GPX writer around an explicit export-point model with timestamp,
optional elevation and Rust-owned section id. Each consecutive section becomes its own
`<trkseg>`, preventing other apps from drawing a bridge across a manual pause. Added a
unit regression covering 200 ms timestamps and separate pause sections. Recording and
Activity unit tests, Activity lint and the full debug assembly pass. Installed and
visually checked both menu choices on the OnePlus: the sampled bus ride produced a
valid 689-point processed GPX and a valid 167-point raw GPX with all 167 elevations.
Core Recording lint remains blocked by the pre-existing missing-permission annotation
at `RecordingService.kt:521`; its report contains no GPX exporter findings. Processed
elevation remains open: the current finalized replay contract is horizontal-only, so
Android intentionally does not manufacture vertical interpolation outside fusion-core.

## 2026-07-14 — Versioned canonical artifact generated from immutable raw

Added Rust `finalize_recording`, which parses a raw recording once and returns one
complete `gps-bounded-0.2` result: ride analysis, original GPS diagnostic points and
the GPS-bounded finalized 5 Hz track. Finalized points now include optional elevation,
accuracy, speed, stationarity and manual-pause section id. The vertical pass converts
barometer pressure into relative altitude, anchors it to median-filtered GPS altitude
from ≤20 m fixes and falls back to section-aware GPS interpolation when barometer data
is unavailable. Ascent/descent is recomputed from that finalized profile with the
hysteresis reference reset across pauses.

Android persists the result as `files/activity-artifacts/<id>.canonical.json.gz`.
Schema version, Rust algorithm version and the raw file's size/mtime form the cache
key, and the fingerprint is rechecked after computation so a concurrently resumed raw
file cannot validate a partial artifact. Writes use atomic replace; corrupt or stale
artifacts recompute from raw. Explicit
Finish starts generation after the writer closes without delaying the save workspace;
Activity Detail lazily fills any missing artifact and otherwise reads it instead of
replaying the large raw file on every opening. Raw GPX reads the cached exact GPS view;
processed GPX now includes Rust-finalized `<ele>` at 5 Hz. Discard serializes against
generation and removes both files, while every normal recomputation leaves raw intact.

Added Rust regressions for GPS-only 5 Hz altitude, barometric detail and pause-safe
vertical totals, plus Android store regressions for cache reuse, raw/algorithm
invalidation, corrupt-file replacement, atomic persistence and raw preservation. All
51 fusion-core tests plus the forest fixture, the complete fusion workspace tests and
strict clippy pass. Core Recording and Activity unit tests and debug assembly pass.

Installed on the OnePlus and lazily finalized the existing bus ride: a 29 KB artifact
contains 167 raw fixes, 689 finalized points and 689 elevations. Reopening preserved
the artifact mtime, confirming a cache hit. The resulting processed GPX is valid and
contains 689 track points with 689 `<ele>` elements. The original raw recording remains
present. Transport classification and explicit GPS/elevation quality indicators remain
separate next steps.

## 2026-07-18 — Local storage management and offline map behavior

Settings gained a Storage section. Three rows report the raw recordings
(count + size, `files/recordings/*.jsonl.gz`), the derived canonical artifacts
(`files/activity-artifacts/*.canonical.json.gz`) and the MapLibre cache
database, all sized on IO once per section entry with a small spinner while
measuring; the footer shows the device's free space. Two confirmed actions
clear derived data only: "Clear processed artifacts" goes through a new
`CanonicalActivityStore.clearAll()` under the store mutex (raw is never
touched; artifacts recompute lazily on the next activity open), and "Clear map
cache" runs MapLibre's `OfflineManager.clearAmbientCache` followed by
`packDatabase` so the SQLite file actually shrinks. Results surface as toasts
and re-trigger sizing. There is deliberately no bulk raw delete — that arrives
with per-activity delete.

Offline map behavior is now explicit. `initDhavaMap` (core:map) replaces the
bare `MapLibre.getInstance` in both map screens and applies a 512 MB ambient
cache ceiling once per process, so previously seen tiles render offline and the
cache stays bounded via LRU eviction. `MapView.setDhavaMapStyle` wraps style
loading with an offline fallback: if the remote style document cannot load
(offline with a cold cache), a minimal local background-only style is applied
and the same overlay callback still runs — track polylines, markers and the
live position never depend on tile or style availability. The fallback is
one-shot per style attempt and ignores per-resource failures once a style has
loaded, so failed tile fetches cannot replace a good style or loop.

Added `directoryUsage` (core:recording) as the pure sizing primitive with unit
tests (suffix filtering, non-recursion, missing dir) and a store regression
proving `clearAll` removes artifacts and temp files, preserves raw and
recomputes on the next load. Core Recording and Activity unit tests and the
full debug assembly pass; the APK was installed on the OnePlus, but the phone
was dozing so the visual pass and an airplane-mode check are still pending.

## 2026-07-18 — Elevation source, GPS quality and uncertainty indicators

Rust now derives a `QualitySummary` alongside every canonical activity. The
elevation source (Barometric / GpsInterpolated / None) is threaded directly
out of the vertical pass — it reports which signal each finalized point
actually used (majority wins, since a barometric profile can still fall back
to GPS at the edges of the baro time range) instead of re-deriving the answer
heuristically. The summary also carries baro sample and GPS fix counts, the
accepted count under the same ≤20 m gate the altitude anchors use, median and
interpolated p90 accuracy, within-section >5 s gap count and longest gap
(manual pause boundaries change the section id and never count as gaps), and
a coarse elevation uncertainty documented as heuristic v0 for UI display
only: barometric = 2 m + stddev of the raw baro-vs-GPS anchor offsets (fixed
3 m spread when under two anchors), GPS-only = max(5 m, p90 × 1.5).

The Android artifact stores the summary as a nullable `quality` block and the
store schema bumped 1 → 2 so pre-quality artifacts recompute from raw on next
open; a new store regression proves a legacy-schema file is rebuilt. Activity
Detail shows two tappable chips under the stat tiles once the artifact is
loaded (hidden while computing, so no wrong-data flicker): an elevation chip
("Barometric" positive, "GPS-only (±N m)" caution using the amber accuracy
palette, or "No elevation") and a GPS chip bucketing median accuracy into
Good ≤5 m / Fair ≤10 m / Poor with the gap count appended. Either chip opens
a plain AlertDialog with the full numbers (fixes, accepted %, median/p90,
gaps, baro samples, source, uncertainty).

All 57 fusion-core tests (five new: gap counting,
pause-spanning holes, rejected fixes, altitude-free recordings, plus a forest
fixture quality check), strict clippy and fmt pass; UniFFI bindings and both
Android .so files regenerated. Core Recording and Activity unit tests and the
full debug assembly pass. Only the physical OnePlus was attached (no
emulator), so the on-device visual pass is still pending.

## 2026-07-19 — Activity edit/delete, raw diagnostics export, detail reliability

The activity detail screen finishes Phase 2's last item. An overflow menu (next
to the export share icon) gains Edit and Delete. Edit opens a dialog prefilled
from the index entry — title, notes and a bike FilterChip row with the same
add-bike dialog shape as the save sheet (module-local copy; features must not
depend on each other) — and persists through the new
`RecordingRepository.updateMetadata(id, title, description, bike)`. The
transform is extracted into `LocalRecording.withMetadata`, now shared with
`saveActivity` so the save and edit paths cannot drift; it trims fields, clears
them when blank and never touches lifecycle fields (status, savedAtMs,
serverId). Deliberately local-only: an uploaded activity's server copy is not
re-synced (needs a metadata-update endpoint in the contract first).

Delete is a two-step confirm that names the activity and warns that the raw
sensor recording goes with it. `RecordingRepository.deleteActivity(id)` cancels
the WorkManager unique upload job first (new `UploadWorker.cancel`, sharing the
`upload-<id>` name with enqueue), removes the index entry under the index
mutex, deletes the raw `.jsonl.gz` and finally the canonical artifact through
the store mutex, so an in-flight finalization cannot resurrect it. This is the
one deliberate exception to the raw-forever principle — that principle governs
automatic behavior, not an explicit confirmed user request. `discard` now
delegates to `deleteActivity`, keeping a single deletion path. The screen pops
itself when the recording flow emits null after having been seen once, which
covers both self-delete and deleted-elsewhere without double-popping.

The export menu adds "Raw recording (.jsonl.gz)": the immutable raw file is
copied into `cache/exports/` (the only FileProvider-exposed dir) and shared as
`application/gzip`; `GpxExportKind` became `ActivityExportKind` carrying the
mime type. Reliability pass: a missing raw file now shows a terminal
"Activity data unavailable" state instead of a misleading "no GPS" empty state,
and a raw file whose GPS extraction is empty while the Rust replay also fails
is reported as unreadable (raw export stays enabled exactly then — that is the
bug-report use case; only a missing file disables it).

New `LocalRecordingMetadataTest` (3 tests) pins withMetadata trimming/clearing
and lifecycle-field immutability. Repository-level round-trip tests were
skipped as not cheap: RecordingRepository needs a real Context, WorkManager and
the native FusionCore, and the project has no Robolectric; the file-side delete
semantics stay covered by CanonicalActivityStoreTest. Core Recording and
Activity unit tests plus the full debug assembly pass. No device attached —
the on-device pass (edit round-trip, delete from an open detail screen, raw
share into another app) is pending.

## 2026-07-19 — Kojoring long-ride field diagnostics

Pulled the single user-authorized `Kojoring` activity from the attached OnePlus
into a temporary local directory, verified the raw and schema-2 artifact against
on-device SHA-256 hashes, and analyzed them without adding either private file
to the repository. The 1:51:39 recording is complete: 6,875 GPS fixes at
1.026 Hz and 3,353,112 IMU rows at 500.5 Hz span the full interval, with no IMU
gap even over 20 ms and only the terminal pause event. This is strong field
evidence that the foreground service plus partial wake lock survived screen-off
for a long ride. The raw gzip is 91.7 MB (544 MB uncompressed), about 49 MB per
recorded hour; keeping 500 Hz forever deserves a later storage/battery tradeoff
test against a capped raw rate while retaining sufficient airtime evidence.

Horizontal GPS quality was generally useful in the forest: median accuracy
4.97 m, p90 9.99 m, and only 18/6,875 fixes over the canonical 20 m gate.
There were 24 within-section gaps over 5 s (longest 8.303 s), concentrated late
in the activity while IMU remained continuous, so these are GPS availability
gaps rather than recorder/process stalls. Stop pinning also behaved as intended:
65 finalized STILL spans of at least 5 s were found; during the longest raw GPS
fixes wandered up to roughly 14 m while the finalized track stayed pinned.

Two metric problems are now grounded in real data. First, canonical max speed
is 60.6 km/h because the naive analysis takes the maximum plausible derived
step even when Android supplies a contradictory Doppler speed: one accepted
3.9 m-accuracy fix moved 16.8 m in one second while reporting only 10.5 km/h.
The maximum reported speed was 48.78 km/h and was supported by adjacent fixes.
The next stats revision should prefer reported speed when present and only use
derived speed when absent or corroborated, then recompute from immutable raw.
Second, this device exposes no pressure sensor in Android `sensorservice`, so
the activity is GPS-elevation-only. Its net height change is about -899 m, but
low-frequency GPS altitude drift inflates totals to +637/-1,535 m even after
the current five-fix median and 2 m hysteresis. The UI correctly labels the
source and ±15 m point uncertainty, but ascent/descent themselves need a
stronger GPS-only model (or later DEM/segment consensus) before being presented
as trustworthy.

The current detector produced 23 airtime candidates totaling 6.637 s, including
several clustered sequences and landing peaks up to 21.2 g. These remain
candidates at the individual-event level, but the rider confirmed several
feature jumps plus many bunny hops, so the overall count and clustered pattern
are plausible rather than obvious vibration false positives. Exact event recall
or video is still needed to measure missed/merged/split detections; the raw IMU
is preserved for replay after detector changes. No production code or raw
activity data changed in this diagnostic pass.

## 2026-07-19 — Accuracy/Doppler GPS gate and trustworthy max speed

The canonical algorithm advanced to `gps-bounded-0.3`. A new shared
`gps_quality` pass now protects both causal live/replay geometry and ride
distance from short coordinate teleports that contradict Android's independent
Doppler speed. For 0.2–5 s intervals with a corroborating endpoint speed of at
least 1.5 m/s, the chord must fit inside both fixes' summed horizontal accuracy
radii plus `(reported speed + 3 m/s) × dt`. The accuracy radii deliberately
remain correction room rather than movement; missing/near-zero speed cannot
reject coordinate motion because the OnePlus bus fixture proved that exact zero
may be false on a smoothly moving platform. A rejected fix does not advance the
anchor, so the next consistent fix recovers without a lasting hole.

Replay now explicitly starts a new horizontal section at every manual
pause/resume boundary instead of relying on an accompanying IMU gap to trigger a
reseat. This was exposed by an altitude/pause regression containing no IMU:
without the explicit reset, the new gate correctly saw the cross-pause
coordinate jump as impossible continuous motion but incorrectly rejected the
new section. Live Android already obtained the equivalent reset from the sensor
gap; making the event boundary first-class keeps synthetic, recovered and real
recordings aligned.

Maximum speed no longer lets coordinate-derived velocity override available
Doppler samples. Derived maxima remain available only across consecutive fixes
with no reported speed, with average moving speed as a conservative floor so a
coarse/sparse speed stream cannot produce `max < average`. The real Kojoring raw
validated the whole change: exactly one additional fix was rejected beyond the
existing 18 fixes over 20 m accuracy — the known +08:39 teleport. Max speed
changed from the false 60.6 km/h to the supported 48.78 km/h, distance changed
by only -0.34 m (10,992.64 → 10,992.30 m), and finalized output stayed at 19,282
points because the removed anchor was replaced by the bounded 5 Hz interval.
Elevation and airtime were intentionally unchanged.

Added three direct gate tests plus analysis and live recovery regressions. All
61 fusion-core unit tests, both real forest fixture tests, full Rust workspace
tests and strict clippy pass. Regenerated committed arm64-v8a/x86_64 native
libraries; Core Recording and Activity unit tests plus `assembleDebug` pass.
Installed the APK on the OnePlus and opened Kojoring: schema-2 cache invalidation
rebuilt it locally as `gps-bounded-0.3`, and Activity Detail shows 48.8 km/h
while preserving 11.0 km and 6.6 s × 23 airtime. The temporary Mac raw copy was
used only for the full replay validation and removed afterward.

## 2026-07-20 — Honest GPS-only net elevation

The canonical algorithm advanced to `gps-bounded-0.4`. Barometric activities
continue to use accumulated, hysteresis-filtered ascent/descent. GPS-only
activities now avoid accumulating low-frequency altitude drift: Rust groups
accepted altitude anchors by pause-aware section and reports only the net
change between robust endpoint medians (up to five fixes at each edge, 2 m
deadband). The interpolated elevation profile and immutable raw recording are
unchanged, so a later DEM or multi-run segment model can recompute richer
vertical totals without data loss.

Android now names that different quantity honestly: Activity Detail shows
`NET DROP`, its chip says `ELEVATION: GPS NET (±N M)`, and the signal-quality
dialog identifies both the GPS-interpolated track source and `Net change per
section`. Barometric activities retain `DESCENT` and accumulated semantics.
GPS-only display uncertainty is more conservative at
`max(7 m, p90 horizontal accuracy × 2)`; it remains UI metadata, not an input
to correction, timing or segment math.

Full replay of the private Kojoring raw file changed the previous misleading
GPS accumulation of +637/-1,535 m to 0/+898.7 m net drop, matching the
approximately 899 m endpoint difference. Horizontal distance (10,992.30 m),
supported max speed (48.78 km/h), 19,282 finalized points and airtime
(6.637 s across 23 candidates) stayed unchanged. On the OnePlus, stale-cache
invalidation rebuilt the same schema-2 artifact locally as `gps-bounded-0.4`;
the detail screen showed 899 m `NET DROP`, `GPS NET (±20 M)`, 11.0 km,
48.8 km/h and 6.6 s × 23. Both the detail card and signal-quality dialog were
visually checked on the physical 1080 × 2412 display.

Added GPS-noise and barometric accumulation regressions, updated quality/UI
tests and regenerated both committed Android native libraries. Rust formatting,
all 62 fusion-core unit tests, both forest fixtures, the full workspace tests
and strict all-target clippy pass. Activity and Recording unit tests plus the
full debug Android assembly also pass.

## 2026-07-27 — Repeated OEM-kill recovery and visible interrupted rides

The attached OnePlus supplied decisive evidence for the latest lost-ride
reports. `ApplicationExitInfo` contains foreground-process OxygenOS
`o-kill(6)` exits (reason OTHER, importance 125) on July 23 and three times on
July 25, including 17:38, 18:15 and 18:44. There is no corresponding Dhava
Java/native crash. The app is present in the device-idle user whitelist and
fine/background location plus notification permissions are granted, confirming
the rider's settings were already correct.

No private raw was copied off the device. Streaming only counts and timestamps
showed that the three newest visible recovered entries preserve 0:36, 49:18
and 29:50. The two long files contain 3,021 GPS / 1,385,937 IMU rows and
2,008 GPS / 863,255 IMU rows respectively. Their maximum IMU gaps of about
190 s and 66 s prove START_STICKY did restart and append after an earlier kill.
The root logic bug was that the restarted service began its repair/claim in a
coroutine but immediately evaluated `recording == false` and returned
START_NOT_STICKY. A second OEM kill therefore became terminal.

RecordingService now has an explicit asynchronous `recovering` lifecycle state,
so both automatic and user-requested recovery remain START_STICKY until the
recording has been claimed. Manual Continue can claim any readable, recovered,
unsaved entry, append a fresh RFC 1952 gzip member and add pause/resume events
around the process gap. Live elapsed time excludes restart downtime. An
explicit Finish preserves recovery history but disables another Continue.

Recovery no longer removes an unreadable entry from `recordings.json` or skips
an unreadable orphan. The original bytes remain visible as `Raw only`, allowing
the rider to save the entry and reach raw diagnostics/export. The Record screen
now gives interrupted data first-class priority with `Continue ride`, `Save`
and `Start a new ride`; Activities reports how many rides need attention and
uses clearer recovered/raw-only copy. The new state was visually checked on the
physical 1080 × 2412 OnePlus screen.

To reduce the sustained resource profile that makes an OEM kill more likely,
raw accel/gyro capture is capped at 200 Hz (5 ms, still finer than jump/landing
timing needs) and live Rust input remains 50 Hz. RecordingWriter now separates
lossless GPS/meta/barometer/events from a bounded 4,096-row IMU queue; an
overflow is preserved as an `imu_overflow:<count>` diagnostic event instead of
allowing unbounded process-memory growth.

All Android debug unit tests and the full debug assembly pass. On-device, a fresh
test recording survived two consecutive `SIGKILL`s: Android restarted the
foreground service both times (`lastStartId` reached 3), the same indexed entry
returned to `recording`, and its raw file resumed growing. The test activity
was then finished and discarded through the normal UI; the three real recovered
rides and all other user data were left untouched. A real 1–2 hour field ride
is still required to measure whether OxygenOS kills become less frequent, but
repeat recovery no longer depends on that outcome.

## 2026-07-28 — Durable recorder health telemetry and 2-hour field evidence

The first real ride after the repeat-recovery fix lasted 2:14:08
(`Afternoon ride`, 27.6 MB). OxygenOS killed the foreground process seven
times between 16:47 and 18:02, every time with reason OTHER / `o-kill(6)` and
importance 125. All seven sticky restarts succeeded and the one recovered
activity contains 7,711 GPS fixes, 790,096 IMU rows and seven synthetic
pause/resume pairs. Streamed aggregate inspection only — no private raw was
saved off the phone. Restart gaps were 16.4, 51.8, 91.7, 69.0, 17.7, 20.3 and
105.4 seconds (6:12 total); the largest adjacent GPS/IMU gaps were about 121
and 119 seconds. This validates repeated recovery in the field, while also
showing why exact per-process evidence is necessary.

Each new recording now gets an append-only `<id>.health.jsonl` beside immutable
raw. Fresh start, once-per-wall-clock-minute heartbeat, Android process exit,
sticky restart and explicit stop entries capture PSS/RSS, Java/native heap,
process uptime/CPU, raw size, per-process sample counts, GPS age, writer queue
depth/drop totals, thermal/battery state and restart gap. Every tiny append is
flushed and fsynced; a later append remains readable after a truncated tail.
Collection and I/O are strictly best-effort and cannot block raw repair or
recording. Activity Detail offers the sidecar as a separate
`Recording health (.jsonl)` Share artifact. Explicit deletion removes it with
raw and the derived artifact.

A five-minute physical OnePlus smoke ran with the screen off. At 60 seconds,
writer backlog and IMU drops were both zero; PSS/RSS were 292/358 MB and native
heap was 77.5 MB. At 120 seconds those values had plateaued (292/358/77.6 MB),
ruling out a linear writer or live-fusion leak in that interval. One controlled
SIGKILL produced `ApplicationExitInfo` reason SIGNALED/status 9, a new process,
foreground `lastStartId=2`, and a health restart gap of 2.5 seconds. The next
heartbeat continued normally with zero backlog/drops and lower
PSS/RSS/native-heap values of 270/335/57.6 MB. Explicit Stop drained both
queues and persisted a final checkpoint.

Added health-log corruption/deduplication/optional-field tests and writer queue
telemetry assertions. All Android debug unit tests and the full debug assembly
pass; the instrumented APK is installed on the OnePlus. The phone reported
thermal status 3 while plugged in during the desk smoke, so the next outdoor
ride will distinguish charging/desk heat from field conditions.

The Activity Detail export menu was visually checked on the physical
1080 × 2412 display: all four artifacts remain readable and the two-line health
description fits without clipping. Selecting it opened the system Share sheet
with `dhava-de0d4fe4-health.jsonl`. Nothing was shared. The synthetic activity,
its raw file, health sidecar and derived artifact were then deleted through the
normal confirmed UI flow; the user's real rides were untouched.

## 2026-07-28 — One-tap Strava export implemented, live credentials pending

Implemented the previously specified Strava path end to end without weakening the
offline recorder. `proto/openapi.yaml` now defines connection, status, OAuth callback
and idempotent multipart GPX export. Migration 0004 adds hashed anonymous-device
credentials, server-owned rotating OAuth tokens and unique export jobs. The Go broker
uses mobile OAuth with only `activity:write`, ten-minute hashed state, six-hour access
tokens with early refresh, asynchronous upload polling and a stable external id. It
recognizes Strava's documented `duplicate of activity <id>` result after an ambiguous
network retry and recovers the existing activity instead of reporting a false failure.
Only the canonical processed GPX is accepted; raw GPS/IMU/barometer and recorder health
remain device-local.

Android generates and retains a random 256-bit connection credential, handles the
server-to-`dhava://strava/connected` return, and exposes a dedicated Activity Detail
action. Its explicit states are Connect, ready to Export, queued/processing, retryable
failure and `View on Strava`. Export creates the same Rust-finalized 5 Hz GPX used by
local sharing, queues network-constrained unique WorkManager work, persists Strava
upload/activity ids in `recordings.json`, and cleans its temporary GPX. A ride on an
e-bike is labeled `EMountainBikeRide`; other current bike classes use
`MountainBikeRide`.

Backend tests cover OAuth URL/state/scope, rotating refresh tokens, create/poll
idempotency, duplicate recovery, multipart contract and real HTTP request shapes.
Go vet, all Go tests/build, all Android debug unit tests and the full debug assembly
pass; Android debug lint also passes. Docker Compose and OpenAPI YAML validate.
The APK was installed on the physical
OnePlus; the deep link was delivered to the existing singleTop activity, and the
unconfigured/disconnected action was visually inspected on the real 1080 × 2412
Activity Detail screen without clipping or touching ride data.

Live OAuth/upload is intentionally not claimed yet. It requires the owner to create a
Strava API application (Strava currently requires a subscription), provide its client
id/secret, choose the backend HTTPS hostname as Authorization Callback Domain, run
migration 0004 and deploy/configure the Go API. The current phone build still targets
the local development API default, so it honestly reports that the Dhava backend is
unreachable until rebuilt with that deployed HTTPS base URL.
An attached-device integration test can happen before deployment by using Strava's
whitelisted `127.0.0.1` callback plus `adb reverse tcp:8080 tcp:8080`; the README
documents the exact local origin and Gradle override.
