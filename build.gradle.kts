// Root build script.
//
// AGP 9 ships its own built-in Kotlin support. Two consequences:
//   1. `org.jetbrains.kotlin.android` must NOT be applied anywhere (it is an
//      error on AGP 9). Kotlin compilation is provided by the Android plugins.
//   2. The Kotlin *version* used for the Kotlin compiler plugins (compose,
//      serialization) is pinned here on the buildscript classpath by declaring
//      them with `apply false`. Without this, a module requesting a specific
//      Kotlin version fails with "already on the classpath with an unknown
//      version".
//
// Shared Android settings (compileSdk / buildToolsVersion / minSdk / Java
// level) are declared per module rather than injected from here: AGP 9 removed
// the type parameters from `CommonExtension`, and a `subprojects {}` block that
// configures every Android module via `plugins.withType` fights the
// configuration cache for no real benefit at this size (see docs/PLAN.md 3.4).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
