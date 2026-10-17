# ROOT + In-App TERMINAL on Android — Research Report for RegTrail (2026)

**Compiled:** 2026-10-17 · **Target:** Android app with a root shell, a persistent root daemon, and an embedded terminal (Compose Multiplatform).
**Method:** every version/flag/path below was fetched from a live source on 2026-10-17. Nothing is inferred from memory. Claims I could not verify are explicitly marked `UNVERIFIED`. Sources are listed inline and consolidated in §7.

**TL;DR**
- Root shell library: **libsu 6.0.0** (JitPack only, Apache-2.0) + `:service` for the persistent daemon. No coroutine API — bridge it yourself.
- Shizuku is **not** a root path. It gives you ADB (UID 2000) unless the user already rooted and started Shizuku as root. Use it as an optional *second tier*, never as the root mechanism.
- Terminal: **Termux `terminal-emulator` + `terminal-view` 0.118.3** (`com.github.termux.termux-app:*` on JitPack). The repo is GPLv3-only **except** an explicit Apache-2.0 carve-out for exactly those two modules. `TerminalSession` and `TerminalView` both live *inside* those modules. Fallback if legal signs off against it: **xterm.js 6.0.0 (MIT)** in a WebView.
- Beware the folklore "root can't read `/data/data/<pkg>` because of SELinux MLS categories". It is **false for Magisk**: Magisk's own sepolicy makes its root domain `mlstrustedsubject` (MLS-exempt) + `permissive`. The MLS barrier is real but only bites non-`mlstrustedsubject` domains (e.g. ADB `shell` / Shizuku UID 2000).

---

## 1. Root shell libraries

### 1.1 libsu (`com.github.topjohnwu.libsu`)

| Item | Value | Source |
|---|---|---|
| Latest version | **6.0.0** (released 2024-06-28; repo last pushed 2026-03-27) | [releases/latest](https://api.github.com/repos/topjohnwu/libsu/releases/latest), [JitPack metadata](https://jitpack.io/com/github/topjohnwu/libsu/maven-metadata.xml) |
| Repository | **JitPack, NOT Maven Central** — `com/github/topjohnwu/libsu` 404s on repo1 | `https://repo1.maven.org/maven2/com/github/topjohnwu/libsu/` → 404 |
| License | **Apache-2.0** | [README](https://raw.githubusercontent.com/topjohnwu/libsu/master/README.md) |
| Modules | `core`, `service`, `nio`, and legacy `io` (`example` is the sample app) | [file listing @6.0.0](https://data.jsdelivr.com/v1/packages/gh/topjohnwu/libsu@6.0.0?structure=flat) |
| Maintenance | Alive but slow: 6.0.0 is the newest tag; no 6.1.x | tag list / CHANGELOG |

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies {
    implementation "com.github.topjohnwu.libsu:core:6.0.0"     // Shell, Shell.Job, initializers
    implementation "com.github.topjohnwu.libsu:service:6.0.0"  // RootService (root daemon over Binder)
    implementation "com.github.topjohnwu.libsu:nio:6.0.0"      // FileSystemManager / ExtendedFile over Binder
    // legacy, discouraged:
    // implementation "com.github.topjohnwu.libsu:io:6.0.0"    // SuFile, SuFileInputStream, ...
}
```

**Corrections to assumptions in the brief — these matter:**
- There is **no `libsu-extension` module**. Modules are `core` / `service` / `nio` (+ legacy `io`).
- There is **no `ShellService` class**. The persistent-root-daemon mechanism is `com.topjohnwu.superuser.ipc.RootService` with `RootService.CATEGORY_DAEMON_MODE`.
- The `busybox` module was **removed**; the CHANGELOG says "*The `busybox` module is removed. Pin the `busybox` module to version `5.0.0` if you cannot remove its usage immediately*" ([CHANGELOG](https://raw.githubusercontent.com/topjohnwu/libsu/master/CHANGELOG.md)). JitPack metadata still lists a `busybox` artifact up to 6.0.0, but the 6.0.0 source tree contains no `busybox/` directory — **do not depend on it.** Take busybox from Magisk/KernelSU at runtime instead (see §4.5).

**API surface verified in [Shell.java](https://raw.githubusercontent.com/topjohnwu/libsu/master/core/src/main/java/com/topjohnwu/superuser/Shell.java):**

| API | Signature / semantics |
|---|---|
| `Shell.setDefaultBuilder(Builder)` | set flags/initializers/timeout **before** the main shell exists |
| `Shell.getShell()` `@NonNull` | **blocking**; can block for a long time (root prompt). Do not call on the main thread. |
| `Shell.getShell(GetShellCallback)` | async; callback delivered **on the main thread** (`UiThreadHandler.executor`) |
| `Shell.getShell(Executor?, GetShellCallback)` | async with your executor; `null` executor = same thread |
| `Shell.isAppGrantedRoot(): Boolean?` | **nullable tri-state**: `true` = root shell created; `false` = definitively impossible; `null` = undetermined. Non-blocking. |
| `Shell.cmd(String...): Job` | static; uses the main shell. Equivalent to `getShell().newJob().add(...).to(ArrayList())` |
| `Shell.cmd(InputStream): Job` | sources the stream into **STDIN** (like `. script.sh`, *not* `sh script.sh`) |
| `Shell.Builder.create().setFlags(...)` | `FLAG_MOUNT_MASTER` (1<<1) → builds via `su --mount-master`; `FLAG_NON_ROOT_SHELL` (1<<0) |
| `Job.to(List)` / `Job.to(List, List)` | stdout / (stdout, stderr). **Recommended way to merge stderr** — `FLAG_REDIRECT_STDERR` is deprecated in 6.0.0 |
| `Job.exec()` / `Job.submit(cb)` / `Job.submit(Executor?, cb)` / `Job.enqueue(): Future<Result>` | sync / async-with-callback / async returning `java.util.concurrent.Future` |
| `Shell.Result` | `getOut(): List<String>`, `getErr(): List<String>`, `getCode(): Int`, `isSuccess()` |
| `Shell.Task` / `Shell#execTask` / `#submitTask` | raw STDIN/STDOUT/STDERR access (6.0.0 added `submitTask`, `Task.shellDied()`) |
| `Shell.FLAG_MOUNT_MASTER` fallback chain | `su --mount-master` → `su` → `sh`; you must read `shell.getStatus()` and check `isRoot()` because the fallback can silently give you a non-root `sh` |
| `Shell.rootAccess()` | **deprecated** — use `isAppGrantedRoot()` |

**Kotlin coroutines / Compose:** libsu ships **no coroutine or Kotlin API at all** — all four modules are plain Java (verified from the 6.0.0 file listing: only `*.java`, no `-ktx`, no `Coroutine*`). There is no `libsu-extension`. You must write the bridge (§6, snippet a). Compose-friendly: yes, trivially — libsu has no UI dependency, so it works in a Compose Multiplatform Android target; just keep `Shell.getShell()` off the main thread and note that `Shell.getShell(callback)` and `Job.submit(cb)` deliver callbacks on the **main** thread (use `Job.submit(Executor, cb)` for background delivery).

`SuFile` is `com.topjohnwu.superuser.io.SuFile` and lives in the **`io`** module; it extends `com.topjohnwu.superuser.nio.ExtendedFile` from `nio`. The 5.0.0 CHANGELOG explicitly discourages `SuFile`/`SuFileInputStream`/`SuFileOutputStream`/`SuRandomAccessFile`: "*please migrate to use `RootService` + the `nio` module for all root I/O operations.*"

### 1.2 libsu `service` mode — the persistent root daemon

`RootService` ([source](https://raw.githubusercontent.com/topjohnwu/libsu/master/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java)) is **not** an Android `Service` and needs **no `<service>` manifest entry**. It is a `ContextWrapper` constructed inside a root process; libsu launches that process through a trampoline `main.jar` shipped in `service/src/main/assets/main.jar` and an internal AIDL (`IRootServiceManager.aidl`). Communication is real Binder.

Semantics that matter for an UI-streaming daemon:
- All `RootService`s from one app share **one root process**. A root service is destroyed when the last client unbinds — *unless* it is in **Daemon Mode**.
- **Daemon Mode** = add `RootService.CATEGORY_DAEMON_MODE` (`"com.topjohnwu.superuser.DAEMON_MODE"`) to the bind `Intent`. A daemon process is per-package, survives app relaunches, and **is shared across users** on the device. It dies on app update/uninstall or an explicit `RootService.stop(intent)`.
- Only `Messenger` or AIDL works for the IPC (no direct method calls).
- `android:extractNativeLibs="false"` **is** allowed, so JNI inside the root service works.
- Debugging: if the client process has a debugger attached, the root service enables debug mode and waits for the debugger; Android Studio → *Run > Attach Debugger to Android Process* → tick *Show all processes* (Java-only debugger).
- The dev-loop gotcha documented in the Javadoc: on Android 11+ you must enable **"Always install with package manager"** in the run configuration, or code changes won't reach the root process.

### 1.3 Alternatives, and why most are traps

**Raw `Runtime.getRuntime().exec("su")` / `ProcessBuilder` — pitfalls (all verifiable from the manager sources):**
1. **`su` is not always a file on disk.** KernelSU and APatch do not place a `su` binary; they hook the kernel. KernelSU: `#define SU_PATH "/system/bin/su"` in [`kernel/feature/sucompat.c`](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/kernel/feature/sucompat.c), intercepting `faccessat`/`execve`. So `File("/system/bin/su").exists()` can be **false** while `exec("su")` still works. Conversely, `which su` may succeed on a path that `stat` denies. **Never use file existence as your root detector.**
2. **`-c` argument grouping.** MagiskSU historically treats *everything after `-c`* as part of the command (CHANGELOG v11.0: "*The su binary will now consider everything after -c as a part of the command*"). KernelSU explicitly re-joins the tail after `-c` with spaces ([su.rs](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/userspace/ksud/src/su.rs)). Don't pass multi-arg commands after `-c` and expect per-arg quoting to survive.
3. **`--mount-master` availability differs.** Magisk `-M/--mount-master` ✔; KernelSU `-M/--mount-master` ✔; APatch — `UNVERIFIED` for the flag itself, but it has a global-namespace switch file `/data/adb/.global_namespace_enable` ([defs.rs](https://raw.githubusercontent.com/bmax121/APatch/11282/apd/src/defs.rs)). libsu degrades gracefully: if `--mount-master` fails it falls back to plain `su`.
4. **`redirectErrorStream(true)`** is *not* equivalent to libsu's `Job.to(sameList, sameList)` for interactive use: with a merged stream you lose the ability to distinguish prompts, and libsu warns that `FLAG_REDIRECT_STDERR` semantics changed and are deprecated in 6.0.0.
5. **STDIN handling.** `ProcessBuilder` on Android gives you no PTY. A `su` child whose stdin is a pipe will not be interactive and will not render anything requiring a terminal (progress bars, `read`, `stty`). MagiskSU only allocates a PTY (`su/pts.rs`) when it decides it needs one; with `-c` it is explicitly non-interactive unless you pass `-i/--interactive`.
6. **A pipe-based `su` leaks a process per command.** libsu keeps one long-lived shell and multiplexes jobs, which is the actual reason it exists.

**Shizuku (`dev.rikka.shizuku`) — precise scope:**
- **Shizuku does NOT provide root.** From the Shizuku-API README: "*Shizuku can be started with ADB or ROOT, and Sui is a Magisk module, so the privilege could be ADB or ROOT. You can use `Shizuku#getUid()` to check your privilege, for ROOT it returns `0`, for ADB is `2000`.*" and "*Shell (ADB) cannot access other apps' data files `/data/user/0/<package>`*" ([Shizuku-API README](https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md)).
- So: **ADB/shell tier = UID 2000** — no `/data/data` access, no `su`, no kernel-level anything. ROOT tier is only reachable if the device is *already* rooted and the user started Shizuku as root (or uses the Sui Magisk module).
- Also stated in the same README: `newProcess` "*lacks tty support, it is not possible to implement an interactive shell with it*" — so Shizuku is **not** a terminal transport. `rish` is the sanctioned interactive-shell escape hatch.
- Shizuku's own README lists its Apache-2.0 §6 carve-outs: you may **not** use its launcher icons, may **not** name your app "Shizuku", may **not** use application id `moe.shizuku.privileged.api`, and may **not** declare `moe.shizuku.manager.permission.*`.

| Coordinate | Latest published | Where | Notes |
|---|---|---|---|
| `dev.rikka.shizuku:api` | **13.1.5** | Maven Central | `lastUpdated` = **2023-09-21** ([metadata](https://repo1.maven.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml)) |
| `dev.rikka.shizuku:provider` | **13.1.5** | Maven Central | same ([metadata](https://repo1.maven.org/maven2/dev/rikka/shizuku/provider/maven-metadata.xml)) |
| `dev.rikka.shizuku:aidl` / `:shared` | **13.1.5** | Maven Central | transitive |
| Shizuku **app** (user-installed) | **v13.6.0** (2025-05-25) | GitHub release | the API is frozen while the app moved on |

Requires `minSdk` 23 + `coreLibraryDesugaring` from API v13.1.0 onward, and a `<provider android:name="rikka.shizuku.ShizukuProvider" android:authorities="${applicationId}.shizuku" android:exported="true" android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"/>`.

**Verdict on alternatives:** Termux is not a root-shell library — its `su` usage is just `su` inside a PTY; its value is the terminal (see §4). `com.github.termux` has no root-IPC library. libsu + `RootService` is the only maintained, license-clean, non-folklore option. Keep Shizuku as an optional degraded mode for unrooted devices.

---

## 2. Root managers in 2026

Any of these can be installed on a user's device; your app must not assume Magisk.

| Manager | Latest (2026-10-17) | License | Root mechanism | Notes |
|---|---|---|---|---|
| **Magisk** | changelog **v31.0 (2026.9.4)**; GitHub `releases/latest` API still reports **v30.7 (2026-02-23)** | GPL-3.0 ([LICENSE](https://raw.githubusercontent.com/topjohnwu/Magisk/master/LICENSE)) | systemless boot-image patch + `magiskd` daemon; `su` is a real binary | See pre-release caveat below |
| **KernelSU** | **v3.3.1** (2026-10-12) | GPL-2.0 (kernel/LICENSE) | kernel mode; grants root **in kernel space** | GKI devices; `su` is a **kernel-faked** `/system/bin/su` |
| **KernelSU-Next** | **v3.4.2** (2026-10-13) | **GPL-2.0** (badge in [docs/README.md](https://raw.githubusercontent.com/KernelSU-Next/KernelSU-Next/v3.4.2/docs/README.md)) | fork of KernelSU, same kernel approach | Separate project, same `/data/adb/ksu` layout (`UNVERIFIED` for exact path identity — verify per release) |
| **APatch** | tag **11282** (2026-10-15) | **GPL-3.0** | kernel patch via [KernelPatch](https://github.com/bmax121/KernelPatch); **arm64 only**, kernels 3.18–6.12 | "APatch UI and APModule source derived and modified from KernelSU" |

**Magisk version caveat (do not skip):** the changelog and the git tag both say **v31.0 (2026.9.4)**, and it is a big one — "*[App] Complete UI overhaul with Jetpack Compose and Material 3*", "*[App] Add new built-in terminal emulator with full TTY and ANSI support*", "*[Zygisk] Support Android 17 (API 37)*". But GitHub's `releases/latest` endpoint — which by definition excludes drafts and pre-releases — returned **v30.7 (2026-02-23)**. So v31.0 is currently either a pre-release or has no published Release object. Read the changelog: <https://github.com/topjohnwu/Magisk/blob/master/docs/changes.md>. **Verify on the releases page before you pin a version string in user-facing copy.**

**Zygisk API version = `5`.** Verified twice: internal [`native/src/core/zygisk/api.hpp`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/zygisk/api.hpp) → `#define ZYGISK_API_VERSION 5`, and the *published* header for module authors, [`zygisk-module-sample/module/jni/zygisk.hpp`](https://raw.githubusercontent.com/topjohnwu/zygisk-module-sample/master/module/jni/zygisk.hpp) → `#define ZYGISK_API_VERSION 5`. (History: v3 in Magisk v24.3, v4 in v26.0, v5 now.) Magisk v30.7 added Zygisk support for Android 16 QPR2+; v31.0 adds Android 17.

### 2.1 The `su` protocol — verbatim from source

**MagiskSU** (verbatim usage string, [`native/src/core/su/su.cpp`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/su/su.cpp)):

```
MagiskSU

Usage: su [options] [--] [user [argument...]]

Options:
  -s, --shell SHELL             Use SHELL instead of the default /system/bin/sh
  -i, --interactive             Force pseudo-terminal allocation when using -c
  -g, --group GROUP             Specify the primary group
  -G, --supp-group GROUP        Specify a supplementary group
                                The first specified supplementary group is also used
                                as a primary group if the option -g is not specified
  -Z, --context CONTEXT         Change SELinux context
  -t, --target PID              PID to take mount namespace from
                                pid 0 means magisk global mount namespace
  -d, --drop-cap                Drop all Linux capabilities
  -m, -p,
  --preserve-environment        Preserve the entire environment
  -v, --version                 Display version number and exit
  -V                            Display version code and exit
  -h, --help                    Display this help message and exit

--: Force stop options parsing, and also stop when an unknown option is found
User: The user to switch to (default root), it can be name or uid
Argument: Pass it to the shell as is
```

Note `-t 0` **is** `--mount-master`: `case 'M': case 't':` with no optarg sets `req.target_pid = 0`. Magisk `su -v` prints `<version>:MAGISKSU`; `su -V` prints the numeric version code — that's your Magisk detector. Since v30.7 MagiskSU "*does not drop capabilities by default, even when switching to non-root UIDs*" — you must pass `--drop-cap` explicitly. v30.1 added the ability to restrict capabilities while still uid 0.

**KernelSU `su`** (verbatim option table, [`userspace/ksud/src/su.rs`](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/userspace/ksud/src/su.rs)):

```
KernelSU

Usage: {program} [options] [-] [user [argument...]]
  -c, --command COMMAND        pass COMMAND to the invoked shell
  -h, --help                   display this help message and exit
  -l, --login                  pretend the shell to be a login shell
  -p, --preserve-environment   preserve the entire environment
  -s, --shell SHELL            use SHELL instead of the default /system/bin/sh
  -v, --version                display version number and exit
  -V                           display version code and exit
  -M, --mount-master           force run in the global mount namespace
  -g, --group GROUP            Specify the primary group
  -G, --supp-group GROUP       Specify a supplementary group. [...]
  -W, --no-wrapper             don't use ksu fd wrapper
      --ksu-no-new-privs       Prevent this process (and its children) from privilege
                               re-escalation via KernelSU
  -Z, --context CONTEXT        Specify the SELinux context
```

KernelSU additionally rewrites legacy `-mm` → `-M` and `-cn` → `-z`. **Differences that break naive code:** KernelSU has no `-i`, no `-d`, no `-t`, but has `-l`, `-W`, `--ksu-no-new-privs`. `--mount-master` exists on both.

**APatch `su`:** the kernel execs the handler with `argv[0]` = `"/system/bin/kp"` or `"/system/bin/su"` or `"su"` or `"kp"`, and the CLI checks `if arg0.ends_with("kp") || arg0.ends_with("su")` ([`apd/src/cli.rs`](https://raw.githubusercontent.com/bmax121/APatch/11282/apd/src/cli.rs)). APatch also has a "SuperKey" which the README calls out as **"higher privileges than root access"**. Exact APatch `su` flag list: `UNVERIFIED`.

### 2.2 Manager layout, for detection and for finding tools

| | Magisk | KernelSU | APatch |
|---|---|---|---|
| Data dir | `/data/adb/magisk`, `/data/adb/modules` | `/data/adb/ksu/`, `/data/adb/modules` | `/data/adb/ap/`, `/data/adb/modules` |
| Daemon | `magiskd` | `/data/adb/ksud` (`KSUD_PATH`) | `/data/adb/apd` |
| Daemon link | — | `/data/adb/ksu/bin/ksud` | — |
| busybox | `/data/adb/magisk/busybox`, bundled in the APK as `lib/<abi>/libbusybox.so` | `/data/adb/ksu/bin/busybox` | `UNVERIFIED` |
| Policy DB | `/data/adb/magisk.db` (SQLite) | kernel-side allowlist (`kernel/policy/allowlist.c`) + app profiles | SuperKey-based |
| Module env marker | — | `KSU=true` | — |
| Temp dir | — | `/metadata/ksu/` or `/metadata/watchdog/ksu/` | `/debug_ramdisk` (fallback `/sbin`) |

Sources: KernelSU [`defs.rs`](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/userspace/ksud/src/defs.rs), [`ksud.h`](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/kernel/runtime/ksud.h), [`difference-with-magisk.md`](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/website/docs/guide/difference-with-magisk.md); Magisk [`build.py`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/build.py); APatch [`defs.rs`](https://raw.githubusercontent.com/bmax121/APatch/11282/apd/src/defs.rs).

**Magisk policy DB — exact schema** (from [`native/src/core/sqlite.cpp`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/sqlite.cpp), DB version 12). Path macro is `MAGISKDB` = `/data/adb/magisk.db`:

```sql
CREATE TABLE IF NOT EXISTS policies (uid INT, policy INT, until INT, logging INT, notification INT, PRIMARY KEY(uid));
CREATE TABLE IF NOT EXISTS settings (key TEXT, value INT, PRIMARY KEY(key));
CREATE TABLE IF NOT EXISTS strings  (key TEXT, value TEXT, PRIMARY KEY(key));
CREATE TABLE IF NOT EXISTS denylist (package_name TEXT, process TEXT, PRIMARY KEY(package_name, process));
```

`policies.policy` values (from [`SuPolicy.kt`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/app/core/src/main/java/com/topjohnwu/magisk/core/model/su/SuPolicy.kt)): `QUERY=0, DENY=1, ALLOW=2, RESTRICT=3`. `until` = unix epoch expiry, `0` = forever, negative = "until reboot"-ish; the runtime query is `WHERE uid=? AND (until=0 OR until>strftime('%s','now'))` ([`su/db.rs`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/su/db.rs)). `settings.key` values include `root_access`, `multiuser_mode`, `mnt_ns`, `denylist`, `zygisk`, `bootloop`, `requester` ([`db.rs`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/db.rs)). `root_access`: `0=Disabled, 1=AppsOnly, 2=AdbOnly, 3=AppsAndAdb`. `multiuser_mode`: `0=OwnerOnly, 1=OwnerManaged, 2=User`. The DB is a plain SQLite file owned by root — as root you can read/write it with `sqlite3` (`magisk --sqlite` also exists; exact usage `UNVERIFIED`). Note Magisk migrated much of this to Rust (`native/src/core/db.rs`, `su/db.rs`).

**How the grant prompt works:** MagiskSU's client `su` blocks while `magiskd` broadcasts to the Magisk app, which shows the Allow/Deny dialog and writes `policies`; KernelSU shows it in the manager's **Superuser** tab; APatch uses the **SuperKey**. There is no library-level "ask for root" API — the prompt *is* the `su` invocation. **Design consequence: trigger a `su` call early (splash/onboarding) so the prompt appears in a context you control, and handle the user choosing "Deny / don't ask again" by telling them exactly where to re-enable it** (Magisk app → Superuser; KernelSU manager → Superuser; APatch → SuperKey). libsu's `Shell.isAppGrantedRoot()` tri-state plus `Shell.getStatus()` is the right primitive for this.

**Detection strategy that actually works across all four:**
1. Try `su` — behaviour, not files. `Shell.getShell()` + `shell.isRoot()` is authoritative.
2. Prefer `su -v` / `su -V` output parsing only as a *hint* (`:MAGISKSU` ⇒ Magisk).
3. Optional manager probes for UI copy: `magisk -v` (Magisk), `ksud -V` (KernelSU), `apd --version` (APatch), presence of `/data/adb/ksu`, `/data/adb/ap`, `/data/adb/magisk`. Never gate functionality on these.
4. `KSU=true` marks KernelSU module-script environments — irrelevant for an app process.

---

## 3. Root capabilities relevant to this project

### 3.1 Reading another app's private data — the real story

The mechanism behind the "root can't read `/data/data/<pkg>`" myth is **SELinux MLS categories assigned per app UID**. Verbatim from AOSP [`private/seapp_contexts`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts):

```
# levelFrom and level are used to determine the level (sensitivity + categories)
# levelFrom=none omits the level.
# levelFrom=app determines the level from the process UID.
# levelFrom=user determines the level from the user ID.
# levelFrom=all determines the level from both UID and user ID.
# levelFrom=user is only supported for _app or _isolated UIDs.
# levelFrom=app or levelFrom=all is only supported for _app UIDs.

user=_app minTargetSdkVersion=34 domain=untrusted_app    type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=32 domain=untrusted_app_32 type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=30 domain=untrusted_app_30 type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=29 domain=untrusted_app_29 type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=28 domain=untrusted_app_27 type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=26 domain=untrusted_app_27 type=app_data_file levelFrom=user
user=_app                       domain=untrusted_app_25 type=app_data_file levelFrom=user
```

So for any app with `targetSdk >= 28`, its files are labelled `u:object_r:app_data_file:s0:c<uid-low>,c<uid-high>` — a **per-UID category set**. A process whose domain is *not* `mlstrustedsubject` and whose clearance lacks those categories is denied **even at UID 0** under an enforcing policy. `ls -Z` shows it.

**But Magisk deliberately opts out.** From [`native/src/sepolicy/rules.rs`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/sepolicy/rules.rs), verbatim:

```rust
typeattribute([proc], ["mlstrustedsubject", "netdomain", "appdomain"]);
typeattribute([file], ["mlstrustedobject"]);
...
// Make our root domain unconstrained
allow([proc], ["fs_type","dev_type","file_type","domain","service_manager_type",
               "hwservice_manager_type","vndservice_manager_type",
               "port_type","node_type","property_type"], all, all);
// Just in case, make the domain permissive
permissive([proc]);
```

`mlstrustedsubject` **exempts the domain from MLS constraints**, `allow(..., file_type, all, all)` grants full file access, and `permissive(...)` disables enforcement for that domain entirely. **Therefore a Magisk `su` shell can read and write `/data/data/<pkg>` normally.** The MLS barrier is real but only affects domains that are *not* `mlstrustedsubject` — most importantly the ADB/Shizuku `shell` domain (UID 2000). This is exactly why Shizuku-with-ADB cannot read app data while `su` can.

Practical consequences:
- **Do not implement a `chcon`/`restorecon` dance as your primary path.** It is unnecessary for Magisk and dangerous (relabelling app data breaks the target app's own access and is a `neverallow` in stock policy). `restorecon` and `chcon` *do* exist in Android's toybox (§4.5) if you need them.
- **Privileged apps are different:** `privapp_data_file` is a *different type*. Magisk's blanket allow covers `file_type`, so it still works, but a policy based on `app_data_file` alone would not.
- **`su --context` (`-Z`) and `-t PID` are the precise tools** for namespace/context work — e.g. `su -Z u:r:untrusted_app:s0:c512,c768 -c ...`, or `su -t <pid>` to enter another process's mount namespace. Magisk's `-Z` sets `req.context`; KernelSU implements it via `std::fs::write("/proc/thread-self/attr/current", context)`.
- **Targeting another UID is supported:** Magisk `su` accepts a *user* argument that "can be name or uid"; KernelSU likewise (`[user [argument...]]` + `getpwnam`/numeric parse). So "a shell bound to another app's uid" is `su <uid> -c <cmd>` or `su <uid>`.
- **`run-as <pkg>`** requires the target to be **debuggable**; it is not a general cross-app read primitive. (Target app debuggability is enforced by `PackageManager`; `run-as` needs no root.) `UNVERIFIED` for the Android 17-specific wording.
- `debugfs` is not part of Android's shipped shell — `UNVERIFIED` as a general mechanism; do not build on it.
- KernelSU/APatch: kernel-mediated root domains are configured via **App Profile** (`kernel/policy/app_profile.c`, `ksud profile`, `website/docs/guide/app-profile.md`) with uid/gid/groups/capabilities/namespace knobs. A profile can *withhold* capabilities (notably `CAP_SYS_PTRACE`) — that is the main way a KernelSU/APatch root shell can behave differently from a Magisk one. Exact default profile contents: `UNVERIFIED`.

### 3.2 Launching / installing / managing other apps with root

`am` / `pm` are shell commands that talk to `ActivityManagerService` / `PackageManagerService`. Run as root they inherit root's binder identity, so they bypass the normal app sandbox limits.

| Task | Command |
|---|---|
| Reinstall keeping data, allowing version downgrade | `pm install -r -d /path/app.apk` |
| Install into a specific user | `pm install --user 0 -r /path/app.apk` |
| Bypass the low-targetSdk block | `pm install --bypass-low-target-sdk-block -r /path/app.apk` |
| Uninstall but keep data | `pm uninstall -k --user 0 <pkg>` |
| Disable without uninstalling | `pm disable-user --user 0 <pkg>` |
| Force-stop | `am force-stop <pkg>` |
| Launch | `am start -n <pkg>/<activity>` |
| Newer surface | `cmd package install ...` / `cmd activity ...` |

AOSP constants ([`PackageManager.java`](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/pm/PackageManager.java)), verbatim:

```java
public static final int INSTALL_ALLOW_TEST = 0x00000004;
public static final int INSTALL_ALLOW_DOWNGRADE = 0x00100000;              // -d
public static final int INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK = 0x01000000;  // --bypass-low-target-sdk-block
public static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;           // signature mismatch
public static final int INSTALL_FAILED_VERSION_DOWNGRADE = -25;            // needs -d
public static final int INSTALL_FAILED_DEPRECATED_SDK_VERSION = -29;       // targetSdk too low
```

**Android version thresholds for the low-targetSdk block (critical, and it moved):**

| Android | Threshold | Evidence |
|---|---|---|
| 14 (API 34) | raised the floor to **23**; `--bypass-low-target-sdk-block` introduced | `INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK` constant + Android 14 behaviour-change docs |
| **15 (API 35)** | **raised 23 → 24** — "Increased minimum target SDK version from 23 to 24 ... `targetSdkVersion` lower than 24 can't be installed" | [Android 15 behavior changes (all apps)](https://developer.android.com/about/versions/15/behavior-changes-all) |
| 16 (API 36) | no further raise found in the "all apps" behaviour-change page — treat as **24** (`UNVERIFIED` for a 16-specific raise) | [Android 16 behavior changes (all apps)](https://developer.android.com/about/versions/16/behavior-changes-all) |
| 17 (API 37) | exists as of 2026 (first beta Feb 2026); APatch 11282 builds `compileSdk 37 / targetSdk 36`. No published raise found — `UNVERIFIED` | [Android 17 announcement](https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html), [APatch build.gradle.kts](https://raw.githubusercontent.com/bmax121/APatch/11282/build.gradle.kts) |

Exact error text from the Android 15 docs: `INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 24, but found 7`. The documented escape hatch is `adb install --bypass-low-target-sdk-block FILENAME.apk`; the equivalent `pm install` flag is `--bypass-low-target-sdk-block`. Apps already installed with a lower target remain installed across the OTA ("*On devices upgrading to Android 15, any apps with a `targetSdkVersion` lower than 24 remain installed*").

**Compliance warning:** installing APKs silently with `--bypass-low-target-sdk-block` downgrades the platform's malware protections. Google Play policy and most OEMs treat this as a hostile action; shipping it in a Play-distributed app is a strong candidate for removal. If RegTrail does this, gate it behind an explicit, informed user action with a visible warning, keep a full audit log, and never do it silently.

### 3.3 Inspecting other processes (`/proc/<pid>/mem`, `maps`, `status`)

- Reading `/proc/<pid>/mem` is gated by `ptrace_may_access()` → requires same-UID, or `CAP_SYS_PTRACE`, and passes LSM/SELinux checks. There is **no** Android-specific `hidepid`-based global block for root; the relevant constraints are (a) capabilities of the `su` shell and (b) SELinux.
- **Capabilities:** MagiskSU **no longer drops capabilities by default** as of v30.7 ("*[MagiskSU] Do not drop capabilities by default, even when switching to non-root UIDs. Explicitly use the `--drop-cap` argument to drop capabilities*"). A plain `su` therefore keeps the full permitted set including `CAP_SYS_PTRACE`. `su --drop-cap` (or a restrictive KernelSU **App Profile**) removes it and `ptrace` will then fail with `EPERM`. **This is the single most likely cause of "reading /proc/pid/mem doesn't work" in 2026.**
- **SELinux:** the same Magisk analysis as §3.1 applies — the root domain is `mlstrustedsubject` + fully allowed + `permissive`, so `ptrace`, `process:ptrace`, and `proc` file access are not blocked for MagiskSU. For KernelSU/APatch, the kernel **deliberately provides stronger introspection than ptrace** (hardware breakpoints, "access the physical memory of any process invisibly, intercept any system call" — [what-is-kernelsu.md](https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/website/docs/guide/what-is-kernelsu.md)), which is the sanctioned path there.
- **YAMA `ptrace_scope`:** whether Android kernels ship `CONFIG_SECURITY_YAMA`: `UNVERIFIED`. Do not rely on `/proc/sys/kernel/yama/ptrace_scope` existing; test for it and fall back.
- **Alternatives that avoid ptrace entirely:** `/proc/<pid>/maps`, `/proc/<pid>/status`, `/proc/<pid>/cmdline`, `/proc/<pid>/fd/*` are plain file reads and are generally sufficient for memory *mapping* inspection; `/proc/<pid>/mem` is only needed for raw content. KernelSU explicitly advertises invisible physical-memory access as a kernel-mode feature — the honest recommendation is to use manager-provided kernel facilities where available rather than fighting ptrace.
- `untrusted_app` → `untrusted_app` ptrace is blocked by SELinux MLS (different categories) — so an app cannot ptrace another app even at the same UID class, and `neverallow` rules enforce the general case. Exact `neverallow` text: `UNVERIFIED` (the `public/domain.te` and `private/untrusted_app.te` fetches did not surface a ptrace rule; AOSP has restructured these files).

---

## 4. In-app terminal implementation

### 4.1 Termux licenses — the answer, precisely

`termux-app/LICENSE.md`, verbatim:

> The `termux/termux-app` repository is released under [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) license.
>
> ### Exceptions
>
> - [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.

And upstream [`jackpal/Android-Terminal-Emulator` LICENSE](https://raw.githubusercontent.com/jackpal/Android-Terminal-Emulator/master/LICENSE) is unambiguously **Apache License 2.0**.

Crucially, **`TerminalSession`, `TerminalSessionClient`, `JNI`, and the VT emulator all live inside the `terminal-emulator` library module**, and `TerminalView` inside `terminal-view`:

```
/terminal-emulator/src/main/java/com/termux/terminal/{TerminalEmulator,TerminalBuffer,TerminalRow,TerminalStyle,
    WcWidth,TerminalOutput,TerminalSession,TerminalSessionClient,JNI,KeyHandler,ByteQueue,...}.java
/terminal-emulator/src/main/jni/{termux.c,Android.mk}
/terminal-view/src/main/java/com/termux/view/{TerminalView,TerminalViewClient}.java
```

Only `com.termux.app.*` (the app module, e.g. `TermuxTerminalSessionClient`, `TermuxTerminalViewClient`) is inside the GPLv3-only part.

**Compliance reality check (do not gloss over this):**
- `LICENSE.md` is the repository's own, explicit statement that this code is Apache-2.0, and Apache-2.0 is the upstream license. That is a strong basis for reuse in a closed-source app.
- **However**, the individual files carry **no per-file license headers** (verified: `TerminalSession.java` starts directly with `package com.termux.terminal;`), and the Termux project has modified them since import. Those modifications nominally fall under the repository default (GPLv3-only) unless you rely on the `LICENSE.md` exception. The ambiguity is exactly what [issue #4257](https://github.com/termux/termux-app/issues/4257) asks ("*What is the license of code under terminal-view and terminal-emulator?*") — I could not retrieve the maintainer's answer (github.com is unreachable from this network and the GitHub API returned 403 rate-limit).
- **Recommended mitigation, in order:** (1) reproduce the upstream Apache-2.0 `NOTICE`/`LICENSE` and the `LICENSE.md` exception clause in your third-party notices; (2) prefer vendoring/jitpack-pinning the exact tag rather than copying loose files; (3) if your legal review is strict, use the pristine **jackpal/Android-Terminal-Emulator** code (pure Apache-2.0) or **xterm.js** (MIT) instead; (4) open an issue upstream asking the maintainer to add Apache-2.0 SPDX headers — that single change removes the risk permanently.

### 4.2 Options compared

| Option | License | Language/integration | Verdict for Compose Multiplatform |
|---|---|---|---|
| **Termux `terminal-emulator` + `terminal-view` 0.118.3** | Apache-2.0 per `LICENSE.md` exception (see caveat) | Java + NDK `libtermux.so`; `TerminalView` is a classic `android.view.View` | **Best emulation quality** (real VT100/xterm, `WcWidth`, alternate screen, scrollback). Needs an `AndroidView` interop wrapper in Compose. |
| **xterm.js 6.0.0** | **MIT** ([npm](https://registry.npmjs.org/@xterm/xterm)) | JS in a `WebView` | Cleanest license, zero JNI; adds WebView weight, IME/keyboard latency, and a JS↔Kotlin bridge. Good fallback. |
| **libvterm (Paul Evans)** | **MIT** ([LICENSE](https://raw.githubusercontent.com/neovim/libvterm/master/LICENSE)) | C, no Android glue | You write the entire Android view + input layer. Only worth it for a from-scratch engine. |
| **libtsm** | `UNVERIFIED` (was LGPL-ish historically — verify before use) | C | Not recommended without a license check. |
| **ConnectBot terminal** | Apache-2.0 project, but its terminal was forked *from* the same jackpal lineage | Java | No advantage over Termux; different maintenance. |
| **AOSP `TerminalView` (packages/apps/Terminal)** | Apache-2.0 historically | Java | The AOSP Terminal app is no longer in the modern tree (`packages/apps/Terminal` → 404 on android.googlesource). **Do not depend on it.** |
| **Magisk v31.0's own terminal** | **GPL-3.0 — do NOT copy** | Kotlin + Compose (`TerminalEmulator.kt`, `TerminalBuffer.kt`, `TerminalRow.kt`, `TerminalStyle.kt`, `WcWidth.kt`, `TerminalProcess.kt`, `ui/terminal/TerminalRenderer.kt`, `TerminalScreen.kt`) | Excellent *reference* for architecture (and proof that Kotlin/Compose terminals are practical), but copying it into a closed-source app is a GPL violation. |

**Recommendation for a Compose Multiplatform app:** use the Termux libraries, wrapped in Compose `AndroidView`, with xterm.js-in-WebView as the documented fallback. Do **not** put the terminal in shared/common CMP code — the emulator is Android/JNI-specific; keep it in the `androidMain` source set behind an `expect`/`actual` interface so a desktop/iOS target could later use xterm.js or a native engine.

### 4.3 Spawning a PTY — the JNI route (what Termux actually does)

Termux does **not** use `forkpty()`; it does the equivalent by hand in [`terminal-emulator/src/main/jni/termux.c`](https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/jni/termux.c):

```
open("/dev/ptmx", O_RDWR | O_CLOEXEC)
grantpt(ptm); unlockpt(ptm); ptsname_r(ptm, devname, sizeof(devname))
tcgetattr → set IUTF8, clear IXON|IXOFF   // Ctrl+S must not freeze the display
ioctl(ptm, TIOCSWINSZ, {rows, columns, columns*cell_w, rows*cell_h})
fork()
  parent: return ptm (the master fd)
  child:  sigprocmask(UNBLOCK, all); close(ptm); setsid(); open(devname);
          dup2(pts,0/1/2); close all other fds > 2 via /proc/self/fd;
          clearenv(); putenv(each envVars entry); chdir(cwd); execvp(cmd, argv);
```

Two consequences you must design around:
1. **`clearenv()` then `putenv(env)` then `execvp(cmd, argv)`** — so **`PATH` must be in the env array you pass**, or `execvp` cannot resolve `"su"`/`"sh"` by name. This is a real, common bug. Pass an explicit `PATH`.
2. The JNI entry point is `Java_com_termux_terminal_JNI_createSubprocess`, but the Kotlin/Java `JNI` class is **package-private** (`final class JNI` in `com.termux.terminal`). **You cannot call it from your own package.** Drive PTYs through the public `TerminalSession`.

Public `TerminalSession` API (verified against v0.118.3):

```java
public TerminalSession(String shellPath, String cwd, String[] args, String[] env,
                       Integer transcriptRows, TerminalSessionClient client)
public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels)
public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels)
public void write(byte[] data, int offset, int count)
public TerminalEmulator getEmulator()
public boolean isRunning();  public int getExitStatus();  public int getPid();  public String getCwd()
public void finishIfRunning()
```

So the "spawn a PTY" decision tree is:
- **You want a terminal UI** → `TerminalSession(...)` + `TerminalView` (Termux libs). PTY is handled for you.
- **You want a streamed, non-interactive command** → libsu `Shell.cmd(...)` (no PTY needed).
- **You want interactive output without a full emulator** → `script -q -c "<cmd>" /dev/null`. This is the POSIX trick that allocates a PTY for you (`script` uses `openpty`/`forkpty` internally; see [util-linux script(1)](https://man7.org/linux/man-pages/man1/script.1.html)). **But `script` is NOT in stock Android** — see §4.5. You must supply busybox. Magisk v31.0's own terminal does exactly this, verbatim from [`TerminalProcess.kt`](https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/app/apk/src/main/java/com/topjohnwu/magisk/terminal/TerminalProcess.kt):

```kotlin
private val busyboxPath: String by lazy {
    Shell.cmd("readlink /proc/self/exe").exec().out.firstOrNull()
        ?: "/data/adb/magisk/busybox"
}
...
val wrappedCmd = "export TERM=xterm-256color; stty cols $cols rows $rows 2>/dev/null; $command"
val escapedCmd = wrappedCmd.replace("'", "'\\''")
val process = ProcessBuilder("su", "-c", "$busyboxPath script -q -c '$escapedCmd' /dev/null")
    .redirectErrorStream(true).start()
process.outputStream.close()
// then read process.inputStream in 4096-byte chunks and feed the emulator
```

Note the last trick: `readlink /proc/self/exe` from inside a root shell returns the Magisk binary path, from which the bundled busybox is derived — a portable way to find the manager's busybox (`/data/adb/magisk/busybox` fallback; KernelSU uses `/data/adb/ksu/bin/busybox`).

**"Multi-mode" terminal** — the three modes and their exact invocation:

| Mode | UID | Invocation |
|---|---|---|
| **App-context shell** | your app's UID | `TerminalSession("/system/bin/sh", cwd, arrayOf("sh"), env, rows, client)` — or `sh -i` |
| **Root shell** | 0 | `TerminalSession("/system/bin/sh", cwd, arrayOf("sh","-c","exec su"), env, rows, client)`, or `su` directly. Add `-M`/`--mount-master` for the global mount namespace. |
| **Bound to another app's UID / namespace** | target uid | `su <uid> -c '<cmd>'` (Magisk: user "can be name or uid"), plus `su -t <pid> -c ...` to inherit that PID's mount namespace, plus `su -Z <context> -c ...` to set the SELinux context |

For a PTY-based root shell, prefer `su` with **no `-c`** when stdin is already a TTY: MagiskSU sets `interactive |= !has_command`, so a bare `su` inherits your PTY. If you must use `-c`, add `-i`.

### 4.4 Bundling a userland

**Do not ship your own static busybox unless you must.** Priority order:

1. **Reuse the manager's busybox at runtime.** `/data/adb/magisk/busybox` (Magisk), `/data/adb/ksu/bin/busybox` (KernelSU). Both are the same upstream version today: osm0sis's module is `version=1.36.1, versionCode=13614`, and Magisk's changelog v26.3 says "*Update BusyBox to 1.36.1*". Cost: zero APK size, but it only exists when a manager is installed (which is exactly when you have root).
2. **`/system/bin/toybox`** — present on every Android since Marshmallow, license is permissive. From [`toybox/LICENSE`](https://raw.githubusercontent.com/landley/toybox/master/LICENSE): "*Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted*" (0BSD-style/ISC-style, **not** GPL). This is your license-clean baseline userland.
3. **Only if you need busybox applets toybox lacks**, bundle a static busybox. The trusted build is **osm0sis's `busybox-ndk`** ([Magisk-Modules-Repo/busybox-ndk](https://github.com/Magisk-Modules-Repo/busybox-ndk), 1171★, last pushed 2026-08-08): "*Static busybox binary for all Android architectures built with the NDK*", detecting ARM/64, x86/_64, MIPS/64. Build configs/patches: [osm0sis/android-busybox-ndk](https://github.com/osm0sis/android-busybox-ndk). **License: busybox is GPL-2.0 — shipping the binary obliges you to provide complete corresponding source (or a written offer) and makes the binary redistributable-but-GPL.** Exact per-ABI byte sizes: `UNVERIFIED` (the release assets were not retrievable from this network — GitHub API 403 and github.com unreachable). Treat "a few hundred KB to ~1.5 MB per ABI" as unconfirmed and measure the release zip yourself.
   - Precedent for shipping it legally inside an APK: libsu did it (as `jniLibs`), which is why the `busybox` module existed — but it was **removed in 5.0.1** and Magisk ships busybox as `lib/<abi>/libbusybox.so` inside the APK (see `build.py`), the same trick.
4. **Termux bootstrap** — `termux-packages` publishes per-Android-version bootstrap archives (latest observed tag `bootstrap-2026.10.11-r1+apt.android-7`). This gives you a full apt userland but is a large, GPL-adjacent, high-maintenance dependency and Termux's own docs warn against redistributing it as a library. Use it only if RegTrail genuinely needs a package manager.

**Do not depend on `script`** — see next section.

### 4.5 What stock Android actually gives you (verified command list)

From the AOSP [`shell_and_utilities/README.md`](https://raw.githubusercontent.com/aosp-mirror/platform_system_core/master/shell_and_utilities/README.md), Android 13/14/15 `/system/bin` is toybox (0.8.6 / 0.8.9 / 0.8.11-ish) plus mksh, bzip2, one-true-awk, gavinhoward/bc, and a small toolbox (`getevent getprop setprop start stop`).

| Command | Stock Android 15? |
|---|---|
| `unshare`, `nsenter`, `chroot`, `pivot_root` | ✅ toybox |
| `setsid`, `timeout`, `stdbuf`* | ✅ `setsid`, `timeout`; `stdbuf` ❌ |
| `mount`, `losetup`, `mountpoint` | ✅ |
| `chcon`, `restorecon`, `runcon`, `setenforce`, `getenforce`, `load_policy` | ✅ toybox |
| `setfattr`, `getfattr` | ✅ (symlinks added in Android 15) |
| `ls`, `lsof`, `nc`/`netcat`, `xxd`, `base64`, `sha256sum`, `find`, `xargs`, `timeout`, `flock`, `ionice`, `taskset`, `prlimit` | ✅ |
| `ifconfig`, `netstat`, `ping`, `traceroute`, `ip` | `ifconfig`/`netstat`/`ping` ✅; **`ip` ❌** |
| **`script`** | ❌ **NOT PRESENT** |
| `iptables`, `nft`, `tcpdump`, `strace`, `gdb`, `lsof`(✅) | `iptables` ❌, `nft` ❌, `tcpdump` ❌, `strace` ❌ |
| `ss`, `ps`(✅), `top`(✅), `vmstat`(✅), `free`(✅) | `ss` ❌ |

\* Listed as `setsid`, `timeout` present; verify flags with `toybox <cmd> --help` on the actual device — toybox applets are often built with a reduced flag set.

**This is why §4.3's Magisk pattern uses busybox `script`.** If you rely on `script` and only have toybox, your terminal will fail. Either ship/borrow busybox, or implement the PTY yourself via `TerminalSession`/JNI.

### 4.6 Mount namespaces, chroot, and "isolated vs network-enabled" workspaces

Verified building blocks:
- **`--mount-master` / `-M` / `-t 0`** — enter the *global* Magisk mount namespace (needed if you bind-mount or chroot and want it visible to other root processes). Magisk: `-M`/`--mount-master`/`-t 0`; KernelSU: `-M`/`--mount-master`; APatch: `/data/adb/.global_namespace_enable`.
- **`-t <pid>`** — adopt an arbitrary process's mount namespace (Magisk). Verify the KernelSU equivalent; KernelSU's `-M` only does global (`utils::switch_mnt_ns(1)`).
- **`unshare` / `nsenter`** are both present in stock toybox (Android 13+), so `su -c 'unshare -m ...'` and `nsenter -t <pid> -m ...` are viable without busybox. Verify applet flags on-device.
- **`chroot`** is present in toybox; combine with `--mount-master` for a real workspace root.

Concrete design that works with everything above:

| Workspace | Implementation | Caveats |
|---|---|---|
| **Isolated workspace** | Own dir under `context.filesDir` or `/data/local/tmp/<uuid>`; run the shell with cwd there; optionally `unshare -m` for a private mount view. | `unshare -m` makes the namespace private to that process tree only — good for isolation, but changes made there are invisible to other root processes. |
| **Network-enabled workspace** | Same dir, no `unshare -n`. Network is the default; you only need explicit work to *remove* it. | — |
| **Network-isolated workspace** | `unshare -n` gives a namespace with only loopback. Adding later requires `nsenter` into it and configuring an interface — awkward. | Simplest reliable "no network" is `unshare -n`; per-UID filtering via `iptables -m owner --uid-owner` is **not available on stock Android** (no `iptables`) and would need a kernel module / Magisk module. |
| **Shared/global view** | `su -M` (a.k.a. `--mount-master`) + a dedicated dir. | Globally visible side effects — document them. |

**Recommendation:** start with a dedicated directory + `su` + (optionally) `unshare -m` for isolation. Do not build on `iptables owner` matching: it needs binaries stock Android does not ship.

---

## 5. AI agent + tool use (brief)

**Transport / model options:**

| Option | Coordinate (latest verified) | Notes |
|---|---|---|
| OpenAI-compatible HTTP (recommended default) | `com.aallam.openai:openai-client:4.1.0` (Maven Central, updated 2026-02-07) | Kotlin Multiplatform; points at any OpenAI-compatible endpoint (OpenAI, OpenRouter, vLLM, Ollama, LM Studio). Supports function/tool calling. |
| JetBrains **Koog** (agent framework) | `ai.koog:koog-agents:1.3.0` (Maven Central, updated 2026-09-23) | Kotlin-native agent framework with tool-calling, strategies, history. CMP-friendly. |
| **LangChain4j** (JVM) | `dev.langchain4j:langchain4j:1.22.0` (Maven Central, updated 2026-10-07) | Java; mature tool/function support, but a heavier dependency for Android (watch method count / desugaring). |
| Google **Firebase AI Logic** | `com.google.firebase:firebase-ai:18.0.0` (Google Maven, updated 2026-10-07) | The *current* Google path. Function calling supported. |
| Google **Generative AI SDK** | `com.google.ai.client.generativeai:generativeai:0.9.0` (Google Maven, **last updated 2024-07-02**) | Effectively frozen/superseded by Firebase AI Logic. Do not start new work here. |
| **On-device** (no network) | `com.google.mediapipe:tasks-genai:0.10.35` (Google Maven, updated 2026-04-27) | MediaPipe LLM Inference API — the supported on-device LLM path for Android; supports constrained/JSON-ish output for tool selection. |
| llama.cpp via JNI | various third-party wrappers (e.g. `ljcamargo/kotlinllamacpp`, `rogelioRuiz/dust-llm-kotlin`) | **No canonical Maven artifact.** If you go this route you vendor and build llama.cpp yourself (MIT). Treat community wrappers as unaudited. |

**Standard Kotlin/Android stack for a ReAct-style tool loop** (all versions verified on Maven Central unless noted):

```
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0
org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0     // tool schemas + arg parsing
com.aallam.openai:openai-client:4.1.0                        // or ai.koog:koog-agents:1.3.0
io.ktor:ktor-client-okhttp:3.6.0  (or com.squareup.okhttp3:okhttp:5.5.0)
androidx.compose:compose-bom:2026.09.00                      // compose.snapshotFlow / Flow for streaming UI
```

Architecture that fits Android: keep the loop a plain `suspend` function returning `Flow<AgentEvent>`; declare tools as `@Serializable` arg classes + a `ToolRegistry` mapping name → `suspend (JsonElement) -> JsonElement`; dispatch each tool through libsu `Shell.cmd(...)` on `Dispatchers.IO`; require an explicit user-visible confirmation step for destructive tools (every root command is destructive). Streaming the model's tokens into the terminal view is a nice unification of §4 and §5.

---

## 6. RECOMMENDATION

### 6.1 Exact Gradle coordinates

```kotlin
// settings.gradle.kts — repositories
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // REQUIRED: libsu and Termux terminal libs live here
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    compileSdk = 36            // 37 exists (Android 17) — raise when your AGP/NDK support it
    defaultConfig { minSdk = 26; targetSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    // libsu 6.0.0 is Java 8+; Shizuku API 13.1.0+ REQUIRES coreLibraryDesugaring if minSdk < 24
    // (we set minSdk 26, so desugaring is only needed for other reasons)
    buildFeatures { aidl = true }   // for RootService IPC
}

dependencies {
    // ── Root shell + persistent root daemon (Apache-2.0, JitPack) ─────────────────
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")     // remote FS over Binder (preferred over SuFile)

    // ── Terminal emulator + view (Apache-2.0 per termux-app LICENSE.md exception) ─
    implementation("com.github.termux.termux-app:terminal-emulator:0.118.3")
    implementation("com.github.termux.termux-app:terminal-view:0.118.3")
    // FALLBACK if legal rejects the Termux carve-out: androidWebView + bundled
    // @xterm/xterm 6.0.0 (MIT, npm) in app/src/main/assets — no Gradle coordinate.

    // ── Optional: ADB-tier (UID 2000) support for unrooted devices ────────────────
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ── Concurrency / serialization ───────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ── AI tool-calling ───────────────────────────────────────────────────────────
    implementation("com.aallam.openai:openai-client:4.1.0")     // OpenAI-compatible HTTP
    // implementation("ai.koog:koog-agents:1.3.0")              // alternative: Kotlin agent framework
    // implementation("com.google.firebase:firebase-ai:18.0.0") // alternative: Gemini via Firebase
    // implementation("com.google.mediapipe:tasks-genai:0.10.35")// alternative: fully on-device
}
```

Reference toolchain (all verified on 2026-10-17): Kotlin **2.4.21** (2.5.0-Beta1 is the newest, not stable), Compose Multiplatform **1.12.2** (1.13.0-alpha02 is newest), AGP **9.4.1** (9.5.0-alpha10 newest), `androidx.compose:compose-bom:2026.09.00`.

### 6.2 License summary and compliance warnings

| Component | License | Obligation / risk |
|---|---|---|
| libsu (core/service/nio) | **Apache-2.0** | Include `LICENSE` + `NOTICE` in your about screen. No copyleft. |
| Termux `terminal-emulator`, `terminal-view` | **Apache-2.0** *per `termux-app/LICENSE.md` exception* | ⚠️ **Residual risk.** Individual files have no Apache headers; the repo default is GPLv3-only. Reproduce the exception clause verbatim in your notices, and get upstream confirmation (issue #4257) or fall back to xterm.js (MIT) / pristine jackpal Apache-2.0 code. |
| `termux-app` **app module** (`com.termux.app.*`, `TermuxTerminalSessionClient`, `TermuxTerminalViewClient`) | **GPL-3.0-only** | ⛔ **Do not copy.** Linking to `termux-app` app code, or vendoring it, GPL-infects your app. Only the two library modules are safe. |
| `termux-shared` | GPLv3 + specific exceptions in its own `LICENSE.md` | ⛔ Assume GPLv3 unless you read that file in full. |
| Shizuku API (`dev.rikka.shizuku:api/provider`) | Apache-2.0 | ✅ But Apache-2.0 §6 carve-outs: no Shizuku icons, no "Shizuku" app name, no `moe.shizuku.privileged.api` id, no `moe.shizuku.manager.permission.*`. |
| Magisk (incl. its Kotlin/Compose terminal) | **GPL-3.0** | ⛔ Reference only. Copying `TerminalEmulator.kt`/`TerminalRenderer.kt` into a closed-source app is a violation. Also: Magisk's busybox binary is GPL-2.0. |
| KernelSU / KernelSU-Next | GPL-2.0 | Only relevant if you ship a module (then license your module GPL-2.0 or use the module-script interface, which is not linking). |
| APatch / KernelPatch | GPL-3.0 | Same as above. |
| toybox | **0BSD/ISC-style** (["permission ... with or without fee"](https://raw.githubusercontent.com/landley/toybox/master/LICENSE)) | ✅ Best license-clean userland; already on-device. |
| busybox (osm0sis `busybox-ndk`) | **GPL-2.0** | ⚠️ Redistribution requires complete corresponding source. Prefer borrowing the installed manager's busybox at runtime — zero redistribution. |
| libvterm | **MIT** | ✅ |
| xterm.js | **MIT** | ✅ |
| MediaPipe `tasks-genai` / Firebase AI / Koog / openai-client | Apache-2.0 (verify each) | ✅ |

**Legal warnings, ranked:**
1. **Never link or copy GPLv3 code from `termux-app` beyond the two exception modules** — that converts RegTrail to GPLv3 and forbids closed distribution.
2. **Never copy Magisk's Kotlin terminal** (v31.0 is a tempting, high-quality reference) — same violation.
3. **`--bypass-low-target-sdk-block` is a security-control bypass.** Play policy risk; require explicit user consent and log it (§3.2).
4. **Bundling GPL-2.0 busybox** forces source-offer compliance. Reuse the manager's copy instead.
5. This report is engineering research, not legal advice. The Termux carve-out and APatch/KernelSU module licensing should be reviewed by counsel before shipping.

### 6.3 Snippets

#### (a) libsu root shell + coroutine output streaming

```kotlin
// RootShell.kt — libsu has NO coroutine API; these bridges are ours.
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

object RootShell {

    fun install(builderFlags: Int = Shell.FLAG_MOUNT_MASTER) {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        // Must be set BEFORE the main shell is created.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(builderFlags)      // FLAG_MOUNT_MASTER -> `su --mount-master`
                .setTimeout(20)              // seconds to wait for the root prompt
        )
    }

    /** Pre-heat during onboarding so the Magisk/KernelSU prompt appears in your own UI. */
    suspend fun acquire(): Shell = suspendCancellableCoroutine { cont ->
        Shell.getShell { shell -> if (cont.isActive) cont.resume(shell) }  // callback = main thread
    }

    /** Nullable tri-state wrapped non-blockingly. */
    val granted: Boolean? get() = Shell.isAppGrantedRoot()

    /** One-shot command, suspend. Avoids Job.enqueue()/Future entirely. */
    suspend fun run(vararg cmds: String): Shell.Result =
        suspendCancellableCoroutine { cont ->
            Shell.cmd(*cmds).submit(Shell.EXECUTOR) { r -> if (cont.isActive) cont.resume(r) }
        }

    /**
     * Live stdout+stderr as a Flow<String>.
     * Passing the SAME list for out and err is libsu 6.0.0's sanctioned stderr merge
     * (FLAG_REDIRECT_STDERR is deprecated).
     * NOTE: libsu's CallbackList is an abstract class, not a SAM interface.
     */
    fun stream(vararg cmds: String): Flow<String> = callbackFlow {
        val sink = object : CallbackList<String>() {
            override fun onAddElement(s: String) { trySend(s) }
        }
        Shell.cmd(*cmds)
            .to(sink, sink)
            .submit(Shell.EXECUTOR) { close() }   // background delivery; close on completion
        awaitClose { /* libsu has no cancel API: the job runs to completion */ }
    }.flowOn(Dispatchers.IO)
}

// ---- UI usage (Compose) ----
@Composable
fun BootLog() {
    var lines by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        RootShell.stream("id", "getenforce", "cat /proc/version").collect { lines = lines + it }
    }
    LazyColumn { items(lines) { Text(it) } }
}
```

> Why not `Job.enqueue()`? It returns `java.util.concurrent.Future<Result>`; you can await it via `kotlinx-coroutines-jdk8`, but the `submit(Executor, ResultCallback)` form above needs no extra artifact and no uncertainty about the `await()` import path.

#### (b) A persistent root daemon that streams output over AIDL

```aidl
// app/src/main/aidl/com/example/regtrail/IRootTerminal.aidl
package com.example.regtrail;
import com.example.regtrail.IRootTerminalCallback;

interface IRootTerminal {
    void start(in String[] argv, int rows, int cols, IRootTerminalCallback cb);
    void write(in byte[] data);
    void resize(int rows, int cols);
    void destroy();
}
```

```aidl
// app/src/main/aidl/com/example/regtrail/IRootTerminalCallback.aidl
package com.example.regtrail;
oneway interface IRootTerminalCallback {
    void onOutput(in byte[] data);   // <-- streams bytes to the UI process
    void onExit(int code);
}
```

```kotlin
// Root terminal lives in the ROOT process; the UI binds to it.
class RootTerminalService : RootService() {

    private var session: TerminalSession? = null    // runs with uid 0 inside this process

    private val binder = object : IRootTerminal.Stub() {
        override fun start(argv: Array<String>, rows: Int, cols: Int, cb: IRootTerminalCallback) {
            val env = arrayOf(
                // REQUIRED: termux.c does clearenv() before execvp, so PATH must be explicit.
                "PATH=/system/bin:/system/xbin:/data/adb/magisk:/data/adb/ksu/bin",
                "TERM=xterm-256color", "HOME=/data/local/tmp", "LANG=en_US.UTF-8"
            )
            val s = TerminalSession("/system/bin/sh", "/data/local/tmp", argv, env, 2000,
                object : TerminalSessionClient {
                    override fun onTextChanged(e: TerminalEmulator) {
                        // TerminalEmulator screen -> bytes; push to the client process.
                        cb.onOutput(describeTranscript(e).toByteArray())
                    }
                    override fun onTitleChanged(e: TerminalEmulator) {}
                    override fun onSessionFinished(exitStatus: Int) { cb.onExit(exitStatus) }
                    override fun onCopyTextToClipboard(e: TerminalEmulator, t: String) {}
                    override fun onPasteTextFromClipboard(e: TerminalEmulator) {}
                    override fun onBell(e: TerminalEmulator) {}
                    override fun onColorsChanged(e: TerminalEmulator) {}
                    override fun onTerminalCursorStateChange(state: Boolean) {}
                    override fun getTerminalCursorStyle(): Int = 0
                    override fun logError(tag: String, msg: String) {}
                    override fun logWarn(tag: String, msg: String) {}
                    override fun logInfo(tag: String, msg: String) {}
                    override fun logDebug(tag: String, msg: String) {}
                    override fun logVerbose(tag: String, msg: String) {}
                })
            s.initializeEmulator(cols, rows, 0, 0)
            session = s
        }
        override fun write(data: ByteArray) { session?.write(data, 0, data.size) }
        override fun resize(rows: Int, cols: Int) { session?.updateSize(cols, rows, 0, 0) }
        override fun destroy() { session?.finishIfRunning(); stopSelf() }
    }

    override fun onBind(intent: Intent): IBinder = binder
}

// ---- client side ----
val conn = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        terminal = IRootTerminal.Stub.asInterface(service)
    }
    override fun onServiceDisconnected(name: ComponentName) { terminal = null }
}

// DAEMON_MODE => the root process survives app relaunches (per-package, shared across users).
val intent = Intent(ctx, RootTerminalService::class.java)
    .addCategory(RootService.CATEGORY_DAEMON_MODE)
RootService.bind(intent, conn)                // or:
// RootService.bindOrTask(intent, Shell.EXECUTOR, conn)?.let { Shell.execTask(it) }  // suspend-friendly

// Teardown (must pass the SAME daemon category):
// RootService.stop(Intent(ctx, RootTerminalService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE))
```

Manifest: **no `<service>` entry is required** — `RootService` is not an Android `Service`. Keep `aidl = true` in `buildFeatures`. On Android 11+, enable *"Always install with package manager"* in the run configuration or root-process code changes won't take effect.

#### (c) Spawn a shell and attach a PTY / terminal emulator

```kotlin
// Compose host for the Termux TerminalView (classic View -> AndroidView interop).
@Composable
fun TerminalScreen(session: TerminalSession, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(object : TerminalViewClient {
                    override fun onScale(scale: Float) = 1.0f
                    override fun onSingleTapUp(e: MotionEvent) { requestFocus(); showSoftInput() }
                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                    override fun shouldEnforceCharBasedInput(): Boolean = true
                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                    override fun isTerminalViewSelected(): Boolean = true
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
                    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
                    override fun onLongPress(event: MotionEvent): Boolean = false
                    override fun readControlKey(): Boolean = false
                    override fun readAltKey(): Boolean = false
                    override fun readShiftKey(): Boolean = false
                    override fun readFnKey(): Boolean = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
                    override fun onEmulatorSet() { setTerminalCursorBlinkerState(true, true) }
                    override fun logError(tag: String, msg: String) {}
                    override fun logWarn(tag: String, msg: String) {}
                    override fun logInfo(tag: String, msg: String) {}
                    override fun logDebug(tag: String, msg: String) {}
                    override fun logVerbose(tag: String, msg: String) {}
                })
                attachSession(session)
            }
        }
    )
}

// Three modes, one constructor. PATH is mandatory (clearenv() in termux.c).
private fun envFor(mode: String) = arrayOf(
    "PATH=/system/bin:/system/xbin:/data/adb/magisk:/data/adb/ksu/bin",
    "TERM=xterm-256color", "HOME=/data/local/tmp", "LANG=en_US.UTF-8", "REGTRAIL_MODE=$mode"
)

fun appShell(client: TerminalSessionClient) =                      // UID = your app
    TerminalSession("/system/bin/sh", "/data/local/tmp",
        arrayOf("sh", "-i"), envFor("app"), 2000, client)

fun rootShell(client: TerminalSessionClient, mountMaster: Boolean = true) =   // UID 0
    // bare `su` inherits this PTY (MagiskSU: interactive |= !has_command).
    // mountMaster -> `su -M` == `su --mount-master` == global mount namespace.
    TerminalSession("/system/bin/sh", "/data/local/tmp",
        if (mountMaster) arrayOf("sh", "-c", "exec su -M") else arrayOf("sh", "-c", "exec su"),
        envFor("root"), 2000, client)

fun foreignUidShell(uid: Int, pid: Int?, selinuxCtx: String?, client: TerminalSessionClient) =
    // Magisk: user may be a name or a uid; -t adopts that PID's mount namespace; -Z sets the context.
    TerminalSession("/system/bin/sh", "/data/local/tmp",
        buildList {
            add("sh"); add("-c")
            add(buildString {
                append("exec su ")
                pid?.let { append("-t $it ") }
                selinuxCtx?.let { append("-Z '$it' ") }
                append("$uid")
            })
        }.toTypedArray(), envFor("foreign"), 2000, client)
    // NOTE: su also accepts `-c '<cmd>'` for non-interactive runs; add `-i` if you use -c and need a PTY.

// Initial size must be reported once the view is measured:
//   terminalView.onSizeChanged -> session.updateSize(cols, rows, cellW, cellH)
```

For the xterm.js fallback, the Kotlin side is a `WebView` + `@JavascriptInterface` bridge: `evaluateJavascript("term.write(${json(data)})")` inbound, `addJavascriptInterface` outbound for keystrokes; run the PTY with the Magisk-style `su -c "$busybox script -q -c '<cmd>' /dev/null"` pattern (§4.3) and stream the merged stdout into `term.write`.

---

## 7. Sources (all fetched 2026-10-17)

**libsu**
- <https://github.com/topjohnwu/libsu> · <https://raw.githubusercontent.com/topjohnwu/libsu/master/README.md> · <https://raw.githubusercontent.com/topjohnwu/libsu/master/CHANGELOG.md>
- <https://raw.githubusercontent.com/topjohnwu/libsu/master/core/src/main/java/com/topjohnwu/superuser/Shell.java>
- <https://raw.githubusercontent.com/topjohnwu/libsu/master/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java>
- <https://jitpack.io/com/github/topjohnwu/libsu/maven-metadata.xml> · <https://data.jsdelivr.com/v1/packages/gh/topjohnwu/libsu@6.0.0?structure=flat>
- Maven Central absence: <https://repo1.maven.org/maven2/com/github/topjohnwu/libsu/> (404)

**Shizuku**
- <https://github.com/RikkaApps/Shizuku> · <https://raw.githubusercontent.com/RikkaApps/Shizuku/master/README.md>
- <https://raw.githubusercontent.com/RikkaApps/Shizuku-API/master/README.md>
- <https://repo1.maven.org/maven2/dev/rikka/shizuku/api/maven-metadata.xml> · <https://repo1.maven.org/maven2/dev/rikka/shizuku/provider/maven-metadata.xml>

**Magisk**
- <https://github.com/topjohnwu/Magisk> · <https://raw.githubusercontent.com/topjohnwu/Magisk/master/docs/changes.md>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/su/su.cpp>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/sqlite.cpp>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/db.rs> · <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/su/db.rs>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/app/core/src/main/java/com/topjohnwu/magisk/core/data/magiskdb/PolicyDao.kt> · <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/app/core/src/main/java/com/topjohnwu/magisk/core/model/su/SuPolicy.kt>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/sepolicy/rules.rs>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/native/src/core/zygisk/api.hpp> · <https://raw.githubusercontent.com/topjohnwu/zygisk-module-sample/master/module/jni/zygisk.hpp>
- <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/app/apk/src/main/java/com/topjohnwu/magisk/terminal/TerminalProcess.kt> · <https://raw.githubusercontent.com/topjohnwu/Magisk/v31.0/build.py>
- <https://api.github.com/repos/topjohnwu/Magisk/releases/latest> (returned v30.7)

**KernelSU / KernelSU-Next / APatch**
- <https://github.com/tiann/KernelSU> · <https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/userspace/ksud/src/su.rs> · <https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/userspace/ksud/src/defs.rs>
- <https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/kernel/feature/sucompat.c> · <https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/kernel/runtime/ksud.h>
- <https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/website/docs/guide/what-is-kernelsu.md> · <https://raw.githubusercontent.com/tiann/KernelSU/v3.3.1/website/docs/guide/difference-with-magisk.md>
- <https://github.com/KernelSU-Next/KernelSU-Next> · <https://raw.githubusercontent.com/KernelSU-Next/KernelSU-Next/v3.4.2/docs/README.md>
- <https://github.com/bmax121/APatch> · <https://raw.githubusercontent.com/bmax121/APatch/main/README.md> · <https://raw.githubusercontent.com/bmax121/APatch/11282/apd/src/defs.rs> · <https://raw.githubusercontent.com/bmax121/APatch/11282/apd/src/pty.rs> · <https://raw.githubusercontent.com/bmax121/APatch/11282/apd/src/cli.rs> · <https://raw.githubusercontent.com/bmax121/APatch/11282/build.gradle.kts> · <https://github.com/bmax121/KernelPatch>

**Android platform / SELinux / install policy**
- <https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts>
- <https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/content/pm/PackageManager.java>
- <https://developer.android.com/about/versions/15/behavior-changes-all> (minimum target SDK 23 → 24)
- <https://developer.android.com/about/versions/14/behavior-changes-14> · <https://developer.android.com/about/versions/16/behavior-changes-all> · <https://developer.android.com/about/versions/17/behavior-changes-all>
- <https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html> · <https://source.android.com/docs/compatibility/17/android-17-cdd>
- <https://raw.githubusercontent.com/aosp-mirror/platform_system_core/master/shell_and_utilities/README.md>

**Terminal**
- <https://github.com/termux/termux-app> · <https://raw.githubusercontent.com/termux/termux-app/master/LICENSE.md> · <https://github.com/termux/termux-app/issues/4257>
- <https://raw.githubusercontent.com/termux/termux-app/v0.118.3/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java>
- <https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/src/main/jni/termux.c> · <https://raw.githubusercontent.com/termux/termux-app/master/terminal-emulator/build.gradle> · <https://raw.githubusercontent.com/termux/termux-app/master/terminal-view/build.gradle>
- <https://jitpack.io/com/github/termux/termux-app/terminal-view/maven-metadata.xml>
- <https://github.com/jackpal/Android-Terminal-Emulator> · <https://raw.githubusercontent.com/jackpal/Android-Terminal-Emulator/master/LICENSE>
- <https://registry.npmjs.org/@xterm/xterm> (6.0.0, MIT) · <https://raw.githubusercontent.com/neovim/libvterm/master/LICENSE> (MIT)
- <https://raw.githubusercontent.com/landley/toybox/master/LICENSE> · <https://man7.org/linux/man-pages/man1/script.1.html>
- <https://github.com/Magisk-Modules-Repo/busybox-ndk> · <https://raw.githubusercontent.com/Magisk-Modules-Repo/busybox-ndk/master/module.prop> (busybox 1.36.1) · <https://github.com/osm0sis/android-busybox-ndk>
- <https://github.com/termux/termux-packages/releases/latest>

**Libraries (versions)**
- <https://repo1.maven.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml> · <https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml> · <https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml>
- <https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml> · <https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml>
- <https://repo1.maven.org/maven2/com/aallam/openai/openai-client/maven-metadata.xml> · <https://repo1.maven.org/maven2/ai/koog/koog-agents/maven-metadata.xml> · <https://repo1.maven.org/maven2/dev/langchain4j/langchain4j/maven-metadata.xml> · <https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml> · <https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml>
- <https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-ai/maven-metadata.xml> · <https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-genai/maven-metadata.xml> · <https://dl.google.com/dl/android/maven2/com/google/ai/client/generativeai/generativeai/maven-metadata.xml> · <https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml>

### Open items marked UNVERIFIED
1. Whether Magisk **v31.0** is a stable release or a pre-release (changelog says 2026.9.4; `releases/latest` says v30.7).
2. APatch's exact `su` flag list and its busybox path.
3. Whether Android 16 or 17 raised the minimum target SDK above 24.
4. Whether Android kernels ship `CONFIG_SECURITY_YAMA` (`ptrace_scope`).
5. Exact AOSP `neverallow` text for `untrusted_app` ptrace (files restructured).
6. KernelSU-Next's exact on-disk path identity vs KernelSU.
7. Per-ABI byte sizes of static busybox binaries.
8. The maintainer's answer in termux/termux-app issue #4257 (network-blocked).
