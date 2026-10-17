plugins {
    alias(libs.plugins.android.library)
    // Kotlin support comes from AGP's built-in Kotlin. `kotlin.android` is NOT
    // applied (it is an error on AGP 9).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.wg1337.regtrail.shared"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        // OFF by default in AGP 9; enabled explicitly where needed.
        buildConfig = false
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
