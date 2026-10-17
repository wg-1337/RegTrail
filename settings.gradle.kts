// Enables the `projects.shared` style type-safe project accessors used in the
// module build scripts.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu lives on JitPack only (NOT Maven Central). Not used in P0 but
        // declared now so later milestones cannot forget it.
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}

rootProject.name = "RegTrail"

include(":app")
include(":ui")
include(":shared")

// The injection payload (P6). Not a dependency of `:app`: its classes are dexed
// by `:payload:dexPayload` into `<root>/build/payload-assets/regtrail/payload.dex`,
// which `:app` merges as an asset so the patcher can copy it into a target APK.
include(":payload")
