set shell := ["bash", "-euc"]

# List the available project commands.
default:
    @just --list

# Build the Android debug APK.
dev: android-dev

# Build the signed Android release APK.
prod: android-prod

# Run the standard checks for every workspace.
check: android-check backend-check fusion-check deploy-check

# Build the Android debug APK.
android-dev:
    cd android && ./gradlew :app:assembleDebug

# Build the signed Android release APK (fails closed without release signing).
android-prod:
    cd android && ./gradlew :app:assembleRelease

# Build the signed Android release app bundle.
android-prod-bundle:
    cd android && ./gradlew :app:bundleRelease

# Run Android unit tests, lint and a debug build.
android-check:
    cd android && ./gradlew test lintDebug assembleDebug

# List connected Android devices and their serials.
devices:
    adb devices -l

# Install the debug APK in place on one exact ADB device.
install-dev serial: android-dev
    adb -s "{{serial}}" install -r android/app/build/outputs/apk/debug/app-debug.apk

# Install the signed release APK in place on one exact ADB device.
install-prod serial: android-prod
    adb -s "{{serial}}" install -r android/app/build/outputs/apk/release/app-release.apk

# Run the Go API locally (DATABASE_URL is optional at startup).
backend-dev:
    cd backend && make run

# Vet, test and build the Go API.
backend-check:
    cd backend && make vet test build

# Test and lint the Rust fusion workspace.
fusion-check:
    cd fusion && cargo test && cargo clippy

# Validate the Compose build context used locally and by Coolify.
deploy-check:
    ./deploy/check-compose.sh

# Start the local API and PostGIS stack with private development defaults.
stack-up:
    POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-dhava-local}" API_ACCESS_KEY="${API_ACCESS_KEY:-dhava-local}" PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://127.0.0.1:8080}" docker compose --project-directory . -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml up --build

# Stop the local stack without deleting its database volume.
stack-down:
    POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-dhava-local}" API_ACCESS_KEY="${API_ACCESS_KEY:-dhava-local}" PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://127.0.0.1:8080}" docker compose --project-directory . -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml down
