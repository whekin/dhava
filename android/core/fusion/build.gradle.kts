// :core:fusion — Kotlin facade over the Rust fusion-core crate (UniFFI).
//
// COMMITTED ARTIFACTS: src/main/jniLibs/<abi>/libfusion_core.so and the
// generated bindings in src/main/java/com/dhava/fusion/ are build outputs of
// fusion/crates/fusion-core, committed to the repo so the app builds without
// a local Rust toolchain. Trade-off accepted for now; CI will own artifact
// generation later. After ANY fusion-core change, regenerate both with:
//   fusion/scripts/build-android.sh
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dhava.core.fusion"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The UniFFI-generated bindings load libfusion_core.so through JNA.
    // Must be the @aar artifact: it bundles the per-ABI libjnidispatch.so
    // that the plain jar lacks.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    compileOnly(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
