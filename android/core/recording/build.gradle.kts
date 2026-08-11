plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nakvali.core.recording"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Dev default: Android emulator loopback to a backend on the host
        // machine. Override for a physical device with the host's LAN IP:
        //   ./gradlew installDebug -PnakvaliApiBaseUrl=http://192.168.x.x:8080
        // Replace with the real backend URL (HTTPS) per build type once one exists.
        val apiBaseUrl = (project.findProperty("nakvaliApiBaseUrl") as String?)
            ?: "http://10.0.2.2:8080"
        // Private-alpha perimeter key. Keep it in ~/.gradle/gradle.properties
        // or pass it at build time; never commit the real value.
        val apiAccessKey = (project.findProperty("nakvaliApiAccessKey") as String?) ?: ""
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "API_ACCESS_KEY", "\"$apiAccessKey\"")
        // Written into the recording meta line. Keep in sync with the :app
        // versionName until version info is centralized in the catalog.
        buildConfigField("String", "APP_VERSION", "\"0.1.0\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:fusion"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
