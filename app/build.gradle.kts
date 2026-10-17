plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Code package name (lower case, per Java/Kotlin convention).
    namespace = "io.github.wg1337.regtrail"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // The user-facing package name, and the module's identity for LSPosed.
        // Everything that needs the runtime package (provider authorities,
        // component names, LSPosed scope entries) reads BuildConfig.APPLICATION_ID
        // rather than a hard-coded literal.
        applicationId = "io.github.wg1337.RegTrail"

        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.0-p1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // OFF by default in AGP 9; we need BuildConfig.APPLICATION_ID.
        buildConfig = true
    }

    buildTypes {
        debug {
            // No applicationIdSuffix on purpose: the runtime package name must be
            // exactly io.github.wg1337.RegTrail, both for the user's expectation
            // and because the module names itself in scope entries.
            isMinifyEnabled = false
        }
        release {
            // R8 is important for the module payload: the injected code should be
            // small. Rules live in proguard-rules.pro and include the mandatory
            // `-adaptresourcefilecontents META-INF/xposed/java_init.list`.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // META-INF/xposed/* MUST survive into the APK root: that is where the
            // framework looks for java_init.list and module.prop.
            merges += "META-INF/xposed/*"
        }
    }
}

// ---------------------------------------------------------------------------
// Injected payload asset (P6)
// ---------------------------------------------------------------------------
//
// `:payload:dexPayload` generates `payload.dex` into the *root* build directory
// (never committed; `build/` is ignored). Registering that directory as an extra
// asset root puts it at `assets/regtrail/payload.dex` in the APK, which is where
// the patcher reads it from.
//
// The old `android.sourceSets … assets.srcDir(provider)` route is rejected by AGP
// 9 ("You cannot add Provider instances to the Android SourceSet API"), so the
// variant API is used instead. It takes a path, not a Provider, which means the
// task dependency has to be declared by hand — hence the two `dependsOn` hooks
// below (`merge<Variant>Assets` is the task that actually consumes the directory;
// `preBuild` is the backstop in case AGP renames it again).
val payloadAssetsPath = rootProject.layout.buildDirectory
    .dir("payload-assets")
    .get()
    .asFile
    .absolutePath

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(payloadAssetsPath)
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(":payload:stagePayloadDex")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(":payload:stagePayloadDex")
}

dependencies {
    implementation(projects.ui)
    implementation(projects.shared)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ---- LSPosed module API -------------------------------------------
    // compileOnly is MANDATORY: the framework refuses to load a module that
    // bundled the API classes ("The Xposed API classes are compiled into the
    // module's APK"). Do not change this to `implementation`.
    compileOnly(libs.libxposed.api)

    // Ships the ${applicationId}.XposedService ContentProvider, which is how the
    // settings app asks the framework to grant scope and reads/writes remote
    // preferences. This one IS bundled on purpose.
    implementation(libs.libxposed.service)

    // ---- Root ----------------------------------------------------------
    // Used to publish the config snapshot to /data/local/tmp for the module,
    // and (from P3) to run the terminal.
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

    // ---- Persistent injection (P6) -------------------------------------
    // The no-root path: copy a target APK, inject the payload dex + assets,
    // rewrite the binary manifest, re-sign, export. All of it runs on-device.
    implementation(libs.apksig)
    implementation(libs.manifest.editor)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)

    debugImplementation(libs.compose.ui.tooling)

    // ---- JVM unit tests -------------------------------------------------
    // `ScriptStore` (versioned files on disk) and `LineRangeEdit` (the
    // arithmetic behind read_file/write_file) are pure JVM logic. Testing them
    // here is what makes "3 saves then restore v1" and "replace lines 2..3,
    // reject out-of-range" checkable without a device.
    testImplementation(libs.junit)
}
