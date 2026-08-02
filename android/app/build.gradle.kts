import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

/**
 * Release signing, read from `android/keystore.properties` (gitignored) or from
 * `NAKVALI_KEYSTORE_*` environment variables for a machine without that file.
 *
 * A missing keystore deliberately leaves the release unsigned instead of falling
 * back to the debug key: a debug-signed build cannot later be updated by a real
 * one without uninstalling, which would take the rider's recordings with it.
 * See `docs/release-build.md`.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

val releaseStoreFile = signingValue("storeFile", "NAKVALI_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "NAKVALI_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "NAKVALI_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "NAKVALI_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).none { it.isNullOrBlank() } && rootProject.file(releaseStoreFile!!).exists()

android {
    namespace = "com.nakvali.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.nakvali.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.0-test1"

        ndk {
            // Ship only the ABIs the whole app supports. MapLibre publishes
            // four, but fusion-core is cross-compiled for these two, so a device
            // resolving to armeabi-v7a installs happily and then dies with
            // UnsatisfiedLinkError on its first fusion call.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:map"))
    implementation(project(":core:recording"))
    implementation(project(":feature:record"))
    implementation(project(":feature:activity"))
    implementation(project(":feature:segments"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Fail loudly rather than handing over an APK nobody can install.
if (!releaseSigningReady) {
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
        .configureEach {
            doFirst {
                error(
                    "Release signing is not configured, so this build would be " +
                        "unsigned and impossible to install. Create a keystore and " +
                        "android/keystore.properties as described in " +
                        "docs/release-build.md, or use assembleDebug for local testing.",
                )
            }
        }
}
