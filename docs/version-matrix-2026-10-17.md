# Buildable version matrix — Compose Multiplatform + Material 3 + LSPosed module + NDK

**Compiled:** 2026-10-17 · **Scope:** brand-new Android project, planned 2026
**Method:** every version below was read from a live `maven-metadata.xml`, a Gradle services endpoint, an Android/Kotlin doc page, or a first-hand build file already vendored under `.research/`. Nothing is from memory.
**Tooling caveat:** the `web_fetch` tool is broken in this sandbox (`URL hostname ... resolves to a non-public IP address`). All HTTP was done with `pwsh` + `Invoke-WebRequest`. Every URL below is reproducible that way.

Local machine facts used throughout:

| Fact | Value |
|---|---|
| Android SDK | `F:\Android\androidsdk` |
| Platforms present | `android-30`, `android-34`, `android-36`, `android-36.1`, `android-37.0` |
| Build-tools present | `33.0.1, 34.0.0, 35.0.0, 36.0.0, 36.1.0, 37.0.0` |
| NDK present | `28.2.13676358` (**r28c**) |
| `F:\Android\androidsdk\cmake` | **does not exist — CMake is not installed** |
| Gradle wrapper dists cached | `gradle-9.4.1-bin`, `gradle-9.6.1-bin` |
| JDK | `C:\jdk21` → **OpenJDK 21.0.10+7-LTS (Microsoft-13106404)** |

---

## (a) Complete version table

### a.1 Toolchain

| Component | Pin | Latest available | Confirm URL |
|---|---|---|---|
| **AGP** | **9.3.1** (see §B for why not 9.4.1) | 9.4.1 stable; 9.5.0-alpha10 latest | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml |
| AGP, latest stable 8.x | 8.13.2 | — | same |
| **Gradle** | **9.6.1** (in local wrapper cache) | 9.8.1 | https://services.gradle.org/versions/current · https://services.gradle.org/versions/all |
| **Kotlin / KGP** | **2.4.21** | 2.4.21 stable (2.5.0-Beta1 latest) | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml |
| Kotlin 2.3.x tail | 2.3.0, 2.3.10, 2.3.20, 2.3.21 | — | same |
| Kotlin 2.2.x tail | 2.2.0, 2.2.10, 2.2.20, 2.2.21 | — | same |
| **KSP** | **2.3.12** | 2.3.12 | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml |
| **JDK** | **21** (`C:\jdk21` 21.0.10) | AGP needs ≥17 | https://developer.android.com/build/releases/gradle-plugin |
| **compileSdk** | **36** + `compileSdkMinor = 1` (= API 36.1) — or **37** | 37 is max for AGP 9.1–9.4 | see §C |
| **targetSdk** | **36** | 37 allowed by AGP 9.1+ | https://developer.android.com/google/play/requirements/target-sdk |
| **minSdk** | **27** | CMP floor is 21; LSPosed floor is 8.1 = API 27 | https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html |
| **buildToolsVersion** | **36.0.0** (AGP min+default) or `37.0.0` (installed) | 37.0.0 | https://developer.android.com/build/releases/agp-9-3-0-release-notes |
| **ndkVersion** | **`"28.2.13676358"`** (installed, AGP default) — see §D for the API-37 caveat | `30.0.16248370` latest | https://dl.google.com/android/repository/repository2-3.xml |
| **CMake** | **`"3.31.6"`** (must be installed first) | 4.1.2 latest; 3.31.6 / 3.30.x available | same repository2-3.xml |

> **KSP version scheme changed.** It is no longer `<kotlin>-<ksp>` (last old-style release was `2.2.21-2.0.5`); from `2.3.0` onward it is plain semver and decoupled from the Kotlin version. AGP 9.0's docs still quote the old form (`2.2.10-2.0.2`) as its minimum.

### a.2 Compose

| Component | Version | Confirm URL |
|---|---|---|
| **Compose Multiplatform plugin** (`org.jetbrains.compose`) | **1.12.2** | https://repo1.maven.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml |
| **Compose compiler plugin** (`org.jetbrains.kotlin.plugin.compose`) | **2.4.21** (== Kotlin, always) | https://kotlinlang.org/docs/multiplatform/compose-compiler.html |
| **Compose BOM** (`androidx.compose:compose-bom`) | **2026.09.00** | https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml |
| ↳ `androidx.compose.runtime:runtime` | 1.12.1 | BOM POM: https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom |
| ↳ `androidx.compose.ui:ui` | 1.12.1 | same |
| ↳ `androidx.compose.foundation:foundation` | 1.12.1 | same |
| ↳ `androidx.compose.material:material` (M2) | 1.12.1 | same |
| ↳ **`androidx.compose.material3:material3`** | **1.4.0 (stable)** | same |
| ↳ `androidx.compose.material3.adaptive:adaptive` | 1.3.0 | same |
| ↳ `androidx.compose.material:material-icons-extended` | 1.7.8 (frozen) | same |
| CMP `compose.runtime` / `compose.ui` / `compose.foundation` / `compose.material` | 1.12.2 | https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui/maven-metadata.xml |
| **CMP `compose.material3`** | ⚠️ **1.12.0-alpha03** (→ AndroidX M3 **1.5.0-alpha22**) | CMP 1.12.2 release notes: https://github.com/JetBrains/compose-multiplatform/releases |
| CMP `compose.material3.adaptive` | 1.3.0 | https://repo1.maven.org/maven2/org/jetbrains/compose/material3/adaptive/adaptive/maven-metadata.xml |
| CMP lifecycle (`org.jetbrains.androidx.lifecycle`) | 2.11.0 | https://repo1.maven.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml |
| CMP navigation (`org.jetbrains.androidx.navigation:navigation-compose`) | 2.9.2 stable (2.10.0-rc01 latest) | https://repo1.maven.org/maven2/org/jetbrains/androidx/navigation/navigation-compose/maven-metadata.xml |

### a.3 AndroidX / other libraries

| Coordinate | Version | Confirm URL |
|---|---|---|
| `androidx.activity:activity-compose` | **1.13.0** | https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml |
| `androidx.lifecycle:lifecycle-runtime-compose` | **2.11.0** | https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/maven-metadata.xml |
| `androidx.navigation:navigation-compose` | **2.10.2** | https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/maven-metadata.xml |
| `androidx.navigation3:navigation3-runtime` / `-ui` | 1.2.0 (stable since Nov 2025) | used by JingMatrix/Vector `.research/Vector/gradle/libs.versions.toml` |
| `androidx.core:core-ktx` | 1.19.1 | https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml |
| `androidx.room:room-runtime` | 2.8.5 | https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml |
| `androidx.datastore:datastore-preferences` | 1.2.1 (1.3.0-rc01 latest) | https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/maven-metadata.xml |
| `androidx.constraintlayout:constraintlayout-compose` | 1.1.2 | https://dl.google.com/dl/android/maven2/androidx/constraintlayout/constraintlayout-compose/maven-metadata.xml |
| `com.google.android.material:material` | 1.14.0 | https://dl.google.com/dl/android/maven2/com/google/android/material/material/maven-metadata.xml |
| `androidx.annotation:annotation` | 1.11.0 | `.research/Vector/gradle/libs.versions.toml` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-android` | **1.11.0** | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** (1.12.0-RC is not stable) | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml |
| `org.jetbrains.kotlinx:kotlinx-datetime` | **0.8.0** | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml |

### a.4 Xposed / LSPosed

| Coordinate | Version | Notes | Confirm URL |
|---|---|---|---|
| **`io.github.libxposed:api`** | **102.0.0** | `aar`, Apache-2.0, "Modern Xposed API"; built with Gradle 9.5.1 | https://repo1.maven.org/maven2/io/github/libxposed/api/maven-metadata.xml |
| `io.github.libxposed:annotation` | 1.0.0 | `compileOnly` only; `@SinceApi` / `@InternalApi` | https://repo1.maven.org/maven2/io/github/libxposed/annotation/maven-metadata.xml |
| `io.github.libxposed:service` | **102.0.0** | `implementation` — needed for remote preferences / scope requests (versions: 101.0.0, 102.0.0) | https://repo1.maven.org/maven2/io/github/libxposed/service/maven-metadata.xml |
| `io.github.libxposed:interface` | **102.0.0** | present on Central | https://repo1.maven.org/maven2/io/github/libxposed/interface/maven-metadata.xml |
| `io.github.libxposed:lint` | 1.0.0 | `lintPublish` for library authors | https://repo1.maven.org/maven2/io/github/libxposed/lint/maven-metadata.xml |
| `de.robv.android.xposed:api` | **82** (legacy) | `jar` (25 478 B), `pom`, `sources.jar` all live; metadata `lastUpdated=20160415` | https://api.xposed.info/de/robv/android/xposed/api/maven-metadata.xml |

### a.5 Color / theming libraries

| Coordinate | Version | License | KMP? | Color wheel? | Confirm URL |
|---|---|---|---|---|---|
| `com.github.skydoves:colorpicker-compose` | **1.3.0** (2026-09-16) | Apache-2.0 | ✅ Android / desktop / iosArm64 / iosSimulatorArm64 / macosArm64 / js / wasmJs | ❌ HSV square + alpha/brightness/hue/saturation sliders + image picking | https://repo1.maven.org/maven2/com/github/skydoves/colorpicker-compose/maven-metadata.xml |
| `com.materialkolor:material-kolor-material3` | **6.0.0** (2026-10-15) | MIT (+Apache-2.0 upstream) | ✅ android / ios / js / jvm / macosArm64 / wasmJs | ❌ scheme generator only | https://repo1.maven.org/maven2/com/materialkolor/material-kolor-material3/maven-metadata.xml |
| `com.materialkolor:material-color-utilities` | **6.0.0** | MIT (+Apache-2.0) | ✅ full KMP | ❌ engine only, **Compose-free** (deps: kotlin-stdlib, poko-annotations) | https://repo1.maven.org/maven2/com/materialkolor/material-color-utilities/maven-metadata.xml |
| `com.materialkolor:material-kolor` (aggregate) | 5.0.1 — **not republished at 6.0.0** | MIT | ✅ | ❌ | https://repo1.maven.org/maven2/com/materialkolor/material-kolor/maven-metadata.xml |
| `com.github.ajalt.colormath:colormath` | **3.7.0** | MIT | ✅ very wide native set | ❌ conversions only | https://repo1.maven.org/maven2/com/github/ajalt/colormath/colormath/maven-metadata.xml |
| `com.github.antonpopoff:colorwheel` | 1.1.13 (**2021-03-21**) | — | ❌ | ✅ but Android `View`, abandoned 5 yrs | https://repo1.maven.org/maven2/com/github/antonpopoff/colorwheel/maven-metadata.xml |
| `io.github.vanpra.compose-material-dialogs:color` | 0.9.0 (**2022-10-16**) | — | ❌ | ✅ but Material2-era, abandoned | https://repo1.maven.org/maven2/io/github/vanpra/compose-material-dialogs/color/maven-metadata.xml |

**There is no maintained Compose-Multiplatform *color wheel* library.** `colorwheel` is a 2021 Android-View artifact; `compose-material-dialogs` is a 2022 Material2 artifact. If a literal wheel is required, draw it with `Canvas` + `Color.hsv(hue, saturation, value)` (both in `androidx.compose.ui.graphics`, available through the Compose BOM) and use `colorpicker-compose` for the HSV square + sliders.

Material You (`dynamicLightColorScheme` / `dynamicDarkColorScheme`) **is** in `androidx.compose.material3`, confirmed: https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary — requires Android 12 (API 31)+ and a `Context`.

### a.6 `gradle/libs.versions.toml` (copy-pasteable)

```toml
[versions]
agp                 = "9.3.1"
kotlin              = "2.4.21"
ksp                 = "2.3.12"
composeMultiplatform = "1.12.2"
composeBom          = "2026.09.00"
# Material 3: pinned OVER the BOM on purpose. See PITFALL #4.
material3           = "1.4.0"
activityCompose     = "1.13.0"
lifecycle           = "2.11.0"
navigationCompose   = "2.10.2"
coreKtx             = "1.19.1"
coroutines          = "1.11.0"
serialization       = "1.11.0"
datetime            = "0.8.0"
libxposedApi        = "102.0.0"
libxposedAnnotation = "1.0.0"
libxposedService    = "102.0.0"
colorpicker         = "1.3.0"
materialKolor       = "6.0.0"

[plugins]
android-application             = { id = "com.android.application",             version.ref = "agp" }
android-library                 = { id = "com.android.library",                 version.ref = "agp" }
android-kmp-library             = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
kotlin-multiplatform            = { id = "org.jetbrains.kotlin.multiplatform",  version.ref = "kotlin" }
# NOTE: no org.jetbrains.kotlin.android — forbidden on AGP 9 (see PITFALL #1)
kotlin-compose                  = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization            = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-parcelize                = { id = "org.jetbrains.kotlin.plugin.parcelize", version.ref = "kotlin" }
compose-multiplatform           = { id = "org.jetbrains.compose",               version.ref = "composeMultiplatform" }
ksp                             = { id = "com.google.devtools.ksp",            version.ref = "ksp" }

[libraries]
compose-bom                     = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-material3      = { module = "androidx.compose.material3:material3", version.ref = "material3" }
androidx-compose-ui             = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics    = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling     = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-activity-compose       = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose  = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose     = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-core-ktx               = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }

kotlinx-coroutines-core         = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android      = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization-json      = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-datetime                = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }

# Xposed — compileOnly, never packaged.
libxposed-api                   = { module = "io.github.libxposed:api",        version.ref = "libxposedApi" }
libxposed-annotation            = { module = "io.github.libxposed:annotation", version.ref = "libxposedAnnotation" }
libxposed-service               = { module = "io.github.libxposed:service",    version.ref = "libxposedService" }
# Legacy 2016 stub, only if you must keep de.robv.* sources:
# libxposed-legacy = { module = "de.robv.android.xposed:api", version = "82" }

colorpicker-compose             = { module = "com.github.skydoves:colorpicker-compose", version.ref = "colorpicker" }
material-color-utilities        = { module = "com.materialkolor:material-color-utilities", version.ref = "materialKolor" }
```

### a.7 Required repositories

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // ONLY if you keep the legacy de.robv.android.xposed:api:82 dependency.
        // Confirmed live 2026-10-17 (HTTP 200, latest=82, lastUpdated=20160415).
        maven("https://api.xposed.info/") {
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}
```

---

## Compatibility matrices (all read from primary sources)

### AGP ↔ Gradle ↔ JDK ↔ max API ↔ min KGP

| AGP | min & default Gradle | max API level | min JDK | min SDK Build Tools | default NDK | min KGP |
|---|---|---|---|---|---|---|
| 9.0.x | **9.1.0** | **36.1** | 17 | 36.0.0 | 28.2.13676358 | 2.2.10 |
| 9.1.x | **9.3.1** | **37.0** | 17 | 36.0.0 | 28.2.13676358 | 2.2.10 |
| 9.2.x | **9.4.1** | **37.0** | 17 | 36.0.0 | 28.2.13676358 | 2.2.10 |
| 9.3.x | **9.5.0** | **37** | 17 | 36.0.0 | 28.2.13676358 | 2.2.10 |
| 9.4.x | **9.6.0** | **37** | 17 | 36.0.0 | 28.2.13676358 | 2.2.10 |

Sources: https://developer.android.com/build/releases/agp-9-0-0-release-notes · https://developer.android.com/build/releases/agp-9-1-0-release-notes · https://developer.android.com/build/releases/agp-9-2-0-release-notes · https://developer.android.com/build/releases/agp-9-3-0-release-notes · https://developer.android.com/build/releases/gradle-plugin

### Kotlin Multiplatform ↔ Gradle ↔ AGP (**the binding constraint for CMP**)

| Kotlin | Gradle | AGP | Xcode |
|---|---|---|---|
| **2.4.20 – 2.4.21** | **7.6.3 – 9.7.0** | **8.5.2 – 9.3.1** | 26.4 |
| 2.4.0 – 2.4.10 | 7.6.3 – 9.5.0 | 8.5.2 – 9.1.0 | 26.4 |
| 2.3.20 – 2.3.21 | 7.6.3 – 9.3.0 | 8.2.2 – 9.0.0 | 26.0 |
| 2.3.10 | 7.6.3 – 9.0.0 | 8.2.2 – 9.0.0 | 26.0 |
| 2.3.0 | 7.6.3 – 9.0.0 | 8.2.2 – 8.13.0 | 26.0 |
| 2.2.21 | 7.6.3 – 8.14 | 7.3.1 – 8.11.1 | 26.0 |

Source: https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html

> **This is why the pin is AGP 9.3.1, not 9.4.1.** Kotlin 2.4.21 (the latest stable Kotlin) officially supports AGP only up to **9.3.1** and Gradle only up to **9.7.0**. AGP 9.4.1 and Gradle 9.8.1 both sit outside the supported window for a KMP project.

### Gradle ↔ Java

| Java | Runs Gradle from |
|---|---|
| 21 | **8.5** and after |
| 25 | 9.1.0 |
| 26 | 9.4.0 |
| 27 | 9.8.0 |

Also from the same page: *"Android Gradle is tested with Android Gradle Plugin 9.0 through 9.5.0-alpha02"* and *"Kotlin Gradle is tested with Kotlin 2.0.0 through 2.4.20-RC2."*
Source: https://docs.gradle.org/current/userguide/compatibility.html

**→ JDK 21 works on every Gradle in play (9.5.0 … 9.8.1).**

### CMP ↔ Kotlin

JetBrains' position: *"The latest Compose Multiplatform is always compatible with the latest version of Kotlin. There is no need to manually align their versions."* Minimum is Kotlin 2.1.0; JetBrains recommends 2.2.20+ for iOS/web. CMP 1.12.2 is built against Kotlin 2.4.20 stdlib. The Compose compiler plugin must be applied **with the same version as the Kotlin plugin** — the docs' own example uses `kotlin = "2.4.21"` + `compose-plugin = "1.12.2"`, which is exactly the pair recommended here.
Source: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html · https://kotlinlang.org/docs/multiplatform/compose-compiler.html

CMP 1.12.2 platform floors: Android 5.0 (API 21), iOS 14, macOS 13 arm64, Windows 10, Ubuntu 20.04, WasmGC browsers. 64-bit only.

---

## (a.5) Android SDK levels in 2026

| API level | Platform | Status on this machine | Notes |
|---|---|---|---|
| 30 | Android 11 | installed | old |
| 34 | Android 14 | installed | old |
| 35 | Android 15 | **not installed** | first 16 KB-page device support; AGP 8.5.1+ for aligned packaging |
| **36** | **Android 16** (`BAKLAVA`) | installed | **Play floor since 2026-08-31** |
| **36.1** | Android 16 minor/QPR release | installed (`android-36.1`) | `Platform.Version=16`, `AndroidVersion.ApiLevel=36.1`, `ExtensionLevel=20`, `IsBaseSdk=true`, `PreviewSdkInt=0` → **stable, not preview** |
| **37.0** | **Android 17** (`CINNAMON_BUN`) | installed (`android-37.0`) | `Platform.Version=17`, `AndroidVersion.ApiLevel=37.0`, `ExtensionLevel=22`, `IsBaseSdk=true`, `PreviewSdkInt=0` → **stable, not preview** |

Sources: https://developer.android.com/guide/topics/manifest/uses-sdk-element (API-level table: "Android 17 → 37 CINNAMON_BUN", "Android 16 → 36 BAKLAVA") · local `platforms/android-3{6.1,7.0}/source.properties`

**Minor API levels** (this is new and easy to get wrong). AGP 9.4 exposes:

- `CommonExtension.compileSdkMinor : Int?` — *"Specify a minor API version for the compile SDK"* — and `compileSdk : Int?`, `compileSdkExtension : Int?`, `compileSdkPreview : String?`
- Or the newer block form (`CompileSdkSpec`): `release(version)`, `release(version) { minorApiLevel = n }`, `beta`, `canary(date)`, `preview(codeName)`, `addon(vendor, name, version)`

Real usage found in `.research/luolong47-template/libxposed-compat/build.gradle` (AGP 9.x):

```kotlin
android {
    compileSdk {
        version = release(36) {
            minorApiLevel = 1        // ← API 36.1
        }
    }
}
```

Equivalent flat form: `compileSdk = 36; compileSdkMinor = 1`.

**Play targetSdk requirement** (confirmed): *"Starting August 31 2026: New apps and app updates must target Android 16 (API level 36) or higher to be submitted to Google Play"*; existing apps need ≥35 to stay available to new users; you can request an extension to **November 1, 2026**.
Source: https://developer.android.com/google/play/requirements/target-sdk

*Caveat:* AGP 9.0 caps at API 36.1 and AGP 9.1+ at API 37. **Do not set `compileSdk = 37` on AGP 9.0.x.** AGP 9.3.1 (the recommended pin) supports API 37.

---

## (b) Recommended module layout

### b.1 The decision: plain AndroidX Compose for Android, CMP only for shared code

**Recommendation: do NOT build the Android UI with Compose Multiplatform's `compose.material3`. Build the Android UI with plain AndroidX Compose + Material 3 from the Compose BOM (2026.09.00), and use Compose Multiplatform only for shared, non-UI (or shared-UI-but-not-M3) code.**

Rationale, all verified from artifact metadata rather than docs:

1. **CMP 1.12.2's `compose.material3` resolves to `1.12.0-alpha03`**, whose Android variant depends on **`androidx.compose.material3:material3:1.5.0-alpha22`**. CMP's own release-notes "Components" table for 1.12.2 says exactly this (`org.jetbrains.compose.material3:material3*:1.12.0-alpha03` → Jetpack Material3 `1.5.0-alpha22`). `org.jetbrains.compose.material3:material3-android` has **no stable release at all** — its complete version list is `1.12.0-alpha03, 1.13.0-alpha01, 1.13.0-alpha02`.
2. **The last stable JetBrains Material3 was 1.9.0**, and its Android variants (`debugApiElements-published`, `releaseApiElements-published`) contain `dep: androidx.compose.material3:material3:1.4.0` — i.e. JetBrains used to *delegate* M3 to AndroidX on Android rather than shipping its own. From the 1.10 line onward it publishes its own `material3-android` instead. Using the 1.9.0 artifact under CMP 1.12.2 mixes a 1.9-era foundation with 1.12.2 ui — unsupported, don't.
3. **The other CMP artifacts are safe.** CMP's Android variants for `runtime`, `ui`, `foundation` are thin wrapper AARs that carry `dep: androidx.compose.runtime:runtime:1.12.1`, `androidx.compose.ui:ui:1.12.1`, `androidx.compose.foundation:foundation:1.12.1` respectively. That is exactly the Compose BOM 2026.09.00 alignment, so **no duplicate classes and no version skew** for runtime/ui/foundation. Only Material3 is the problem.
4. **Gradle conflict resolution makes the alpha non-optional.** Even if you pin `androidx.compose.material3:material3:1.4.0`, Gradle picks the highest version, and `1.5.0-alpha22 > 1.4.0`. So the moment anything on the classpath asks for the CMP M3 artifact, your Android M3 becomes an alpha. This is confirmed in the wild: `.research/Vector/gradle/libs.versions.toml` carries the comment *"Material 3 Expressive is not in any stable material3 release... Pinned explicitly OVER the Compose BOM, which resolves material3 to 1.4.0"* and pins `m3 = "1.5.0-alpha29"`.
5. **Two real 2026 projects already do exactly this**, and neither uses `org.jetbrains.kotlin.multiplatform` at all: `JingMatrix/Vector` (AGP 9.4.1, Kotlin 2.4.20, `compileSdk = 37`, BOM 2026.09.00, `manager` + `manager-ui` + `xposed` + `native/` + `services/`) and `XposedKit-template` (AGP 9.4.1, Kotlin 2.4.21, `:api` + `:app` + `:xposed`).
6. If you *do* want a single shared CMP UI, the only way to keep stable M3 is to put `androidx.compose.material3:material3:1.4.0` in `androidMain` and **never apply the `compose.material3` accessor in `commonMain`** — which means the shared UI cannot use Material 3 composables. At that point CMP buys you little for this app.

Concretely: `:shared` may use `compose.runtime`, `compose.foundation`, `compose.ui`, `compose.components.resources` from CMP 1.12.2 in `commonMain` (safe, they land on AndroidX 1.12.1). The Material 3 surface lives only in `:app`/`:ui` on Android.

### b.2 Module list

```
RegTrail/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties      # gradle-9.6.1-bin.zip
├── shared/          com.android.kotlin.multiplatform.library + org.jetbrains.kotlin.multiplatform
│                    → shared model/logic (+ optional CMP runtime/ui/foundation, NO material3)
├── native/          com.android.library   + externalNativeBuild/CMake + ndkVersion
│                    → JNI bridge, C/C++ payload, ships .so
├── xposed/          com.android.library   → XposedModule impl, META-INF/xposed/*, compileOnly libxposed api
├── ui/              com.android.library   → plain AndroidX Compose + Material 3 (BOM), theme, color picker
├── app/             com.android.application → Activity, Application, DI wiring; packages :ui + :shared
└── (optional) xposedapp/  com.android.application → separate APK if the module needs its own launcher/settings
```

If the LSPosed module and the user-facing app are the same APK, collapse `:xposed` into `:app` (that is what `.research/libxposed-example` does: one `:app` module carrying both the Activity and `META-INF/xposed/java_init.list`). If they must be separate APKs (the usual LSPosed arrangement where only the manager is installed and the module rides in it), keep `:xposed` as a library and let `:app` consume it — that is the `XposedKit-template` layout.

### b.3 `settings.gradle.kts`

```kotlin
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // only if you keep de.robv.android.xposed:api:82
        maven("https://api.xposed.info/") {
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "RegTrail"
include(":shared", ":native", ":xposed", ":ui", ":app")
```

### b.4 Root `build.gradle.kts` — the AGP 9 built-in-Kotlin pin

This is the piece that is easy to get wrong and is documented first-hand in `.research/Vector/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    // AGP 9 supplies its OWN, older Kotlin. Declaring the Kotlin plugin here with
    // `apply false` pins the version on the buildscript classpath for every module.
    // Without it, a module asking for a specific Kotlin version fails with
    // "already on the classpath with an unknown version".
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
}
```

### b.5 Shared Android config (from `.research/Vector/build.gradle.kts`, adapted)

```kotlin
val androidCompileSdkVersion = 36          // or 37
val androidMinSdkVersion     = 27
val androidTargetSdkVersion  = 36
val androidBuildToolsVersion = "36.0.0"
val androidNdkVersion        = "28.2.13676358"
val androidJavaVersion       = JavaVersion.VERSION_21

subprojects {
    plugins.withType(com.android.build.gradle.api.AndroidBasePlugin::class.java) {
        // AGP 9 removed the type parameters from CommonExtension.
        extensions.configure(com.android.build.api.dsl.CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            buildFeatures.buildConfig = true            // OFF by default in AGP 9 — see PITFALL #6
            compileOptions {
                sourceCompatibility = androidJavaVersion
                targetCompatibility = androidJavaVersion
            }
        }
    }
}
```

### b.6 `:shared/build.gradle.kts` — the AGP-KMP plugin, not `androidTarget()`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)      // com.android.kotlin.multiplatform.library
}

kotlin {
    // NOT androidTarget(), NOT a top-level android {} block, NOT androidLibrary {}.
    android {
        namespace = "dev.regtrail.shared"
        compileSdk = 36
        minSdk = 27
        // androidResources { enable = true }        // opt-in; res lives in src/androidMain/res
        // withJava()                                 // Java compilation is opt-in
    }
    // iosArm64(); jvm(); … as needed

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // NO compose.material3 here — see PITFALL #4
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // If shared UI code needs Material 3 on Android, depend on AndroidX here:
            // implementation(libs.androidx.compose.material3)
        }
    }
}
```

Source dirs are `src/androidMain`, `src/androidHostTest`, `src/androidDeviceTest` — **not** `src/main`. Native code is impossible in this module (see PITFALL #3).

### b.7 `:native/build.gradle.kts` + CMake

```kotlin
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "dev.regtrail.native"
    compileSdk = 36
    minSdk = 27
    ndkVersion = "28.2.13676358"

    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                    // Belt-and-braces for 16 KB pages even though NDK r28+ is aligned by default.
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"                     // must be installed via sdkmanager
            buildStagingDirectory = layout.buildDirectory.get().asFile
        }
    }

    buildTypes.getByName("release") {
        isMinifyEnabled = true                     // still valid in AGP 9.4.1
        isShrinkResources = true
    }
}
```

```cmake
# :native/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)   # NDK r28 toolchain requires >= 3.10; use 3.22+
project(regtrail_native C CXX)
set(CMAKE_CXX_STANDARD 20)
add_library(regtrail SHARED src/bridge.cpp)
target_link_libraries(regtrail PUBLIC log android)
```

### b.8 `:xposed` — module contract (library variant)

```kotlin
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "dev.regtrail.xposed"
    compileSdk = 36
    defaultConfig { minSdk = 27 }

    buildFeatures { buildConfig = true }

    packaging {
        resources {
            // REQUIRED: the modern Xposed entry-point files live in src/main/resources/META-INF.
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    buildTypes.getByName("release") {
        isMinifyEnabled = true
        proguardFiles("proguard-rules.pro")
    }
}

dependencies {
    compileOnly(libs.libxposed.api)        // supplied by the framework at runtime
    compileOnly(libs.libxposed.annotation)
    implementation(libs.libxposed.service) // the module talks to the framework through this
    implementation(project(":shared"))
}
```

Files (paths verified in `.research/libxposed-example/app/src/main/resources/META-INF/xposed/`):

```
xposed/src/main/resources/META-INF/xposed/java_init.list     →  dev.regtrail.xposed.RegTrailModule
xposed/src/main/resources/META-INF/xposed/native_init.list   →  libregtrail_hook.so
xposed/src/main/resources/META-INF/xposed/scope.list         →  com.android.settings
xposed/src/main/resources/META-INF/xposed/module.prop        →  minApiVersion=102
                                                                 targetApiVersion=102
                                                                 staticScope=true
```

Entry class: `public class RegTrailModule : io.github.libxposed.api.XposedModule` — the framework calls `attachFramework(XposedInterface)`; do not do work before `onModuleLoaded()`.

`proguard-rules.pro` — copied from `.research/luolong47-template/app/proguard-rules.pro`, which is the current correct set:

```proguard
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keepattributes RuntimeVisibleAnnotations

-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onPackageLoaded(...);
    public void onSystemServerLoaded(...);
}
-keep,allowshrinking,allowoptimization,allowobfuscation class ** implements io.github.libxposed.api.XposedInterface$Hooker
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    public *** before(***);
    public *** after(***);
    public static *** before();
    public static *** before(io.github.libxposed.api.XposedInterface$BeforeHookCallback);
    public static void after();
    public static void after(io.github.libxposed.api.XposedInterface$AfterHookCallback);
    public static void after(io.github.libxposed.api.XposedInterface$AfterHookCallback, ***);
}

-repackageclasses
-allowaccessmodification
```

### b.9 `:app` — plain AndroidX Compose + Material 3

```kotlin
plugins {
    alias(libs.plugins.android.application)
    // NO org.jetbrains.kotlin.android — it is an error on AGP 9. Kotlin comes from AGP's
    // built-in support, at the version pinned on the buildscript classpath by the root build.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Top-level `kotlin { }` — provided by AGP 9 built-in Kotlin. NOT android { kotlinOptions { } }.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

android {
    namespace = "dev.regtrail"
    defaultConfig {
        applicationId = "dev.regtrail"
        minSdk = 27
        targetSdk = 36
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":shared"))
    implementation(project(":native"))
    implementation(project(":xposed"))          // if the module rides in this APK

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)   // pinned 1.4.0 over the BOM
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.colorpicker.compose)
    implementation(libs.material.color.utilities)     // Compose-free scheme engine
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

---

## (c) Concrete pitfalls and blockers for THIS combination

| # | Pitfall / blocker | Evidence | Mitigation |
|---|---|---|---|
| **1** | **`org.jetbrains.kotlin.android` is an error on AGP 9.** Also, KMP in the same subproject as `com.android.library` / `com.android.application` is not compatible with the new DSL. | AGP 9.0 notes; Vector's `manager/build.gradle.kts` comment *"applying org.jetbrains.kotlin.android is an error since AGP 9.0"*; Google: *"There isn't a direct replacement for configuring a Kotlin Multiplatform module using com.android.application plugin. To migrate, extract your Android application to a separate Gradle module."* | Never apply `kotlin.android`. Declare `org.jetbrains.kotlin.multiplatform` / `...plugin.compose` `apply false` at the root to pin the version, apply only the specific plugin per module. The Android app is always its own `com.android.application` module. |
| **2** | **Classic CMP layout is illegal.** `androidTarget()` + `com.android.library` in one module needs opt-in on AGP 9.0+ and is removed in AGP 10. `androidLibrary {}` is deprecated since AGP 9.1.0-alpha09. | AGP 9.0 notes; Google KMP plugin doc; Kotlin compatibility guide (Kotlin 2.3.0 added the `androidTarget` deprecation warning, 2.3.10 reverted it — so on 2.4.21 the warning may or may not fire, but the plugin is the supported path either way) | Use `com.android.kotlin.multiplatform.library` with `kotlin { android { … } }`. Move sources from `src/main` → `src/androidMain`. Set `androidResources { enable = true }` if you need `res/`. |
| **3** | **`:shared` (AGP-KMP plugin) cannot build native code.** No `externalNativeBuild`, no AIDL, no RenderScript, no build variants, no BuildConfig, tests off by default, Java off by default. | https://developer.android.com/kotlin/multiplatform/plugin ("Native build support … our recommendation is to create a separate standalone `com.android.library` module") | Keep all C/C++ in a plain `:native` (`com.android.library`) module and consume it from `:shared`'s `androidMain`. Use BuildKonfig (or a hand-rolled codegen task) in place of BuildConfig for `:shared`. |
| **4** | **`compose.material3` in CMP 1.12.2 is an alpha and drags AndroidX M3 to `1.5.0-alpha22`.** Gradle conflict resolution upgrades even an explicit `1.4.0` pin. | CMP 1.12.2 release notes table; `material3-android-1.12.0-alpha03.module` `androidApiElements` → `dep androidx.compose.material3:material3:1.5.0-alpha22`; `material3-android` has zero stable versions; Vector's catalog comment | Do not use `compose.material3` in `commonMain`. Put `androidx.compose.material3:material3:1.4.0` in `androidMain` / `:ui` / `:app`, pinned over the BOM. If you adopt Material 3 Expressive, pin `1.5.0-alpha29` **explicitly and knowingly**, as Vector does, and opt in via `kotlin { compilerOptions { optIn.addAll("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi", …) } }`. |
| **5** | **`com.materialkolor:material-kolor-material3:6.0.0` also requires `org.jetbrains.compose.material3:material3:1.12.0-alpha03`** (and so does `material-kolor:5.0.1`). Adding MaterialKolor silently reintroduces PITFALL #4. | `material-kolor-material3-6.0.0.module` `metadataApiElements` deps | If you want a seed-colour scheme generator **and** stable M3, depend on **`com.materialkolor:material-color-utilities:6.0.0`** instead — it is Compose-free (only `kotlin-stdlib` + `poko-annotations`) and KMP — then feed the generated colours into `androidx.compose.material3.lightColorScheme(...)` yourself. |
| **6** | **`buildConfig` is OFF by default in AGP 9.4.1** (`BuildFeatures.buildConfig` — *"Default value is false"*). `compose`, `prefab`, `aidl` are also false. The global `android.defaults.buildfeatures.buildconfig` property was **removed** in AGP 9.0. | AGP 9.4 DSL reference; AGP 9.0 fixed-issue *"remove buildconfig defaults gradle.properties flags"* | Set `android { buildFeatures { buildConfig = true } }` per module that needs it. Delete `android.defaults.buildfeatures.buildconfig=false` from `gradle.properties` (it is stale AGP-8 config). An Xposed module usually does not need BuildConfig at all. |
| **7** | **R8 will rename your entry class and break `java_init.list`.** The module silently fails to load. | `.research/luolong47-template/app/proguard-rules.pro` | `-adaptresourcefilecontents META-INF/xposed/java_init.list` is mandatory whenever `isMinifyEnabled = true`. Also keep `RuntimeVisibleAnnotations` and the `XposedModule` / `Hooker` classes per §b.8. |
| **8** | **The `META-INF/xposed/*` files must be merged, not excluded.** Most app modules exclude `META-INF/**` (Vector's `manager` does). | `.research/libxposed-example/app/build.gradle.kts` uses `merges += "META-INF/xposed/*"; excludes += "**"` | In `:app`/`:xposed`, merge `META-INF/xposed/*` before excluding `**`. Verify with `unzip -l app-release.apk | grep xposed`. |
| **9** | **NDK r28c's sysroot maxes out at API 35.** With `compileSdk = 36/36.1/37`, platform C/C++ APIs newer than 35 are unavailable unless you install a newer NDK. | Local `ndk/28.2.13676358/meta/platforms.json` → `{"min": 21, "max": 35}`; NDK r30 changelog → *"Upgraded the max API to 37 for NDK sysroots"* | If your C/C++ only needs APIs ≤ 35 (normal for a hook library), **`ndkVersion = "28.2.13676358"` is fine and is AGP's own default**. If you need API 36/37 platform symbols, `sdkmanager "ndk;30.0.16248370"` and set `ndkVersion = "30.0.16248370"` (Vector's choice). |
| **10** | **CMake is not installed.** `F:\Android\androidsdk\cmake` does not exist, so any `externalNativeBuild.cmake` fails immediately. | local filesystem check | `sdkmanager --sdk_root=F:\Android\androidsdk "cmake;3.31.6"` (or `4.1.2`). Vector asks for `version = "3.29.8+"`; NDK r28 raised its own toolchain floor to **CMake 3.10.0**; pick 3.22+ in your `cmake_minimum_required`. |
| **11** | **16 KB page alignment** — NDK r28+ is already 16 KB-aligned by default; `PAGE_SIZE` is no longer defined by default on arm64-v8a/x86_64. | https://developer.android.com/guide/practices/page-sizes; NDK r28 changelog | Keep AGP ≥ 8.5.1 (we are on 9.3.1) and NDK r28+. Add the explicit linker flags anyway (Vector does). Opt-out knobs, if a third-party prebuilt forces it: `APP_SUPPORT_FLEXIBLE_PAGE_SIZES=false` (ndk-build) / `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=false` (CMake). Verify with `bundletool dump config --bundle=x.aab \| grep alignment` → expect `PAGE_ALIGNMENT_16K`. |
| **12** | **LSPosed's Gradle plugins are dead ends.** `org.lsposed.lsplugin.apksign:1.4` reads `Project.getProperties`, deprecated in Gradle 9 and removed in Gradle 10; 1.4 is its last release. Upstream LSPosed itself is still on AGP **8.2.1** / Kotlin **1.9.22**. | `.research/Vector/manager/build.gradle.kts` comment; `.research/LSPosed-upstream/gradle/libs.versions.toml` | Do not copy LSPosed's build files. Write the signing config yourself with `providers.gradleProperty("androidStoreFile")` (Vector's approach), as in XposedKit-template's `signingConfigs`. |
| **13** | **`getDefaultProguardFile("proguard-android.txt")` no longer works.** AGP 9.0 only supports `proguard-android-optimize.txt`. | AGP 9.0 notes (`android.r8.proguardAndroidTxt.disallowed` false→true) | Always use `getDefaultProguardFile("proguard-android-optimize.txt")` (already in §b.9). Note `proguard-android.txt` carried `-dontoptimize`; if you relied on that, add it to your own file. |
| **14** | **AGP 9 flips several defaults that change R8 behaviour**: `android.r8.optimizedResourceShrinking`, `android.r8.strictFullModeForKeepRules` (a `-keep class A` no longer implies `-keep class A { <init>(); }`), plus `android.enableAppCompileTimeRClass`, `android.sdk.defaultTargetSdkToCompileSdkIfUnset`, `android.uniquePackageNames`, `android.useAndroidx` all false→true. `android.r8.integratedResourceShrinking` and `android.enableNewResourceShrinker.preciseShrinking` are now **hard errors** if set. | AGP 9.0 notes property tables | Audit `gradle.properties` for the removed/enforced flags — `.research/luolong47-template/gradle.properties` contains three such stale flags (`android.experimental.enableNewResourceShrinker[.preciseShrinking]`, `android.defaults.buildfeatures.buildconfig`). Add `{ <init>(); }` explicitly where a keep rule must preserve a constructor — **this matters for the Xposed `Hooker` classes**, which the framework instantiates reflectively. |
| **15** | **KSP version scheme changed.** `com.google.devtools.ksp` is now plain semver (`2.3.12`), not `<kotlin>-<ksp>`. | KSP maven-metadata (160 versions; `2.2.21-2.0.5` → `2.3.0`) | Use `ksp = "2.3.12"`. Do not try to construct a `<kotlin>-2.0.x` string. AGP 9's floor is the old-form `2.2.10-2.0.2`. |
| **16** | **`kapt` still exists but `kotlinOptions {}` is the wrong block.** AGP 9 built-in Kotlin exposes the top-level `kotlin { compilerOptions { } }`. | Kotlin 2.4.0 whatsnew (kapt gained `includeCompileClasspath` exclusion support — it is maintained, not removed); Vector + XposedKit both use `kotlin { compilerOptions { } }` | Prefer **KSP2** (`2.3.12`) for Room/Moshi/Hilt. If you must use kapt, apply `org.jetbrains.kotlin.kapt` (K2 kapt) and note AGP 9.0 has an open issue: *"`legacy-kapt` plugin skips annotation processing unlike `kotlin-kapt`"*. |
| **17** | **`kotlin-parcelize` on AGP 9.** The plugin is versioned with Kotlin (`org.jetbrains.kotlin.plugin.parcelize:2.4.21`) and works through built-in Kotlin; it is *not* one of the plugins AGP forbids (only `kotlin.android` is). | AGP 9.0 notes list only `org.jetbrains.kotlin.android` as incompatible; no AGP 9 note retracts parcelize | Apply `alias(libs.plugins.kotlin.parcelize)` in Android modules that need `@Parcelize`. **Unverified by a live AGP 9.3.1 build — see §(d).** |
| **18** | **AGP 10 removes the escape hatches.** `android.newDsl=false` and `android.builtInKotlin=false` (and the per-module `android.newDsl.optOut=:module` added in AGP 9.4) all disappear in AGP 10; `renderScript` is already deprecated in 9.4.1 and removed in 10.0; `android.enableLegacyVariantApi` likewise. | AGP 9.0 notes; AGP 9.4 DSL reference (`renderScript` — *"Deprecated in 9.4.1"*) | Treat `newDsl`/`builtInKotlin` opt-outs as temporary only. Build fresh on the new DSL from day one — which this layout does. |
| **19** | **Pick a colour-picker API that actually exists.** `colorpicker-compose` has no colour wheel, and the wheel libraries on Central are abandoned (`colorwheel` 1.1.13, 2021; `compose-material-dialogs:color` 0.9.0, 2022). | Maven Central metadata + `releases.atom` | Use `com.github.skydoves:colorpicker-compose:1.3.0` (KMP, Apache-2.0) for the HSV square + sliders, and hand-draw a wheel with `Canvas` + `Color.hsv(...)` if the UI genuinely needs a wheel. Note the 1.3.0 breaking change: `ColorPickerController` no longer takes a `CoroutineScope`. |
| **20** | **Material You dynamic colour is Android-only and needs API 31+.** | `dynamicLightColorScheme` / `dynamicDarkColorScheme` in `androidx.compose.material3` | Gate with `Build.VERSION.SDK_INT >= 31`; provide a seed-colour fallback via `com.materialkolor:material-color-utilities:6.0.0` + `lightColorScheme(...)`/`darkColorScheme(...)`. A user-chosen seed colour for the floating window can go through the same path. |

---

## (d) Explicit uncertainty flags

Things I could **not** confirm, or that rest on inference:

1. **AGP 9.3.1 exact Gradle minimum is quoted from the AGP 9.3 release-notes table ("Gradle 9.5.0, default 9.5.0")**. I read the 9.0/9.1/9.2/9.3/9.4 tables but did not verify AGP **9.3.1** specifically (patch releases do not normally change the Gradle floor). Gradle 9.6.1 clears 9.5.0 either way.
2. **AGP 9.3.2 / 9.3.3 exist** (metadata tail: `9.3.0, 9.3.1, 9.3.2, 9.3.3`). Kotlin's table says Kotlin 2.4.21 supports AGP up to **9.3.1**. 9.3.2/9.3.3 are patches on the same minor and almost certainly fine, but they are formally outside the stated range. Pin 9.3.1 for zero risk.
3. **Whether `androidTarget()` in Kotlin 2.4.21 warns or errors.** The Kotlin compatibility guide documents: 2.3.0 introduced the deprecation warning, **2.3.10 reverted it**. It says nothing about 2.4.x. I did not find a 2.4 `whatsnew` statement either way. Do not rely on `androidTarget()`; use `com.android.kotlin.multiplatform.library`.
4. **`kotlin-parcelize` under AGP 9 built-in Kotlin on Kotlin 2.4.21 is not confirmed by any first-hand build file.** None of the four vendored projects (`Vector`, `XposedKit-template`, `libxposed-example`, `luolong47-template`) uses `@Parcelize`. Treat as "should work, verify on first sync".
5. **`android.buildTypes.release.optimization { enable = true }`** appears in `XposedKit-template` and as an `@Incubating` function in the AGP 9.4 DSL, while `isMinifyEnabled` remains documented in the same reference ("Specifies whether to enable code shrinking"). Both appear valid; I could not confirm whether `optimization {}` supersedes `isMinifyEnabled` in 9.4.1 or merely adds to it. `isMinifyEnabled` is used by Vector on 9.4.1 and is the safer choice.
6. **CMP 1.12.2's `compose.material3` accessor mapping** is taken from the CMP 1.12.2 GitHub release-notes "Components" table (`org.jetbrains.compose.material3:material3*:1.12.0-alpha03`). I did not decompile the Gradle plugin to verify the accessor resolves to exactly that string. The metadata corroborates it: `1.12.0-alpha03` is the newest non-1.13 material3 and `material3-android` has no stable release.
7. **No `org.jetbrains.compose.material3:material3` version between 1.10 and 1.13 is stable.** I verified the full stable tail ends at `1.9.0`, and `material3-android` contains only `1.12.0-alpha03, 1.13.0-alpha01, 1.13.0-alpha02`. I did not enumerate every 1.10.x/1.11.x version string; the "STABLE TAIL" filter would have surfaced any stable entry in the last 8, and there were none.
8. **NDK r29's and r28's max sysroot API level are not stated in their changelogs.** Only r30 says "Upgraded the max API to 37". The authoritative local file for the *installed* NDK says `max: 35`. I cannot state r29's max without downloading it.
9. **CMake 3.31.6 vs 4.1.2**: both are SDK packages (`cmake;3.31.6`, `cmake;4.1.2`). Vector requests `3.29.8+`. I did not test either against this project; 3.31.6 is the conservative pick and is ≥ the NDK r28 toolchain floor of 3.10.
10. **KSP 2.3.12's exact Kotlin compatibility** is not verified. The version decoupling means KSP 2.3.x is presumably built against a 2.3/2.4-era compiler, but I found no explicit statement. Verify with `./gradlew :app:dependencies` after first sync.
11. **`android.compileSdkMinor` vs `compileSdk { version = release(36) { minorApiLevel = 1 } }`** — I confirmed both exist in the AGP 9.4 DSL surface, and the block form is used in a real project's build file, but I did not confirm the flat `compileSdkMinor = 1` form works *specifically* on AGP 9.3.1 (the DSL reference I read is the 9.4 one). AGP 9.0 already reports API 36.1 support, so minor levels exist from 9.0, but the property name may differ by version.
12. **Whether `de.robv.android.xposed:api:82` still *functions* on modern LSPosed.** The repo is live and the jar downloads, but the artifact was last published 2016-04-15 and there is no statement I found that current LSPosed still honours the legacy `assets/xposed_init` + `meta-data` contract. The modern API's own wiki documents `XSharedPreferences` as still-supported "Legacy", and LSPosed ships a `legacy` module (Vector has `:legacy` with `de.robv.android.xposed.XposedInit`, `XC_InitPackageResources`, etc.), so legacy modules clearly still load — but for a *new* project the modern API is the right call.
13. ~~**`io.github.libxposed:service` version**~~ — **RESOLVED**: `service` and `interface` are both at **102.0.0** (versions `101.0.0, 102.0.0`), `lint` at `1.0.0`. `libxposed-example` still pins api/service `100` / `100-1.0.0` because it has not been bumped. Use `102.0.0` throughout to match `api:102.0.0`.
14. **`material-kolor:6.0.0` does not exist**; the aggregate artifact stopped at `5.0.1` while the split modules (`-core`, `-material3`, `-palette`, `-unstyled`, `-fluent`, `material-color-utilities`) went to `6.0.0` on 2026-10-15. I inferred the intended 6.0 dependency for a Material 3 app is `material-kolor-material3`, since that is the module whose POM description is *"Material You dynamic color for Compose Multiplatform"* and which depends on CMP material3. The 6.0 migration guide at https://github.com/jordond/MaterialKolor/blob/next/docs/migration-6.0.md would confirm.

---

## All source URLs

**Maven metadata / POMs / Gradle module metadata**
- https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom
- https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/androidx/constraintlayout/constraintlayout-compose/maven-metadata.xml
- https://dl.google.com/dl/android/maven2/com/google/android/material/material/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3/1.9.0/material3-1.9.0.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3/1.12.0-alpha03/material3-1.12.0-alpha03.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3-android/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3-android/1.12.0-alpha03/material3-android-1.12.0-alpha03.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui/1.12.2/ui-1.12.2.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui-android/1.12.2/ui-android-1.12.2.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/runtime/runtime-android/1.12.2/runtime-android-1.12.2.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/foundation/foundation-android/1.12.2/foundation-android-1.12.2.module
- https://repo1.maven.org/maven2/org/jetbrains/compose/material3/adaptive/adaptive/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/androidx/navigation/navigation-compose/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml
- https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml
- https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/api/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.pom
- https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.module
- https://repo1.maven.org/maven2/io/github/libxposed/annotation/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/
- https://repo1.maven.org/maven2/com/github/skydoves/colorpicker-compose/maven-metadata.xml
- https://repo1.maven.org/maven2/com/github/skydoves/colorpicker-compose/1.3.0/colorpicker-compose-1.3.0.pom
- https://repo1.maven.org/maven2/com/github/skydoves/colorpicker-compose/1.3.0/colorpicker-compose-1.3.0.module
- https://repo1.maven.org/maven2/com/materialkolor/
- https://repo1.maven.org/maven2/com/materialkolor/material-kolor/maven-metadata.xml
- https://repo1.maven.org/maven2/com/materialkolor/material-kolor-material3/maven-metadata.xml
- https://repo1.maven.org/maven2/com/materialkolor/material-kolor-material3/6.0.0/material-kolor-material3-6.0.0.module
- https://repo1.maven.org/maven2/com/materialkolor/material-kolor-material3/6.0.0/material-kolor-material3-6.0.0.pom
- https://repo1.maven.org/maven2/com/materialkolor/material-color-utilities/6.0.0/material-color-utilities-6.0.0.module
- https://repo1.maven.org/maven2/com/github/ajalt/colormath/colormath/maven-metadata.xml
- https://repo1.maven.org/maven2/com/github/ajalt/colormath/colormath/3.7.0/colormath-3.7.0.pom
- https://repo1.maven.org/maven2/com/github/antonpopoff/colorwheel/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/vanpra/compose-material-dialogs/color/maven-metadata.xml
- https://api.xposed.info/de/robv/android/xposed/api/maven-metadata.xml
- https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
- https://api.xposed.info/de/robv/android/xposed/api/82/api-82.pom

**Toolchain / SDK**
- https://services.gradle.org/versions/current
- https://services.gradle.org/versions/all
- https://docs.gradle.org/current/userguide/compatibility.html
- https://dl.google.com/android/repository/repository2-3.xml
- https://developer.android.com/build/releases/gradle-plugin
- https://developer.android.com/build/releases/agp-9-0-0-release-notes
- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://developer.android.com/build/releases/agp-9-2-0-release-notes
- https://developer.android.com/build/releases/agp-9-3-0-release-notes
- https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CommonExtension
- https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/CompileSdkSpec
- https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/ApplicationBuildType
- https://developer.android.com/reference/tools/gradle-api/9.4/com/android/build/api/dsl/BuildFeatures
- https://developer.android.com/guide/topics/manifest/uses-sdk-element
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/guide/practices/page-sizes
- https://github.com/android/ndk/wiki/Changelog-r27
- https://github.com/android/ndk/wiki/Changelog-r28
- https://github.com/android/ndk/wiki/Changelog-r29
- https://github.com/android/ndk/wiki/Changelog-r30

**Compose / Kotlin**
- https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- https://kotlinlang.org/docs/multiplatform/compose-compiler.html
- https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
- https://kotlinlang.org/docs/whatsnew24.html
- https://github.com/JetBrains/compose-multiplatform/releases
- https://github.com/JetBrains/compose-multiplatform/releases.atom
- https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary

**KMP on Android / Xposed**
- https://developer.android.com/kotlin/multiplatform/plugin
- https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules
- https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API
- https://raw.githubusercontent.com/skydoves/colorpicker-compose/main/README.md
- https://github.com/jordond/MaterialKolor/releases.atom
- https://github.com/jordond/MaterialKolor/blob/next/docs/migration-6.0.md

**First-hand build files already vendored in this workspace** (all read directly)
- `.research/Vector/{settings.gradle.kts,build.gradle.kts,gradle/libs.versions.toml,gradle.properties,gradle/wrapper/gradle-wrapper.properties}`
- `.research/Vector/{manager,manager-ui,zygisk,daemon,xposed}/build.gradle.kts`
- `.research/Vector/{native,external}/CMakeLists.txt`
- `.research/libxposed-example/{settings.gradle.kts,gradle/libs.versions.toml,app/build.gradle.kts,app/src/main/AndroidManifest.xml,app/src/main/resources/META-INF/xposed/*}`
- `.research/libxposed-api/{gradle/libs.versions.toml,api/build.gradle.kts}`
- `.research/libxposed-service/gradle/libs.versions.toml`
- `.research/LSPosed-upstream/gradle/libs.versions.toml`
- `.research/XposedKit-template/{settings.gradle.kts,build.gradle.kts,app,xposed,api}/build.gradle.kts`
- `.research/luolong47-template/{settings.gradle,build.gradle,app/build.gradle,app/proguard-rules.pro,libxposed-compat/build.gradle,gradle.properties}`
