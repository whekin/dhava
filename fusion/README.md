# Nakvali fusion

Rust is the single implementation of Nakvali's ride reconstruction and segment
timing logic.

- `crates/fusion-core` parses raw GPS/IMU/barometer recordings and implements
  bounded fusion, canonical ride analysis, activity classification, airtime,
  segment authoring, gate crossing and uncertainty.
- `crates/uniffi-bindgen` generates the Kotlin interface used by Android.
- `crates/fusion-worker` is a server-side verification skeleton. It is not part
  of the production deployment and does not receive complete raw rides.

The Android app runs `fusion-core` on-device through UniFFI. Generated Kotlin
bindings and native libraries are committed so a normal app build does not need
Rust installed.

## Commands

From the repository root:

```sh
just fusion-check
```

Or directly:

```sh
cd fusion
cargo test
cargo clippy
```

After changing the UniFFI surface or Rust implementation used by Android,
rebuild the bindings and native libraries with:

```sh
./fusion/scripts/build-android.sh
```

That script requires the configured Android NDK, `cargo-ndk`, and the Rust
targets for the Android ABIs shipped by the app.
