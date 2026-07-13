#!/usr/bin/env bash
#
# Builds fusion-core for Android and regenerates the Kotlin bindings.
#
# Re-run after ANY change to fusion/crates/fusion-core (exported API, algorithm
# code, uniffi.toml) — the .so files and generated Kotlin under
# android/core/fusion are committed artifacts and must stay in sync with the
# Rust source. (Trade-off: the app builds without a Rust toolchain; CI will
# own artifact generation later.)
#
# Outputs:
#   android/core/fusion/src/main/jniLibs/<abi>/libfusion_core.so   (arm64-v8a, x86_64)
#   android/core/fusion/src/main/java/com/dhava/fusion/fusion_core.kt
#
# Requirements: rustup toolchain with Android targets, cargo-ndk, Android NDK.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FUSION_DIR="$REPO_ROOT/fusion"
MODULE_DIR="$REPO_ROOT/android/core/fusion"
JNILIBS_DIR="$MODULE_DIR/src/main/jniLibs"
KOTLIN_OUT_DIR="$MODULE_DIR/src/main/java"

# Prefer the rustup toolchain over any package-manager cargo on PATH.
export PATH="$HOME/.cargo/bin:$PATH"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.1.12297006}"

cd "$FUSION_DIR"

echo "==> Cross-compiling fusion-core for Android (arm64-v8a, x86_64, release)"
# cargo-ndk drops libfusion_core.so into $JNILIBS_DIR/<abi>/ itself.
cargo ndk -t arm64-v8a -t x86_64 -o "$JNILIBS_DIR" build -p fusion-core --release

echo "==> Building host cdylib for uniffi-bindgen"
cargo build -p fusion-core

case "$(uname -s)" in
    Darwin) HOST_LIB="target/debug/libfusion_core.dylib" ;;
    *)      HOST_LIB="target/debug/libfusion_core.so" ;;
esac

echo "==> Generating Kotlin bindings -> $KOTLIN_OUT_DIR"
cargo run -q -p uniffi-bindgen -- generate \
    --library "$HOST_LIB" \
    --language kotlin \
    --out-dir "$KOTLIN_OUT_DIR"

# uniffi-bindgen emits trailing spaces in generated Kotlin. Keep committed
# bindings diff-clean so `git diff --check` remains useful after every build.
find "$KOTLIN_OUT_DIR" -name '*.kt' -exec perl -pi -e 's/[ \t]+$//' {} +

echo "==> Done"
find "$JNILIBS_DIR" -name '*.so' -exec ls -lh {} +
find "$KOTLIN_OUT_DIR" -name '*.kt' -path '*com/dhava/fusion*' -exec ls -lh {} +
