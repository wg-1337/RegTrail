// `:payload` — the code that gets dexed *into other apps' APKs*.
//
// This module is deliberately NOT a dependency of `:app`: nothing at runtime links
// against it, and shipping it inside RegTrail's own dex would be pointless and
// would make the payload look like part of the settings app. What `:app` consumes
// is exactly one generated artifact — `payload.dex` — produced by the
// `dexPayload` task below and merged into RegTrail's assets, from where the P6
// patcher copies it into a target APK as `classesN.dex`.
//
// Why a hand-wired javac + d8 pair instead of dexing this module's AGP output:
//   * it pins the bytecode/API level the payload is built for (Java 11 / API 28),
//     which is what the *patched* app will run;
//   * it does not depend on AGP-internal task names or intermediate directories,
//     which change between AGP majors (this project is on AGP 9);
//   * it uses the platform's own d8 from the installed build-tools, i.e. the same
//     dexer AGP would have used, so the produced dex is equivalent.
// The module is still a normal `com.android.library`, so `:payload:build` compiles
// it through AGP as well and it stays inspectable.

import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.wg1337.regtrail.payload"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // `AppComponentFactory` is API 28, and the patcher raises the patched
        // APK's `minSdkVersion` to 28 for exactly that reason. The payload can
        // therefore never run below 28.
        minSdk = 28
    }

    compileOptions {
        // Java 11 keeps the payload source plain (no Kotlin stdlib in the dex) while
        // staying well inside what d8 desugars for API 28.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // OFF by default in AGP 9; the payload needs no BuildConfig.
        buildConfig = false
    }
}

// ---------------------------------------------------------------------------
// Build-time `payload.dex`
// ---------------------------------------------------------------------------
//
// Output location is the *root* build directory, not this module's: `:app` has to
// register the directory as an asset root, and a single, project-independent
// convention (`<root>/build/payload-assets/`) is far less brittle than reaching
// into another module's intermediates. It is generated, and `build/` is ignored by
// version control, so `payload.dex` is never committed.

/** `sdk.dir` from `local.properties`, else the standard environment variables. */
fun regtrailAndroidSdkDir(): File {
    val properties = Properties()
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        localProperties.inputStream().use { properties.load(it) }
        val raw = properties.getProperty("sdk.dir")
        if (!raw.isNullOrBlank()) {
            return File(raw)
        }
    }
    val fromEnv = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_SDK_HOME")
    return File(
        fromEnv ?: error(
            "Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME",
        ),
    )
}

/** `"36.1.0"` -> `[36, 1, 0]`, for version-ordered directory selection. */
fun regtrailVersionKey(name: String): List<Int> =
    name.split('.', '-', '_').mapNotNull { it.toIntOrNull() }

/** Sorts directories newest-version-first, numerically. */
val regtrailNewestFirst = Comparator<File> { left, right ->
    val a = regtrailVersionKey(left.name)
    val b = regtrailVersionKey(right.name)
    var diff = 0
    for (index in 0 until maxOf(a.size, b.size)) {
        diff = b.getOrElse(index) { 0 } - a.getOrElse(index) { 0 }
        if (diff != 0) break
    }
    if (diff != 0) diff else right.name.compareTo(left.name)
}

/** Newest directory under [parent] that contains every path in [required]. */
fun regtrailNewestDirWith(parent: File, vararg required: String): File? =
    parent.listFiles().orEmpty()
        .filter { candidate -> candidate.isDirectory && required.all { File(candidate, it).isFile } }
        .sortedWith(regtrailNewestFirst)
        .firstOrNull()

val sdkDir = regtrailAndroidSdkDir()

/**
 * `android.jar` to compile and d8 against.
 *
 * AGP resolves `compileSdk = 37` to a directory whose name is not simply
 * `android-37` on newer SDK layouts (`android-37.0` exists on this machine), so
 * both spellings are tried before falling back to the newest installed platform.
 */
val payloadAndroidJar: File = run {
    val platforms = File(sdkDir, "platforms")
    val compileSdk = libs.versions.compileSdk.get()
    val preferred = listOf("android-$compileSdk", "android-$compileSdk.0")
        .map { File(platforms, it) }
        .firstOrNull { File(it, "android.jar").isFile }
    val resolved = preferred ?: regtrailNewestDirWith(platforms, "android.jar")
    ?: error("No platforms/android-*/android.jar under $platforms")
    File(resolved, "android.jar")
}

/** d8 from the same build-tools AGP is configured with, else the newest present. */
val payloadD8Jar: File = run {
    val buildTools = File(sdkDir, "build-tools")
    val preferred = File(buildTools, "37.0.0")
    val resolved = preferred.takeIf { File(it, "lib/d8.jar").isFile }
        ?: regtrailNewestDirWith(buildTools, "lib/d8.jar")
        ?: error("No build-tools/*/lib/d8.jar under $buildTools")
    File(resolved, "lib/d8.jar")
}

val payloadClassesDir = layout.buildDirectory.dir("payloadClasses")
val payloadJarDir = layout.buildDirectory.dir("payloadJar")
val payloadD8Archive = layout.buildDirectory.file("payload-d8.zip")
val payloadAssetsDirFile = rootProject.layout.buildDirectory.dir("payload-assets").get().asFile

/** Compiles the payload sources against `android.jar` at Java 11 / no Kotlin. */
val compilePayload = tasks.register<JavaCompile>("compilePayloadJava") {
    group = "payload"
    description = "Compiles the injected payload (plain Java, no Kotlin) against android.jar."

    // Reproducible input wiring: sources + the exact android.jar + JDK options.
    source(fileTree("src/main/java") { include("**/*.java") })
    classpath = files(payloadAndroidJar)
    destinationDirectory.set(payloadClassesDir)
    options.release.set(11)
    options.encoding = "UTF-8"
    // `-parameters` is not needed; the payload reflects on nothing.
    options.compilerArgs.addAll(listOf("-Xlint:-options", "-nowarn"))
}

/**
 * Packs the compiled classes into a jar.
 *
 * d8 accepts class files, jars and zips as program input, but explicitly *not* a
 * directory ("Unsupported source file type"), so the jar is what makes the d8 step
 * work at all.
 */
val jarPayloadClasses = tasks.register<Jar>("jarPayloadClasses") {
    group = "payload"
    description = "Packs the compiled payload classes into a jar for d8."

    dependsOn(compilePayload)
    archiveFileName.set("payload-classes.jar")
    destinationDirectory.set(payloadJarDir)
    from(payloadClassesDir)
    include("**/*.class")
}

/**
 * Dexes the payload jar with the platform d8 (`--min-api 28`).
 *
 * The output is a `.zip` holding `classes.dex` because d8 requires its output to
 * either exist already (directory) or be an archive — an archive needs no
 * pre-created directory, so the task stays a plain [JavaExec] with no script-level
 * `doFirst`/`doLast` closure (which the configuration cache rejects in build
 * scripts). `--release` is intentionally not passed: keeping line numbers makes an
 * on-device payload crash readable in logcat.
 */
val dexPayload = tasks.register<JavaExec>("dexPayload") {
    group = "payload"
    description = "Runs d8 over the payload jar, producing a zip that contains classes.dex."

    dependsOn(jarPayloadClasses)
    inputs.files(payloadJarDir.map { it.file("payload-classes.jar") })
    inputs.file(payloadD8Jar)
    inputs.file(payloadAndroidJar)
    outputs.file(payloadD8Archive)

    classpath = files(payloadD8Jar)
    mainClass.set("com.android.tools.r8.D8")
    args = listOf(
        "--min-api", "28",
        "--lib", payloadAndroidJar.absolutePath,
        "--output", payloadD8Archive.get().asFile.absolutePath,
        payloadJarDir.get().asFile.resolve("payload-classes.jar").absolutePath,
    )
}

/**
 * Stages the dex at `<root>/build/payload-assets/regtrail/payload.dex`.
 *
 * That path is what `:app` registers as an extra asset root, so the file lands in
 * the settings APK as `assets/regtrail/payload.dex` — the exact name [ApkPatcher]
 * looks for. Renaming here (instead of `classes.dex`) is deliberate: inside a
 * patched APK the file *becomes* `classesN.dex`, so `payload.dex` is the only name
 * that says what the asset is for someone inspecting the APK later.
 */
val stagePayloadDex = tasks.register<Copy>("stagePayloadDex") {
    group = "payload"
    description = "Stages payload.dex into the shared asset directory consumed by :app."

    dependsOn(dexPayload)
    from(zipTree(payloadD8Archive))
    include("classes.dex")
    rename("classes\\.dex", "payload.dex")
    into(File(payloadAssetsDirFile, "regtrail"))
}

// `:payload:assemble` produces the artifact that `:app` actually consumes.
tasks.named("assemble") {
    dependsOn(stagePayloadDex)
}
