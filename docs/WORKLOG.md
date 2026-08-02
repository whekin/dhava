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

## 2026-07-28 — Strava export moved into the secondary export menu

The inactive Strava integration no longer occupies a full-width primary-action row on
every Activity Detail card. Its complete state machine now lives inside the existing
Share menu alongside processed/raw GPX and diagnostic artifacts: connect, export,
queued/processing, retry after failure and open the uploaded activity. This keeps the
map-led activity summary compact while retaining the implementation for later backend
deployment and Strava app registration.

Activity unit tests and the full debug assembly pass. The APK was installed on the
physical 1080 × 2412 OnePlus. Both the compact Activity Detail card and expanded Share
menu were visually checked; the current unavailable backend message is readable and no
export action or user ride mutation was triggered.

## 2026-07-28 — Map-first Activity Detail bottom sheet

Replaced the fixed floating Activity Detail card with a persistent Material standard
bottom sheet. It opens at a 112 dp peek that shows only the drag handle, activity
identity, local status and Share/overflow actions; no metric is left visibly clipped.
Dragging it upward reveals the full metric and quality content. The expanded height
wraps short content instead of leaving an empty panel, caps at 72% of the viewport for
long content and gives the body a nested vertical scroll while the action header stays
pinned. The sheet cannot be fully hidden and has no modal scrim, so the exposed map
remains the primary working surface.

The physical 1080 × 2412 OnePlus was used for both collapsed and expanded visual checks.
A horizontal gesture over the exposed map left the sheet anchor unchanged; handle
swipes moved it reliably between expanded and partial anchors. Share remained available
from the collapsed header and opened the complete export menu without triggering an
export. Activity unit tests, feature lint and the full debug assembly pass, and the
final APK is installed on the phone.

Updated the repository `dhava-ui-design` skill with the reusable map-led detail pattern:
standard rather than modal sheets, compact identity/action peeks, pinned headers,
bounded nested scrolling, preserved map gestures and explicit device verification.
Its metadata remains current and the frontmatter passed an equivalent local YAML check;
the bundled Python validator itself could not run because the host Python lacks PyYAML.

## 2026-07-28 — Actionable recording notification and recovery feedback

The foreground recording notification now reflects the real recorder state and elapsed
ride time. Active recordings expose Pause, paused recordings expose Resume, and unsafe
Finish remains inside the app. Tapping the notification always opens the current Record
screen, including when the existing singleTop activity was showing another route.
Preparing, restoring and the first 30 seconds after a successful process recovery have
explicit messages, so users can distinguish sensor warm-up from a recovered ride whose
raw data remained safe.

Notification presentation is a pure tested state model. This work also fixed a latent
paused-state refresh bug: the old ticker only refreshed when elapsed time changed, but
elapsed time intentionally freezes while paused, so the notification could remain
visually active. Pause and Resume now publish their state immediately.

Recording unit tests, app debug lint and the full debug assembly pass. The APK was
installed and exercised on the physical OnePlus through active, pause, resume, process
SIGKILL, sticky service recovery, notification-to-Record navigation and normal
finish/discard. The post-kill notification reported `Ride restored`, elapsed time
continued, and the generated test recording was discarded through the regular UI.
The service and active notification were gone afterward; existing user rides were not
modified.

## 2026-07-28 — Conservative ride states, semantic maps and adaptive still persistence

Added a Rust-only post-ride `ActivityState` pass over the canonical 5 Hz track.
Each finalized point now carries `Unknown`, `Still`, `Downhill`, `Transit` or
tentative `LikelyMotorized` plus confidence; the canonical algorithm advanced to
`gps-bounded-0.5` and Android's rebuildable artifact schema to v3. Classification
uses section- and gap-bounded ten-second evidence. Direct Rust stationarity always
wins, sustained descent produces Downhill, and motorized evidence requires more
than speed alone (a sustained fast climb or unusually smooth fast IMU motion).
Short motorized islands under twelve seconds degrade to Transit. States are visual
diagnostics only: they do not yet alter metrics, exports, auto-pause or segment
eligibility, and no backend change is required.

Initial labelled-recording checks used temporary copies of three existing OnePlus
rides without modifying the phone originals. `udzo 1` separated roughly 178 s
Downhill and 277 s Still; `in bus` found roughly 260 s LikelyMotorized alongside
Transit/Still; `Kojoring` retained roughly 835 s Downhill and produced no
LikelyMotorized after the twelve-second guard removed a seven-second false island.
These are calibration evidence, not golden labels.

Activity Detail keeps GPS/Fusion/Compare but renders the finalized track
semantically: thick orange Downhill, thinner secondary Transit, cyan dashed
`Transport?`, muted dotted Unknown and one duration-scaled ring per Still run.
Fusion sample dots inherit the state color at close zoom, while raw accuracy dots
remain visible above fusion in Compare and stop rings remain legible above their
stationary cloud. A compact legend explains both color and pattern. Camera bounds
use finalized geometry in Compare and accepted `<=20 m` fixes in GPS mode. All
geometry splits at manual pause sections and gaps over three seconds; state changes
share exactly one boundary vertex so colored lines have no holes. The live recorder
honestly shows only Moving/Still, adds aggregated stop rings and starts a new
unconnected section after Resume.

To reduce long-stop writer pressure without weakening live evidence, sensor
acquisition remains 200 Hz, Rust live fusion remains 50 Hz and GPS remains
high-accuracy at approximately 1 Hz, while only persisted stationary IMU is reduced
to a replay-safe 20 Hz. A process-local two-second full-rate pre-roll flushes before
motion, Pause and Finish. Epoch-aligned buckets plus a low-density guard prevent
sensor jitter from aliasing a near-20 Hz source below Rust's 12-sample/700 ms
stationary requirement. The bounded trade-off is explicit: a hard kill while Still
can lose that final two-second in-memory stationary window in addition to the
writer's normal tail.

The delayed pre-roll exposed and fixed two recovery/consistency bugs. Recovery now
uses true minimum/maximum timestamps rather than physical gzip row order, so a late
older IMU row cannot move the resumable end time backward. Manual Resume now resets
IMU timing, orientation/motion windows and GPS motion hold before the first resumed
sample in both Android live capture and timestamp-ordered Rust replay; an event tied
with IMU/GPS wins the tie, and the next GPS still authoritatively reseats horizontal
state.

Rust passes 74 unit tests, two fixtures, formatting and strict clippy. Android's 57
recording tests pass, Activity Detail unit tests and lint pass, and the live-map
module compiles. The combined all-module Gradle command and final OnePlus install
could not be repeated after the host's Codex execution/approval quota was exhausted;
this is an environment limitation rather than a failing check. A physical
long-stationary/long-ride validation of the adaptive persistence policy remains open.

## 2026-07-28 — Activity-state device verification and minSdk-safe UniFFI cleanup

Completed the verification that was previously blocked by the host. All Android debug
unit tests, app-level debug lint and the full debug assembly pass. Lint exposed a
separate generated-binding issue: UniFFI's default Kotlin cleaner directly referenced
`java.lang.ref.Cleaner`, which is unavailable below API 33 despite Dhava's minSdk 26.
`android_cleaner = true` now generates the Android-aware implementation: API 34+ uses
`android.system.SystemCleaner`, while API 26–33 falls back to the already packaged JNA
cleaner. `androidx.annotation` is compile-only metadata for the generated API guard.

The rebuilt APK was installed with `install -r` on the API 36 OnePlus 9 Pro, preserving
all 37 visible local rides and raw recovery entries. Real `in bus` and `udzo 1`
artifacts were opened without changing their source data. The bus overview visibly
contains cyan dashed tentative transport, thinner transit and stop rings; `udzo 1`
shows its downhill path, raw GPS dots above fusion in Compare and aggregated stop
rings without the previous large fusion loops. GPS and Fusion modes expose only their
relevant legend, and overview zoom continues to hide synthetic fusion sample dots.
Process-only logcat contained no crash or application exception after both replays.

The first physical screenshot also caught a real layout defect: the 190 dp state card
truncated `Transport?` and `Uncertain`. The overlay is now 220 dp wide, line samples
are more compact and the final uncertain row can use the full width. The corrected
legend was rebuilt, reinstalled and visually rechecked on both recordings. A runtime
smoke test on API 26–33 remains useful specifically for UniFFI's JNA fallback; the
current OnePlus exercises the API 34+ cleaner branch.

## 2026-07-28 — Activity map legends use progressive disclosure

The always-visible track-state and GPS-accuracy cards no longer cover the map. Activity
Detail now starts with one right-aligned 48 dp outlined information control below
GPS/Fusion/Compare. The first physical draft labeled it `KEY`, but that read as
unnecessary jargon; the final standard icon keeps the same touch target and explains
its purpose through Show/Hide map legend semantics. It opens a single anchored popup
with no scrim; tapping outside or pressing Back dismisses it. GPS exposes only its
accuracy scale, Fusion only its state key and Compare combines both sections with one
divider. Selecting another track mode also returns the key to its collapsed state.

The state and accuracy drawings were separated from their old Surface wrappers so the
popup is one strong surface rather than nested cards. The GPS key no longer has a
fixed 72 dp height, allowing larger text to determine its own height. Existing detailed
TalkBack descriptions remain on each section, while the control reports Show/Hide and
Collapsed/Expanded semantics. A pure selector test covers all three modes, ordering
and missing-data behavior without adding a Compose test stack.

Activity unit tests and feature lint pass, as do app debug lint and the full debug
assembly. The APK was installed with data preservation on the physical 360 dp OnePlus.
Collapsed Compare, expanded Compare, GPS-only, Fusion-only, outside-tap dismissal and
Back dismissal were visually checked on `udzo 1`; labels remain readable and the map
is unobstructed by default.

## 2026-07-28 — Local draft segments: Rust gates, incremental matching, three screens

Segments are unfrozen as a fully local feature; no backend is involved. `fusion-core`
gained `segment.rs` with `SEGMENT_MATCH_VERSION = "gates-0.1"` and four exported
functions: `propose_segment`, `build_segment`, `segment_search_bounds` and
`match_segment`. A segment is authored from one ride as a draft (`trusted = false`):
its centerline is that ride's finalized sub-track, so it times runs but is not
authoritative geometry and never corrects GPS.

The matcher deliberately does not trust gate crossings alone. Both gates are directed
against the local centerline tangent, and a candidate additionally has to stay inside
the corridor, keep monotone forward progress (bounded backtracking) and cover the
segment by binned centerline visitation. Without those checks a start and a finish line
are both crossed by any parallel trail or by a straight shortcut between them. Gate
width and corridor come from the source ride's own p90 accuracy (`2×` half-width,
`3×` corridor, clamped 10–30 m and 15–40 m) rather than a fixed number, which would
either miss a crossing at 15 m error or swallow a neighboring trail.

Timing runs on the canonical 5 Hz finalized track, never on ~1 Hz raw fixes, and every
result carries a derived margin: `accuracy / speed` per gate, combined as a root sum of
squares, bounded at 10 s and never rounded down to a fake zero. Nothing is silently
dropped: rejected gate pairs surface as `NoFinish`, `PausedInside`, `GapInside`,
`OffCorridor`, `Backtracked` or `Incomplete` with a human-readable detail, and countable
runs can carry `DefiningRide`, `LowGpsQuality`, `LikelyMotorized` or `HighUncertainty`.
Consistent with the ride-state decision, tentative motorized evidence marks a run
uncertain instead of deleting it.

Android persistence separates authored input from derived cache. `segments/*.segment.json`
is rider-authored and never auto-rebuilt; `segment-results/*.results.json.gz` is keyed by
canonical algorithm version, match version, geometry version and each ride's raw
fingerprint, so an algorithm upgrade is a cache invalidation. A segment survives deletion
of the ride that authored it because the geometry is copied into the segment file.
Matching is incremental — after one new ride exactly that ride is matched.

The first device run exposed a real cost problem: the prefilter originally needed the
canonical artifact to know a ride's bounds, so authoring one segment built all 37
artifacts and took roughly two minutes. Bounds now come from raw GPS lines only
(cheap substring-filtered pass, conservative superset of finalized geometry) and are
cached per fingerprint in `segment-results/track-bounds.json`; the finalized track is
built only for rides whose GPS hull can actually touch the segment. The loading state
also says `Matching your rides…` instead of showing a bare spinner.

UI: `:feature:segments` is now a real module wired into navigation with a Segments tab.
Activity Detail's overflow gained `Create segment`, enabled only once the finalized
track exists. The editor selects start/finish as track indexes (not free map points),
defaults to Rust's longest continuous `Downhill` run and shows Rust's own verdict on the
selection — length, drop, this pass, gate/corridor widths, or the rejection message.
`:core:map` gained a shared `rememberDhavaMapView` (TrackMap's private copy was removed)
and a `SegmentMap` that draws pause-split ride context under the segment line with
start/finish markers.

Rust passes 91 unit tests, formatting and strict clippy; new Kotlin tests cover result
formatting (`2:31.4 ± 0.8 s`), GPS bounds, bbox intersection and best/latest selection.
App debug lint, the full debug assembly and all module unit tests pass. On the physical
OnePlus (37 rides preserved) the whole flow was exercised: Create segment on
`Down by the road`, proposal 2.41 km / −149 m, save, match, and a segment detail showing
`3:23.6 ± 1.3 s` with `DEFINES SEGMENT` and the draft notice. After the raw-bounds
rebuild the segment opened from cache without a matching pass, the stacked time/margin
layout no longer truncates at 360 dp, and the All-runs row renders its own margin and
flags; process logcat contained no exception. Open items: a second recorded run of the
same trail to see an independent (non-defining) attempt, and field calibration of the
gate, corridor, backtracking and coverage thresholds.

## 2026-07-29 — Segment review: stable editing, elevation and safer matching

Reviewed the complete local-segments vertical before committing it. The separation
between authored draft geometry, derived result caches and Rust-owned matching is
sound, but the review found several correctness and usability gaps.

The editor no longer fits the whole ride every time the range changes. Geometry and
camera updates are separate, so a rider can zoom and pan to a gate and keep that view
while editing. The range slider begins as the coarse full-ride overview; 800 ms after
a completed drag it expands the selected interval across almost its full width, with a
small grab area outside both handles. `Show full ride` is the explicit way back.
The editor is now map-led: a standard bottom sheet leaves the map full-screen, keeps
the selection slider visible in its collapsed state, and reveals fine controls,
metrics, explanation, name and save action when expanded.
Start/Finish selection plus minus/plus controls still move the active gate by one
canonical 5 Hz point. At the intended 15–20 km/h authoring speed that is commonly close
to one meter, but it is not a speed-independent one-meter primitive. True fixed-distance
editing would require fractional/interpolated positions and a new geometry version
rather than silently pretending that every sample interval is one meter.

Rust now authors accumulated climb, accumulated descent and a distance-based elevation
profile from the selected canonical geometry. Climb/descent reuse the canonical 2 m
hysteresis and the persisted profile is bounded to 192 points. Segment detail renders
the profile offline with climb, descent and endpoint altitude, while the editor and
list expose climb alongside descent. Existing segment JSON remains compatible through
defaults; old drafts intentionally show no invented profile until they are recreated.

Matching now retains the first start crossing while inside an attempt and does not let
an early incomplete finish crossing consume a later valid finish, which matters on
switchbacks. The result cache compares full ride identities instead of only their
count, and stale raw-bounds entries are pruned after ride deletion. Because those rules
change cached outcomes, `SEGMENT_MATCH_VERSION` advanced to `gates-0.2`.

Rust passes 91 unit tests plus two fixture tests, formatting and strict clippy. Targeted
Android recording/segments/activity tests, feature and app lint, and the full debug
assembly pass. The APK was installed with data preservation on the physical OnePlus;
the existing authored segment and all rides remained readable. On its 360 dp viewport,
the collapsed editor sheet leaves most of the map visible while retaining both range
handles; the expanded sheet scrolls the complete form, and range focus plus `Show full
ride` were exercised without changing the map camera. A temporary 12.59 km selection
visually verified the persisted elevation chart and both directions of elevation
(+186 m / −861 m); that temporary segment and its cache were then deleted through the
normal UI, leaving the original 2.41 km segment as the only authored segment. Remaining
pre-trust work is field-calibrating gates/corridor/coverage on independent runs and
adding an explicit raw-GPS sample-density component to timing uncertainty before
leaderboards.

## 2026-07-29 — Hold-to-precision segment trimming

The focused slider's text-heavy `Show full ride` action is now the standard
zoom-out-map icon with a `Show full ride` accessibility description. It returns only
the slider domain to the complete recording and does not disturb the rider's map
camera.

Both range handles now expose a real precision gesture. Holding a handle for 700 ms
activates Android long-press haptic feedback, adds a visible halo and wider active
handle, selects the corresponding Start/Finish control, and changes the status to
`Precision · 5× slower`. Movement after activation is integrated at 20% of the raw
finger delta and anchors on the first drag event, so entering precision does not jump
the gate. Releasing or cancelling returns to the normal control. The existing focused
domain and one-point buttons remain complementary: domain focus provides roughly
point-level screen resolution, hold precision handles shaky one-handed movement, and
buttons provide deterministic final steps.

The scaling rule has a unit test. Segment tests, feature lint and the debug app
assembly pass. The APK was installed with data preservation on the physical OnePlus.
The full-range state, long-held Finish handle, haptic-triggered visual state and
zoom-out-map return were exercised; the map camera stayed fixed and no segment was
saved during the interaction test.

## 2026-07-29 — Reversible segment scale and 10× precision

The segment editor now starts focused on Rust's proposed selection instead of showing
the entire recording. This keeps both handles separated on long recordings containing
multiple descents. The single scale icon is reversible: zoom-out-map exposes the full
ride, while zoom-in-map restores a working window around the current selection without
changing the map camera. The collapsed sheet labels both states explicitly.

Hold precision now integrates 10% of finger movement and reports `10× slower`. The
separate Start/Finish chips and minus/plus point-step row were removed because the
handles now provide both coarse and precise adjustment. The reduced content also lets
the collapsed sheet shrink from 196 dp to 176 dp, exposing more map.

The next authoring increment is continuous gate placement on the existing canonical
polyline, implemented in Rust as geometry v2. A gate position should be an edge plus a
fraction between its endpoints; Rust should own interpolation, selected duration and
the persisted endpoint geometry. Uniformly resampling the same line was rejected
because it adds visual density but no location evidence.

Segment unit tests, feature lint and the debug app assembly pass. The APK was installed
with data preservation on the physical OnePlus. Initial selected range, full ride,
return to selected range and the haptic-triggered `Precision · 10× slower` state were
all exercised without saving a segment.

## 2026-07-29 — Continuous geometry v2 and map-aware gate editing

Segment endpoints are no longer quantized to canonical 5 Hz indexes. Rust now exports
`build_segment_continuous`: each selector is an edge index plus a fractional position,
and Rust interpolates its coordinate, timestamp, optional sensor fields and endpoint
elevation before building the definition. New drafts are geometry v2; the existing
integer builder and stored geometry v1 remain readable and matchable. Fractional
positions across a pause or recording gap are rejected. Two Rust tests cover exact
coordinate/time interpolation and pause-edge rejection.

The Compose range slider now retains floating-point positions throughout the gesture.
Its immediate marker preview interpolates only the display coordinate; Rust remains
authoritative for persisted geometry, metrics and timing. At map zoom 16 or below,
movement is unchanged. Above 16, sensitivity halves for every zoom level down to a 5%
floor; the existing long-hold precision multiplies it by another 10%. This makes a
manually zoomed road-level view progressively finer without inventing a discrete
"one point" step.

Camera ownership now follows the editing intent. The range icon fits either every ride
section or the current segment into the unobscured area above the sheet. While a handle
is engaged, its endpoint is tracked: a manual zoom is preserved, no camera action
occurs while the marker remains in the safe viewport, and the map pans at the same zoom
only when the marker reaches an edge or the sheet. Geometry rendering still never
bridges manual pause sections in the ride context.

The collapsed sheet shrank from 176 dp to 152 dp, so length, pass time, descent and
climb no longer peek into the map state; they remain available after expanding.

Rust passes 93 unit tests, two fixture tests, formatting and strict clippy. Android
segment/recording tests, map and segment lint, and the full debug assembly pass. The
generated UniFFI Kotlin and both Android native libraries were rebuilt. On the physical
OnePlus, initial segment fit, full-ride fit, reverse segment fit, high-zoom sensitivity,
endpoint following with preserved zoom and the clean collapsed sheet were exercised.
No segment was saved during validation, so the existing ride and authored segment data
were not changed.

## 2026-07-29 — Engineering skills repository setup

The repository now declares the shared engineering-skill configuration in the primary
`AGENTS.md`; `CLAUDE.md` remains a pointer to it. GitHub Issues is the request and PRD
tracker, while pull requests are not a triage request surface. The standard
`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`
vocabulary is recorded under `docs/agents/`.

Domain-document consumers use a single-context layout. The existing chronological
`docs/DECISIONS.md` remains authoritative, and focused ADRs may be added lazily under
`docs/adr/`; no empty `CONTEXT.md` or ADR directory was created during setup.

## 2026-07-30 — Map-led segment library and countable-only records

A product grilling session settled two things ahead of implementation, and
`CONTEXT.md` now carries the resolved vocabulary (`Published segment`,
`Segment overlap`, `Countable attempt`, `Uncertain attempt`).

First, segment identity: Dhava never merges or deduplicates segment definitions.
A local draft and a published segment covering the same trail are separate
identities and both are timed, because deciding that two lines are "the same
trail" is the trusted-centerline problem we deferred, and migrating attempts
between geometry versions would fabricate times that belong to different gates.
Overlap therefore constrains *publication*, decided by moderation later, and
never the timing engine. Recorded as `docs/adr/0001`.

Second, what counts. `bestAttempt()` used to fall back to the fastest uncertain
run when no clean run existed; it is gone. `personalRecord()` returns the
fastest countable attempt or nothing, and a segment with no countable run shows
`—` with the reason instead of a number the rider can never honestly beat.
`fastestUncountableAhead()` surfaces a faster non-counting run explicitly — a
list whose quickest row is not the PR reads as a bug unless the screen says why.
Countability stays one condition (`quality == GOOD`), since Rust already folds
weak GPS, wide margins and vehicle-like evidence into that verdict.

The Segments tab is now map-led: `:core:map` gained `SegmentLibraryMap`, which
draws every segment in one muted weight over a dark casing, highlights the tapped
one and hit-tests with a 22 dp box rather than the exact pixel. Muting uses the
brand hue, not the label colour — the first device build drew segments in
`onSurface` and they were indistinguishable from the road they follow. The camera
belongs to the rider: it is framed only on a first visit or on an explicit
action, is reported on idle into a process-scoped store so a tab switch does not
reframe it, and survives a round trip through Segment Detail together with the
selection. Tapping empty map clears the selection. `:core:map` also gained a
one-shot `currentLocationFix` for `My location` and declares
`ACCESS_FINE_LOCATION` itself instead of relying on a consumer to.

The list lives in a persistent sheet whose peek is the pinned header only:
a peek ending mid-card read as a clipped layout rather than an invitation to
scroll. The expanded sheet stops at 72% so the map and its top controls stay
visible. Selection is marked by a quiet outline; the first attempt filled the
card with `primaryContainer` and shouted over the map.

Android module unit tests, `:app:lintDebug`, `:feature:segments:lintDebug`,
`:core:map:lintDebug` and the debug assembly pass. Rust was not touched. On the
physical OnePlus with all rides preserved, the whole flow was exercised: library
fit on first visit, muted line, tap to select with start/finish markers and the
`SELECTED` peek showing `2.41 km · −149 m · PR 3:23.6`, expand, `Open` into a
detail whose panel now reads `PERSONAL RECORD 3:23.6 / ± 1.3 s`, back with camera
and selection intact, tap-to-clear, `My location` centring on the real fix, and
`Fit area` returning to the segment. Process logcat contained no exception.

Pre-existing and untouched today: `:core:recording:lintDebug` fails on
`RecordingService.kt:659` with `MissingPermission`, because that module also
calls the location API without declaring the permission in its own manifest.
That target is not part of the verified set today. (Closed in the next entry.)

Open items: a second recorded run of the same trail to see an independent
non-defining attempt, field calibration of gate/corridor/coverage thresholds,
and the next agreed step — on-ride segment detection, which needs a streaming
Rust matcher over an active segment set rather than a pass over saved tracks.

## 2026-07-30 — Segment trimming on the elevation profile

The grilling continued and settled the colour question: a segment's colour will
carry exactly one meaning, its difficulty grade on the scale riders already read
off trail signage, and selection stays encoded by weight and opacity rather than
by hue so "muted until tapped" survives. `CONTEXT.md` gained `Difficulty grade`
and `Candidate descent`, and `Segment overlap` was widened: it now also warns a
rider about to author a duplicate, which does not weaken `docs/adr/0001` — the
warning never merges anything and never touches timing. The colouring itself is
not implemented yet; the editor came first so two changes would not land in one
screen.

Two reported editor faults turned out to be one bad decision. Trimming used a
two-thumbed `RangeSlider`, and the "10× slower" precision mode felt frozen
because Material3 re-anchors the slider's internal offset to whatever value the
caller feeds back: the reported delta was therefore *already* scaled, and scaling
it again made movement 100× slower than the finger — 400× once map zoom joined
in. The thumbs also collapsed onto each other, since the minimum gap was a
thousandth of a track position, and coincident thumbs cannot be pulled apart.
Neither is fixable inside a slider, so the slider is gone.

Trimming now happens directly on the ride's own elevation profile, which for a
downhill-first app is the axis that answers the actual question — does this
selection go down. The gates are dragged on the chart, precision comes from
narrowing the chart's domain (pinch, or the focus/full toggle) instead of scaling
finger movement, the minimum gap is 25 m of *ridden* trail, and the two handles
sit at opposite ends of their vertical line so they stay individually grabbable
when they share an x. The profile is coloured by gradient sign, so a climb inside
the selection is visible, and it breaks at pauses and recording gaps instead of
drawing across them.

New Rust module `segment_editor.rs`, because all of this is geometry:
`ride_profile` (sampled elevation, windowed gradient, ridden distance that does
not accumulate across a pause, and the continuous track position of every
sample, so Kotlin maps chart pixels back to gates without doing geometry),
`propose_descents` (every candidate descent, longest first) and
`selection_overlap`. Candidate rule, as agreed: hard stops on a stop, a pause, a
recording gap or motorised evidence, but short non-descending links inside one
trail are bridged (≤ 8 s and ≤ 40 m) — strict splitting fragmented real trails
into pieces that each fell under the 200 m floor and made the candidate vanish
entirely, which is the worst failure available. Filters: ≥ 200 m, a real drop,
and climb ≤ 15% of the drop. Candidates that duplicate an existing segment are
drawn marked rather than hidden, because hiding them would tell the rider
nothing was found where a trail plainly is.

`continuous_selection` also had a real edge: a gate interpolated a hair short of
the next canonical sample rounds onto that sample's own timestamp, and the
strict-monotonicity check then rejected a geometrically fine selection. The
rider's endpoint is authoritative, so the duplicated inner sample is dropped
instead. The rejection message itself was leaking as `msg=…`; the editor now
reads the typed `SegmentException.InvalidSelection.msg` rather than the
binding's rendering of it.

One bug worth remembering: gate dragging silently did nothing because the
handler called `change.consume()` before reading `positionChange()`, which
reports zero once consumed. Diagnosed by logging the deltas on the device — the
gesture layer was fine all along, which a candidate tap had already proven.

Verified: 104 Rust tests, `cargo fmt`, `cargo clippy -D warnings`; the whole
Android `./gradlew test lintDebug` is green, which needed three pre-existing lint
failures fixed along the way — `:core:recording` and `:feature:record` now
declare the `ACCESS_FINE_LOCATION` they use, and `RecordingHealth`'s API-30
helper carries `@RequiresApi` for the guard that lives in its caller. On the
device with all 37 rides preserved: the editor opens with the longest candidate
selected, ten candidates in the ribbon with the existing segment's one outlined,
the duplicate warning naming it, both gates dragging, the full-ride toggle
showing the whole descent with flat stretches greyed, and the library and detail
screens unchanged. Process logcat contained no exception.

Open items unchanged, plus: difficulty grade and its colouring, and pinch-zoom of
the chart domain has only been exercised by unit tests — adb cannot inject two
pointers.

## 2026-07-30 — Dhava text fields, IME behaviour and sheet fling boundary

Four rider-reported faults, all in the same family: platform defaults doing the
wrong thing for this product.

The editor's loading spinner drew against the left edge. Its `Box` took height
from `weight(1f)` but never `fillMaxWidth()`, so centring had no horizontal room.
Segment Detail had the mirror of it — `fillMaxSize()` inside a column is measured
against the whole screen, not the space left under the header, so the box
overflowed and its centre sat below the visible middle. Both now take
`fillMaxWidth().weight(1f)`.

Inputs are no longer Material text fields. `DhavaTextField` in `:core:ui` is
built on `BasicTextField`, because the platform default brings a whole vocabulary
this product does not use: a label that animates into a notch in an outline, an
indicator line, and a container that reads as a web form control. A Dhava field
is one quiet filled surface with its label stated plainly above it, matching the
panels and metrics it sits between; focus is a 1.5 dp primary border. It replaced
every `OutlinedTextField` in the app — segment editor and rename, the save sheet,
and the activity edit and add-bike dialogs — so there is one input in the product
rather than seven.

The keyboard covered the name field because the window is edge-to-edge and
therefore is *not* resized for the IME; insets are dispatched instead. The
editor's scaffold and every affected dialog now apply `imePadding()` at their own
boundary, and the field brings itself into view on focus, so the fix holds
wherever the component is reused.

Bottom sheets collapsed when the rider scrolled back. Material hands a
scrollable's leftover *fling* velocity to the sheet, so one flick that reaches the
top of the content carries straight on into the sheet: the rider asked to scroll
back and the sheet closed. `rememberSheetFlingBoundary()` sits between the
scrollable and the sheet and swallows only that leftover velocity, so a
deliberate slow drag at the top still collapses the sheet and the handle still
moves it. Applied to the segment editor, the segment library and activity detail.

Also fixed: the empty-record copy read "None of the 1 timed runs counts yet".

Verified: `./gradlew test lintDebug` green, and on the device — the editor loader
centred, the new field styled and fully visible above the open keyboard with its
focus border, the rename dialog lifted above the keyboard, scroll-back inside the
expanded editor sheet no longer collapsing it, and the activity edit dialog
consistent with the same component. Process logcat contained no app exception.

Unplanned but valuable: the rider's own second segment, `Reservoir road`, is the
first real case of a segment with no countable run. It renders exactly as
designed — `—` for the record, "the one timed run does not count yet", and the
run itself listed with `NOT COUNTED`, `DEFINES SEGMENT` and `WIDE MARGIN`. That
path had only unit coverage until now.

## 2026-08-01 — Coolify private-alpha deployment prepared

The first hosted-backend seam is ready without pretending that the future shared
segment service already exists. `deploy/docker-compose.yml` now contains only the Go
API and a persistent PostGIS database. MinIO was removed because production raw
recordings stay on the phone, and the Rust worker was removed because it is still a
skeleton that exits rather than consumes a verification queue. The API is reachable
only through Coolify's proxy (`expose`, no host `ports`). Required Compose variables
fail closed before deployment instead of silently using development passwords.

The API image contains a pinned golang-migrate CLI and applies pending SQL before it
opens the HTTP listener. This replaced an initial one-shot migration service: Coolify's
`exclude_from_hc` extension handles that service correctly, but makes the same file
invalid to standard `docker compose`, violating the single-source deployment contract.
Startup migrations keep the file portable and make a failed schema upgrade prevent
readiness. A static `/healthcheck` binary drives container readiness through `/readyz`.

Private-alpha routes are guarded by `X-Dhava-Access-Key`, configured separately from
the existing per-installation Strava Bearer credential. Android adds the shared header
without overwriting `Authorization`; its value is supplied through the untracked
`dhavaApiAccessKey` Gradle property. Health/readiness and Strava's browser callback stay
public. This is deliberately only an owner-build perimeter: the key is recoverable
from the APK, so it is documented for rotation and replacement with user identity
before public distribution.

The legacy activity/raw/finish API is now explicitly opt-in through
`RAW_UPLOADS_ENABLED` and disabled by default and in production. The OpenAPI contract
records both the private-alpha header and this dev-only compatibility status. The
Coolify runbook documents GitHub App deployment, required secrets, domain routing,
first-deploy probes, database backups, owner APK configuration, and read-only MCP
registration; normal deploys remain GitHub-owned, with any future manual deploy token
kept separate and least-privilege.

Verified: backend `go vet`, all Go tests and build; recording unit tests and lint plus
the full Android debug assembly; standard Compose config; production API image build;
and an isolated API/PostGIS smoke stack. The clean database migrated to v4 and reported
ready, then an API restart printed `no change` and returned healthy again. The isolated
containers, network and volume were removed afterward.

Open items: create the Coolify GitHub App resource, choose its public API hostname,
enter secrets, configure a database backup, and perform the first live deployment.
Strava remains disabled until its application credentials and callback domain exist.
Coolify MCP can be registered after a team-scoped read token is created; no token or
production secret belongs in this repository or chat.

## 2026-08-01 — Coolify build context follows the repository project directory

The first live deployment exposed a Compose path assumption that the local command did
not reproduce. Coolify invokes Compose with `--project-directory` set to the cloned
repository root, so the API context `../backend` resolved outside the clone as
`/artifacts/backend`. The exact command shape reproduced locally as
`/Users/whekin/Projects/backend not found`.

The API build context is now repository-root-relative (`./backend`). Local commands use
the same explicit project directory, and `deploy/check-compose.sh` locks that invariant
down without building an image. `AGENTS.md` and the deployment runbook now show the
portable invocation.

Verified both the fast context check and the original root-project Docker build. The
same build command that failed before now builds the API image successfully. No debug
instrumentation or throwaway containers remain. The deployment needs a new commit and
Coolify redeploy; no environment-variable change is required for this failure.

## 2026-08-02 — GPX-seeded segments and freely authored timing gates

Segments can now be started from an existing GPX without pretending the file is a
Dhava ride. The Segments screen opens Android's document picker, validates GPX with
external entities disabled, preserves the original file under `imported-traces/`, and
opens the normal editor over the most detailed continuous track section. Import is
bounded to 25 MB. An imported trace never creates an activity, attempt, PR or KOM;
saved segments are labelled `GPX seed` and remain untrusted drafts.

The segment model is geometry v3. `SegmentDefinition` now stores explicit start and
finish gate centers, matcher/search bounds use those centers, and `gates-0.3` forces
derived result recomputation. Old authored JSON remains compatible by deriving absent
anchors from the first and last centerline points. The `source_kind` persistence field
distinguishes ride-authored drafts from imported GPX seeds without confusing the Rust
defining-ride flag.

In the editor, the profile continues to trim the reference centerline, while either
gate marker can be grabbed directly on the map and moved to an arbitrary coordinate.
The hit target is larger than the visible marker, acquisition gives haptic feedback,
and the map retains its manually chosen zoom while tracking a marker only near the
usable viewport edge. Rust validates and persists the exact coordinate and continues
to derive gate direction from the local centerline tangent.

Verified: 103 `fusion-core` unit tests plus two forest-fixture tests; full Android
`test lintDebug`, including GPX namespace/elevation parsing and DOCTYPE rejection. A
debug APK assembled successfully. Installation on the available emulator was blocked
because an existing `com.dhava.app` has a different signing key; the connected device
was not erased. OnePlus was not visible over ADB during this verification.

Open items: exercise picker and map-gate dragging on the OnePlus; add explicit editing
of gates for an already saved segment; define the quality-weighted multi-pass
centerline refinement and the backend publication contract. Imported GPX currently
selects the continuous section with the most points when a file contains several
unrelated tracks.

## 2026-08-02 — Verified local backup and restore

Settings now owns a normal Storage Access Framework backup flow instead of routing
raw sensor data through Android's share targets. `Export backup` writes one versioned
ZIP containing recording/bike indexes, every raw and health file, authored segment
definitions and preserved GPX seeds. Recomputable canonical artifacts, segment-result
caches, map tiles, upload state and secrets stay out. `Restore backup` first shows the
bounded manifest, then verifies every payload in a private staging directory before a
loss-averse merge. Existing local data wins, missing data is added, and different raw
bytes under the same immutable recording name abort before installation.

The archive implementation uses a first-entry manifest, ZIP CRC plus SHA-256 per
payload, allowlisted flat paths, limits of 10,000 entries / 2 MB manifest / 50 GB data,
and a free-space reserve. Export rechecks each source while writing, catching a file
that changes between the manifest pass and archive pass. Unit coverage exercises a
complete round trip, corrupted stored payload and rejection of an ordinary ZIP.

Verified with the full Android `test lintDebug assembleDebug assembleRelease` run.
The release certificate exactly matched the installed OnePlus production certificate
(`f8dc85…bf45ca`), so the APK was installed in place with `adb install -r`; all three
rides (`dirt`, `road to dirt`, `Evening ride`) remained visible. The Settings layout
was inspected on the OnePlus and a real archive was created through `CreateDocument`:
90,481,425 bytes, three rides and eight payload files. Independent `unzip -t` and
manifest SHA-256 verification passed for every payload. The permanent copy is in
`~/Documents/Dhava Backups/2026-08-02 OnePlus/`; the phone keeps its Download copy.

Open item: exercise the confirmation UI against a clean disposable install when an
emulator with the current debug signing key is available. Restore extraction and
checksum failure are unit-tested; the production data was not destructively restored
over itself merely to test the button.
