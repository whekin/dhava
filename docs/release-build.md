# Android release builds

Dhava's release build deliberately has no debug-signing fallback. A build signed
with a different certificate cannot update an installed app, and uninstalling the
old app would remove recordings that have not been backed up.

## Backend configuration

Put owner-build values in the untracked user Gradle file
`~/.gradle/gradle.properties`:

```properties
dhavaApiBaseUrl=https://api.example.com
dhavaApiAccessKey=replace-with-the-private-alpha-key
```

The access key is compiled into the APK. It is only a private-alpha perimeter,
not user authentication, and must be rotated if the APK is distributed. Never put
the Strava client secret in Gradle properties or in the app.

You can override either property for a single build with Gradle `-P` arguments.
Without an override, debug builds target the Android emulator host at
`http://10.0.2.2:8080` and use no access key.

## Release signing

Create `android/keystore.properties` locally. It is ignored by Git:

```properties
storeFile=/absolute/path/to/dhava-release.jks
storePassword=replace-me
keyAlias=dhava
keyPassword=replace-me
```

The same values can instead be provided by CI or another machine:

```sh
export DHAVA_KEYSTORE_FILE=/absolute/path/to/dhava-release.jks
export DHAVA_KEYSTORE_PASSWORD=replace-me
export DHAVA_KEY_ALIAS=dhava
export DHAVA_KEY_PASSWORD=replace-me
```

`storeFile` may be absolute or relative to `android/`. Keep the keystore and its
passwords in separate secure backups. Losing this key means future builds cannot
update the installed app in place.

If a release key does not exist yet, Android's `keytool` can create one:

```sh
keytool -genkeypair -v \
  -keystore /secure/path/dhava-release.jks \
  -alias dhava \
  -keyalg RSA -keysize 4096 -validity 10000
```

Do not replace an existing production key with a newly generated one.

## Build and verify

From the repository root:

```sh
just prod
just android-prod-bundle
```

The APK and Play-compatible bundle are written to:

- `android/app/build/outputs/apk/release/app-release.apk`
- `android/app/build/outputs/bundle/release/app-release.aab`

The release build fails before producing an installable artifact if any signing
value is missing or the keystore cannot be found.

Inspect the APK certificate before updating a phone when there is any doubt:

```sh
apksigner verify --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
```

To compare it with an installed build, first ask Android for the installed APK
path, pull that `base.apk`, and run the same `apksigner` command on the copy:

```sh
adb -s DEVICE_SERIAL shell pm path com.dhava.app
adb -s DEVICE_SERIAL pull /data/app/.../base.apk /tmp/dhava-installed.apk
apksigner verify --print-certs /tmp/dhava-installed.apk
```

Back up important rides before changing signing or installation tooling. To
preserve application data, install only as an update:

```sh
just install-prod DEVICE_SERIAL
```

This runs `adb install -r`. It does not uninstall, downgrade, clear data or bypass
Android's certificate check. A signature mismatch should be investigated rather
than worked around on a phone containing irreplaceable recordings.
