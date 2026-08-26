# iOS port: platform constraints and framework options

Date: 2026-08-25

**Conclusion:** `fusion-core` survives every option — UniFFI has tier-1 Swift support, so
the 11 k LOC Rust engine is ported by adding a build target, not by rewriting anything.
Flutter and React Native are the wrong lever: neither can host the part of Nakvali that
is actually hard on iOS (continuous background GPS + high-rate IMU capture), so both
would still require a native Swift recorder while forcing a rewrite of ~12.7 k LOC of
Compose UI. **Recommended: keep Android native, add a native SwiftUI iOS app sharing
`fusion-core` via `uniffi-bindgen-swift`.**

## What exists today

| Layer | Size | Portability |
|---|---|---|
| `fusion-core` (Rust) | 11,154 LOC | Portable as-is. Pure Rust, no Android coupling — only `std::fs`, `serde`, `flate2`. Android appears only in comments and in one optional input (`ActivityState` from activity recognition). |
| Generated UniFFI Kotlin bindings | 5,207 LOC | Regenerated per platform; not hand-written code. |
| Compose UI (`@Composable` files) | 12,658 LOC | Not portable. Rewrite in SwiftUI, or rewrite in Dart/TSX. |
| Non-UI Kotlin (recorder, stores, repos, sync) | ~9,000 LOC | Logic is portable in principle; the platform-facing half (`RecordingService`, sensors, foreground service, WorkManager) is Android-specific by nature and needs a Swift twin regardless of framework. |
| Kotlin tests | 2,805 LOC | Android-side only. |

## iOS platform constraints (the real cost)

Nakvali's recorder acquires at 200 Hz accel/gyro, 50 Hz mag, 10 Hz baro, 1 Hz GPS
(`RecordingService.kt:89-94`), persists an adaptive 20 Hz stream with a full-rate
200 Hz pre-roll, and feeds live fusion at 50 Hz (`proto/raw-recording-format.md`).
iOS cannot reproduce that acquisition profile.

### 1. IMU is capped at ~100 Hz, deliberately

Apple DTS confirms there is no supported way for a third-party iOS app to sample the
accelerometer above ~100 Hz, and that the cap is enforced on purpose ("waking a thread
100× per sec is at the outer edge of what would generally be considered reasonable
behavior… the kernel starts complaining at 150 thread wakes/second"). No entitlement
lifts it. `CMBatchedSensorManager` (200 Hz device motion / 800 Hz accelerometer) is
**watchOS-only**, Series 8 / Ultra. `CMSensorRecorder` records up to 12 h in the
background but at a fixed 50 Hz and accelerometer-only — no gyro.

**Impact on Nakvali:** the 50 Hz live path and the 20 Hz persisted stream are
unaffected. What degrades is the 200 Hz pre-roll around airtime events — jump
detection resolution halves on iOS. `motion.rs` already reduces ~200 Hz to a 20 Hz
max-error envelope, so the envelope survives; only the fine structure is lost.
Recordings are already self-describing (`meta.os`), so a per-platform rate is
representable without a format change, but algorithm behaviour on a 100 Hz source
must be validated before shipping.

### 2. Barometer is capped at 1 Hz, fixed

`CMAltimeter` update interval is not configurable — 1 Hz, versus Nakvali's 10 Hz on
Android. `pressure` is delivered in **kPa**, not hPa. Elevation quality on iOS will be
measurably worse than on Android; `canonical.rs` barometric elevation needs revisiting
for that input.

Additional gotcha: since **iOS 17.4** `startRelativeAltitudeUpdates()` silently returns
no data unless motion authorization has been triggered; the working pattern is to wrap
the start inside `CMMotionActivityManager.queryActivityStarting(from:to:to:)`.
`NSMotionUsageDescription` is mandatory.

### 3. There is no foreground service, and no background mode for CoreMotion

CoreMotion has no background mode of its own. Motion callbacks stop within seconds of
backgrounding **unless a Core Location session keeps the process alive**. With
`allowsBackgroundLocationUpdates = true` the device-motion callbacks continue for the
whole background period — but this is an undocumented side effect, not a contract.

Required configuration to avoid background suspension (post-iOS 16.4 rules):

- `allowsBackgroundLocationUpdates = true`
- `distanceFilter = kCLDistanceFilterNone`
- `desiredAccuracy` at least `kCLLocationAccuracyHundredMeters`
- `showsBackgroundLocationIndicator = true`
- `pausesLocationUpdatesAutomatically = false`
- start the session in the **foreground** — starting it while already backgrounded
  frequently fails with `CLError.denied`

Reported failure modes in the wild: location sessions dying after 60–130 minutes even
with `authorizedAlways`; motion updates needing re-initialisation after a
background/foreground transition. A watchdog that detects stalled motion callbacks and
restarts both managers is mandatory, not optional. Nakvali's existing
`RecordingRecovery` and `RecordingHealth` concepts map directly onto this — the OEM
`o-kill` work on Android is the same shape of problem.

### 4. App Review

The `location` background mode used purely as a keep-alive is a documented rejection
pattern (Guideline 2.5.4). Nakvali is a genuine GPS ride recorder with a persistent,
user-facing location feature, so the entitlement is defensible — but the IMU stream
must be presented as supporting the location feature, and Apple historically asks for
a battery-use disclaimer in the App Store description for continuous background GPS.

### 5. Everything else ports cleanly

- **Map:** MapLibre Native has a first-class iOS SDK — same C++ engine as the Android
  SDK already in use, offline regions included.
- **Auth:** Firebase Auth + Google Sign-In have iOS SDKs.
- **Sync:** `WorkManager` → `BGTaskScheduler` + background `URLSession`.
- **Activity recognition:** Android's activity recognition input in `live.rs` maps to
  `CMMotionActivityManager`.
- **Storage:** raw `.jsonl.gz` files in the app container; `fusion-core` reads paths.

## Framework options

### A. Native iOS (SwiftUI) + shared `fusion-core` — recommended

Add `uniffi-bindgen-swift` output to `fusion/scripts/`, build for
`aarch64-apple-ios`, `aarch64-apple-ios-sim`, `x86_64-apple-ios`, `lipo` the simulator
slices, package as an XCFramework consumed as a Swift Package. `uniffi-bindgen-swift`
runs in library mode and generates XCFramework-compatible modulemaps directly, which
removes the manual `module.modulemap` rename that the generic `uniffi-bindgen -l swift`
path requires. `cargo swift` automates the whole packaging step if desired.

- **Rust:** 0 changes. Same crate, same UniFFI annotations, new target.
- **Android:** 0 changes. Zero regression risk to a recorder that took a year of
  field work to make trustworthy.
- **Cost:** the iOS recorder in Swift (unavoidable in every option) + iOS UI in
  SwiftUI (~12.7 k LOC of Compose to re-express).
- **Risk:** two UI codebases to keep in sync forever.

### B. Kotlin Multiplatform, native UI per platform (KMP logic, SwiftUI + Compose)

Move the ~9 k LOC of non-UI Kotlin into `commonMain`; keep both UIs native. Rust is
reached through **Gobley** (`dev.gobley.cargo` + `dev.gobley.uniffi`), the maintained
successor to the trixnity KMP UniFFI bindings, which Mozilla's uniffi-rs lists as the
external KMP project. It supports Android, JVM and Kotlin/Native; iOS uses
`staticlib`, Android uses `cdylib`, so `fusion-core`'s `crate-type` needs
`"staticlib"` added.

- **Rust:** survives; one extra crate-type and a different bindgen.
- **Saves:** repository, store, sync and model code shared instead of duplicated.
- **Costs:** Gobley is pre-1.0 (0.3.x), versioned independently of uniffi-rs, and
  binding stability across versions is explicitly not guaranteed. The current Android
  UniFFI path would be replaced by a younger toolchain — a real regression risk to a
  working Android build.
- **Verdict:** the right *second* step, after iOS exists and the duplication is
  measurable. Not the first step.

### C. Compose Multiplatform (shared Compose UI on iOS)

CMP for iOS went Stable in 1.8.0 (May 2025) and is production-viable in 2026: Metal
rendering, ~1.5 MB runtime overhead, VoiceOver support. It would preserve most of the
12.7 k LOC of Compose.

- **But:** still needs Xcode, Swift and native iOS knowledge for exactly the layer
  that matters here — CoreMotion, CoreLocation, background execution, MapLibre as a
  platform view. Nakvali's UI is Material 3 Expressive alpha-pinned; a CMP port
  inherits that pin plus CMP's own material3 version skew.
- **Verdict:** plausible, but it buys UI reuse in exchange for taking on a second
  UI-toolkit risk while the hard iOS work stays unchanged. Reasonable only if the
  Compose UI is judged more valuable than an iOS-idiomatic feel.

### D. Flutter

`flutter_rust_bridge` is the most production-hardened Rust bridge of the two
cross-platform options — 2.13.0, Flutter Favorite, stable 2.x line. `maplibre_gl`
supports Android/iOS/Web with offline regions and PMTiles.

- **Rust:** survives, but through a **different, Dart-specific bridge**. The existing
  UniFFI annotations do not transfer; the whole FFI surface (`FusionCore.kt` facade,
  ~30 exported functions and types) is redefined. UniFFI's multi-language value is
  discarded.
- **Kills:** all 21.7 k LOC of hand-written Kotlin. The Android recorder — the most
  field-hardened part of the product — is rewritten as a native Android plugin plus a
  Dart layer.
- **Background:** "it's a platform problem that Flutter sits on top of." Continuous
  GPS + IMU still requires native Swift and native Kotlin behind platform channels.
  Plugins that promise otherwise (e.g. silent-audio keep-alive) are exactly the
  rejection pattern above.
- **Also:** MapLibre is a platform view in Flutter — no widget composition inside the
  map, and only a subset of the native SDK API is exposed.

### E. React Native

`uniffi-bindgen-react-native` is the one option that **keeps the existing UniFFI
annotations**, generating Turbo Modules from the same Rust. Same annotations →
Kotlin, Swift, RN and WASM. MapLibre RN v11 (2026) is new-architecture-only with a
rebuilt offline manager.

- **But:** younger and churnier — ~477 stars, uniffi 0.31 changed method checksums so
  bindings must be regenerated across upgrades, a rename to
  `uniffi-bindgen-javascript` is planned, and recent releases still fixed basic
  platform issues (Windows paths, Android dynamic library support).
- **Background:** same as Flutter — native Swift and Kotlin required anyway.
- **Kills:** the same 21.7 k LOC of Kotlin, and adds a JS runtime to a battery- and
  thermal-sensitive recorder.

## Recommendation

1. **Now:** extend `fusion/scripts/` with an iOS build (`build-ios.sh`) producing an
   XCFramework via `uniffi-bindgen-swift`. Cheap, additive, zero risk to Android, and
   it proves the engine claim before any UI decision is made.
2. **Then:** prototype the iOS recorder in Swift — `CLLocationManager` +
   `CMMotionManager` + `CMAltimeter`, writing the same `.jsonl.gz` format — and run a
   1–2 h field test mirroring the Android Phase 1 test. This measures the actual cost
   of constraints 1–3 with real data instead of estimates.
3. **Then:** decide UI. Native SwiftUI by default; revisit CMP only if the recorder
   prototype shows the platform layer is smaller than feared.
4. **Later, optional:** move shared non-UI Kotlin into KMP with Gobley once the
   duplication between the two apps is measurable and Gobley has matured past 0.x.

Do not adopt Flutter or React Native for this product. They do not solve the iOS
problem (which is Apple's background-execution model, not the UI toolkit), and they
charge a full rewrite of a recorder whose reliability is the product.

## Open questions

- Does `fusion-core` produce acceptable airtime and gate-crossing results from a
  100 Hz / 1 Hz-baro source? Needs a decimated-replay experiment against existing
  Android recordings before any iOS commitment.
- Should `meta` gain an explicit acquisition-rate field, or is `os` enough to infer
  it during recomputation?
- Does the iOS location-session watchdog need a different recovery contract than
  Android's `RecordingRecovery`, given sessions can die silently after 60–130 min?

## Sources

- [uniffi-bindgen-swift — UniFFI user guide](https://mozilla.github.io/uniffi-rs/next/swift/uniffi-bindgen-swift.html)
- [mozilla/uniffi-rs](https://github.com/mozilla/uniffi-rs)
- [Rust on iOS: XCFramework & Swift Package Packaging — Stadia Maps / Ferrostar](https://stadiamaps.com/blog/ferrostar-building-a-cross-platform-navigation-sdk-in-rust-part-2/)
- [Accelerometer sampling rate limits for third-party iOS apps — Apple Developer Forums](https://developer.apple.com/forums/thread/813136)
- [What's new in Core Motion — WWDC23](https://developer.apple.com/videos/play/wwdc2023/10179/)
- [CMSensorRecorder.recordAccelerometer(forDuration:)](https://developer.apple.com/documentation/coremotion/cmsensorrecorder/recordaccelerometer(forduration:))
- [CMAltimeter update interval not sufficient for sport applications](https://developer.apple.com/forums/thread/123983)
- [iOS 17.4 CMAltimeter not working — Apple Developer Forums](https://developer.apple.com/forums/thread/747797)
- [CMDeviceMotion events are gone once App enters background](https://developer.apple.com/forums/thread/126045)
- [allowsBackgroundLocationUpdates — Apple Developer Documentation](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates)
- [App rejected due to UIBackgroundModes (Guideline 2.5.4)](https://developer.apple.com/forums/thread/771202)
- [gobley/gobley — Embed Rust into your Kotlin Multiplatform project](https://github.com/gobley/gobley)
- [Gobley UniFFI Gradle plugin](https://gobley.dev/docs/gradle-plugins/uniffi/)
- [Compose Multiplatform 1.8.0: iOS is Stable and production-ready — JetBrains](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
- [flutter_rust_bridge — pub.dev](https://pub.dev/packages/flutter_rust_bridge)
- [jhugman/uniffi-bindgen-react-native](https://github.com/jhugman/uniffi-bindgen-react-native)
- [maplibre/flutter-maplibre-gl](https://github.com/maplibre/flutter-maplibre-gl)
- [maplibre/maplibre-react-native](https://github.com/maplibre/maplibre-react-native)
- [MapLibre Newsletter April 2026](https://maplibre.org/news/2026-05-02-maplibre-newsletter-april-2026/)
- [Mobile Background Execution: iOS Background Modes, Android WorkManager, Dart — freeCodeCamp](https://www.freecodecamp.org/news/mobile-background-execution-ios-background-modes-android-workmanager-and-background-services-in-dart)
