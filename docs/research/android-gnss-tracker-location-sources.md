# Android location sources for long-running GNSS recording

Research date: 2026-08-29. Sources are limited to official Android/Google
documentation and the projects' source code. Repository links are pinned to the
HEAD commits inspected on that date rather than to moving branches.

## Conclusion

For Nakvali's **canonical ride recording**, use Android's platform
`LocationManager` with an explicit `LocationManager.GPS_PROVIDER`. Keep Google
Play services fused location for map browsing, a non-authoritative pre-start
hint, or another feature where an approximate answer is preferable to no answer.
Do not silently feed fused/network fallbacks into the raw ride stream.

This is not an unusual design. The two closest mature sport/track recorders in
this review, OpenTracks and GPSLogger, explicitly subscribe to `GPS_PROVIDER`.
Projects whose primary goal is periodic location sharing or fleet telemetry tend
to offer fused and platform implementations as build variants. OsmAnd, which has
both navigation and GPX recording use cases, exposes Google Play services versus
Android API as a setting.

The change is logical for Nakvali, but it is not yet proof that direct GNSS fixes
the observed Galaxy S25 failure. It removes the specific possibility of FLP
substituting a coarse network estimate, makes source provenance unambiguous, and
opens direct GNSS diagnostics. Samsung could still throttle or break the platform
GNSS provider. A screen-on/screen-off field test must therefore be part of the
change, not an assumption made before it.

## What the Android APIs actually promise

### Direct platform provider

Android defines [`GPS_PROVIDER`](https://developer.android.com/reference/android/location/LocationManager#GPS_PROVIDER)
as the standard GNSS provider and says explicitly that it determines location
using GNSS satellites. Responsiveness and accuracy still depend on signal
conditions. An explicit provider request therefore answers a question fused
location cannot answer: the callback is a GNSS-derived fix or there is no fix.

`LocationManager` also exposes
[`registerGnssStatusCallback`](https://developer.android.com/reference/android/location/LocationManager#registerGnssStatusCallback(java.util.concurrent.Executor,%20android.location.GnssStatus.Callback)),
which can report GNSS start/stop, time to first fix, visible satellites, and which
satellites were used in a fix. These are useful diagnostics independent of the
coordinate's self-reported accuracy.

### Google Play services fused provider

Google recommends `requestLocationUpdates` for continuous tracking and supports
it from a foreground location service. However, its
[`LocationRequest` documentation](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest)
states that clients cannot select the exact sensors used to satisfy an FLP
request: FLP may combine many sensors. `PRIORITY_HIGH_ACCURACY` is a power-versus-
accuracy preference, not a contract that every result came from GNSS. The same
documentation recommends a short interval for real-time location and warns that
some devices can produce faster than 1 Hz, so an explicit minimum interval is
important.

The
[`FusedLocationProviderClient` reference](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)
says a foreground location service is sufficient for background delivery, and
that FLP may hold a wakelock while delivering active (non-passive) requests. It
does not promise source purity or minimum accuracy for a high-accuracy request.

### Screen off and foreground service

Android considers an app with a running foreground service to be using
**foreground location**, and explicitly says access is retained when the user
presses Home or turns the display off. The service must declare type `location`;
on current Android it also needs the corresponding foreground-service permission
and a fine or coarse runtime location permission. See
[`Request location permissions`](https://developer.android.com/develop/sensors-and-location/location/permissions)
and the official
[`location` foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#location).

This separates two concerns:

1. a foreground location service preserves the app's right and process context
   to receive locations with the screen off;
2. the selected provider determines whether those locations must be GNSS-derived
   or may be fused/network estimates.

`ACCESS_BACKGROUND_LOCATION` is not required merely because the screen turns off
after a user starts a correctly declared foreground location service from a
visible activity. It becomes relevant when location is accessed without that
foreground state or when a location foreground service must be created while the
app itself is already in the background.

## What current open-source trackers do

| Project and inspected revision | Location source | Long-running practice | Meaning for Nakvali |
|---|---|---|---|
| OpenTracks `e28a84f` (2026-08-28) | Direct platform `GPS_PROVIDER` only for the internal phone GPS | Foreground `location|connectedDevice` service and partial wakelock around sensor acquisition | Closest precedent: a sport recorder chooses GNSS provenance over an approximate fallback |
| GPSLogger `5a37e85` (2026-08-14) | User-selectable direct `GPS_PROVIDER`, `NETWORK_PROVIDER`, and `PASSIVE_PROVIDER`; no FLP in the recording path | Sticky foreground location service; registers GNSS status and NMEA listeners when satellite logging is selected | Sources are explicit and independently selectable; network points are not silently relabelled as GPS |
| OsmAnd `c4aae32` (2026-08-29) | Configurable: GMS build defaults to FLP high accuracy; Android API option uses direct GPS; separate network updates can act as fallback | Foreground location navigation/GPX service; source can be changed while running | A valid hybrid for navigation, where some position may be better than none, but not a good canonical evidence policy for Nakvali |
| OwnTracks `b9f32df` (2026-08-29) | GMS flavor uses FLP; OSS flavor maps high accuracy to direct GPS and lower priorities to fused/network/passive platform providers | Periodic location-sharing design with `ACCESS_BACKGROUND_LOCATION`; its ongoing service is typed `connectedDevice`, not a continuous sport-recording location service | Shows build-flavor abstraction, but its low-duty-cycle sharing goal is materially different from a 1 Hz ride trace |
| Traccar Client `2c5cd8f` + SDK `8d42259` (2026-08) | Current client SDK defaults to FLP when Play services are available; an option or missing GMS selects platform providers, where highest/high maps to GPS | Sticky foreground location service; optional partial wakelock; activity/geofence stop detection for battery | Another explicit backend split, optimized by default for fleet presence rather than a 1 Hz sport trace |

### OpenTracks

OpenTracks' internal GPS driver pins `LOCATION_PROVIDER` to
`LocationManager.GPS_PROVIDER`, then requests unbatched high-accuracy updates at
the configured sampling interval:
[`GpsInternal.java` lines 18–64](https://codeberg.org/OpenTracksApp/OpenTracks/src/commit/e28a84fc88c60c2a2a51cf920c8b30baeca730a6/src/main/java/de/dennisguse/opentracks/sensors/driver/GpsInternal.java#L18-L64)
and
[`GpsInternal.java` lines 100–104](https://codeberg.org/OpenTracksApp/OpenTracks/src/commit/e28a84fc88c60c2a2a51cf920c8b30baeca730a6/src/main/java/de/dennisguse/opentracks/sensors/driver/GpsInternal.java#L100-L104).

Its sensor manager acquires a partial wakelock for the duration of sensor
recording:
[`SensorManager.java` lines 58–80](https://codeberg.org/OpenTracksApp/OpenTracks/src/commit/e28a84fc88c60c2a2a51cf920c8b30baeca730a6/src/main/java/de/dennisguse/opentracks/sensors/SensorManager.java#L58-L80).
The recording service is promoted with the location foreground-service type:
[`TrackRecordingService.java` lines 213–222](https://codeberg.org/OpenTracksApp/OpenTracks/src/commit/e28a84fc88c60c2a2a51cf920c8b30baeca730a6/src/main/java/de/dennisguse/opentracks/services/TrackRecordingService.java#L213-L222).

There is no network or fused fallback in this internal-GPS driver. External
Bluetooth sensors are separate drivers, which keeps provenance explicit.

### GPSLogger

GPSLogger separately requests satellite, network, and passive updates according
to user preferences. Satellite recording is a direct 1 Hz `GPS_PROVIDER`
subscription; it also registers `GnssStatus.Callback` and an NMEA listener:
[`GpsLoggingService.java` lines 668–781](https://github.com/mendhak/gpslogger/blob/5a37e85f70382698e513fb3c7142ce88af9b4153/gpslogger/src/main/java/com/mendhak/gpslogger/GpsLoggingService.java#L668-L781).
The passive subscription is likewise explicit:
[`GpsLoggingService.java` lines 655–664](https://github.com/mendhak/gpslogger/blob/5a37e85f70382698e513fb3c7142ce88af9b4153/gpslogger/src/main/java/com/mendhak/gpslogger/GpsLoggingService.java#L655-L664).

The service starts in the foreground with type `location`, returns
`START_STICKY`, and restores listeners after an unexpected restart:
[`GpsLoggingService.java` lines 96–150](https://github.com/mendhak/gpslogger/blob/5a37e85f70382698e513fb3c7142ce88af9b4153/gpslogger/src/main/java/com/mendhak/gpslogger/GpsLoggingService.java#L96-L150).
Its manifest declares the location foreground-service type and relevant
permissions:
[`AndroidManifest.xml` lines 6–30](https://github.com/mendhak/gpslogger/blob/5a37e85f70382698e513fb3c7142ce88af9b4153/gpslogger/src/main/AndroidManifest.xml#L6-L30),
[`AndroidManifest.xml` lines 47–51](https://github.com/mendhak/gpslogger/blob/5a37e85f70382698e513fb3c7142ce88af9b4153/gpslogger/src/main/AndroidManifest.xml#L47-L51).

### OsmAnd

OsmAnd has two implementations behind `LocationServiceHelper`. The user setting
selects Google Play services or Android API; a Google-enabled build defaults to
Google Play services:
[`OsmandSettings.java` lines 1478–1479](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/settings/backend/OsmandSettings.java#L1478-L1479)
and
[`OsmandApplication.java` lines 419–425](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/OsmandApplication.java#L419-L425).

The GMS implementation requests FLP with `PRIORITY_HIGH_ACCURACY` and a 100 ms
nominal interval:
[`GmsLocationServiceHelper.java` lines 47–83](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/helpers/GmsLocationServiceHelper.java#L47-L83).
The Android implementation explicitly chooses `GPS_PROVIDER` and requests
updates with no time or distance threshold:
[`AndroidApiLocationServiceHelper.java` lines 41–62](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/helpers/AndroidApiLocationServiceHelper.java#L41-L62).

NavigationService also asks for separate network-provider updates. It suppresses
them while following a route and for a grace period after a GPS fix; otherwise a
network update can be used:
[`NavigationService.java` lines 236–264](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/NavigationService.java#L236-L264)
and
[`NavigationService.java` lines 285–293](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/NavigationService.java#L285-L293).
The same service is promoted as a location foreground service before requesting
updates:
[`NavigationService.java` lines 125–183](https://github.com/osmandapp/OsmAnd/blob/c4aae32e8dcc84fae6d242d43e2b9579ef0b4a0f/OsmAnd/src/net/osmand/plus/NavigationService.java#L125-L183).

### OwnTracks

OwnTracks intentionally ships different GMS and OSS implementations. The GMS
implementation translates the app request into a Google request and subscribes
through `FusedLocationProviderClient`:
[`GMSLocationProviderClient.kt` lines 70–92](https://github.com/owntracks/android/blob/b9f32df0b6e23aa7c16af581c6281094e3f80ddc/project/app/src/gms/java/org/owntracks/android/gms/location/GMSLocationProviderClient.kt#L70-L92).

The OSS implementation maps `HighAccuracy` to `GPS` only. Other priorities may
subscribe to the available platform fused, network, and passive providers:
[`AospLocationProviderClient.kt` lines 17–41](https://github.com/owntracks/android/blob/b9f32df0b6e23aa7c16af581c6281094e3f80ddc/project/app/src/oss/java/org/owntracks/android/location/AospLocationProviderClient.kt#L17-L41)
and
[`AospLocationProviderClient.kt` lines 63–96](https://github.com/owntracks/android/blob/b9f32df0b6e23aa7c16af581c6281094e3f80ddc/project/app/src/oss/java/org/owntracks/android/location/AospLocationProviderClient.kt#L63-L96).

OwnTracks schedules pings according to monitoring mode rather than keeping a
sport-style 1 Hz GNSS stream:
[`BackgroundService.kt` lines 610–635](https://github.com/owntracks/android/blob/b9f32df0b6e23aa7c16af581c6281094e3f80ddc/project/app/src/main/java/org/owntracks/android/services/BackgroundService.kt#L610-L635).
Its manifest requests background location, while the persistent service itself is
typed for connected-device work:
[`AndroidManifest.xml` lines 43–47](https://github.com/owntracks/android/blob/b9f32df0b6e23aa7c16af581c6281094e3f80ddc/project/app/src/main/AndroidManifest.xml#L43-L47)
and
[`AndroidManifest.xml` lines 188–194](https://github.com/owntracks/android/blob/b9f32df0b6e23aa7c16af581c6281094e3f80ddc/project/app/src/main/AndroidManifest.xml#L188-L194).
This makes OwnTracks useful evidence about provider abstraction, but not a model
for Nakvali's continuous recording lifecycle.

### Traccar Client

The old native `traccar-client-android` repository is no longer the current
implementation. The current Flutter client delegates tracking to the open-source
Kotlin Multiplatform `traccar-client-sdk`. Its defaults are medium accuracy,
300-second interval, stop detection enabled, wakelock disabled, and platform
providers not preferred:
[`preferences.dart` lines 50–78](https://github.com/traccar/traccar-client/blob/2c5cd8fdba9d286f0862496632cc15c33d6bd872/lib/preferences.dart#L50-L78).

On Android the SDK selects FLP when Play services are available, unless
`preferPlatformProviders` is true. Otherwise it selects the platform source:
[`PlatformModule.kt` lines 21–33](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/PlatformModule.kt#L21-L33).
FLP receives a continuous request at the configured priority, interval, and
distance:
[`FusedLocationSource.kt` lines 74–89](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/FusedLocationSource.kt#L74-L89).
The platform source maps highest/high to GPS, medium to network, and low to
passive, with a fallback to an available provider if the preferred one is absent:
[`AndroidLocationSource.kt` lines 73–85](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/AndroidLocationSource.kt#L73-L85)
and
[`AndroidLocationSource.kt` lines 127–136](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/AndroidLocationSource.kt#L127-L136).

The SDK always includes a sticky foreground location service:
[`ForegroundServiceHolder.kt` lines 40–76](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/ForegroundServiceHolder.kt#L40-L76)
and
[`ForegroundServiceHolder.kt` lines 87–103](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/ForegroundServiceHolder.kt#L87-L103).
A partial wakelock is optional:
[`PlatformModule.kt` lines 41–55](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/PlatformModule.kt#L41-L55)
and
[`WakeLockHolder.kt` lines 22–43](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/WakeLockHolder.kt#L22-L43).
With stop detection enabled, activity recognition and geofencing are added, and
continuous location stops in stationary mode after requesting a final fix:
[`PlatformModule.kt` lines 41–55](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/PlatformModule.kt#L41-L55)
and
[`FusedLocationSource.kt` lines 39–65](https://github.com/traccar/traccar-client-sdk/blob/8d422598dbf0eff32261c85c51668357c54ac53f/core/src/androidMain/kotlin/org/traccar/client/FusedLocationSource.kt#L39-L65).
This is a mature resilience/battery abstraction, but its defaults target fleet
presence, not second-by-second raw sport capture.

## Trade-offs

### Explicit `GPS_PROVIDER`

Advantages:

- provider provenance is strong: accepted coordinates are GNSS-derived;
- network/cell estimates cannot silently replace a missing satellite fix;
- GNSS status, time-to-first-fix, satellites visible, and satellites used can be
  observed alongside coordinates;
- works without Google Play services;
- a gap accurately represents loss of usable GNSS, which is preferable evidence
  for segment timing and anti-cheat.

Costs:

- no indoor or deep-canyon network fallback, so gaps and slower cold starts are
  expected;
- sustained GNSS is power-expensive;
- it does not bypass Android/OEM lifecycle and power management;
- direct GNSS is a narrower API contract, not a guarantee that every vendor
  implementation is bug-free.

### GMS `FusedLocationProviderClient`

Advantages:

- fast and useful estimates across GNSS, Wi-Fi, cellular, and sensors;
- FLP can optimize shared hardware use and power;
- good fit for map blue-dot, navigation continuity, one-shot location, weather,
  location sharing, and telemetry where some location can be better than none;
- convenient availability and batching APIs.

Costs for Nakvali's canonical recorder:

- high accuracy does not force GNSS and does not guarantee a maximum error;
- source changes are opaque to the client;
- a coarse fallback can be temporally fresh yet geographically useless for a
  trail trace;
- filtering after receipt prevents false geometry but cannot recover the missing
  GNSS samples or explain why the source changed.

### Hybrid

A hybrid is useful only if the channels have explicit roles. Examples:

- direct GPS is authoritative ride evidence;
- fused is a pre-start/map hint and may help the UI center quickly;
- a network fix may be recorded as diagnostics or a clearly separate
  non-authoritative event, never as a canonical track point.

Subscribing simultaneously and merging locations only by reported accuracy is
not enough: it recreates the ambiguity this change is meant to remove. If both
channels are temporarily recorded during an experiment, persist their source as
separate diagnostic streams and never deduplicate them into the production raw
GPS stream by timestamp alone.

## Concrete recommendation for Nakvali

Nakvali already has the correct lifecycle scaffolding: a foreground service of
type `location`, a persistent notification, a dedicated sensor thread, and a
partial wakelock. The current recording path differs at only the source boundary:
it requests GMS FLP with high accuracy at the normal cadence and balanced power
in transport mode.

Implement the source change as follows:

1. Introduce a small recording-location source interface so lifecycle code does
   not depend directly on either GMS or `LocationManager`.
2. Make the production ride source use `LocationManager` /
   `LocationManagerCompat.requestLocationUpdates` with the explicit
   `GPS_PROVIDER`, the existing normal ride interval, minimum callback interval,
   and zero batching (`maxUpdateDelayMillis = 0`).
3. Keep the source invariant in transport power-save mode. Reduce the direct GPS
   cadence if required, but do not switch the raw stream to balanced fused
   location merely because the activity was classified as transport.
4. Register `GnssStatus.Callback` for the lifetime of the ride and capture at
   least GNSS started/stopped, TTFF, visible count, and used-in-fix count. Also
   capture provider enabled state and `getLocationPowerSaverMode()` transitions.
   These are diagnostic facts, not alternative coordinates.
5. Treat absence of a GNSS fix as a gap. Continue IMU/barometer recording and let
   fusion express uncertainty; never fill the canonical trace with network or
   fused coordinates.
6. Keep FLP for the browse-map blue dot. For pre-start readiness, either require
   the first acceptable direct-GPS fix or show fused centering and GNSS readiness
   as distinct states so the UI cannot call a network fix “GPS ready”.
7. Preserve the existing foreground service and partial wakelock. Do not add
   `ACCESS_BACKGROUND_LOCATION` solely for screen-off recording started by the
   user from the visible recorder screen.

Verification should compare, on the affected Galaxy S25:

- 3+ minutes screen on followed by 3+ minutes screen off;
- the current fused build versus the direct-GPS build on the same route;
- coordinate cadence/accuracy, callback gaps, `Location.provider`, GNSS
  started/stopped, TTFF, visible/used satellites, service survival, power-saver
  mode, and battery consumption;
- open sky and a deliberately poor-signal section.

Success is not “the map always has a line.” Success is: direct GPS continues at
the expected cadence with the screen off, or, when GNSS genuinely fails, Nakvali
records an honest gap accompanied by enough GNSS state to explain it. If direct
GPS degrades at exactly the same moment on Samsung, the investigation moves below
FLP to OEM GNSS/power behavior; the architecture is still better because the
coarse-source substitution has been eliminated.
