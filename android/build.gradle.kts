// Top-level build file. Plugin versions are declared once here (via the version
// catalog) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // No kotlin.android: AGP 9 compiles Kotlin itself and rejects the separate
    // plugin. The compose and serialization plugins are still applied per module.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}
