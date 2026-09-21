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

## 2026-08-02 — Project quickstart and repeatable developer commands

The repository root README is now an accurate entry point instead of describing the
retired raw-upload/server-worker architecture. It explains the phone-as-source-of-truth
model, current module boundaries, prerequisites, artifact locations, safe in-place ADB
installation and the small set of commands needed for routine work. The backend and
fusion READMEs were corrected at the same seam; notably, `fusion-core` is documented as
the already-shipping on-device implementation rather than a future integration.

A tracked root `justfile` now provides debug APK and signed production APK/AAB builds,
explicit-device installs, Android/Go/Rust/Compose checks, the local API, and the full
API/PostGIS stack. Release tasks continue to fail closed when the real signing key is
absent. `docs/release-build.md` records API Gradle properties, both supported signing
configuration sources, output paths, certificate comparison and the data-preserving
`adb install -r` rule.

Production Compose remains proxy-only. A separate local override publishes the API on
`127.0.0.1:8080`; `just stack-up` includes it and supplies non-secret development
defaults, while Coolify continues to read only the production file. This avoids making
the hosted deployment more permissive merely to improve the local command.

Verified: `just --list` and recipe dry runs; rendered local Compose configuration and
Coolify build-context check; full `just check` (Android tests/lint/debug APK, Go
vet/tests/build, 103 Rust unit tests plus two forest fixtures, Cargo clippy); signed
release APK and signed release AAB through the documented `just` targets. Both release
artifacts were produced successfully; no APK was installed and no app data was touched.

## 2026-08-02 — Preliminary public brand screening

The current public name has a direct collision with an active GPS fitness-tracking
app in Google Play, so naming was screened before binding a Firebase project, OAuth
identity, store listing, and application ID to the brand. The research in
`docs/research/brand-name-screening.md` evaluates names against Dhava's actual
downhill-first positioning rather than generic activity tracking.

The initial shortlist is Gravtrace, Daghma, LoamSignal, and Gravra. Gravtrace is the
current product-fit recommendation; Daghma has the strongest Georgian story;
LoamSignal is the strongest deliberately MTB-only direction; Gravra is the most
flexible invented blank canvas. Exact indexed Play/App Store searches found no direct
matches for those four and their `.com` names returned no Verisign RDAP record at the
time checked. These are preliminary signals, not legal clearance or a reservation.

No rename decision was made and no code identifiers changed. Next step: select one or
two tonal directions, test spoken spelling with riders, then perform formal trademark
and handle checks before buying identity work or configuring production auth.

## 2026-08-02 — Gravora rejected after exact-name check

Gravora initially felt like a strong gravity-adjacent public brand, but an exact-name
check found three current products: a gravity-based Steam physics game, a Google Play
weight tracker, and an App Store finance app. The game is especially problematic
because it uses the same gravity meaning rather than merely sharing an unrelated
string.

Gravora was added to the rejected list and was not adopted as a project decision. No
code or identifiers were renamed. Future broad ideation may remain unresearched, but
any candidate must pass a quick exact store/web check before being recorded as the
working brand.

## 2026-08-02 — Nakvali selected as the working public brand

Nakvali is now the working customer-facing name. Georgian `ნაკვალი` is a trace,
footprint, or track left behind, directly matching a recorded ride line, a multi-ride
reference line and the history a rider leaves on a trail. Preliminary exact web and
indexed store searches found no product collision, and `nakvali.com` had no Verisign
RDAP record at check time. Native-speaker tone and formal trademark clearance remain
open before a public launch.

The launcher label, visible Android copy, default exported ride names and GPX creator
now say Nakvali. Internal source symbols, repository paths, backup filenames/schema,
API headers, deep links and deployment identifiers remain `dhava` for compatibility.
Most importantly, Android retains `com.dhava.app` and its existing release signing
lineage so updates preserve the OnePlus recordings.

`docs/firebase-auth-setup.md` records the console handoff for Google sign-in, including
the exact debug/release SHA-1 and SHA-256 fingerprints verified by Gradle. Firebase
will register `com.dhava.app`; the console display identity will use Nakvali. No
Firebase dependency or secret was added yet. The next implementation step begins only
after the project is created, Google is enabled, and the refreshed
`google-services.json` plus securely stored backend service-account credentials are
available.

Verified the full Android `test lintDebug assembleDebug` run. The resulting APK still
has package `com.dhava.app` and now reports application label `Nakvali` through `aapt`.
The OnePlus was connected, but no production APK was installed for this naming/docs
step and its local recordings were not touched.

## 2026-08-02 — Full technical rename to Nakvali

The private-alpha compatibility choice above was superseded after the rider created
and verified a local backup. Nakvali is now both the product name and the active
technical identity: Android uses application ID `com.nakvali.app` and package root
`com.nakvali`; source directories, Compose theme/map/backup symbols, Gradle
properties, the UniFFI Kotlin package, Go module imports, API headers, Strava deep
links, GPX/backup names, deployment/database defaults, OpenAPI, scripts and the
repository-local UI skill were renamed consistently.

The existing release signing certificate is deliberately reused. A key alias and
keystore filename are private signing implementation details and do not need a
cosmetic rename. The new application ID installs alongside the retired prototype,
so the old installation remains the safety copy until its format-v1 archive has been
restored and checked in Nakvali. Backup v1 contains no brand or Android package marker;
its manifest and durable payload paths are unchanged, making the existing archive
compatible without conversion.

Regenerated both Android native libraries and Kotlin UniFFI bindings from Rust after
changing the binding package. Verified a clean Android `test lintDebug assembleDebug`
run, including the renamed backup tests and lint; `aapt` reports package
`com.nakvali.app` and label `Nakvali`, and the built dex contains none of the retired
package/header/deep-link identifiers. Verified Go vet/tests/build, 103 Rust unit tests
plus two forest fixtures, Cargo clippy with warnings denied, and the rendered local
Compose configuration under project name `nakvali`.

Installed the debug APK on OnePlus `49b5d08f` as `com.nakvali.app` and launched it
successfully. Android reports both the new and retired packages installed; no old app
data was cleared or uninstalled. External follow-up remains: rename the GitHub
repository/remote, point Coolify at it and redeploy with Nakvali's API header,
database/user and `nakvali://strava/connected` redirect, choose the final API domain,
and finish Firebase Google-provider/SHA setup before downloading a refreshed config
and implementing sign-in. The first `google-services.json` already targets
`com.nakvali.app` but contains no OAuth clients, so it is intentionally not committed.

## 2026-08-02 — Firebase Android foundation configured

After Google Authentication and both signing-certificate fingerprints were configured,
the refreshed `android/app/google-services.json` was validated for
`com.nakvali.app`. It contains both Android OAuth clients and one Web OAuth client, so
the server client resource required by Credential Manager can be generated. The file
is committed as public Firebase project configuration; no service-account credential
or OAuth client secret is present.

Added Google Services Gradle plugin 4.5.0, Firebase BoM 34.17.0, the main
`firebase-auth` module, Credential Manager, its Play Services bridge and the Google ID
library through the version catalog. Analytics was intentionally omitted: it is not
required for authentication and deserves a separate product/privacy decision.

Verified `processDebugGoogleServices` and `processReleaseGoogleServices`; each produced
exactly one `google_app_id` and one `default_web_client_id` resource without logging
their values. Full Android tests, debug lint, debug APK and signed release APK all
passed. Actual sign-in UI/session handling and Firebase ID-token verification in the Go
API remain the next implementation step.

The resulting debug APK was installed as an in-place update to `com.nakvali.app` on
OnePlus `49b5d08f`; the process launched and remained alive with no Firebase startup
or Android fatal exception in the captured log. The retired prototype package and its
data were not touched.

## 2026-08-02 — Google account sign-in and verified `/me` identity

Implemented the first complete Nakvali account path. The new Profile destination uses
Android Credential Manager to choose a Google account, exchanges the Google ID token
through Firebase Authentication, restores Firebase sessions, signs out cleanly and
shows explicit loading, signing-in, synced, local-only and retryable-error states.
Raw recordings and local segments remain visibly account-independent. A fresh Firebase
ID token is fetched only for API sync, never persisted by Nakvali, and one forced token
refresh is attempted after a 401.

Added `GET /api/v1/me` to the OpenAPI contract and Go API. The endpoint keeps the
private-alpha access-key perimeter, additionally verifies the Firebase bearer token
with the official Admin SDK, and upserts a Postgres user by verified Firebase UID.
Migration 0005 adds the unique external UID plus selected profile fields while keeping
the existing UUID as the internal product identity. Raw tokens and arbitrary claims
are not logged or stored. Deployment now accepts the Firebase project ID and an ADC
service-account path; the private JSON remains a Coolify-mounted secret, outside Git.

Verified Go vet/tests/build and the full Android `test lintDebug assembleDebug` plus
signed `:app:assembleRelease` pass. A debug APK configured for the deployed backend
and local private-alpha key was installed
over `com.nakvali.app` on OnePlus without touching either app's recordings. The Profile
layout was inspected on-device; Credential Manager opened the system account chooser
for Nakvali, cancellation restored the signed-out UI, and no fatal Android exception
was observed. The shared Coolify Compose build context also passed `just deploy-check`.

Production sync remains intentionally unverified until Coolify mounts a Firebase
service-account JSON, sets `FIREBASE_PROJECT_ID=nakvali-app`, and redeploys. The later
identity migration must deliberately link the existing anonymous installation/Strava
credential to the signed-in user rather than silently changing those routes now.

## 2026-08-02 — Firebase Admin credentials use a runtime Compose secret

Completed the production credential mount that the authentication implementation had
previously only documented. Coolify now receives the complete service-account JSON as
the manually created `FIREBASE_SERVICE_ACCOUNT_JSON` locked multiline runtime
variable. Native Docker Compose mounts that value read-only into only the API container
at `/run/secrets/firebase-service-account.json`; the API process receives only the
`GOOGLE_APPLICATION_CREDENTIALS` path. The private key is not a build argument, image
layer, ordinary process environment variable or repository file.

The Compose validation script and local stack recipes provide an inert `{}` secret
while Firebase is disabled, preserving credential-free local development. Production
enables verification only when `FIREBASE_PROJECT_ID=nakvali-app` is set; malformed or
missing credentials then fail startup rather than silently accepting identities.
Recommended local storage is `~/.config/nakvali/firebase-service-account.json` with
mode `600`; repository ignore rules also reject common Firebase Admin key filenames as
defense in depth without ignoring the public Android `google-services.json`.

## 2026-08-02 — Local Firebase key uses a repository-local ignored slot

The owner preferred project-local secret storage for development. The earlier
home-directory recommendation is superseded by the explicit ignored path
`deploy/secrets/firebase-service-account.json`; only a `.gitkeep` is tracked. The local
stack helper automatically reads that file into the runtime Compose secret and enables
project `nakvali-app`, while absence of the file keeps Firebase disabled with the
inert local placeholder. Common Firebase Admin filenames and the whole secrets folder
remain ignored as defense in depth.

## 2026-08-02 — Firebase credential path no longer creates a Coolify variable

Removed `GOOGLE_APPLICATION_CREDENTIALS` from the Git Compose environment after an
accidental JSON value became locked under that name in Coolify. Backend configuration
now passes the fixed mount path explicitly to the Firebase Admin SDK, while Compose
still mounts only `FIREBASE_SERVICE_ACCOUNT_JSON` at that location. This permanently
keeps the path out of Coolify's managed variables and lets the stale value be deleted
after the updated Compose source is loaded. No credential content moved into the image
or process environment.

Coolify serialized the initially recommended multiline JSON into invalid raw `.env`
lines. Production guidance now uses `jq -c .` output as a locked literal runtime value
with Multiline disabled; the mounted file remains valid JSON because private-key line
breaks stay escaped as `\n`.

## 2026-08-02 — Clean Postgres volume after the database identity rename

The first Coolify volume retained a PostgreSQL cluster initialized before the final
Nakvali database/user/password configuration. Updating `POSTGRES_*` in Compose could
not mutate that existing cluster, so migration startup failed authentication. Because
the backend still contained no authoritative ride data and the owner approved dropping
the old database, the active Compose volume was advanced from `db-data` to
`db-data-v2`. Coolify will create and migrate a clean `nakvali` cluster with the
current URL-safe password; the old volume remains detached for explicit cleanup after
the new deployment is healthy. Phone recordings are unaffected.

## 2026-08-02 — Launcher icon refreshed around a downhill switchback

Replaced the placeholder mountain-and-dot launcher foreground with a compact trail
mark: a light switchback drops through a dirt-red disc on Nakvali's dark background.
The geometry stays inside the adaptive-icon safe zone and preserves its identity under
the OnePlus circular mask. Added a dedicated Android 13+ monochrome trail layer so
themed launchers no longer have to infer a silhouette from the full-color artwork.

Verified Android resource processing and `:app:assembleDebug`, installed the APK as an
in-place update on OnePlus `49b5d08f`, and inspected the icon in the app-drawer search
result with the device's monochrome icon theming active. The mark remained centered
and legible; existing app data was not removed.

## 2026-08-09 — Post-ride elevation instrument and richer ride data

Brought up the new Galaxy S25 (`SM-S931B`, Android 16) and confirmed its Bosch BMP5
continuous pressure sensor advertises 1–12.5 Hz with a 10,000-event FIFO. Nakvali's
existing 10 Hz barometer request is therefore appropriate; no device-specific sampling
override was added.

Activity Detail now reuses Rust's canonical, pause-aware `ride_profile` instead of
leaving that data visible only in the segment editor. The expanded sheet shows elapsed
and moving time separately, ascent as well as descent, low/high elevation, and a
dedicated detected-jumps row with total and longest airtime. GPS-only elevation keeps
the honest `Net climb` / `Net drop` terminology.

Added a compact interactive elevation/gradient profile. Horizontal scrubbing reports
distance, elevation, gradient, speed and classified activity state, while highlighting
the exact corresponding finalized-track sample on the map. The chart preserves manual
pause and recording-gap breaks and performs no ride geometry or fusion calculations in
Kotlin. Empty/legacy activities simply omit the profile instead of fabricating data.

Verified focused Activity tests, full debug lint and APK assembly, and a signed release
build. The release APK was installed in place on the Galaxy S25 without clearing app
data and launched without a fatal exception. The phone is intentionally still awaiting
the rider's foreground/background location and notification grants. Full visual
calibration of the graph and pressure-derived elevation awaits the first two real S25
shuttle recordings; state-duration/downhill-only summaries and live barometric fields
remain separate Rust work.

## 2026-08-09 — Recorder camera focus and stationary GPS-spike rejection

Reproduced the Record map opening at world scale even after its current-position marker
appeared. MapLibre could report an API gesture before Nakvali had applied the first
location camera update, which disabled follow mode prematurely. The listener now ignores
gesture cancellation until the first location has actually been focused. A focused unit
test covers both sides of that boundary. The recenter control also moved from the visual
middle of the map to the bottom-right working edge immediately above the state-dependent
recording panel; it no longer floats disconnected from either the rider or the controls.

Added a Rust regression matching the S25 stationary report: calm IMU, ±8 m accuracy,
false 2.8 m/s speed and one 12 m coordinate jump. Previously that single fix released
ZUPT and started a 2.5-second GPS-motion hold. Live fusion now keeps the stop anchor until
a second accepted fix makes minimum forward progress away from the same anchor. Alternating
±12 m jumps stay pinned, while a smooth sequence of two displaced fixes still releases
motion; the delayed bounded pass retains responsibility for restoring the real departure.
Raw inputs are unchanged and the canonical algorithm advances to `gps-bounded-0.6` so
existing rides can be safely recomputed.

Regenerated both committed Android native libraries. Verified 105 `fusion-core` unit
tests, both forest fixtures, strict Rust clippy, all Android unit tests, debug lint and a
signed release build. Installed the release in place on Galaxy S25 `RFCY904ZXQY` without
clearing its rides. On the emulator, a supplied Tbilisi fix opened directly at street
scale (`±5 m`); after a manual pan the recenter control appeared above the idle card in
the intended lower-right position.

## 2026-08-09 — Saved-activity affordance and account-card polish

Fixed an offline-state mismatch in Activities. Saving metadata in the default offline
mode deliberately leaves the lifecycle status as `RECORDED`, so the list must use
`savedAtMs == null`—not the status alone—to decide whether the unfinished `Save` action
is needed. Saved offline rides now show the existing `LOCAL` status; finalized but
genuinely unsaved/recovered recordings still retain `Save`. Added focused unit coverage
for unsaved, saved-local, queued and uploaded states.

Reworked the signed-in account card after inspecting it on the Galaxy S25. The trailing
`VERIFIED` pill had reduced the identity column enough to wrap both a normal two-word
name and `stanislavkalishin@gmail.com`. The card now groups the avatar, single-line name
and textual `Google account · Verified` state, then gives the email its own full-width,
single-line row with ellipsis as a last-resort boundary. The real account renders without
wrapping and with substantially clearer hierarchy.

Verified all Android unit tests and debug lint, produced a signed release APK, installed
it in place on S25 `RFCY904ZXQY`, and inspected both Activities and Profile against the
device's real local rides and signed-in account. No application data was cleared.

Settings is no longer a fifth top-level destination. Profile now owns a compact,
always-available Settings entry, while the recorder/backup/storage screen is pushed as
a nested destination with an explicit accessible back action and no bottom navigation.
This leaves the primary bar focused on Record, Activities, Segments and Profile and
gives each label more room. Verified the complete Profile → Settings → Profile flow and
screen hierarchy on the emulator; the same signed release was installed in place on the
S25, which was locked during the final interaction check.

## 2026-08-09 — First cross-screen visual consistency pass

Audited the four top-level destinations plus nested Settings as one product on the
427 dp emulator. The highest-impact rough edges were inconsistent empty states,
missing hierarchy on the empty segment library, duplicated Settings headings and an
oversized signed-out account pitch.

`NakvaliEmptyState` now owns a quiet primary-container icon treatment and an optional
action slot. Empty Activities and Segments place that state in the same strong panel
directly below a standard screen header. Activities can jump straight to Record;
Segments can choose a saved ride through Activities or import GPX as a secondary
action. Both top-level navigation transitions retain the existing tab state rules.

The signed-out Profile card now uses the same compact identity hierarchy as the
signed-in account instead of a centered onboarding poster, and avoids repeating its
offline guarantee below the Settings entry. Nested Settings uses one concise back
header and description rather than presenting both `Settings` and `Field kit` as
competing titles.

Verified the rendered dark-theme states, Profile → Settings → Profile, empty
Activities → Record and empty Segments → Activities on the emulator. Full Android
unit tests and `lintDebug` pass. Installed the signed release in place on Galaxy S25
`RFCY904ZXQY` without clearing local rides.

## 2026-08-09 — Rider profile and local bike garage

Reframed Profile from an authentication utility into the rider's durable home. Account
identity and backend sync now share one compact card, long real-world email addresses
stay on one line, and Sign out is a quiet footer action below Settings with an explicit
reminder that local rides and bikes remain on the phone. Signed-out riders keep the same
screen structure instead of losing access to local features.

Added the existing offline bike store to Profile as a first garage UI. Riders can add a
bike, see its type, and select the active bike for the next save; the active state is
both colored and labelled. The repository now persists explicit bike selection through
the existing `last_used_id`, makes a newly added Profile bike active atomically, and
repairs missing or stale legacy selections by choosing the first stored bike. No server
model, fake profile data or migration was introduced.

Verified empty and populated garage states plus the add-bike dialog on the emulator.
Inspected the signed-in account, real Capra bike, Settings and Sign out placement on
Galaxy S25 `RFCY904ZXQY`. Full Android unit tests and `lintDebug` pass; the signed release
APK was installed in place on both devices without clearing local rides.

Continued the consistency pass through the real populated Activities state. Bare rows
and dividers became bounded ride cards; internal raw-file sizes were removed from the
primary scan path. A finalized recording without metadata is now named `Unfinished ride`
with a direct `Finish` action, while date, duration and bike are separated into readable
metadata lines. Both recovered and ordinarily unfinished recordings contribute to the
header's attention count. Rechecked the two real S25 entries and installed the final
signed release in place without clearing data.

## 2026-08-09 — Activity summary hierarchy and hidden developer tools

Reworked the saved Activity sheet around progressive disclosure. Distance is now the
dominant result, moving time and descent form the secondary summary, and average/max
speed share one bounded comparison surface. Total duration, ascent and elevation range
remain available under `More ride details` instead of competing in the old nine-cell
grid. Jumps, elevation profile and recording quality keep their existing dedicated
sections. The expanded details use two columns plus a full-width elevation row so real
four-digit mountain elevations remain legible on a phone.

Made the canonical fused track the normal Activity presentation and moved raw track
comparison behind an app-wide developer mode. Seven taps on the real build number in
Settings unlock Developer tools; GPS/Fusion/Compare, live sensor diagnostics and the
keep-awake field option are only exposed while it is enabled. Fusion remains the
default even in developer mode. Recorder preferences now use one shared key contract
instead of duplicating string constants across app and feature modules.

Verified both modes on Galaxy S25 `RFCY904ZXQY`: the normal Activity has no diagnostic
selector, the unlocked mode restores all three choices with Fusion selected, and the
new collapsed and expanded metric layouts render without truncation. Full Android unit
tests and `lintDebug` pass. Built and installed the signed release in place without
clearing the two local rides or account data.

## 2026-08-09 — Trail-green product palette

Replaced the dirt-orange brand accent with a vivid fern/trail green across the shared
Material scheme, canonical tracks, controls, charts, navigation state and adaptive
launcher foreground. The warm carbon/soil surfaces remain, preserving Nakvali's field
instrument character while removing the strongest visual overlap with Strava. Both
dark and light primary/on-primary pairs meet strong text contrast; dynamic color stays
disabled by default.

Kept the base map deliberately neutral and earthy instead of deriving major-road color
from the new green primary container, so the canonical track stays visible over city
and forest geometry. Accurate GPS points now use a cool mint before transitioning
through yellow, gold and rejected red; this preserves clear point/line separation in
Compare after Fusion became green. Updated the durable design and map-presentation
decisions to match the new identity.

Built a signed release, installed it in place on Galaxy S25 `RFCY904ZXQY`, and inspected
the Activity summary plus developer Compare with real local data. Green actions remain
high-contrast, mint GPS fixes remain readable above the track, and no app data was
cleared. Developer mode was disabled again after verification.

## 2026-08-09 — Cross-ride downhill candidate map

Moved the first step of segment authoring out of an individual Activity and into the
segment library. `Find descents` now scans every finalized local ride, asks Rust for all
downhill spans, filters weak GPS seeds and already-covered selections, groups repeated
same-direction passes, and presents the remaining places as selectable lines over one
map with a persistent details sheet. A selected proposal opens the existing editor at
its exact continuous start and finish positions, so the rider still reviews and can
fine-tune both gates before anything is saved. GPX import remains available beside the
new discovery entry point.

Changed Rust descent proposal continuity so a real stationary trail stop can remain
inside one candidate when canonical displacement stays within 12 m. Manual pause,
recording gaps, motorized evidence and a `STILL` interval that actually travels still
split candidates. Added regression coverage for both a held stop and a moving interval
mislabelled as still, then regenerated the committed Android UniFFI libraries.

Validated the complete flow on Galaxy S25 using the app's real backup/restore path. A
verified 90 MB format-v1 archive from OnePlus merged three raw rides without replacing
the S25's existing local data. The first discovery pass produced 20 candidates across
four usable finished rides; leading seeds reported GPS accuracy p90 of 3 m and rendered
as separate selectable trails on the map. Opening a 1.19 km / −11.0% candidate retained
that exact continuous range in the editor instead of falling back to the source ride's
longest descent. Android unit/lint/assemble, all 106 Rust unit tests, forest fixtures and
Clippy pass; the signed release with regenerated arm64/x86_64 libraries is installed on
the S25.

Field review made the original 200 m proposal floor too permissive: the restored rides
produced 20 separate candidates, including 200–250 m fragments whose timing would be
dominated by gate placement. Raised the Rust-owned discovery minimum to 300 m; this is
only a proposal filter and does not mutate raw rides or previously authored segments.

## 2026-08-09 — Planned GoPro telemetry enrichment

Added a future roadmap item for enriching a phone-recorded ride from overlapping GoPro
video metadata. The intended first experiment is GPS-only: match video by time, extract
GPMF locally, measure clock offset/drift and combine it as a separately quality-scored
observation rather than averaging two tracks blindly. Camera IMU is a later experiment,
conditional on demonstrating an actual improvement.

The likely implementation seam is a shared Rust parser/enrichment module. Android can
read a user-selected GoPro folder from an attached SD card; a later Chromium/PWA tool
can reuse the parser through WebAssembly on macOS, Windows and Linux. Browser folder
access remains explicit and cannot silently discover an inserted card, so a native
desktop shell is deferred unless automatic import becomes important. Videos remain
local, while imported telemetry retains source and algorithm provenance alongside the
immutable phone recording for reversible recomputation.

## 2026-08-09 — Trail references and alternate-line hypothesis

Recorded a future product hypothesis without committing it to the current segment
domain model. A segment may expose attributed links to Trailforks or another trail
resource through provider/id/URL references, giving riders richer context without
turning Nakvali into a duplicate trail catalog.

Main and chicken lines may eventually appear as one rider-facing family while remaining
separate timed variants with their own leaderboards. This is not a combo: combo segments
join sequential trails, whereas line variants are mutually exclusive paths between a
shared entry and exit. Rust could classify the line from trusted branch geometry, but
only when the recording and its uncertainty distinguish the corridors; otherwise the
attempt must remain explicitly ambiguous rather than being assigned to the faster or
more prestigious line. Difficulty and feature annotations remain optional,
source-attributed ideas pending real use cases and a durable trail-versus-segment model.

## 2026-08-09 — Minimal segment trail context

Implemented the smallest useful part of the trail-context idea without expanding the
Rust timing model. `StoredSegment` now carries an optional difficulty grade and a
backward-compatible list of attributed external web links. Creation and `Edit details`
support unrated/green/blue/red/black/double-black plus one Trailforks or other public
trail URL. URLs are normalized to HTTPS when the scheme is omitted, restricted to web
schemes and labelled from their provider; metadata edits preserve geometry and cached
results.

The segment library draws graded lines in their trail colour and pairs every card colour
with a text label. A saved segment detail repeats that colour on the map and in a compact
Trail details panel, where the attributed source opens externally. Unrated and legacy
segments retain the primary Nakvali green and deserialize with empty metadata. Main and
chicken line families remain deliberately deferred rather than being flattened into a
boolean feature flag.

Added persistence compatibility, round-trip and URL-normalization tests. All Android
unit tests, segment/app debug lint and debug assembly pass. The signed release was
installed over the existing Galaxy S25 app without clearing its restored rides; a real
2.39 km candidate was temporarily saved as a blue segment with a Trailforks link for
visual validation. Removing that temporary segment through the normal flow is pending
the phone being unlocked after the final release installation.

## 2026-08-09 — Live ride totals on the recorder

The recording screen showed speed, ride time and the pause control, so the rider had no
way to read the ride itself before Finish. Rust now accumulates distance and descent
inside live fusion and reports both on every `LiveSnapshot`; Kotlin only carries the
numbers. Descent deliberately accumulates from the accepted GPS altitude series rather
than the EKF vertical state — the vertical channel is slow by design and reported barely
a third of a synthetic 290 m drop while the rider would still be on the trail. The
accumulators are the canonical ones (1 m anchor filter, 2 m hysteresis over a 5-sample
median), so the live number and the finalized activity measure the same quantity; live
stays provisional because its filter is causal and canonical runs the bounded post-pass.

Totals survive a manual pause without counting the transit across it: the anchors and
the median window are cleared on a section restart while the totals themselves are kept.
Continuing an interrupted ride restores them from the raw file through a new
`live_totals_from_recording`, which reuses the analysis accumulators without the IMU
airtime pass; seeding is additive so metres ridden while the file is being read are not
discarded. Ride time already survived a process restart, so the totals beside it had to.

The recording panel now shows distance and descent under speed and ride time, and the
foreground notification replaces its reassurance line with `8.2 km · −612 m` as soon as
the ride has moved — a rider who stops can read the ride from the shade without
unlocking. All 112 Rust tests, Clippy, Android unit tests, `lintDebug` and debug assembly
pass; the committed arm64/x86_64 libraries were regenerated. Field verification on the
Galaxy S25 is pending.

Live segment timings — the run you just rode, its delta and whether it was a PR, on the
screen and in the notification — remain open. `VISION.md` already carries the idea and
the local PR half needs no server, but `match_segment` consumes a finalized canonical
track, so live feedback needs a streaming gate matcher in Rust that reuses the same gate
geometry rather than a second timing implementation.

## 2026-08-10 — Live segment timing on the trail

Segment feedback now happens where it matters: on the trail, not at upload time.
`fusion-core` gained `live_segment.rs`, a streaming tracker that arms every local
segment and times gate crossings against the live fused track. It is the live half of
canonical matching rather than a parallel one — gates, direction tolerance, corridor,
backtrack allowance and coverage binning are imported from `segment.rs`, and the shared
coverage bin rule was factored out so neither side can drift. A run ends on the same
evidence canonical rejects an attempt for: leaving the corridor, turning back, a manual
pause or a recording gap between the gates, or reaching the finish without covering the
segment.

Live results are provisional and say so. Live fusion is causal, canonical runs the
bounded post-pass, so nothing live is persisted as an attempt; the segment screens
re-match the finished ride and that result stands. The tracker consumes fused positions
rather than raw fixes, so the live clock, the live map and the canonical result describe
the same track.

Segments are armed at Start from their cached results, with no re-match — the rider does
not wait for one — and a cache written by an older algorithm, match or geometry version
arms with no record rather than a stale best. A run finished during the ride becomes the
time to beat for the next lap in the same ride, so a second run down the same trail is
compared against the first.

The recording panel shows the segment being ridden with a running clock, then the run
just finished with its delta and a record highlight, with the ride's earlier runs behind
a tap. The foreground notification puts the newest run on its collapsed line and moves
distance/descent to the expanded one, so a rider who stops reads the run from the shade.
Gate feedback is a direct vibration — one tick entering, two leaving, three for a record
— never an extra notification, and `Segment vibration` in Settings turns it off.

118 Rust tests including five new live-matching cases, Clippy, `cargo fmt`, Android unit
tests, `lintDebug` and debug assembly pass; the committed arm64/x86_64 libraries were
regenerated. Field verification on the Galaxy S25 is pending — in particular whether the
gate haptics are distinguishable through a jacket and whether provisional times match
the canonical ones on real repeated runs.

## 2026-08-10 — Battery: attention-scaled live work and transport power save

Audited what the recorder actually computes with the screen off. Three findings, all
fixed. The live track was rebuilt as a whole list on every GPS fix — up to 10 800
elements once per second, for a phone locked in a pocket — and is now a ring that is
only copied into an immutable snapshot while a screen is up. The state flow was pushed
four times a second to nobody; with no UI it now ticks once a second, which is what the
notification needs anyway. Live segment matching walked every armed segment's whole
centerline on every fix; a padded box per segment now dismisses the ones the rider is
nowhere near, and the padding covers the corridor and both gates so the fast path
cannot skip a possible crossing. A run in progress is never skipped.

Transport detection was failing in the middle of exactly the legs it exists for. The
post-ride classifier only accepted vehicle evidence above 25 km/h with a climb or
36 km/h with smooth IMU, and a shuttle on a switchback fire road reaches neither, so
laps came back as fragments of `LikelyMotorized` in a sea of `Transit`. Rate of climb is
now evidence by itself: 0.6 m/s sustained is beyond any rider — elite road climbing tops
out near 0.5 m/s and a mountain bike is well below — while a shuttle climbs at 1.5–4 m/s.
Motorized runs are also bridged across non-descending interruptions up to 90 s with
vehicle evidence required on both sides, so a traffic light or a flat kilometre no longer
splits one bus ride, while a descent between two lifts still ends the span.

Live fusion gained the same rule as a causal hint, and Android follows it into reduced
sampling: GPS to a 5 s balanced fix, IMU to 25 Hz, both by re-registering rather than by
gating writes — the accelerometer waking the CPU 200 times a second is the cost that
matters. Entering needs 45 s of vehicle-rate climb with smooth motion; a descent, trail
roughness, a 45 s stop or a manual pause leaves immediately. The state is shown as a
`Transport · saving power` pill on the recording panel and in the notification, never
silently. `Save power in transport` in Settings disables it for field tests.

126 Rust tests including eight new classifier and hint cases, Clippy, `cargo fmt`,
Android unit tests, `lintDebug` and debug assembly pass; committed arm64/x86_64
libraries regenerated.

Open: the thresholds are argued from physiology and synthetic tracks, not yet from this
rider's own data. The last ride has two climbs — a shuttle and a bus — and validating
0.6 m/s, the 90 s bridge and the live hint's enter/exit latency against that raw file is
the next step. The release build is not debuggable, so the file has to come out through
Settings → Backup rather than `run-as`.

## 2026-08-10 — Congestion, Android activity recognition, and map hierarchy

Extended the transport work after field feedback. The fixed 90-second bridge could not
cover congestion: a bus crawling and stopping for several minutes produces neither the
speed nor the rate of climb that identifies a vehicle, so a city leg still arrived as
fragments. A vehicle span now absorbs up to 15 minutes of interruption when the motion
stays vehicle-smooth and the gap is stop-and-go rather than one long stop. Waiting at the
bottom for the next shuttle remains STILL, and pushing the bike between two lifts is
never absorbed — both are covered by tests.

Added Android's own activity recognition as an input to the live power decision. It
answers the one question the ride cannot on flat ground, and it costs almost nothing
because it runs on the sensor hub. Rust owns the meaning: Android forwards the
transition, `LiveFusion` decides. Writing the first version exposed a real bug — after a
descent ended power saving, the platform's stale vehicle hint switched it straight back
on for the whole run. Our own evidence now vetoes entry as well as forcing exit. The
permission is optional; declining it leaves the recorder exactly as it was. Transitions
are also written as `activity:*` event lines for later analysis, and `proto/` documents
that classification must stay reproducible without them.

Reworked the activity map after a screenshot of a shuttle day: filled cream stop disks
larger than the trail, drawn at every traffic light, with transit and transport competing
with the descents for attention. Descents are now the loudest thing on the map, transit
is thin and dimmed, a likely vehicle is barely visible, and stops are small hollow rings
sized over a much narrower range. Stillness shorter than 15 s is no longer marked at all,
and stillness with vehicle evidence on both sides is treated as part of the transport
line rather than as a rider's stop.

132 Rust tests, Clippy, `cargo fmt`, Android unit tests, `lintDebug` and debug assembly
pass; committed arm64/x86_64 libraries regenerated.

Not done yet: ridden segments are not drawn on the activity map. The map has no data path
for "which segments were ridden in this recording" — `segmentResults` is per segment and
would have to be inverted per activity — and tapping a highlighted segment needs a
navigation route from Activity into Segment detail.

## 2026-08-10 — Ride statistics exclude transport

The activity summary counted the shuttle. Rust now computes `RideTotals` from the
finalized classified track with `LikelyMotorized` spans removed — distance, moving time,
ascent, descent, max and average speed — reusing the same accumulators as the
whole-recording analysis, with separate distance anchors for the ride and transport
streams so a lap and the shuttle after it never share one. A pair counts as transport
when either end is motorized, so boundaries are never credited to the rider; `STILL` and
`UNKNOWN` stay with the ride because a stop mid-lap is part of that lap.

The excluded part is reported rather than dropped: the activity screen shows
`Not counted · 12.4 km · 00:31:02 by transport` under the summary. `RideAnalysis` keeps
its whole-recording meaning for export and diagnostics. Canonical artifact schema is v4;
older artifacts rebuild, and until they do the screen falls back to the old numbers
instead of showing blanks.

133 Rust tests, Clippy, `cargo fmt`, Android unit tests, `lintDebug` and debug assembly
pass; committed arm64/x86_64 libraries regenerated.

## 2026-08-10 — A shuttle leg is one leg

Field feedback: a serpentine has dips and flat shelves, and every one of them was
splitting the transport span — worse, the dip was then credited to the rider as a
descent. The rule that a descent always breaks a vehicle span was wrong.

The bridge no longer asks whether a gap looks like a vehicle; it asks whether the gap
contains riding. Vehicle-smooth motion never does. Rough motion counts as riding only
when it also does something a walk cannot: give up 30 m of height, or hold a speed above
2.5 m/s for a meaningful share of the gap. The height test carries the weight, because a
slow technical descent is ridden at walking pace and must never be folded into a lift.

This follows the rider's own model: a shuttle leg is one leg. Nobody gets out mid-
transfer, rides down, and gets back in — at most they walk, to a gate or between vans.
Walking between two vehicle spans is therefore part of the transfer now, and the test
that used to assert the opposite was inverted deliberately. A new test guards the case
that actually matters: a 60 m descent ridden at 2 m/s between two lifts stays a ride.

The live hint learned the same distinction. A descent used to end power saving outright,
which made it flap all the way up a switchback road. Now a rough descent ends it at once
— that is a rider — while a smooth one has to give up 40 m from the highest point
recently reached before it counts as leaving the mountain.

136 Rust tests, Clippy, `cargo fmt`, Android unit tests and debug assembly pass;
committed arm64/x86_64 libraries regenerated. The signed release from before these two
fixes is on the Galaxy S25; it needs reinstalling to carry them.

## 2026-08-10 — Segment length floor raised to 300 m

Manual authoring accepted 50 m while discovery required 300 m — two floors for the same
question. Authoring now uses 300 m as well, and `propose_segment` with it, so the editor
cannot open on a default selection it would refuse to save.

The limit stays owned by Rust: the three build entry points take an optional minimum and
clamp it into `[40 m, 300 m]`. Developer mode passes the lower bound so gate behaviour —
entry and finish haptics, the live clock — can be validated on a stretch next to the
house rather than on a mountain; nothing else can lower it, and nothing can raise it.

Geometry fixtures in the tests are a couple of hundred metres and now pass an explicit
test floor, which is honest: they exercise gates and corridors, not the length rule. The
floor itself gained its own test, including that 200 m is rejected in production and
accepted with a developer floor.

137 Rust tests, Clippy, `cargo fmt`, Android unit tests, `lintDebug` and debug assembly
pass; committed arm64/x86_64 libraries regenerated.

## 2026-08-10 — Segment editor: dragged gates, precision, undo, stale candidates

Field feedback on the editor produced one real defect and three gaps.

Dragging a gate marker on the map moved the marker and left the segment behind. The map
line, the trimmer handles and the preview all key off positions along the track, while
`setGateCenter` only moved the authored gate centre, so nothing that keys off positions
recomputed. A dragged gate now projects onto the track through a new Rust
`nearest_track_position`, and the position follows the finger while the authored centre
still stays exactly where it was dropped — the two are deliberately independent, but they
are no longer allowed to disagree about which part of the ride is selected.

Precision was not lost by accident: the two-thumb range slider was replaced by the
profile trimmer, and both the map-zoom sensitivity and the long-press fine mode went with
it, with the domain pinch left as the only precision. All three are back and they
compose. The chart domain is the coarse control; the map's zoom now refines finger
travel again, because a rider zoomed in on a gate is working at that scale; grabbing a
handle while looking at the whole ride narrows the axis around it; and holding a handle
still before moving drops into a fifth-speed fine mode with a haptic. Scaling the finger
is safe in this instrument — the slider's habit of re-anchoring to the value fed back to
it, which squared any scaling, is exactly what the direct trimmer does not do.

Added an undo stack for gate placement, pushed when a handle is grabbed rather than when
it is released: the state at the start of a gesture is what the rider wants back, and a
drag emits hundreds of intermediate positions that must never each become a step. Bounded
to 20; the button only appears once there is something to take back.

Fixed the discovery screen offering a segment that had just been authored. `scan()` ran
only in `init`, so the covered-by-existing filter compared against a snapshot of the
segments taken before the new one existed. The scan now repeats whenever the local
segment list changes.

138 Rust tests, Clippy, `cargo fmt`, Android unit tests, `lintDebug` and assembly pass;
the signed release is installed on the S25 (pid 25393, clean logcat, data preserved).

## 2026-08-10 — Field feedback: developer floor, gate haptics, dismissable run card

First real segment authored and timed on a 300 m stretch. Three things came back.

The developer-mode floor was unreachable. `createSegment` passed the lowered 40 m limit,
but the editor's preview asked Rust with the production floor, so a short selection was
rejected before the save path could ever apply the lower one. The preview now uses the
same floor the save will — a preview that answers a different question than the action it
previews is worse than no preview.

Gate haptics read as notifications. They are now shaped rather than merely timed: paired
timings and amplitudes, with entry left as a single light tick so it cannot distract
mid-run, a firm double beat for a finish, and a rising three-beat ending in a long swell
for a record. Devices without amplitude control still get the rhythm.

The finished-run card could not be dismissed and sat on the map. A finished run is news,
not furniture: it can now be dismissed per run, leaving a one-line `N runs this ride`
button, and the next run announces itself again. A run in progress is never dismissable —
that card is a live clock.

Noted but not changed: an asphalt ride classified as transport. The rider called it fair.
It is the smooth-motion rule doing what it is designed to do, and tightening it needs
real data on both sides rather than a threshold nudge.

138 Rust tests, Android unit tests, `lintDebug` and assembly pass; signed release
installed on the S25.

## 2026-08-11 — Gate haptics with a rhythm, and a metal detector for the finish

Reworked the segment haptics from durations into shapes the rider can name. A run starts
with two revs — each hit at full amplitude and allowed to fall away — and ends with a
"ta-dam": a short upbeat, the long beat, and a fade. A record is the same shape earned
twice over. Devices without amplitude control still get the rhythm.

Added an approach countdown. Inside the last 150 m of a run the recorder ticks, and the
ticks crowd together as the gate comes up, so a sprint can be timed without looking at
the phone. Rust reports the distance left along the centreline on every accepted fix —
the same arclength the corridor test already computes, and what the rider actually still
has to ride rather than a straight line to the gate. Android carries that forward at the
current speed between fixes so the rhythm tightens smoothly instead of stepping once a
second, and the interval shortens with the square of closeness so the last metres tighten
sharply. The countdown stops the moment the run finishes or ends, and obeys the same
`Segment vibration` setting.

139 Rust tests, Clippy, `cargo fmt`, Android unit tests, `lintDebug` and assembly pass;
signed release installed on the S25 (pid 11879, clean logcat).

## 2026-08-11 — Editor gestures, honest profile scaling, and a recording sheet

Four things from using the editor for real.

Grabbing a gate collapsed the axis instantly. Auto-focus was firing on the grab, which
yanked the chart out from under a finger that had asked for nothing yet; it now belongs
to the deliberate hold, alongside the fine sensitivity. A plain grab leaves the view
exactly where the rider put it.

Handles sat on the screen edge, where the system's back gesture owns the strip, so
reaching for a gate left the editor. The chart is inset from both edges and claims the
remaining strip back with `systemGestureExclusion`. Dragging into the edge zone now pans
the axis instead of stopping: the handle rides along with it at just over half a visible
span per second, fast enough to cross a ride and slow enough to release on the metre the
rider wanted.

A smooth asphalt road with a gentle grade drew as a pump track. Both profile charts
scaled to whatever range the data contained, with a floor of one metre, so a few metres
of GPS and barometer noise were magnified to full height. The range is now held to a
25 m floor with the data centred inside it — gentle ground reads as gentle, and real
relief still fills the chart because it exceeds the floor.

The recorder moved from a fixed panel to a bottom sheet. The map is the instrument the
rider is actually reading and must never be traded away for the run list: the peek keeps
status, both metric rows and the controls, and pulling up reveals this ride's segment
runs over a map that stays visible. Camera padding follows the resting height.

Android unit tests, `lintDebug` and assembly pass; signed release installed on the S25
(pid 24071, clean logcat).

## 2026-08-11 — Precision is scale, not a slowed finger

Removed the fine-mode finger scaling added a day earlier. It contradicted the trimmer's
own design note — "precision is a property of the axis, never of a scaled finger delta" —
and the field showed exactly why: with the delta cut to a fifth and the axis narrowed at
the same moment, the gate felt stuck, and a large movement against a shrunken delta
arrived as a jump.

Holding a handle now closes in instead. The axis narrows to 60 m of trail around the
gate, and the map animates down to a gate close-up where a metre is a visible distance;
letting go returns the rider to the framing they had chosen, which the editor tracks
separately so its own close-up can never become the view it restores to. The finger stays
one to one with the axis throughout.

Gate dragging on the map is now a nudge tool: the marker follows only within 10 m of
where the drag began. Beyond that the rider means a different part of the ride, and that
belongs to the trimmer where the whole track is visible.

Android unit tests, `lintDebug` and assembly pass; signed release installed on the S25
(pid 26719, clean logcat).

## 2026-08-11 — Editor polish: held zooms, names with a shape, signage difficulty

A hold is one action with a beginning and an end, so releasing it now returns both zooms
— the chart axis and the map camera — to what the rider had. The zoom toggle button was
kept despite being a candidate for removal: it is the only way to frame the whole ride on
the map, which pinching the chart cannot do.

Segment names are held to a shape rather than accepted as free text, and the rule is
explained while typing rather than saved up for the moment Save is pressed. A name needs
at least one letter, keeps digits as separate words ("Ridge 2", never "Ridge2"), uses one
alphabet, and allows only letters, digits, spaces and hyphens. Spaces are collapsed and
the first letter capitalized as the rider types, so what they see is what is stored. The
default name is gone: "<ride> segment" was a name that always had to be deleted first.

Difficulty is now trail signage — one row of marks with circles for green, blue and red,
a diamond for black and two for double black, and only the chosen one spends a word.

Fixed the editor sheet not scrolling when fully expanded. The body was split into a fixed
header and a weighted scroll column, which left the details with a viewport the size of
their own content — nothing to scroll anywhere except inside the name field. The whole
body is one scroll container now; the trimmer only claims horizontal drags, so vertical
ones belong to the scroll everywhere.

Rust and Android unit tests, `lintDebug` and assembly pass; signed release installed on
the S25 (pid 32407, clean logcat).

## 2026-08-11 — Elevation: one impulse, not roughness

A flat asphalt road was charting as a pumptrack — a 2 m road reading 13 m of relief with a
spurious 11 m dip. The working hypothesis, the rider's and mine, was that the IMU could
arbitrate: calm accelerometer means smooth ground, so the barometer must be misbehaving.
That design was built and then dropped, because the rider's exported recordings say it is
wrong. Flat asphalt reads 1.4–7.9 m/s² of typical accelerometer error; real forest
singletrack reads 2.1–7.6. The accelerometer is measuring the phone shaking in its mount,
not the ground, and it cannot separate the two surfaces at all.

Reading the asphalt trace directly showed what was really there. For sixty seconds the
barometer and the GPS altitude agree to within a metre on a gentle descent — the road the
rider described. Then, in the last two seconds, the pressure drops twelve metres and comes
back: the phone leaving its mount at the end of the run. One impulse, not roughness.

So the filter is narrow. A pressure sample is compared with the median of the ±2 s around
it and replaced only when it disagrees by more than 3 m — the most a rider can gain or
lose against where they were two seconds ago, drops included. Everything else passes
through exactly as measured, which matters: a plain running median cost 1.3 m off the
crest of a synthetic 10 m roller, and that would have been paid at every real feature.
Airtime suspends the test, since being airborne is the one honest way to outrun it.

Separately, the baro-vs-GPS offset now gets a ±30 s median measured in time rather than in
fixes. It is a weather field and belongs on a weather timescale, and this also stops the
fix rate — which the power-saving profiles change — from changing the filter's strength.

Measured against the rider's three exported recordings, before and after:

| recording | relief before | after | ascent before | after |
|---|---|---|---|---|
| Road near home, 61 s asphalt | 13.3 m | 4.6 m | 13.0 m | 2.9 m |
| Test, 50 min | 29.6 m | 25.3 m | 835 m | 537 m |
| Kojoring with Misho, 6.4 h | 1028 m | 1024 m | 5893 m | 5026 m |

`motion_samples` still moved out of `activity.rs` into a shared `motion` module; the
vehicle classifier is now its only caller.

Field check on the S25 showed the impulse was gone and the road was still drawn as a hairy
line, so a second pass was added ahead of the first: a ±0.5 s mean over the pressure. The
jitter it removes is a few tenths of a metre with a period under a second, which no ground
produces — the smallest real feature a rider rides, a roller or a compression, takes a
couple of seconds and passes a window this narrow essentially untouched. Airtime exempts
both passes.

Totals across the rider's three recordings, unfiltered → impulses rejected → hash removed:

| recording | ascent | | | descent | | |
|---|---|---|---|---|---|---|
| Road near home, 61 s asphalt | 13.0 | 2.9 | **0.0** | 15.3 | 4.4 | **2.0** |
| Test, 50 min | 835 | 537 | **138** | 825 | 528 | **129** |
| Kojoring with Misho, 6.4 h | 5893 | 5026 | **2865** | 5897 | 5032 | **2869** |

The asphalt road now reports no climb at all and two metres of descent, which is the ride
the rider described. `Test` still carries 138 m across a 24 m range — better by a factor of
six, and what is left is the barometer wandering by a metre or two, the same size as a real
roller. Separating those needs a second opinion on absolute height, which is the honest
case for a DEM.

144 Rust tests, clippy and the Android build pass; bindings regenerated. Algorithm version
`gps-bounded-0.7`.

## 2026-08-11 — Uploads switched off, shuttle legs joined

Uploads are off behind one constant. The server has raw uploads disabled, so every save
queued a job that could only fail, and the UPLOAD FAILED badge on every activity described
the deployment rather than anything the rider did. `UploadWorker` and `ActivityUploader`
are left whole and unreferenced — turning the constant back on is the whole change when the
endpoint is live. Activities already carrying a queued or failed status are settled to
RECORDED on load, since both meant the same thing once uploads stopped: saved on the device
and nowhere else.

A shuttle leg was still arriving broken. Between two confirmed vehicle spans, the flat
shelves of a serpentine were being handed back to the rider, and the reason was the speed
test inside `contains_riding`: it asks whether motion is faster than walking, which was
built to separate riding from pushing a bike, and a van holding 20 km/h through a switchback
answers yes. So an interruption that gains height is no longer readable as riding. A rider
who gets out gets out to ride, and riding goes down — which the existing drop test already
catches. On the Kojori recording two climbs that arrived as 516 s + 576 s and 450 s now come
through as single spans of 1259 s / 786 m and 813 s / 302 m.

144 Rust tests, clippy, Android unit tests and the release build pass; installed on the S25.

Still open: the bottom sheet's invisible scroll inertia, which deleting `SheetScroll.kt`
did not fix, so that diagnosis was incomplete.

## 2026-08-12 — Version bump the previous two changes needed, and net-loss riding

The road at the rider's house still charted as a hairy line after the smoothing landed,
and the reason was mine: `ALGORITHM_VERSION` was bumped for the first elevation change and
then left alone through two more — the mean pass and the shuttle rule. The store checks
that string to decide whether a cached artifact is stale, so it kept serving the old one
and neither change reached a single existing activity. Now `gps-bounded-0.8`.

The remaining green on the shuttle road was real descent: a serpentine gives up height
between switchbacks, and `contains_riding` was reading the gross figure, so every dip past
30 m became a run. It now takes the net. A rider who gets out to ride does not come back up
to the same height to continue the transfer — they leave, and the interruption ends
hundreds of metres lower; a road that dips forty and takes them straight back ends level.
Anything ending level or higher is the shuttle continuing and no longer reaches the speed
test at all.

On the Kojori recording the two shuttle legs now arrive as single spans of 1575 s / +833 m
and 2094 s / +981 m, and the descents left are the real runs (−146 to −167 m each).

144 Rust tests, clippy and the release build pass; installed on the S25.

## 2026-08-12 — Pressure mean widened to ±1.5 s, measured against the cost

The road at the house was smoother but still moving, so the mean was swept over the rider's
own recordings rather than guessed at again:

| half-window | tarmac jitter, m/sample | tarmac ascent | 6.4 h shuttle day, ascent |
|---|---|---|---|
| ±0.5 s | 0.0222 | 2.0 m | 2574 m |
| ±1.0 s | 0.0148 | 0.0 m | 2452 m |
| **±1.5 s** | **0.0114** | **0.0 m** | **2364 m** |
| ±2.0 s | 0.0102 | 0.0 m | 2335 m |
| ±3.0 s | 0.0094 | 0.0 m | 2312 m |

±1.5 s halves the residual jitter for 8% of the long day's accumulated total, and past it
the jitter barely moves while the totals keep falling — so that is where the width sits.
The 8% is mostly the same wander seen from the other side, but it is a real cost and is
recorded here rather than claimed away. `gps-bounded-0.9`.

## 2026-08-12 — Shuttle legs stop being cut into pieces

Closing the tail left from yesterday: a descent inside the Kojori shuttle climb that kept
being read as a run.

**First, the debugging tool was lying.** Yesterday's rule was reverted as "does not fire"
on the evidence that its `eprintln` produced nothing. It did fire — `rtk` filters `cargo
test` output down to a summary, so every diagnostic print was being swallowed. Running the
probe through `rtk proxy cargo test` showed the whole picture immediately. Worth
remembering: **any instrumentation of a Rust test must go through `rtk proxy`.**

With that, the shuttle leg read out clearly:

```
t+5711  LikelyMotorized 1574s +833m
t+7285  Transit          29s   -2m
t+7315  Downhill         36s  -35m   <- road dip
t+7357  LikelyMotorized 373s +121m
t+7741  Downhill        108s  -80m   <- road dip
t+7852  LikelyMotorized 220s  +64m
t+8114  Still           373s         <- waiting for the pickup
t+8629  Downhill        148s -166m   <- a real run
```

Two dips, not one. The discriminator is time, not shape — see DECISIONS. A gap between two
vehicle spans shorter than 240 s cannot contain a ride. Across the whole 6.4 h recording
this changed exactly one leg and nothing else:

| | before | after |
|---|---|---|
| shuttle leg | 3 spans, 1574+373+220 s | one span, 2362 s / +904 m |
| ride descent | 2413 m | 2296 m |
| ride max speed | 20.9 m/s (the van) | 16.9 m/s |

**A bigger bug fell out of checking the arithmetic.** The distance did not add up: 193 s of
reclassified points cannot move 9 km. `ride_totals` never cleared a stream's 1 m anchor
when the other stream took over, so every shuttle handed the ride a phantom straight line
across its own climb. Ride distance for that day was 54 km; it is 30 km. Verified on all
five backed-up recordings.

Both changes are behind `gps-bounded-0.10`, installed on the S25 as a release build (the
phone already carried a release-signed `0.1.0-test1`, so a debug APK cannot replace it —
use `:app:assembleRelease`).

Still open: the bottom sheet needs a moment to "cool down" before it scrolls. Could not be
reproduced this session — the phone was locked and the screen is where the bug lives. The
deleted `SheetScroll.kt` (a fling-velocity boundary) is confirmed gone with no references
left, and the elevation chart only consumes *horizontal* drags, so neither is the cause.

## 2026-08-12 — Bottom-sheet nested-scroll regression updated out

The sheet's two-to-three-second "cool down" after a fast expansion matches AndroidX
`b/452071842`: Material 3's hardcoded bottom-sheet motion made nested-scroll/fling
handoffs overly stiff. Nakvali was pinned to `material3 1.5.0-alpha09`; AndroidX shipped
the fix in **`1.5.0-alpha16`** — "BottomSheet components now respect
`MaterialTheme.motionScheme` during nested scroll and drag gestures". The pin is
`alpha18` because alpha21 replaces the sheet state APIs this app uses with a unified
`rememberBottomSheetState`, which is a separate migration. No feature-level gesture
interceptor or delay was added. `BottomSheetScaffold` remains the correct persistent
sheet for the map-led screens, and both `verticalScroll` and lazy lists already
participate in Compose nested scrolling.

All Android debug unit tests and `assembleDebug` pass. A subsequent `assembleRelease`
also passed lint-vital and was installed in place on Galaxy S25 `RFCY904ZXQY` without
clearing app data. The remaining check is the original fast expand followed immediately
by a content scroll on Activity and one segment sheet.

**The causal claim is not proven, and one measurement argues it is only half the story.**
Driven from adb, the same scripted gesture — expand, then scroll the content with no
pause — scrolled fine on the 50-minute `Test` activity and did nothing on the 6.4 h
Kojori one. A pure gesture-handoff bug would fail on both. What reconciles them is that
the settle is an animation: on a screen heavy enough to drop frames it takes far longer
in wall-clock time, widening the window in which the sheet holds the gesture. So the
version bump can be right and still leave the real cost in place — the elevation profile
redraws its whole path inside the scrolling column every frame, 72 km of points on that
recording. Worth measuring with `gfxinfo` before calling the sheet done.

## 2026-08-25 — iOS port research: the engine is not the problem

Question raised: what does an iOS version need, should the app move to Flutter or React
Native, and does the Rust engine survive. Findings in `docs/research/ios-port-options.md`.

`fusion-core` survives every option. UniFFI treats Swift as tier-1 alongside Kotlin, and
the crate has no Android coupling — only `std::fs`, `serde`, `flate2`. An iOS build is a
new target plus `uniffi-bindgen-swift` and an XCFramework, not a rewrite. So the framework
question and the Rust question are independent, which is what makes the answer easy.

The cost of iOS is not the UI toolkit, it is Apple's sensor and background model:

- Accelerometer/gyro are capped at ~100 Hz, enforced on purpose (Apple DTS: no entitlement
  lifts it). Nakvali acquires at 200 Hz. The 50 Hz live path and 20 Hz persisted stream are
  unaffected; the 200 Hz airtime pre-roll halves in resolution.
- `CMAltimeter` is fixed at 1 Hz versus 10 Hz on Android, and delivers kPa. Barometric
  elevation quality will be worse on iOS.
- There is no foreground service and no background mode for CoreMotion. Motion callbacks
  survive backgrounding only because a live Core Location session keeps the process alive —
  an undocumented side effect. Sessions are reported dying after 60–130 min, so a watchdog
  restarting both managers is mandatory.

Therefore Flutter and React Native buy nothing where it hurts: the recorder must be native
Swift on iOS and native Kotlin on Android under either. What they would charge is the whole
21.7 k LOC of hand-written Kotlin, including the recorder that a year of field work made
trustworthy. Flutter additionally discards the UniFFI surface for a Dart-specific bridge;
React Native at least keeps the UniFFI annotations but adds a JS runtime to a thermally
sensitive recorder.

Recommendation: native SwiftUI on iOS sharing `fusion-core`, Android untouched. KMP with
Gobley is the plausible *second* step for the ~9 k LOC of non-UI Kotlin, once duplication is
measurable and Gobley is past 0.x — adopting a pre-1.0 bindgen on the working Android build
first would be trading a real asset for a hypothetical one.

Next concrete step, deliberately cheap and additive: a `fusion/scripts/build-ios.sh`
producing the XCFramework, then a Swift recorder prototype writing the same `.jsonl.gz`.
Open question that gates everything: replay existing Android recordings decimated to
100 Hz IMU / 1 Hz baro and check whether airtime and gate crossings still hold up.

## 2026-08-26 — material3 alpha26, and a design pass that blames the palette rather than Compose

The UI was called out as not good enough, with the suspicion that RN or Flutter would look
better. Built the app, drove it on emulator-5554 through record → pause → stop → save, and
reviewed the real screens rather than the source. The three reasons the app reads as generic
are all inside it:

- `Type.kt` sets `FontFamily.Default` in all fifteen styles. `tnum` is requested but Roboto
  barely differentiates tabular figures, and the recording screen is four numerals.
- Material derives secondary/tertiary from the fern-green seed and lands on rose-brown
  (`DarkSecondaryContainer #443631` over `#F0D8CF`). Every `FilledTonalButton` inherits it,
  so "Record a ride", "Find descents" and "Add bike" ship as beige-pink pills inside a green
  identity. `Color.kt` was written for soil and carbon; half of what renders is dusty rose.
- `Theme.kt` declares `MaterialExpressiveTheme` + `MotionScheme.expressive()` and the catalog
  pin exists to keep those APIs reachable, yet usage is zero `ButtonGroup`, `SplitButton`,
  `LoadingIndicator`, `MaterialShapes`, `animateBounds`, `ListItem` and `TopAppBar`, against
  17 legacy `CircularProgressIndicator`. The alpha is paid for and baseline M3 is delivered.

Plus 71 raw `Button(` and 34 raw `Surface(` against eight shared components, which is why the
look cannot be changed centrally and every edit feels hopeless.

Two findings are behavioural, not cosmetic. The battery-exemption dialog fires *during* the
five-second warm-up, on top of "Finding a clean start · 8S MAX", and its scrim eats gestures
aimed at the screen behind. And the live control moves: Pause is centred while recording, but
on pause Resume shifts right and Stop appears to its left, so a thumb returning to the
remembered spot lands between them with Stop's edge adjacent. In gloves on a rough trail that
mis-tap ends the ride.

Also observed once, worth reproducing before it counts: after another app stole focus during
the save handoff and the process restarted, Record showed "Ready to ride" with no route back
to the ride that had just ended. Data was intact (`b96b30ac….jsonl.gz`, 18 KB, health sidecar,
`activity-artifacts` written), so this is a surfacing gap, not loss. MapLibre also rendered
blank after that restart while GPS reported ±57 m.

**material3 1.5.0-alpha18 → 1.5.0-alpha26.** The pin comment said the alpha21 sheet-state
migration was its own job; it is done. Five sheets — Record, ActivityDetail, SegmentEditor,
SegmentCandidates, Segments — moved from `rememberStandardBottomSheetState(skipHiddenState =
true)` to `rememberBottomSheetState(initialValue, enabledValues = setOf(PartiallyExpanded,
Expanded))`. alpha21 stopped removing the `PartiallyExpanded` anchor by layout and handed that
to the caller, so naming both anchors is what replaces the flag; omitting `Hidden` is what
`skipHiddenState` did. The parameter is `enabledValues`, not `sheetValues` — read off the
Kotlin metadata in the published AAR after the compiler rejected the guess.

Zero exposure to the other alpha19–26 breaking changes: no `SearchBar`, `ExposedDropdownMenu`,
`SplitButtonLayout`, `TonalToggleButton`, `ComponentOverride`, `WideNavigationRail`, `isAtTop`
or `ListItem` anywhere. Build green, 147 unit tests pass, sheet expand/collapse verified by
driving a real recording. The only remaining deprecation warning is an unrelated foundation
`rememberTransformableState`.

Worth watching: the July bottom-sheet cool-down traced to sheet motion under load, and this
bump lands on the same component. Re-check expand-then-scroll on a multi-hour activity during
the next field test before calling the version settled.

Useful side effect: alpha19 lets `Typography` carry one default font family merged into every
style that does not override it, so the largest finding above is now a one-line change plus a
font file.

Full graded review with screenshots: https://claude.ai/code/artifact/b3bf4847-69cd-449d-a511-71ac41d14fb7

## 2026-08-26 — UI refactor: the causes, not the symptoms

Acted on the design/UX review from earlier today. The work was deliberately ordered by how
much of the app each change moves, which meant the two files nobody had opened in months
came first and the screens came after.

**Typeface.** Archivo variable (`wght` 100–900, `wdth` 62–125) replaces `FontFamily.Default`,
which was set in all fifteen styles. It carries `tnum` *and* `zero`, so the recording screen's
`tnum` request finally does something and a zero can no longer be misread as a capital O at a
glance — visible immediately in `0.0`, `0:11`, `−0 m`. The width axis is what separates the
two voices: `NakvaliText` at width 100 for reading and instruments, `NakvaliSignage` at 113
for screen headlines and the all-caps eyebrows. One 643 KB file, and alpha19's default font
family on `Typography` means it is declared once instead of fifteen times.

First attempt regressed the navigation bar: `labelMedium` in the signage voice with 1.4sp
tracking pushed "Activities" onto two lines. Signage is now restricted to `labelSmall` and
the headline styles — short strings, which is what lets them carry tracking at all.

**Palette.** Secondary and tertiary are written by hand instead of derived. Material rotates a
green seed into a desaturated rose, and since every `FilledTonalButton` reads
`secondaryContainer`, that rose was the resting colour of half the app's controls. The three
families now have jobs: primary green for action and live state, secondary stone/graphite for
resting surfaces, tertiary ochre as the second signal. Neutrals lost their warm-red bias for a
faint green-yellow one — the old bias read as pink once it met the rose.

Changing tertiary had two consequences worth recording, both caught on device rather than in
review. The map took `vegetation` from `tertiaryContainer`, so every forest turned the colour
of dry earth; woodland now has its own written-down colour, because vegetation is not a signal
and should not move when the signal palette does. And `tertiary` had been standing in for
"good" in three places — GPS ready, readiness ticks, the uploaded checkmark — which was fine
while it was sage green and says the opposite now. Those are primary; "recovered", "queued"
and "already covered" stay ochre on purpose.

**Component layer.** `NakvaliMetric` takes a unit and an emphasis (`Hero`/`Primary`/
`Secondary`) instead of a boolean and an alignment, and everything is start-aligned — the old
two-alignment grid left a ragged gutter down the middle. `NakvaliStatusPill` takes a tone
(`Live`/`Held`/`Alert`/`Neutral`) instead of raw colours, so PAUSED stopped wearing the
muddiest colour in the palette. Added `NakvaliPrimaryButton`/`NakvaliSecondaryButton`/
`NakvaliTextAction` so call sites pick a role, `NakvaliLoading` over the Expressive
`LoadingIndicator` (the first component to collect on the alpha pin the theme has been paying
for), and `NakvaliTopScrim` so basemap labels stop colliding with the status-bar clock.

Correction to the review: the "71 raw `Button(`" figure counted every `*Button(` including
`IconButton` and `TextButton`. The real number of filled/tonal buttons deciding their own
weight was five. The finding held — an optional sign-in was louder than anything a rider does
— but the scale did not.

**Hero screen.** The control no longer moves: `NakvaliRideControlBar` anchors the primary
control to the centre and offsets the secondary beside it, so a thumb returning to the
remembered spot after a pause finds Resume rather than the gap next to Stop. Speed is `Hero`
at 64sp with its unit on the same baseline, ride time is `Primary` and now drops an hour that
has not happened (`0:11`, not `00:00:11`) — eight glyphs were competing with three. Distance
and descent sit at the third tier. A line under the controls says how to finish, because the
pause-then-stop guard is right and was completely undiscoverable.

The battery-manager prompt now fires *before* `startRecording()` rather than after, so it sits
on the idle screen instead of landing on top of the five-second warm-up with a scrim that ate
gestures aimed at the screen behind it.

**Map.** `NakvaliMapDetail` splits Browse from Instrument. Browse narrows the style's POI
layers to classes a rider can use — parking, bike shop, water, shelter, lift, viewpoint —
instead of the style's own `rank` filter, which ranks town-centre prominence. Instrument, used
while recording, drops POIs, street names and shields entirely and keeps trail names, water and
place names. Trails read as dashed ochre against green woodland and grey roads.

**Light scheme.** Kept following the system, but designed rather than tolerated: light spells
out its own surface-container ramp instead of leaving it to derivation, sheets and panels over
a map use a raised container plus an optional hairline, and the theme now drives
`isAppearanceLightStatusBars` — `enableEdgeToEdge()` reads night mode once at startup and was
leaving white status-bar icons on a light background. Forcing dark would have been wrong for a
daylight sport; forcing light is wrong at dusk.

Build green, 147 unit tests pass, and every screen was checked on emulator-5554 in both
schemes, including a driven record → pause → stop → save pass.

**Not done, and why.** The review's "no segment presence on the hero screen before a run" needs
a nearest-armed-segment distance on every fix. `liveSegmentArms()` already loads the armed set,
but computing distance-to-next in the recording path is recorder work, not a UI refactor, and
`RecordingState.Recording` has no field for it. Contour lines and hillshade are blocked on a
terrain tile source — OpenFreeMap ships neither, and `ne2_shaded` is global relief that says
nothing at riding zoom. Both want a decision before code.
## 2026-08-29 — Galaxy S25 field recording: fragmented GPS and city-wide Activity camera

Diagnosed activity `b61ce822…` from its exported immutable raw recording after Activity
Detail showed a sparse Fusion trace spanning from the mountain into Tbilisi. The 5 h 3 min
recording contains 5,100 GPS fixes, but only 2,940 pass the canonical ≤20 m gate. Median raw
accuracy is 10.8 m; p95 is 1,194 m and the worst reported radius is 4,325 m. Raw contains
102 consecutive jumps over 500 m (maximum 14.3 km), while current `gps-bounded-0.10`
correctly rejects those coarse fixes rather than turning them into finalized anchors.

The accepted evidence is still extremely discontinuous: current finalization produces
8,188 points split into 261 drawable spans at the Activity map's 3 s gap boundary, with a
longest accepted-fix gap of 52.9 min. The recording stayed active from 16:32 until the only
pause at about 21:35 and later captured real, accurate movement through the city. Canonical
classification reports 4.32 km ride and 4.00 km likely-motorized transport, but Activity
Detail's `cameraBoundsPoints(Fusion)` fits every finalized point regardless of semantic
state. That combination — honest line breaks plus full-recording bounds — explains the
apparently scattered, over-zoomed map.

Ruled out Nakvali's transport power profile as the trigger at the two-minute failure point.
Replaying the accepted GPS altitude through its five-fix median and 45 s climb rule produces
no qualifying entry until almost the end of the recording, and the raw event stream contains
no platform `in_vehicle` transition. An initial inference from persisted IMU frequency was
discarded because confirmed-STILL persistence independently reduces raw rows to 20 Hz and
therefore cannot identify the active acquisition profile. The remaining source question is
why the Samsung fused provider stopped supplying GNSS-quality fixes about two minutes into
this outing (terrain, system behavior, or a provider regression); the phone disconnected
before current app-op state could be inspected. No production code was changed. A follow-up
should A/B screen-on versus locked recording while capturing `LocationAvailability`, GNSS
satellites-used-in-fix and the active recorder power profile, then decide whether recording
should use Android's direct GPS provider rather than accept fused coarse fallbacks. The UI
should also decide whether its default camera prioritizes ride/downhill geometry and whether
severe rejection/gap quality deserves an above-the-fold warning.

Follow-up primary-source review of current Android guidance and OpenTracks, GPSLogger,
OsmAnd, OwnTracks and Traccar is captured in
`docs/research/android-gnss-tracker-location-sources.md`. The closest sport recorders use
an explicit platform `GPS_PROVIDER`; navigation and telemetry apps more often offer both
fused and platform backends because their continuity requirements differ. Recommendation,
not yet an accepted architecture decision: make direct GNSS the canonical ride source,
keep fused location separate for browse/pre-start UX, preserve the existing location FGS
and partial wakelock, record GNSS-status diagnostics, and validate the change with a paired
screen-on/screen-off field test on the affected Galaxy S25.

## 2026-08-29 — Android canonical recording moved from FLP to direct GNSS

Implemented the provider boundary selected after the Galaxy S25 field failure. The raw ride
path no longer owns a `FusedLocationProviderClient`; `RecordingLocationSource` requests the
platform `GPS_PROVIDER` explicitly with an unbatched high-accuracy request. Normal cadence
remains 1 s (500 ms minimum). Transport power saving remains 5 s (2.5 s minimum) but changes
only cadence — both policies are locked to `gps` by unit tests. Browse/live-map preview still
uses fused location independently. The existing warm-up now reads direct GPS and prefers a
≤15 m fix; its bounded timeout may still begin IMU/barometer capture with an honest GNSS gap.

Registered `GnssStatus.Callback` beside the direct location listener. Once-per-minute and
start/stop health records now carry provider name/enabled state, GNSS engine state, TTFF,
visible/used satellite counts, and Nakvali's transport power-saving state. Missing satellite
position remains an honest raw gap while IMU and barometer continue. Declared and requests
the Android 12+ coarse/fine permission pair while continuing to require granted fine location
before direct GNSS registration. Updated the raw/health contracts and recorded the durable
architecture decision in `docs/DECISIONS.md`.

Verification: `:core:recording:testDebugUnitTest`, `:core:recording:lintDebug` and full
`assembleDebug` pass. Installed the APK on emulator-5554, started a recording, injected GPS,
turned the screen off and inspected system state: Android showed
`com.nakvali.app → gps`, high accuracy, 1 s / 500 ms, while the location foreground service
remained active. The resulting raw file contained 33 GPS fixes and finalized successfully;
the health heartbeat and stop record both reported `location_provider: gps`, provider enabled,
and GNSS satellite counters. Emulator-injected coordinates naturally used zero satellites.
Open question requiring the physical device: repeat the paired screen-on/screen-off pass on
the Galaxy S25 and inspect real satellites-used/TTFF plus cadence before treating the Samsung
failure as closed.

## 2026-08-31 — The S25 failure was global Power Saving, not permissions or FLP

Diagnosed direct-GNSS activity `a890973e…` from its 58 MB raw export and Android's retained
battery history. Direct GPS fixed the first failure mode: median accuracy is 10.0 m, p95
12.6 m, and 987/990 fixes pass the canonical 20 m gate. But only 990 fixes exist across a
237 min recording, split by 28 gaps longer than 5 s; the longest is 31.1 min.

Battery history supplies the missing causal evidence. Every dense raw burst matches screen
on, and Android toggles the Nakvali UID's GNSS attribution with the display: for example
`16:17:12 +screen/+gps`, `16:17:30 -screen/-gps`, then no raw fixes until the next wake at
`16:48:33 +screen/+gps`. The same sequence repeats throughout the ride. Nakvali remained a
foreground location service, held `nakvali:recording` as a long partial wake lock and was in
the device-idle user whitelist. Fine/coarse/background location and background app-op were
all granted.

The system cause is explicit in `dumpsys power`: Battery Saver was enabled continuously from
August 23 with policy `location_mode=1`, Android's
`LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`. Per-app Unrestricted cannot override it. The
previous FLP build obscured this by emitting kilometre-scale network estimates after GNSS
stopped; direct GPS turned the same system behavior into honest gaps.

Added a pre-start/Continue guard for every non-`NO_CHANGE` Android location power-save mode,
plus the persisted AOSP `low_power` switch because charging temporarily reports `NO_CHANGE`
even though unplugging will reactivate the unsafe policy. The blocking dialog explains that
system Power saving disables locked-screen GPS, links to Battery Saver settings and has no
unsafe “record anyway” path. Health now records both active mode and persistent setting.
Unit regressions cover all four location-changing modes and the charging-masked case;
recording/feature unit tests, both module lints and signed release assembly pass. The signed
release was installed over the S25 with all ride data preserved. Rendered-dialog check was
completed by the user on the S25 on 2026-09-01 and reported working; this closes the only
pending item of this entry.

## 2026-08-31 — Tab-switch native crash: OpenGL mitigation and immediate navigation

Reproduced the user's crash on the physical Galaxy S25: the four-tab loop killed PID 26006
on round two; the reduced Record ↔ Segments pair also crashed, while ten Record ↔ Activities
rounds survived. Tombstone: null dereference in `mbgl::android::MapRenderer::render()+144`,
through `SurfaceViewMapRenderer` / `MapLibreVulkanSurfaceView$VulkanThread`, MapLibre 13.4.1.

First experiment disabled Navigation Compose's default destination fades. This removed the
earlier visual overlap but did **not** fix the native failure: the signed no-transition
Vulkan build still died on its first fast map-tab round. Therefore simultaneous animated
destinations are not a sufficient root-cause explanation, and no such claim remains in code.

Second experiment changed only the renderer artifact at the same SDK version, from
`org.maplibre.gl:android-sdk` (Vulkan) to the officially supported `android-sdk-opengl`.
Installed the signed release on S25 without uninstalling or clearing data. The previously
failing pair survived 10 rounds at 200 ms, then 50 rounds at 100 ms: 120 tab-switch taps with
the same PID 32256. The app's journal showed Adreno OpenGL ES initialization and no native
crash. A further full Record → Segments → Profile → Activities → Record pass was captured
on video and inspected as frames; no outgoing map/panel overlap was observed. This is a
device-verified mitigation, not a proof of the exact Vulkan lifetime defect or all-device
stability. Upstream reports a matching render offset on Android 16:
https://github.com/maplibre/maplibre-native/issues/4274.

Added `android/scripts/tab-switch-smoke.rb`: discovers navigation bounds from UIAutomator,
checks the foreground app and PID, stops immediately on process death, and never installs,
clears logs/data, or starts a recording. Run from the repo with an unlocked idle Record tab:
`ruby android/scripts/tab-switch-smoke.rb RFCY904ZXQY 10 0.2`.
The native race is not covered by a JVM-only test; this device loop is its regression check.

All 153 Android unit tests, app debug lint, debug assembly and signed release assembly pass.
An extra emulator run could not be completed: the AVD disappeared from ADB after launch.
S25 verification is complete and the OpenGL release remains installed. The UI skill's
render-and-inspect requirement informed the separate immediate-navigation visual policy;
no GPS/fusion, activity files, or map geometry was changed by this crash mitigation.

## 2026-09-01 — Power-save guard confirmed; wearable data scoped to Health Connect

The user ran the power-saving guard on the physical S25 and reported the dialog rendering
and behaving as intended, which closes the only item left open by the 2026-08-31 entry.

Competitive context, recorded because it will shape later phases. A second local app,
Bike Yard, is in the same niche, built by an acquaintance, with a social layer and
leaderboards shaped closely after Strava. It does not collide with the current path:
shared segments, leaderboards, KOM verification and social features are explicitly frozen,
and Nakvali is presently an offline recorder with local segments. What separates Nakvali is
the timing engine — directed gates, crossings interpolated between fixes, reported
uncertainty, countable-versus-uncountable runs, immutable on-device raw, and the IMU stream
as a forgery-resistant signature — none of which lives in a social feed. Three collaboration
shapes were considered and none chosen: Nakvali as a timing engine whose segment results
(time, uncertainty, algorithm version) another app consumes while it owns the social layer;
a shared segment identity and geometry with separate leaderboards per app, so one trail is
not three unrelated boards; or nothing beyond the GPX/FIT export that already exists. The
decision is deliberately deferred until local segments are field-validated, because before
that there is nothing to offer, and an unvalidated gate/uncertainty model should not be
handed out.

Researched what a Mi Band 10 can contribute. There is no official SDK; the three real paths
are a standard BLE Heart Rate Service broadcast if the firmware exposes one, Health Connect,
and reversing the proprietary encrypted protocol. Chose the Health Connect direction: it is
one integration covering every wearable whose own app writes there, rather than a per-vendor
protocol that breaks with firmware. `minSdk = 34` means the provider ships inside the
platform, so no install/availability branching is needed beyond `getSdkStatus()` reporting
`UPDATE_REQUIRED`. The current stable client is `androidx.health.connect:connect-client`
1.1.0, confirmed against Google Maven metadata. Raw wearable IMU is unavailable through any
sanctioned API and wrist motion is not frame motion, so it is not a fusion candidate.

Four constraints shaped the recorded design. Health Connect has no live stream, so heart
rate cannot appear on the recording screen through this path. The owning app may publish
only after its own cloud sync, so a single fetch at Finish would frequently return nothing
and the enrichment must be re-runnable. Sample density is unknown and writer-dependent — a
watch outside a started workout may emit a sample every few minutes — so coverage must be
surfaced the same way GPS accuracy is. And because the data comes from another app's store,
it is trivially forged and must never be treated as anti-cheat evidence.

Written up as `Future — Wearable enrichment through Health Connect` in `docs/ROADMAP.md`.
No code was written and nothing was added to `docs/DECISIONS.md`: this is a recorded
direction, not an accepted architecture decision, and the numbers that would justify one do
not exist yet. When picked up, the first step is a read-only probe reporting source package,
sample count, median and p95 sample interval and ride-window coverage for a real ride,
measured on the S25. Open question left for later: whether Nakvali should also write an
`ExerciseSessionRecord` back to Health Connect so rides surface in other apps, which is a
write into the user's health store and needs explicit opt-in.
## 2026-09-07 — Fresh-eyes product and measurement assessment

Reviewed vision, roadmap, recent field failures, decisions, bounded fusion, segment
uncertainty/PR selection and transport classification. This is an assessment, not an
accepted architecture change; no production code was changed and no tests were run.

Main concerns: the gate uncertainty model assumes independent endpoint errors and
uses horizontal accuracy divided by speed without field calibration; countable PRs
are ordered by elapsed time even when their difference is unresolved by the reported
margins. The 240 s shuttle interruption rule can accept a vehicle bridge before
checking riding evidence, generalizing one Kojori day's turnaround times. Raw IMU
adds consistency evidence but does not itself authenticate a ride. GPS-bounded
interpolation is a sensible existing limit, not proof that IMU improves timing.

Recommended next milestone: a small multi-device, multi-mount field corpus with
independent gate timing, GNSS-only versus IMU-assisted comparisons, held-out rides,
recording continuity/battery metrics and explicit false-rejection rates. Keep public
leaderboards and new enrichment work deferred while testing whether local lap review
and trustworthy personal comparisons provide enough user value. Revisit the absolute
300 m floor as a quality heuristic rather than a universal timing limit. User has not
yet selected or authorized an implementation direction from these recommendations.

## 2026-09-15 — Shuttle cadence regression and riding-only GPX

Replayed the owner's latest `be697d95` recording locally. The false downhill spans
around 12:28, 12:36 and 14:25 were not longer than the existing 240 s bridge: the
recorder's five-second transport GPS cadence repeatedly broke the classifier's
3 s continuity boundary, and its 10 s evidence window lacked five points. A
synthetic cadence-transition regression failed with Unknown climbs surrounding
Downhill before the fix. A location-free field extract now covers the irregular
cadence transition too; raw GPS/IMU and diagnostic outputs stay in ignored `tmp/`.

Classification now recognizes repeated sparse cadence (up to 7.5 s with nearby
coarse-interval support) and extends undersampled evidence windows to 30 s. Dense
isolated gaps, pauses and missing full coarse fixes remain boundaries. The 240 s
bridge policy was not broadened. Version `gps-bounded-0.11` rebuilds old artifacts.
On the full recording the three identified dips become motorized; labels in the
12:50–13:50 and 14:59–16:35 riding intervals are unchanged. All 72,500 canonical
coordinates, timestamps, altitudes, speeds and section IDs are identical. Riding
distance changes from 63.37 to 37.89 km and transport from 29.30 to 54.78 km.

Added shared processed-GPX selection and a `Riding only · GPX` export option.
Direct Strava delivery uses the same exclusion of canonical LikelyMotorized points.
The complete processed and raw exports remain available. Tests cover transport at
both ends/in the middle, pauses, GNSS gaps, unchanged timestamps/elevation/input,
empty results and parsed XML track-segment boundaries. No Strava upload was made.
Updated the API description without changing request fields or backend behavior.

Verification so far: 147 Rust unit tests plus two real-fixture integration tests,
strict Clippy, Android recording/activity unit tests, activity lint, full debug
and signed release assembly passed. Both shipped Android native libraries were
rebuilt. Device export verification is recorded below when complete.

Final export probe used the production Kotlin GpxExporter on all 72,500 newly
classified canonical points: 53,975 points remain in 32 XML track segments; all
18,525 LikelyMotorized points are absent. The generated 8.1 MiB file is available
locally as `tmp/nakvali-be697d95-riding-only.gpx`. The temporary probe and CSV
inspection example were moved into ignored `tmp/`; only the small location-free
regression fixture is tracked.

Visual verification remains pending: the S25 was locked, the shared emulator was
being driven by another project's instrumentation (so it was left alone), and no
compatible Android Studio preview instance was available. The debug APK was
installed and the field recording copied into the emulator without removing its
existing rides; no release was installed on the S25 and no activity was sent to
Strava. The signed release APK is ready for the follow-up device check. Remaining
product limitation: filtering removes detected transport, not proof of every vehicle
metre; ambiguous residuals stay included and the full GPX remains available.

## 2026-09-15 — Export UX and explicit Save file

Replaced the mixed GPX/Strava/debug dropdown with a dedicated export task sheet.
Default GPX offers an Exclude transport switch, detected transport distance/time
and riding-distance summary. Save file and Share are distinct pinned actions;
Strava remains independent and raw GPS/sensor/health choices live in an expandable
Original files & diagnostics section. Unavailable data, preparing, saving, failure,
cancellation and successful filename feedback are represented explicitly.

Save uses ACTION_CREATE_DOCUMENT and copies the prepared file through the selected
provider without storage permissions. Preparation state lives in the ViewModel;
the pending source path is saveable while the system picker owns the foreground.
Unit tests verify exact copying, closed streams, missing-source protection, null
providers and propagated write failures. Activity tests, lint, full debug and signed
release assembly pass.

Created an independent temporary AVD on emulator-5556 because emulator-5554 was in
use by another project's tests. Imported a verified copy of be697d95 there, saved
GPX (8.46 MB) and raw (93.68 MB) into Downloads through the actual UI, and matched
SHA-256 against the prepared GPX and original raw respectively. GPX saving also
succeeded after `am kill com.nakvali.app` while the document picker was open; the
sheet restored and reported the saved filename. Verified picker cancellation,
raw-file Sharesheet without sending it, light/dark themes, 360 dp compact width,
1.3 font scale, and scrolling diagnostics with pinned actions. Render inspection
caught clipping from Strava's capsule TextButton; replaced it with a semantic
clickable row and inspected the corrected rendering. Screenshots are in ignored
`tmp/export-panel-final.png` and `tmp/export-panel-large-font.png` (the latter
records the pre-fix clipping). Native PreviewActivity provided the final isolated
render after the fix. No Strava activity was published and the S25 was not updated.

## 2026-09-15 — Owner-requested Samsung installation and commit

Confirmed no Nakvali recording service was running on Galaxy S25 RFCY904ZXQY,
then installed the verified signed release with `adb install -r`; Android returned
Success. Existing application data was preserved. The installed build includes
`gps-bounded-0.11`, transport-free GPX and the new Save/Share export workflow.
Fetched origin and confirmed main was neither ahead nor behind before committing.

## 2026-09-15 — Remaining transport/transit island and first-open delay

User clarified that the approximately 15 s activity delay occurred only on first
open after the update. CanonicalActivityStore explicitly invalidates old algorithm
artifacts and recomputes raw once, then reuses the persisted artifact; the report
is consistent with that path. No recurring-latency regression was established and
no loading optimization was made in this change.

Reproduced a remaining vehicle-to-transit island with a failing Rust test: a short
gently climbing section before a ten-second missing GPS fix loses the independently
identified vehicle context on the far side despite continuous IMU. Added a bounded
label-only context pass, retaining separate strict position evidence windows.
It requires IMU coverage across each short GPS hole, rejects manual pauses and
holes over 12 s, and reuses existing vehicle-bridge rules. Negative regressions
cover missing IMU, interrupted IMU, manual pause and a longer GPS hole.

Replayed the full be697d95 field recording: exactly nine sparse points at
12:33:52–12:34:32 change from Transit to LikelyMotorized. All other labels and every
position/time/elevation/speed/section remain unchanged. Riding distance becomes
37.32 km rather than 37.89 km; transport becomes 55.35 rather than 54.78 km. Other
reported mixed areas await a time/location example from the user; Transit remains
ordinary movement outside a downhill, not a synonym for motorized transport.

148 Rust unit tests plus two integration fixtures and strict Clippy pass. Native
Android libraries and the release build are being refreshed below. No phone UI was
interrupted: the S25 was in another application's workflow during inspection.

Both Android native libraries were regenerated and full debug/signed release
assembly passed. The 0.12 APK is ready; this follow-up has not been installed on
the S25 or committed. Installing it will require one new canonical recomputation
per previously cached ride, as with every algorithm-version change.

## 2026-09-15 — Whole transport episodes and rider-editable boundaries

The owner accepted a boarding-to-unloading episode model and manual correction of
ambiguous bounds. Replaced window-island bridging with a stateful Rust episode
pass: vehicle evidence establishes entry, road descents/flat stretches/short stops
do not independently end transport, and stops plus subsequent motion resolve exit.
Conservative descending-tail handling preserves riding when unloading was missed.
The tuning choices are documented as estimates, not physical guarantees, in
`docs/adr/0003-transport-episodes-and-rider-overrides.md`; glossary terms added to
CONTEXT.md. This supersedes the uncommitted 0.12 bridge-only follow-up.

Rust now returns explicit automatic episode bounds with canonical results; schema
v5 persists them, rather than recreating identity from adjacent line colours.
Algorithm gps-bounded-0.13 yields exactly three episodes on be697d95:
12:02:38–12:41:15, 14:01:02–14:38:17 and 16:42:51–16:58:32. No DH/Transit islands
remain within them. Known riding blocks 12:50–13:50 and 14:59–16:35 retain all their
labels, and positions/timestamps/elevation are unchanged. Riding is 37.05 km;
transport is 55.62 km. Broader field validation remains necessary, especially for
missed unloading or a vehicle journey that itself ends downhill.

Added Activity → More actions → Transport episodes: a selected-episode map, elapsed
HH:MM:SS boundaries, coarse range slider, add/remove and Use automatic. Drafts stay
separate until Apply. Rust validates intervals, applies the complete override and
recomputes ride totals from the canonical positions. Corrections live in the backed-up
recording index (null=automatic, empty=no transport) with a revision checked by
segment-result caches and live PR arming. A bounded in-memory corrected projection
feeds maps, statistics, discovery, matching and GPX; automatic artifacts/raw stay
untouched. Basic activity data now publishes before the optional profile finishes.

Validation: 151 Rust unit tests plus two integration fixtures, strict Clippy,
Android recording/activity unit tests, activity lint and debug/release builds pass.
Both Android native libraries and Kotlin bindings regenerated. On an isolated
emulator, verified all three episodes, rejected a reversed interval without writing
the index, moved the first start from 12:02:38 to 12:02:43, and confirmed revision 1
persisted after a cold app restart. The GPX produced by the real Share workflow
contains 53,633 points: 26 newly included points before the edited start and zero
points inside the stored transport intervals. Use automatic removed the override
and advanced revision to 2. Raw SHA-256 stayed identical throughout. Light/dark,
360 dp width, font scale 1.3, keyboard visibility and scrolling to fields/reset
were inspected; Apply remains pinned. Screenshots: tmp/transport-editor.png and
tmp/transport-editor-dark.png. Test copies remain local; no external upload occurred.

The Samsung disconnected before the final installation check, so this iteration
is provided as a signed APK and has not been installed on the phone or committed.
As with other algorithm changes, existing rides need one new canonical rebuild.

## 2026-09-15 — Progressive activity loading and warm-cache reuse

Replaced the blocking centre loader with a growing GPS preview and real processing
stages. Native progress comes from the same gzip parsing pass, so no second raw
scan competes with fusion. It emits the first GPS fix and bounded snapshots (up to
2,000 points), throttles normal refreshes to 500 ms, and reports actual compressed
bytes read. The UI shows a preview-through clock, reading percentage and subsequent
stage names; metrics remain unavailable until canonical completion. Original/raw
exports remain distinct from the display-only preview.

The repository now broadcasts bounded progress by recording, including calculations
started at Finish before the activity is opened. A finished retained preview is not
presented as a currently running calculation. Valid source fingerprints allow old
GPS geometry to be shown while an algorithm update runs. Added a one-entry in-memory
canonical cache with disk/source invalidation checks, and deferred segment-library
matching until the focused activity is usable. Profile publication and segment
results are guarded against newer transport edits superseding their inputs.

TrackMap now retains its style and updates GeoJSON sources in place, uses the latest
snapshot when style loading completes, and preserves a user-moved viewport through
subsequent data updates. Fit activity restores automatic framing. Data/appearance
changes are separated so progress-label recompositions do not rebuild map geometry.

Measurements: initial host probe on be697d95 was 3,067 ms parsing + 1,274 ms canonical
processing. A later callback probe under concurrent build/emulator load took 11,216
ms overall, with the first preview available at 0.90 ms. These host timings are not
a valid before/after CPU-speed comparison. The improvement claimed here is early
useful content, warm reuse and removed scheduling/rendering work, not an unmeasured
speedup of the underlying math.

On the isolated emulator, the real 6 h / 93.7 MB recording showed its partial map at
38% read while still processing. The 30 s screen recording captures progressive
reading through final geometry; later reopening did not generate another READING
stage. Observed stages reached FINALIZING around 20.4 s on this emulator. Inspected
video frame tmp/activity-loading-preview.png and final activity, exercised pan/Fit,
and found no AndroidRuntime errors. Artifacts: tmp/nakvali-loading-qa.mp4 and
tmp/activity-ready.png. Snapshot and cache tests cover partial GPS before completion,
monotonic read counters, bounded previews, unchanged canonical results, reuse by
identity, stale-version preview, raw-change rejection and completed-event semantics.
153 Rust unit tests + 2 integration fixtures, strict Clippy, Android unit tests,
activity lint and debug/signed-release assembly passed. Generated Android bindings
and both native libraries were refreshed. Algorithm version remains 0.13 because
measurement math is unchanged. No new Samsung installation or commit was made.

The S25 reconnected at the end of this iteration. Confirmed no Nakvali recording
service was active, then installed the verified signed release with `adb install
-r`; Android returned Success and existing data was preserved. This build includes
the transport-episode editor and progressive loading. No commit was created in
this iteration. The dedicated loading-test emulator was stopped after verification.

## 2026-09-15 — Continuous transport display

Removed the transport layer's explicit dash pattern. Semantic map geometry now
connects adjacent motorized fixes at the normal five-second power-saving cadence
(up to 7.5 seconds with jitter), retaining recorded vertices exactly. Section
boundaries, longer gaps, riding states and transitions keep their existing break
policy. This is display-only; canonical classification, raw data and GPX are unchanged.

Two new regression tests failed before the fix; all activity unit tests, activity
lint and signed release assembly passed afterward. A third test protects strict
riding/transition gap handling. Installed the signed release on Samsung S25 with
`adb install -r` (Success), after confirming no recording service was running.
Physical-device screenshot capture returned a black frame, so visual verification
of this iteration remains incomplete.

### Transport editor preview follow-up

Applied the same sparse transport display cadence to the selected draft in
Transport episodes. A display-only flag preserves its selected color and avoids
assigning a canonical state before Apply. Added coverage for five-second fixes,
cadence jitter, real gaps, manual pauses and the unclassified highlight state.
Activity unit tests, lint and signed release assembly passed. Visual confirmation
remains pending because the physical phone was locked during the prior check.
Installed this follow-up release on Samsung S25 with `adb install -r`: Success.

## 2026-09-15 — Honest export distance: episode coalescing and TCX

Measuring a shared `riding-only` export showed two separate problems. Strava
published 48.91 km for a ride whose 20 track segments sum to 36.90 km: the 12.08 km
of straight lines across the excluded shuttles. And nine of those segments were
degenerate — single points a few seconds apart — because transport labels flickered
around unloading while the fixes were sparse.

**Episode coalescing.** `label_episodes` now collects index ranges and coalesces
them before publishing: merge across a riding gap under 60 s and 100 m, then drop
episodes under 60 s and 150 m. Dropped ranges are relabelled from
`non_motorized_classifications` rather than left motorized. Replaying the measured
gaps of that recording: 19 episodes to 3, 20 exported segments to about 4. Two
pre-existing activity fixtures asserted that a 12-second synthetic climb classifies
as motorized; they were extended to 120 s, which is what "sustained" was meant to mean.

**TCX export.** New `TcxExporter` writes a Garmin TCX v2 activity with one `<Lap>`
per riding run and a stated `DistanceMeters` on every trackpoint. The odometer comes
from a new `ride_odometer_m` in `fusion-core`, factored out of `ride_totals` through
a shared `ride_breakdown` so the exported total cannot drift from the displayed one;
a test asserts the last odometer value equals `RideTotals::distance_m` and that the
shuttle does not advance it. `GpxTrackPoint` became `TrackExportPoint` in a new
shared `TrackExport`, gaining `runId`, which breaks only on transport or a manual
pause while `sectionId` keeps breaking on every real gap.

The in-app Strava upload now sends TCX. The backend derives the Strava `data_type`
from the file extension, defaulting to GPX so older clients are unaffected;
`proto/openapi.yaml` records this. `ExportRequest.GPX` became `.File` with a
`DataType` alongside.

157 Rust tests and strict Clippy pass, `make vet test build` passes, and Android
`assembleDebug testDebugUnitTest` passes. Bindings and both native libraries were
regenerated. Algorithm version stays `gps-bounded-0.13`: `ride_totals` math is
untouched, but episode bounds do change, so caches produce fewer segments on the
next run.

Not verified on device: the fix is predicted from replaying the measured gaps, not
from a fresh export on the phone, and Strava's handling of `DistanceMeters` still
needs one real upload to confirm. Open, deferred at the user's request: exporting a
single run rather than the whole activity, and trimming or hiding the start and finish.

## 2026-09-15 — Single-run export and trimming the start and finish

Both features the previous entry deferred. Designed against a four-way codebase
map (Rust core, recording module, activity UI, contract and written decisions)
produced by a workflow whose design phase then died on a session limit; the map
was the useful half and the design was finished by hand from it.

**Ride bounds.** New `fusion-core/src/ride_bounds.rs`: `RideBounds`,
`ride_within` and `ride_runs`. `ride_breakdown` gained an `Option<RideBounds>`
so totals and the odometer come from one walk that knows the trim; a pair
reaching outside the bounds counts toward neither the ride nor the transport.
`ride_odometer_m` folded into `ride_within`, which also reports the kept index
range so Kotlin never repeats the boundary test with its own inclusivity.

The trim never removes a point. That is the whole design: a transport correction
relabels without removing, and profile positions, segment attempt slices and the
odometer's alignment all depend on the finalized track keeping its length.
Segment matching is deliberately blind to the trim, so a timed crossing in a
trimmed head still stands and the annotation needs no revision counter.

`StoredRideBounds` rides in the recording index next to `transport_episodes`, so
backup and restore carry it with no archive change and the format stays at 1.
`TransportCacheKey` became `CorrectionCacheKey` covering both annotations — one
cached projection, one key, or a trim edit would keep serving the transport-only
projection. `LocalRecording.ridingDurationMs` makes the header and the activity
list follow the trim, which Rust totals cannot do for them.

**Riding runs.** `ride_runs` is now the single definition; `TrackExport` labels
`runId` from it instead of inventing boundaries, and a kept shuttle becomes its
own lap. The export sheet lists runs with distance, descent and moving time, and
`TrackExport.singleRun` slices one out by start time, rebasing the odometer so
the file opens at zero rather than at the kilometres that came before it. The
Strava `external_id` now carries the trim, without which a re-export after
trimming would poll the old upload and report success while Strava kept the
untrimmed ride.

New `TrimEditorSheet` mirrors the transport editor deliberately — two elapsed
clocks as the authoritative, screen-reader-operable input, the slider as the fast
path, a map preview dimming what is dropped, "Use whole activity" as reset. The
`SegmentProfileTrimmer` handles from `:feature:segments` were not promoted: it
lives in another feature module, `:feature:activity` may not depend on it, and
the profile sits inside a draggable sheet its gesture model was not built for.

162 Rust tests and strict Clippy pass, `assembleDebug`, `testDebugUnitTest` and
`lintDebug` pass; bindings and both native libraries regenerated.
`ALGORITHM_VERSION` stays `gps-bounded-0.13` — both features are post-finalize
views and the canonical math is untouched.

Not verified on device: no build has been installed or driven this iteration, so
both sheets are unexercised in the hand. Still open from the previous entry: one
real Strava upload to confirm it honours TCX `DistanceMeters`.

## 2026-09-15 — Where the fake descent comes from (diagnosis)

The rider reported 39 m of descent on the record screen after a stretch that was
only pedalled uphill, and did not believe the descent on the finished activity
either. Both were measured against his own six-hour recording
(`nakvali-be697d95`, 37 km ridden, 55.6 km shuttled, barometric elevation source)
with a new `fusion-core` example, `descent_probe`, which finalizes a raw file and
then re-accumulates the vertical metric several ways for comparison.

**The live number is GPS-only and fabricates about 4 m per minute of climbing.**
Replaying the recording's GPS and IMU through `LiveFusion` reproduces the
complaint exactly: 38.7 m of descent at t+10 min, while the altitude went from
1305 m to 1353 m — nothing had gone downhill yet. At t+20 min it was 82.4 m.
`live.rs` never reads the barometer, although the same recording carries 284282
baro samples at ~12.5 Hz; it accumulates the EKF's GPS-driven altitude behind a
5-sample median and a 2 m band. Over the same window, raw GPS altitude
accumulates 43.2 m of descent and the baro-anchored canonical profile only 4.5 m.

**The finished activity's descent is mostly real.** Three shuttle laps of roughly
930 m each account for about 2870 m of the reported 3188 m. The remainder tracks
the GPS anchor: the barometric profile is offset to GPS by a ±30 s median, and
that offset series wanders 68.5 m end to end, accumulating 319.5 m of descent
entirely on its own. Widening the median to ±300 s drops that to 66.5 m, ±900 s
to 58.2 m. High-frequency noise is not the problem — the finalized altitude sits
0.39 m rms against its own ±5 s median, and excluding stationary points changes
the total by 22 m.

**The activity's *ascent* is the badly wrong number: 2293 m for a shuttle day.**
Attributing one accumulation walk by activity state shows 1894 m of it charged to
points labelled `Still` — the rider sitting in the shuttle. `ride_breakdown`
excludes transport by the per-point test `activity_state == LikelyMotorized`
only, so the `Still` stretches inside a coalesced uplift episode still count as
ridden climbing.

Also noted while reading: the activity tile prefers `ride.descentM` and falls
back to `analysis.descentM`, but labels itself from the elevation source. For a
GPS-only recording `analysis` switches to net-per-section while `ride` keeps
accumulating, so the tile shows an accumulated figure under the label "Net drop".

Nothing is fixed yet; no algorithm version bump. Candidate fixes, in the order
their evidence is strongest: feed the barometer into the live vertical; freeze or
rate-limit the GPS anchor offset instead of the ±30 s median; exclude transport
by coalesced episode rather than per-point label; make `ride_totals` honour the
elevation source the way `analysis` does.

### The four fixes

`ALGORITHM_VERSION` is now `gps-bounded-0.14`: three of the four change what a
finished activity reports, so every cached artifact rebuilds.

**The uplift is no longer charged to the rider.** `ride_breakdown` built a
filtered copy of the track and accumulated over it, which made the last fix
before a shuttle and the first fix after it adjacent. New `ride_vertical` walks
the real track and drops its reference altitude at every point the ride does not
include, so a span ends at a vehicle, a manual pause or a trim boundary and the
next one starts from its own first altitude. On the rider's recording the ride's
ascent fell from 2293 m to 372 m; the two phantom steps were +930.1 m and
+921.5 m, each closing a 39-minute hole.

**Ride totals now honour the elevation source.** The whole-recording analysis
switches to net change per section without a barometer, but the ride totals —
which is what the activity screen actually shows — kept accumulating, under a
label reading "Net drop". `ride_totals`, `ride_breakdown`, `ride_within`,
`ride_runs` and `correct_transport` all take an `ElevationSource` now; Kotlin
passes the artifact's own `quality.elevationSource` through a new
`CanonicalActivityArtifact.elevationSource`, since an anchored track no longer
says which sensor drew it.

**The GPS anchor no longer shapes the barometric profile.**
`OFFSET_MEDIAN_HALF_WINDOW_MS` goes from ±30 s to ±5 min. GPS altitude error
wanders over minutes rather than jittering, and a minute-wide median passed that
wander into the anchored profile: measured on the rider's day, the offset series
alone accumulated 319.5 m of descent at ±30 s, 66.5 m at ±5 min and 58.2 m at
±15 min. Reported descent went from 3188.7 m to 3124.4 m.

**The live screen reads the barometer.** `LiveFusion::push_baro` takes every
pressure sample the recorder writes; `RecordingService` now feeds it alongside
the raw line. Descent accumulates off that series, and the GPS one stands down
until the barometer has been silent for 30 s, with a handover that clears the
climb history because the two series carry different quantities. Replaying the
rider's file through the real `LiveFusion`: 38.7 m of descent at t+10 min
before, 2.0 m after, against a barometer that says 2.3 m and a climb of 43 m.
`live_totals_from_recording` reseeds a resumed ride from the barometer too, so a
resume measures what the screen was already measuring.

Two things were found by testing rather than looked for. The live filter first
judged each sample against its own trailing median, which lags a trailing window
by half its width — on a sustained descent every real sample disagreed with it
and was rejected, freezing the replayed live descent for eighty minutes. It now
judges a sample against the previous one and a physical rate (10 m/s plus 3 m),
and drops a rejected sample without advancing the clock, so the allowance widens
while a disturbance lasts.

And the barometric height scale was briefly changed to a fixed sea-level datum
and changed back. Metres per hectopascal depend on the temperature of the air
being ridden through: read against standard sea level a sample at 1000 m is
converted as if that air were 8 °C, read against the ride's own first sample as
if it were 15 °C. Against GPS altitude over 1000 m of descent the ride-relative
curve disagreed by 11.9 m rms and the sea-level one by 19.3 m, so the original
choice stands, now with the reasoning written down where it can be checked. Only
a real temperature reading removes the ambiguity, and the phone has none.

168 Rust tests and strict Clippy pass, `assembleDebug`, `testDebugUnitTest` and
`lintDebug` pass, and bindings and both native libraries were regenerated. New
regression tests: an uplift between two runs, a GPS-only ride that must report
net and not wander, a ride with no elevation source, a climb under wandering GPS
altitude, a real barometric drop, and a stalled barometer handing the channel
back. `fusion-core`'s new `descent_probe` example is the measuring instrument
for all of the above — it finalizes a raw file, re-accumulates the vertical
metric several ways, and replays the live accumulator.

Not verified on device: no build has been installed since these changes, so the
numbers above are all from replaying the rider's recording on the desktop.

## 2026-09-16 — Five things the activity screen got wrong

All five reported from the hand, all five fixed and seen on screen. The
measuring rig was the rider's own six-hour recording, cut to its first 95
minutes and seeded into an emulator as `Morning ride`, so every screenshot below
is real data rather than a preview fixture.

**The title had no room.** The header row gave the title a weight, then put the
status pill and two action buttons beside it, so "Morning ride" rendered as
"Mornin…". The title owns the line now and the pill moved down next to the
subtitle, where the space was already spoken for.

**The elevation profile was drawn as dashes.** `ride_profile` decided continuity
with `MAX_ATTEMPT_GAP_MS`, which is three seconds — the rule for whether a run
can still be *timed* across a hole. The recorder's own power-saving cadence is
five seconds, up to seven and a half with jitter, so every shuttle and every
power-saving stretch counted as a break: 372 of 1204 samples on that recording.
A chart is asked a different question, so it has its own constant now,
`PROFILE_MAX_GAP_MS` at 7.5 s, and the same recording breaks at 5 samples.
Anything longer still breaks, because past it nobody knows what the ground did.

**The quality chips did not fit and read as capitals.** "GPS: GOOD · 3.8 M · 147
GAPS" ran off the edge, and a metre in capitals reads as the wrong unit. The
category is an icon's job now — terrain, GPS, dropped fixes — so the text is
only the answer: "Barometric", "Good · 3.8 m", "9 gaps". Dropped fixes became
their own chip, because they are a different fact from how accurate the fixes
that arrived were, and the row flows instead of clipping.

**Trimming an activity reused the segment editor's instrument.** The previous
entry left `SegmentProfileTrimmer` in `:feature:segments` for two reasons, and
both turned out to be soluble. It is a general instrument, so it moved to
`:core:ui` as `ProfileTrimmer` with its types and its tests, and `SelectionHandle`
came with it. The sheet gesture conflict is answered by material3's
`sheetGesturesEnabled`, which the trim editor now drives from the active handle:
while a boundary is held the sheet stands down. `TrimEditorState` carries the
profile — reused from the insights the detail screen already computed rather
than paying for a second pass — and positions map to timestamps through the
track, which is what the sample positions index. The elapsed clocks stay below
the chart as the exact, screen-reader-operable input; the range slider is gone.

**Context menus have icons**, and **an expanded sheet is expanded**: the detail
panel was capped at 72% of the screen while holding a summary, a profile, the
segment runs and the quality row. It goes to 94% now, leaving the handle and a
thumb-width of map as the way back down.

Verified on a Pixel 9 Pro emulator against the seeded ride: the title renders in
full, the profile draws as one continuous line, the three chips sit on one row,
the overflow menu carries its icons, and the trim editor opens on the elevation
profile with a boundary at each end. Not verified: dragging a boundary. `adb
input swipe` did not grab a handle — the synthetic gesture does not reproduce
what the gesture detector expects — and the component's drag behaviour is only
proven by its existing use in the segment editor. Worth one check by hand.

168 Android unit tests, `assembleDebug` and `lintDebug` pass; 170 Rust tests and
strict Clippy pass; bindings and both native libraries were regenerated. Nothing
here changes a canonical result, so `ALGORITHM_VERSION` stays `gps-bounded-0.14`
— the profile is a display artifact, computed on demand and never cached.

Emulator housekeeping, stated because it was data: seeding the ride overwrote the
emulator's recording index and deleted four leftover test recordings from August
that were in the way. Nothing on the phone was touched.

## 2026-09-17 — A receiver that went 11.5 km sideways

The rider reported that a ride had glitched badly and that the app handled it
poorly. The recording says exactly what happened, and it is a clean specimen.

At t+45 min, after a fourteen-second hole in the trace, one fix lands **11 566 m
away in one second**. It reports its own accuracy as **13.8 m** — comfortably
inside the 20 m cutoff — and its own ground speed as **0.0 m/s**. The receiver
stays out there for twenty-six seconds and then jumps back. The health log
explains the setting: satellites used fell from 60+ to single digits between
t+108 and t+122, and the hole at t+45 sits on the same decline.

Both guards abstained, each for its own reason. The accuracy cutoff believed the
13.8 m. The kinematic gate only refuses a step when some reported ground speed
of at least 1.5 m/s contradicts it, and a glitching receiver reports zero — so
the gate had nothing to corroborate against on precisely the fix it exists to
refuse. It also skips any pair more than five seconds apart, which is what the
way back was. The rider was shown **30 950 m** for a ride of about twenty.

**The gate now asks physics first.** `MAX_GROUND_SPEED_MPS` is 50 m/s — not a
bike's top speed but the speed past which nothing carrying a phone travels, so a
car on a motorway still passes — and it applies to every pair however far apart
in time, because a hole is not permission to cross ground nobody can cross. The
corroboration rule is unchanged behind it, so a zero-speed report still cannot
veto ordinary displacement. On the rider's file the ride falls from 30 950 m to
**19 395 m**; ascent and descent are untouched, the barometer having had no part
in it.

**And it yields rather than blacking out.** A gate that refuses forever would
lose a rider who really was carried somewhere while the receiver was blind, so
`LiveFusion` counts how long it has been refusing and, past
`IMPLAUSIBLE_RESEAT_MS` (45 s — longer than the 26 s this glitch lasted), takes
the position as authoritative and re-seats onto it. Recording nothing at all is
the worse failure.

`ALGORITHM_VERSION` is now `gps-bounded-0.15`: this changes distance, so cached
artifacts rebuild.

**The export sheet closes itself.** Once the file is handed to the system picker
or the share chooser, the sheet has done its job; coming back to it still asking
what to export is asking a question already answered. The result — saved,
cancelled, failed — arrives as a toast, since the sheet that used to display it
is gone by then. A failure to *prepare* the file still reads out inline, because
that happens while the sheet is open.

174 Rust tests and strict Clippy pass, `assembleDebug`, `testDebugUnitTest` and
`lintDebug` pass, bindings and both native libraries regenerated. New tests: the
re-acquisition kilometres away, the way back from it, a long hole that really
did carry a rider, and a motorway shuttle that must still pass. `gps_probe`
joins `descent_probe` as a measuring instrument — it reports the accuracy
distribution, every step above 25 m/s, which of them each gate refuses, and what
the rider ends up being shown.

Not verified on device: no build installed this iteration.

## 2026-09-21 — BIKEYARD integration research

The owner wants to prioritize BIKEYARD over Strava and keep Nakvali focused on
recording rather than duplicating a friend's trail/community product. This session
was explicitly limited to capabilities and architecture, not implementation.

Reviewed BIKEYARD's official developer documentation and published API contract,
alongside the existing Firebase sign-in, Strava broker/queue, and Rust-derived
GPX/TCX exports. Findings and source links live in
`docs/research/bikeyard-integration.md`.

Proposed first step: an Android public OAuth client with PKCE, direct processed
TCX uploads through a durable local queue, and links back to BIKEYARD. Keep
recording available offline without an account. Export does not require the
Nakvali Go broker. A BIKEYARD-connected profile is possible; replacing Firebase
for authenticated Nakvali API calls additionally requires an explicit identity
and session design. Neither auth migration nor removal of local segments was
approved or implemented.

Open validation: BIKEYARD's exact treatment of TCX distance, pauses, laps and
excluded shuttle travel; a controlled HTTPS mobile callback; and product choices
for publishing privacy and later edited/reprocessed activities. No application
code, deployment configuration, or registered OAuth application changed. No build
or tests run for this research-only change.

## 2026-09-21 — Registration-ready website and BIKEYARD App Link foundation

Prepared a minimal static Astro site in `web/`, using the app's Archivo typography,
trail-green palette and original synthetic contour artwork. Landing, Privacy,
Terms, 404 and the BIKEYARD callback fallback ship without client JavaScript,
remote font requests, analytics or fabricated ride data. BIKEYARD uploads are
explicitly forthcoming. The public contact is a build argument: local previews
show an honest missing-contact notice; the production Docker build requires it.

Added the nginx `web` service to the existing Compose stack, local preview port,
build-context validation and web commands. The existing database volume and API
configuration remain in place. `docs/bikeyard-setup.md` gives the exact Coolify
routing and BIKEYARD form values, including preserving `/api/v1` and retaining the
old API origin during migration. No live configuration has been changed.

Verified the real GitHub repository is public `whekin/dhava`, with no releases;
`whekin/nakvali` is not currently resolvable. Landing links use the real releases
page, not a nonexistent APK. Release documentation now distinguishes future
public distribution from owner builds containing the private-alpha access key.

Added `assetlinks.json` for `com.nakvali.app`, trusting only the existing release
SHA-256 verified with `:app:signingReport`. Android claims the exact HTTPS callback
path and gives a forthcoming-integration message, clearing callback data rather
than pretending to complete OAuth. Google auth and Strava behavior are retained.
OAuth/PKCE, manual uploads and auto-sync are not implemented in this foundation.

Validation: Astro typecheck and static build pass with zero diagnostics; generated
routes and association checks pass; Docker image builds; actual nginx HTTP checks
confirm 200 pages, JSON association without redirects, no-store/no-referrer
callback that does not reflect code/state, and 404s instead of successful HTML for
missing/API routes. Playwright verified the landing at 1440px and 390px and Privacy
at 320px without horizontal overflow, broken images or browser errors. Android
`:app:assembleDebug :app:lintDebug` pass. No device installation or public release.

Pending owner input: actual public support/privacy email and Coolify URL/access.
DNS for `nakvali.whekin.dev` resolves to `152.53.136.162`, but the server currently
returns an untrusted self-signed certificate. Website publication, actual proxy
routing, trusted TLS and release-device domain verification remain to be done
before the BIKEYARD registration URLs are live. The local preview is reviewable;
no Git commit, push, public APK or production deployment was performed.

## 2026-09-21 — Public contact confirmed

The owner approved `whekins@gmail.com` for the site's public contact and identified
`https://coolify.whekin.dev` as the dashboard. Set that email as the site and Docker
build default, keeping an environment override. Rebuilt the static pages to remove
the missing-contact preview. The dashboard opens successfully over HTTPS but
requires login in the available browser session; no production settings changed.
Coolify will manage the website TLS certificate after the HTTPS service routes
are configured and deployed. The website changes still need to reach Git before
Coolify can build them.

## 2026-09-21 — Public website verified after Coolify deployment

The owner deployed commit `3be3f5a` through Coolify; deployment
`cv0wz388zeueeo7ikfvcypsd` is finished and the application reports healthy.
Verified trusted HTTPS without bypassing certificate checks: `/`, `/privacy`,
`/terms`, `/oauth/bikeyard/callback` and `/.well-known/assetlinks.json` all return
200 at `nakvali.whekin.dev`, without redirects. Privacy/Terms contain the approved
contact; the callback sends `Cache-Control: no-store`; the association is JSON
with the expected release certificate. `/api/v1/me` returns the Go API's JSON 401
`access_key_required`, confirming the API path survives proxy routing. BIKEYARD
registration URLs are now live. On-device App Link verification, authenticated
API validation and BIKEYARD OAuth/upload remain separate pending checks.

## 2026-09-21 — Direct BIKEYARD connection and upload queue

Integrated the owner's sandbox and live public client IDs in Android. The profile
now offers environment selection, PKCE connection/cancellation, explicit private
or public visibility, optional automatic uploads and disconnect. The activity
export sheet offers a confirmed whole-ride BIKEYARD upload in place of the Strava
button; the old Strava implementation is retained for compatibility. Google/Firebase
identity is unchanged. Default environment is sandbox, auto-upload is off, and
visibility is private.

The device talks directly to BIKEYARD with `profile:read rides:write`. A strict
HTTPS callback, random state/verifier, consumed-once code and serialized rotating
refresh protect the connection. Android Keystore encrypts the atomic state file
outside backups. Refresh ambiguity/process death requires reconnecting; revocation
failure is reported honestly after clearing the local connection. No credentials
or callback URLs are logged by the integration.

WorkManager sends Rust-derived riding-only TCX. The first prepared file and
metadata remain fixed across retries, using a stable recording-based external ID.
The queue is bound to environment and rider, respects Retry-After, polls processing
receipts and distinguishes a duplicate in another rider's account. Read scopes
are deliberately absent; the completed action opens BIKEYARD rather than inventing
a per-ride URL that the upload receipt does not provide.

Automatic consent is captured on newly saved entries, so startup can repair a
save/enqueue crash without sweeping old rides into uploads. Restore clears incoming
consent markers. Offline mode stops automatic work; enabling auto-upload explicitly
disables Offline mode. Saving reads an immutable consent snapshot without waiting
for the mutex used by network operations. Manual retry remains possible after auto
uploads are disabled. Neither edits nor reprocessing silently replace a remote ride.

Verified: 137 core recording unit tests pass, including 22 new protocol/state tests;
Android debug/release assembly, release vital lint and debug lint pass. Release
certificate matches the published association. Built with the new API origin
`https://nakvali.whekin.dev`. Updated only the existing Pixel 9 Pro emulator in
place (debug key); did not uninstall/clear data or touch the phone. Checked profile
in light/dark themes and at 1080x1920 with font scale 1.3, then restored dimensions,
font scale and light theme. Verified a forged callback is visibly rejected after
process restart. Existing emulator rides were not edited or uploaded.

Not yet verified: an actual authorized sandbox round trip or upload, connected-state
UI with a real BIKEYARD session, release App Link dispatch on a device, and BIKEYARD's
TCX distance/pause/shuttle semantics. Chrome's first-run Terms require owner consent;
asked separately and left acceptance pending. No live authorization/upload, public
APK release, website update, commit or push was performed for this implementation.
The locally signed APK is ready for a controlled connection test. Published website
copy still describes the integration as forthcoming until a release is actually
shipped.
