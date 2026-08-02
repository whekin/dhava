# Nakvali

Nakvali is an offline-first Android ride tracker built around downhill segments:
reliable recording, honest timing with uncertainty, local segment matching and
sensor-assisted track reconstruction.

The repository and established technical identifiers still use `dhava`. In
particular, Android keeps `com.dhava.app` so signed updates preserve the private
alpha's irreplaceable on-device recordings.

The app is currently a private alpha. It is designed to keep recording without
connectivity and to preserve the rider's original sensor data so improved
algorithms can recompute old rides later.

## Architecture

The phone is the primary computer and the source of truth for a ride:

1. Android records raw GPS, IMU and barometer samples and keeps them on-device.
2. The Rust `fusion-core` runs on Android through UniFFI and produces the fused
   track, ride statistics, airtime and segment timing.
3. The Go API is the sync and integration boundary for processed artifacts,
   segment results and social data. It does not receive complete raw recordings
   in the production configuration.
4. Processed results may include a compact evidence pack. A short raw window can
   be requested later for KOM verification without changing the raw-on-device
   default.

Live recording and local segment timing work without the backend. See
[`docs/VISION.md`](docs/VISION.md) for the product direction and
[`docs/DECISIONS.md`](docs/DECISIONS.md) for the architectural record.

## Repository

| Path | Stack | Purpose |
| --- | --- | --- |
| `android/` | Kotlin, Jetpack Compose | App, recording service and on-device features |
| `fusion/` | Rust | Shared fusion, ride analysis and segment timing core |
| `backend/` | Go, chi, pgx | API for processed data, segments and integrations |
| `proto/` | OpenAPI + Markdown | Contracts shared by the app and API |
| `deploy/` | Docker Compose | Coolify production stack and deployment notes |
| `docs/` | Markdown | Vision, roadmap, decisions and worklog |

## Prerequisites

- [`just`](https://just.systems/) for the commands below
- Android Studio with its JBR (Java 17) and the Android SDK
- Go 1.26 for the API
- Rust with Cargo for `fusion-core`
- Docker with Compose for the local API/PostGIS stack
- ADB for installing an APK from the command line

The Android app can be built without a local Rust toolchain because the generated
UniFFI bindings and Android native libraries are committed. Rebuild those artifacts
only when changing `fusion-core` or its public interface.

## Quick start

```sh
just --list
just dev                 # debug APK
just prod                # signed release APK; fails if signing is not configured
just check               # Android, Go, Rust and Compose checks
```

Build outputs:

- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `android/app/build/outputs/apk/release/app-release.apk`
- Release bundle: `android/app/build/outputs/bundle/release/app-release.aab`

To install without clearing app data, pass the exact ADB serial:

```sh
just devices
just install-dev emulator-5554
just install-prod 0123456789ABCDEF
```

Installation uses `adb install -r` and never uninstalls the existing app. Android
will reject the update if its signing certificate differs, which protects local
recordings from an accidental destructive reinstall. Release signing and backend
configuration are documented in
[`docs/release-build.md`](docs/release-build.md).

## Development commands

| Command | What it does |
| --- | --- |
| `just android-dev` | Build the debug APK |
| `just android-prod` | Build the signed release APK |
| `just android-prod-bundle` | Build the signed release AAB |
| `just android-check` | Run Android tests, lint and debug assembly |
| `just backend-dev` | Run the Go API locally; database is optional at startup |
| `just backend-check` | Vet, test and build the Go API |
| `just fusion-check` | Test and lint the Rust workspace |
| `just stack-up` | Start the local API and PostGIS stack |
| `just stack-down` | Stop the local stack without deleting its database volume |
| `just deploy-check` | Validate the portable Coolify Compose build context |

`stack-up` supplies private local defaults when the corresponding environment
variables are absent and publishes the API only on `127.0.0.1:8080`. Production
configuration does not use this local override and must follow
[`deploy/README.md`](deploy/README.md); those defaults are not production secrets.

For current progress and known open questions, read
[`docs/ROADMAP.md`](docs/ROADMAP.md) and the latest entries in
[`docs/WORKLOG.md`](docs/WORKLOG.md).
