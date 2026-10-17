# On-device APK repackaging for code injection (LSPatch / Xpatch lineage) — research report

**Compiled:** 2026-10-17 · **Method:** primary sources only (repo source read locally, Maven metadata XML, AOSP source, official Android docs). Every version below was read from a live endpoint or from downloaded source at the time of writing. Items I could not verify are marked **UNVERIFIED**.

> Network note for whoever continues this: `web_fetch` refuses `github.com`, `api.github.com`, `raw.githubusercontent.com`, `developer.android.com` and anything resolving into `198.18.0.0/15` (fake-IP proxy). Everything there **is** reachable via `pwsh` + `curl.exe`; `codeload.github.com/*/tar.gz` and `*.atom` feeds work and are not rate-limited. `api.github.com` via `Invoke-RestMethod` hit its 60/h unauthenticated cap.

---

## 1. Existing implementations

### 1.1 LSPatch (the reference architecture) — `LSPosed/LSPatch`

| | |
|---|---|
| URL | https://github.com/LSPosed/LSPatch |
| License | **GPL-3.0** (viral/copyleft) — confirmed from `/LICENSE` and README |
| Status | **ARCHIVED.** `pushed_at = 2023-12-13T04:54:49Z`. Last release **v0.6** (2023-11-23) shipping `jar-v0.6-398-release.jar` + `manager-v0.6-398-release.apk` |
| Size | 9,427 stars / 1,150 forks / 12 open issues / default branch `master` / 418 tree entries |
| Min Android | Android 9 (API 28) per README |

Accurate description: *"Rootless implementation of LSPosed framework, integrating Xposed API by inserting dex and so into the target APK."* Credits Xpatch as "Fork source" and apkzlib as the "Repacking tool".

#### Repo structure (verified, recursive git tree)

```
LSPosed/LSPatch
├── apkzlib/          # VENDORED copy of AOSP platform/tools/apkzlib (com.android.tools.build.apkzlib.*)
├── core/             # git submodule -> https://github.com/LSPosed/LSPosed (branch master)
├── jar/              # fat-jar CLI packaging module ("lspatch.jar")
├── manager/          # Android app: Compose UI + RUNS THE PATCHER ON-DEVICE
├── meta-loader/      # the tiny dex that BECOMES classes.dex of the patched APK
├── patch/            # <-- THE REUSABLE PATCHER LIBRARY (pure Java, java-library)
├── patch-loader/     # the runtime stub dex + native liblspatch.so
├── share/android/    # ModuleLoader (SharedMemory dex preloading)
├── share/java/       # Constants + PatchConfig (shared by patcher AND loader)
└── .gitmodules
```

`.gitmodules` (verbatim):
```
[submodule "apksigner"]  path = apksigner  url = https://android.googlesource.com/platform/tools/apksig.git  branch = android10-release
[submodule "core"]       path = core       url = https://github.com/LSPosed/LSPosed.git                    branch = master
[submodule "patch/libs/manifest-editor"] path = patch/libs/manifest-editor url = https://github.com/WindySha/ManifestEditor.git
```

**The reusable pieces are `patch/` (a `java-library`, 22-line build file) and `meta-loader/` + `patch-loader/`.** `patch/build.gradle.kts` depends on: `projects.apkzlib`, `projects.share.java`, `commons-io:commons-io:2.13.0`, `com.beust:jcommander:1.82`, `com.google.code.gson:gson:2.10.1`. It also source-includes the ManifestEditor submodule via `java.srcDirs("libs/manifest-editor/lib/src/main/java")`.

#### Exact classes involved

| Class | Role |
|---|---|
| `org.lsposed.patch.LSPatch` | The patcher. JCommander CLI **and** the API (`patch(File src, File out)`). `jar` module's `Main-Class`. |
| `org.lsposed.patch.util.ManifestParser` | Reads `packageName`, `appComponentFactory`, `minSdkVersion` from binary manifest via `com.wind.meditor.utils.Utils` + `pxb.android.axml.AxmlParser`. |
| `org.lsposed.patch.util.ApkSignatureHelper` | Extracts the **original** signing cert: parses the APK Signing Block manually (magic `"APK Sig Block 42"`, ID `0x7109871a`) → hex string; falls back to v1 `JarFile.getCertificates()`. |
| `org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub` | Injected `classes.dex`. Static initializer loads the native lib and stashes the loader dex in `public static byte[] dex`. |
| `org.lsposed.lspatch.loader.LSPApplication` | `onLoad()` — the real runtime entry point (called from JNI). |
| `org.lsposed.lspatch.loader.LSPLoader` | `initModules(LoadedApk)` → `XC_LoadPackage.callAll(lpparam)` (fires Xposed module hooks). |
| `org.lsposed.lspatch.loader.SigBypass` | Signature-check bypass, levels 1/2. |
| `org.lsposed.lspatch.util.ModuleLoader` | Preloads module dexes into `SharedMemory` for the LSPosed core. |
| `org.lsposed.lspatch.share.Constants` / `PatchConfig` | The contract between patcher and loader. |
| `com.android.tools.build.apkzlib.zip.ZFile` / `AlignmentRules` / `sign.SigningExtension` | The actual repack + sign engine. |
| `com.wind.meditor.core.ManifestEditor`, `com.wind.meditor.property.{ModificationProperty,AttributeItem}`, `com.wind.meditor.utils.NodeValue` | Binary `AndroidManifest.xml` rewriting. |
| native: `lspd::PatchLoader::Load` (`JNI_OnLoad`), `PatchLoader::LoadDex`, `ArtHooker`, `RegisterBypass` → `enableOpenatHook` | `patch_main.cpp`, `patch_loader.cpp`, `bypass_sig.cpp`. |

#### `Constants` (verbatim — this is the on-disk contract)

```java
CONFIG_ASSET_PATH          = "assets/lspatch/config.json"
LOADER_DEX_ASSET_PATH      = "assets/lspatch/loader.dex"
META_LOADER_DEX_ASSET_PATH = "assets/lspatch/metaloader.dex"
ORIGINAL_APK_ASSET_PATH    = "assets/lspatch/origin.apk"
EMBEDDED_MODULES_ASSET_PATH= "assets/lspatch/modules/"
PATCH_FILE_SUFFIX          = "-lspatched.apk"
PROXY_APP_COMPONENT_FACTORY= "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub"
MANAGER_PACKAGE_NAME       = "org.lsposed.lspatch"
MIN_ROLLING_VERSION_CODE   = 348
SIGBYPASS_LV_DISABLE=0  SIGBYPASS_LV_PM=1  SIGBYPASS_LV_PM_OPENAT=2  SIGBYPASS_LV_MAX=3
```
Also note `share/java/src/template/java/org.lsposed.lspatch.share/LSPConfig.java` — `LSPConfig` is **generated at build time** into `PatchConfig.lspConfig`.

#### How the patcher works — `LSPatch.patch()`, line by line

```java
private static final ZFileOptions Z_FILE_OPTIONS = new ZFileOptions().setAlignmentRule(
    AlignmentRules.compose(
        AlignmentRules.constantForSuffix(".so", 4096),
        AlignmentRules.constantForSuffix(ORIGINAL_APK_ASSET_PATH, 4096)));

try (var dstZFile = ZFile.openReadWrite(outputFile, Z_FILE_OPTIONS);
     var srcZFile = dstZFile.addNestedZip((ignore) -> ORIGINAL_APK_ASSET_PATH, srcApkFile, false)) {
```
1. **The output APK *is* an apkzlib `ZFile`**, and the entire original APK is added as a **nested zip** at `assets/lspatch/origin.apk` (4 KiB-aligned).
2. **Signing:** `new SigningExtension(SigningOptions.builder().setMinSdkVersion(28).setV2SigningEnabled(true).setCertificates(chain).setKey(key).build()).register(dstZFile)`. Default keystore is a **shipped resource** (`assets/keystore`, password `123456`, alias `key0`) — LSPatch does **not** generate one.
3. If `sigbypassLevel > 0`, capture `originalSignature = ApkSignatureHelper.getApkSignInfo(srcApk)`.
4. Parse original manifest → `appComponentFactory`, `minSdkVersion`.
5. **Rewrite the manifest** (`modifyManifestFile`), then rewrite it back into the zip with `dstZFile.add("AndroidManifest.xml", is)`:
   ```java
   if (overrideVersionCode) property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, 1));
   if (minSdkVersion < 28) property.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, "28"));
   property.addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, debuggableFlag));
   property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
   property.addMetaData(new ModificationProperty.MetaData("lspatch", metadata));   // base64(Gson(PatchConfig))
   if (useManager) property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");
   ```
   Note the split: **a base64 JSON copy goes into `<meta-data android:name="lspatch">`** (read at runtime by the *app's own* `PackageManager` path — see SigBypass) **and** a plain `assets/lspatch/config.json` copy (read by the loader).
6. `dstZFile.add("classes.dex", metaloaderDex)` — **the patched APK's `classes.dex` is only the ~tiny metaloader; the app's real dexes are not in `classes*.dex` at all.**
7. Embedded (`local`) mode only:
   - `assets/lspatch/loader.dex`
   - `assets/lspatch/so/{armeabi-v7a,arm64-v8a,x86,x86_64}/liblspatch.so` added with `dstZFile.add(entryName, is, false)` (**uncompressed**). Comment in source: *"do not put liblspatch.so into apk!lib because x86 native bridge causes crash"* — hence assets, not `lib/`.
   - each `-m` module APK → `assets/lspatch/modules/<packageName>.apk`.
8. **Link, don't copy** — this is why the output is barely bigger than the input:
   ```java
   for (StoredEntry entry : srcZFile.entries()) {
       String name = entry.getCentralDirectoryHeader().getName();
       if (name.startsWith("classes") && name.endsWith(".dex")) continue;   // <-- original dexes EXCLUDED
       if (dstZFile.get(name) != null) continue;
       if (name.equals("AndroidManifest.xml")) continue;
       if (name.startsWith("META-INF") && (name.endsWith(".SF")||name.endsWith(".MF")||name.endsWith(".RSA"))) continue;
       srcZFile.addFileLink(name, name);
   }
   dstZFile.realign();
   ```
   `addNestedZip` + `addFileLink` are apkzlib's *zip link* feature: the linked entry's content is fetched from the nested zip at read time, so resources/assets are never duplicated.

#### "local" vs "manager" mode

| | **Local / embedded** (`-m`, default) | **Manager** (`--manager`) |
|---|---|---|
| loader dex | inside patched APK at `assets/lspatch/loader.dex` | inside **manager APK** (`org.lsposed.lspatch`) |
| native lib | `assets/lspatch/so/<abi>/liblspatch.so` in patched APK | `manager.sourceDir + "!/assets/lspatch/so/<abi>/liblspatch.so"` |
| modules | embedded at `assets/lspatch/modules/<pkg>.apk`; `LocalApplicationService` | resolved live by `RemoteApplicationService` → manager |
| extra permission | — | `android.permission.QUERY_ALL_PACKAGES` |
| re-patch to change modules | yes | no |

Findability: the injected stub always reads `assets/lspatch/config.json` from **its own** classloader, and only `useManager` changes where the *loader dex + .so + modules* come from:

```java
// LSPAppComponentFactoryStub static {}
String arch = (String) vmInstructionSet.invoke(getRuntime.invoke(null));   // dalvik.system.VMRuntime
String libName = archToLib.get(arch);                                      // arm->armeabi-v7a, arm64->arm64-v8a, ...
if (useManager) {
    var ipm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    manager = (ApplicationInfo) HiddenApiBypass.invoke(IPackageManager.class, ipm,
                 "getApplicationInfo", "org.lsposed.lspatch", 0L, Process.myUid()/100000);   // API 33+
    try (var zip = new ZipFile(new File(manager.sourceDir));
         var is = zip.getInputStream(zip.getEntry("assets/lspatch/loader.dex"))) { dex = readAll(is); }
    soPath = manager.sourceDir + "!/assets/lspatch/so/" + libName + "/liblspatch.so";
} else {
    try (var is = cl.getResourceAsStream("assets/lspatch/loader.dex")) { dex = readAll(is); }
    soPath = cl.getResource("assets/lspatch/so/" + libName + "/liblspatch.so").getPath().substring(5); // strips "file:"
}
System.load(soPath);
```

#### How the module dex is loaded at runtime (the critical trick)

`patch_main.cpp` → `JNI_OnLoad` → `lspd::PatchLoader::GetInstance()->Load(env)`. In `patch_loader.cpp`:

```cpp
// 1. init LSPlant ART hooker; disable inline + background verification
art::DisableInline(initInfo); art::DisableBackgroundVerification(initInfo);

// 2. take the loader dex that the Java stub cached in `public static byte[] dex`
auto stub  = JNI_FindClass(env, "org/lsposed/lspatch/metaloader/LSPAppComponentFactoryStub");
auto array = JNI_GetStaticObjectField(env, stub, JNI_GetStaticFieldID(env, stub, "dex", "[B"));

// 3. InMemoryDexClassLoader, parented to the STUB's classloader (the patched APK's PathClassLoader)
auto mid_init = JNI_GetMethodID(env, in_memory_classloader, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
auto dex_buffer = env->NewDirectByteBuffer(dex.data(), dex.size());
inject_class_loader_ = JNI_NewGlobalRef(env, JNI_NewObject(env, in_memory_classloader, mid_init, dex_buffer, stub_classloader));

// 4. find the entry class from that loader and call it
SetupEntryClass(env);              // FindClassFromLoader(..., "org.lsposed.lspatch.loader.LSPApplication")
FindAndCall(env, "onLoad", "()V");
```

So: **`dalvik.system.InMemoryDexClassLoader`** (not `DexClassLoader`, not `PathClassLoader`), with the stub's own classloader as parent. No writing a dex file to disk for the *loader*.

Then `LSPApplication.onLoad()` does the app-swap, which is the actual "load the real app" mechanism:

```java
Path cacheApkPath = Paths.get(appInfo.dataDir,"cache/lspatch/origin/") .resolve(originEntry.getCrc() + ".apk");
if (!Files.exists(cacheApkPath)) { /* extract assets/lspatch/origin.apk -> cacheApkPath */ }
appInfo.sourceDir = appInfo.publicSourceDir = cacheApkPath.toString();   // <-- point the app at the REAL apk
appInfo.appComponentFactory = config.appComponentFactory;                // restore the app's ORIGINAL factory
((Map) XposedHelpers.getObjectField(activityThread,"mPackages")).remove(appInfo.packageName);
appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo); // rebuild LoadedApk off the real apk
XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);    // swap it into the bind data
/* fix android.app.ActivityThread$ActivityClientRecord.packageInfo in mActivities (and mLaunchingActivities on API 31+) */
var context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"),
                                                      "createAppContext", activityThread, stubLoadedApk);
disableProfile(context);                                   // neuter .prof files (chmod 00400 / delete)
Startup.initXposed(false, ActivityThread.currentProcessName(), dataDir, service);
Startup.bootstrapXposed();
LSPLoader.initModules(appLoadedApk);                        // -> XC_LoadPackage.callAll
switchAllClassLoader();                                     // copy every ClassLoader field appLoadedApk -> stubLoadedApk
SigBypass.doSigBypass(context, config.sigBypassLevel);
```

`LocalApplicationService` (embedded mode) enumerates `assets/lspatch/modules`, extracts each to `cacheDir/lspatch/<pkg>/<crc>.apk`, and calls `ModuleLoader.loadModule(path)`, which reads `classes.dex`, `classes2.dex`, … into `SharedMemory` objects (`PROT_READ`) plus `assets/xposed_init` (module class names) and `assets/native_init` (module native libs) → `PreLoadedApk`.

**Native libs in the injected payload:** LSPatch's `.so` lives at `assets/lspatch/so/<abi>/liblspatch.so`, stored **uncompressed**, and is loaded with `System.load("<apk path>!/assets/...")` — *not* `System.loadLibrary`, and *not* from `lib/<abi>/`. The manager module sets `androidResources { noCompress.add(".so") }` so the same trick works for the loader inside the manager APK. This deliberately sidesteps `extractNativeLibs` and the x86 native-bridge crash they hit.

### 1.2 Actively maintained successors (2024–2026) — use these as your reference implementation

The single most important finding of this research: **the LSPatch *lineage* is alive**, just not in `LSPosed/LSPatch`.

| Project | License | Latest release | Last commit | Android range | Notes |
|---|---|---|---|---|---|
| **`7723mod/NPatch`** "Neo LSPatch Framework" | **GPL-3.0** | **v1.0.9** *"The Bedrock Update"* 2026-10-13 | **2026-10-16** | **min Android 9 (API 28) → max Android 17 (SDK 37)** | Most actively maintained. Powered by **Vector** core. Modes: `--manager` (local) / `--embed` (integrated). Adds **LSPlt** (avoids transient unmapped GOT/PLT during hook install → fixes dex2oat/zygote crashes on 32-bit Android 10) and LSPlant fixes for N+ and ART trampoline alignment. Docs: https://npatch.nkbe.top/en/ |
| **`JingMatrix/LSPatch`** | **GPL-3.0** | **v1.2** 2026-08-23 (+ canary 486) | 2026-08-23 | Android 9+ (upper bound follows Vector) | Direct maintained fork. Built on **`JingMatrix/Vector`** + **`JingMatrix/LSPlant`**. Ships `manager.apk` **and** `lspatch.jar`. Optional **Shizuku** for silent patched-app install. |
| `WindySha/Xpatch` (original) | **Apache-2.0** | **v6.0** 2024-11-04 | 2026-01-04 | legacy | **Permissive license** — the only permissive full implementation in this family. See §1.3. |
| `LSPosed/LSPatch` | GPL-3.0 | v0.6 2023-11-23 | 2023-12-13 | Android 9+ | **Archived.** |

Modern hooking cores:
- **`JingMatrix/Vector`** — **GPL-3.0**, last commit 2026-10-01. Xposed-compatible framework; the thing LSPatch 1.2 / NPatch embed.
- **`HSSkyBoy/Vector`** — **GPL-3.0**, last commit 2026-08-09.
- **`LSPosed/LSPlant`** / **`JingMatrix/LSPlant`** — **LGPL-3.0** (verified from `/LICENSE`: *"GNU LESSER GENERAL PUBLIC LICENSE Version 3"*, © 2022 LSPosed). Last commits 2026-10-06 / 2026-10-01. This is the ART inline-hook engine; the `.so` you'd link.

### 1.3 Xpatch — `WindySha/Xpatch` (Apache-2.0, the permissive ancestor)

| | |
|---|---|
| License | **Apache-2.0** (`LICENSE.txt`, `NOTICE.txt`) — confirmed |
| Releases | v6.0 (2024-11-04, asset `xpatch-6.0.jar`), v5.2, v5.1, v5.0 |
| Last commit | 2026-01-04 |
| Structure | `xpatch/` = patcher CLI (`Main-Class: com.storm.wind.xpatch.MainCommand`), `loader/` = injected stub app |
| Build | root `com.android.tools.build:gradle:7.0.1`; loader `compileSdk 30 / minSdkVersion 21 / targetSdkVersion 30 / versionName "3.0"`, `abiFilters 'armeabi-v7a','arm64-v8a'`, `multiDexEnabled false` |
| Loader deps | `com.jakewharton.android.repackaged:dalvik-dx:9.0.0_r3`, `org.lsposed.hiddenapibypass:hiddenapibypass:3.0` |

Distinguishing facts:
- **It ships zipalign binaries and shells out**: `xpatch/src/main/assets/{zipalign (239,984 B, Linux x86-64), zipalign_arm, zipalign_arm64, win/zipalign.exe}`. Xpatch's answer to alignment was to fork/exec a prebuilt `zipalign`. LSPatch replaced this with apkzlib.
- Ships **two keystores** (`assets/android.keystore`, `assets/keystore`), prebuilt **SandHook** (`assets/dex/sandhook/classes.dex` 1.34 MB, `lib/{arm64-v8a,armeabi-v7a}/libsandhook`) and **whale** (`assets/dex/whale/classes-1.0.dex`, `libwhale`) hook engines, plus an embedded Xposed module `assets/xposedmodule/hook_apk_path_module.apk`.
- Its **own binary-XML stack** is in-tree and *writable*: `wind/v1/XmlSerializer.java` (322 lines), `wind/v1/XmlPullParser.java` (1036), `xpatch/.../wind/android/content/res/{AXMLResourceParser.java (836), StringBlock.java, IntReader.java, ChunkUtil.java, XmlResourceParser.java}`, `wind/test/AXMLPrinter.java`. This is the code LSPatch replaced with WindySha's separate **ManifestEditor**.
- Loader classes: `com.wind.xpatch.proxy.XpatchProxyApplication` (227 lines), `com.wind.xposed.entry.hooker.PackageSignatureHooker` (167), `SandHookInitialization`, `XposedModuleEntry`, `XposedModuleLoader`, `util/{NativeLibraryHelperCompat, PluginNativeLibExtractor, VMRuntime, AndroidAppHelper, XResForwarder, ReflectUtils}`.
- Patcher tasks: `ApkModifyTask`, `BuildAndSignApkTask`, `SoAndDexCopyTask`, `SaveApkSignatureTask`, `SaveOriginalApkTask`, `SaveOriginalApplicationNameTask`.

### 1.4 `WindySha/ManifestEditor` — the binary-manifest editor you actually want

| | |
|---|---|
| URL | https://github.com/WindySha/ManifestEditor |
| License | **Apache-2.0** ("Originally forked from [axml](https://github.com/Sable/axml)", © 2020 WindySha) |
| Latest release | **v2.0** (2024-05-17); releases also 1.0.2/1.0.1. Repo still active: last commit **2026-09-14** |
| Maven Central | **NOT PUBLISHED.** `search.maven.org` `g:"com.wind.meditor"` → `numFound: 0` |
| JitPack | **Available**: `com.github.WindySha:ManifestEditor:v2.0` → build status `"ok"` (also `1.0.2`, `a39ff62`, `master-SNAPSHOT` ok). Confirmed via `https://jitpack.io/api/builds/com.github.WindySha/ManifestEditor` |
| Consumer | Used by **both** Xpatch and LSPatch |

Why it matters — from its README: other AXML editors (apkeditor, AXMLEditor) add attributes that *the Android system cannot resolve* (e.g. adding `debuggable=true` does not actually make the installed app debuggable). ManifestEditor is specifically built to write attributes the platform parses correctly.

API surface (verified from README):
```java
ModificationProperty property = new ModificationProperty();
property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.PACKAGE, "x.y").setNamespace(null))
        .addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, 1))
        .addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_NAME, "1"))
        .addUsesPermission("android.permission.READ_EXTERNAL_STORAGE")
        .addMetaData(new ModificationProperty.MetaData("aa", "11"))
        .addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, true))
        .addApplicationAttribute(new AttributeItem(NodeValue.Application.NAME, "my.app.MyApp"))
        .addApplicationAttribute(new AttributeItem("appComponentFactory", "my.app.MyACF"));
// also: addUsesSdkAttribute(...), addProvider(attrs, action), addDeleteProviderAuthorities(...),
//       setPermissionMapper(...), setAuthorityMapper(...)
FileProcesser.processManifestFile(inXml, outXml, property);
FileProcesser.processApkFile(inApk, outApkUnsigned, property);
// or, as LSPatch does:
new ManifestEditor(inputStream, outputStream, property).processManifest();
```
**Critical gotcha, quoted from the README:** `AttributeItem`'s 2nd arg as `String` writes `TYPE_STRING`; as `Boolean` writes `TYPE_INT_BOOLEAN`. For `debuggable`/`exported`/`grantUriPermissions` you **must** pass a `Boolean`, otherwise the platform reads the default (false). This is exactly the class of bug that silently breaks injected stubs.

CLI options that map 1:1 onto injection needs: `-aa` application attribute, `-an` application name, `-act activity[:exported]`, `-d` debuggable, `-dmd`/`-md` delete/add meta-data, `-e` **`extractNativeLibs`**, `-ma` manifest attribute, `-pkg`, `-up` uses-permission, `-ua android-targetSdkVersion:N -ua android-minSdkVersion:N`, `-vc`/`-vn`.

### 1.5 Other tools

| Tool | What it is | Reusable? |
|---|---|---|
| **NP Manager / MT Manager** | Closed-source Chinese Android file/APK managers with built-in APK editing, dex editing, "APK signature kill"/removal, and (MT) a "去签名校验" feature. They are **end-user GUI apps, not libraries**, no public API, no license to reuse. Their existence proves on-device apk editing at scale is practical (same market as LSPatch's manager). | No |
| **`ApkMod` / `fuckapk`-style scripts** | I found no canonical, maintained repository by these names. Treat as **UNVERIFIED**; they are community script collections in the same "unzip → patch manifest/dex → rezip → resign" family rather than distinct technology. Do not plan around them. | n/a |
| **`REAndroid/APKEditor`** | Java APK editor that also has a library-ish core; listed as a candidate in the brief. | **Not verified in this pass** — flag as follow-up. |

### 1.6 Virtualization instead of repackaging — VirtualApp / VirtualXposed

| Project | License | Reality |
|---|---|---|
| **`asLody/VirtualApp`** | **No OSS license file found at `master`** (`/LICENSE` → 404). The README states the code *"belongs to Jining Luohe Network Technology Co., LTD"* and that you **must purchase a business license**. | **UNUSABLE.** README verbatim: *"The code on Github has stopped updating in December 2017."* The 2026-10-06 commit is a **README-only** edit. It further states the vendor has *"called to the police and sued a number of individuals and companies in 2020"* for unlicensed use. Not an option under any circumstances. |
| **`android-hacker/VirtualXposed`** | **GPL-3.0** | **DEAD.** Last commit **2022-06-07** (tag `0.22.0`). 16,076 stars / 2,529 forks on `vxp` branch. VirtualApp-based; will not support Android 14/15/16. |
| **SandVXposed** | — | Same lineage, **UNVERIFIED** for 2024-2026 activity; assume abandoned with VirtualXposed. |

**Repackage vs virtualize — the honest comparison:**

| | Repackage (LSPatch/Xpatch/NPatch) | Virtualize (VirtualApp) |
|---|---|---|
| Changes the target APK | Yes — new signature, new `classes.dex`, new manifest attrs | No |
| Installable as a normal app | Yes | No — target runs *inside* the host process |
| Target's signature checks | Must be bypassed at runtime (LSPatch does; §3) | Naturally passes (app sees the host's... no — app sees its own installed APK only if actually installed; inside VA it is *not* installed, so `PackageManager` answers are proxied) |
| Play Integrity / server attestation | **Fails** (`appIntegrity`) | **Fails** (unrecognized device/app state) |
| Android 14/15/16 viability | Demonstrated to **Android 17 (SDK 37)** by NPatch | Requires constant Framework-level hook maintenance; OSS version frozen 2017 → **not viable** |
| Legal | Signature change is detectable by the app; GPL-3.0 if you reuse LSPatch code | Vendor asserts commercial licensing + has litigated |

**Conclusion:** the repackaging approach is the only technically and legally tractable one, and you should build on the *permissive* components (Xpatch's Apache-2.0 patcher ideas, ManifestEditor, apkzlib, apksig, dexlib2) rather than copy GPL-3.0 LSPatch/Vector code, unless you are willing to license your whole project GPL-3.0.

---

## 2. APK manipulation toolchain — exact versions, coordinates, licenses

### 2.1 apktool

| | |
|---|---|
| Latest release | **v3.0.3**, 2026-07-20 (asset `apktool_3.0.3.jar`). Previous: 3.0.2 (2026-04-19), 3.0.1 (2026-02-22), 3.0.0 (2026-02-22), 2.12.1 (2025-09-18) |
| In-tree version | `build.gradle.kts`: `version = "3.1.0"`, `suffix = "SNAPSHOT"` → development head is **3.1.0-SNAPSHOT** |
| License | **Apache-2.0** (`LICENSE.md`); POM declares `The Apache License 2.0` |
| Build requirements | **JDK 17** to build (`JavaLanguageVersion.of(17)`), emits **Java 8 bytecode** (`options.release.set(8)`) |
| Publishing | `publishing.gradle:7`: `coordinates("org.apktool", project.name, project.apktoolVersion)` via the `vanniktech.maven.publish` plugin → **Maven Central** |

**Yes, there is a library artifact: `org.apktool:apktool-lib`.** Verified `maven-metadata.xml` → `<release>3.0.3</release>` (lastUpdated `20260720231521`). Companion artifacts (same group/version, all present on Central):

```
org.apktool:apktool-lib:3.0.3     org.apktool:brut.j.common:3.0.3
org.apktool:brut.j.util:3.0.3     org.apktool:brut.j.dir:3.0.3
org.apktool:brut.j.xml:3.0.3      org.apktool:brut.j.yaml:3.0.3
org.apktool:brut.j.zip:3.0.3
```
`apktool-lib-3.0.3.pom` declares **runtime** deps on:
```
com.github.iBotPeaches.smali:smali-baksmali:b6365a84f4
com.github.iBotPeaches.smali:smali:b6365a84f4
com.google.guava:guava:33.6.0-jre
commons-io:commons-io:2.22.0
org.apache.commons:commons-text:1.15.0
```
(Those `com.github.iBotPeaches.smali:*` are **JitPack** coordinates — see §2.2. Note `guava:...-jre`, not `-android`.)

Library entry points (verified class listing): `brut.androlib.ApkDecoder`, `brut.androlib.ApkBuilder`, `brut.androlib.ApkFile`, `brut.androlib.Config`, `brut.androlib.res.decoder.*`, `brut.androlib.res.table.*`. The CLI is `brut.apktool.Main` in the separate `apktool-cli` project.

**Can it run on Android? No — not without a port.** Measured across `brut.apktool`, `brut.j.common`, `brut.j.util`, `brut.j.xml`, `brut.j.zip`, `brut.j.yaml`:

| API | references | Android? |
|---|---|---|
| `java.nio.file` | **129** | API 26+ / desugaring only; heavy use across `ApkInfo`, `ResFileDecoder`, `ResXmlUtils`, `AaptInvoker`, exceptions |
| `javax.xml` | 17 | **Absent on Android** (`javax.xml.stream` etc.) |
| `java.awt` | 6 | **Absent**: `ResNinePatchStreamDecoder.java` imports `java.awt.image.{BufferedImage,Raster,WritableRaster}` |
| `javax.imageio` | 3 | **Absent**: `ResNinePatchStreamDecoder.java` imports `javax.imageio.ImageIO` |
| `java.lang.management` / `ServiceLoader` | 0 / 0 | fine |

Additionally the resource build path shells out: `AaptInvoker` (`binPath = mConfig.getAaptBinary()`, "aapt2 compile command ran", "aapt2 link command ran") and `AaptManager` returns `"aapt2"`. So apktool needs an **external `aapt2` binary** to rebuild resources. Conclusion: **apktool is a desktop/CI tool, not an on-device library.** Use it for offline inspection and for `--use-aapt2` resource work, not in your on-device pipeline.

`brut.j.dir` is a real published artifact (confirmed `<release>3.0.3</release>`), not only a source folder.

### 2.2 smali / baksmali / dexlib2 — and the coordinates problem you must know about

| | |
|---|---|
| Repo | https://github.com/google/smali (default branch now **`main`**) |
| Latest release tag | **3.0.10** (2026-08-20); prior 3.0.9 (2024-12-19), 3.0.8, 3.0.7 (2024-05-13) |
| In-tree version | root `build.gradle`: `version = '3.0.10'` |
| License | **BSD-3-Clause** for the majority (Ben Gruver), **Apache-2.0** for AOSP-derived portions — the `LICENSE` file contains both texts |
| Declared publishing | `group = 'com.android.tools.smali'`; `artifactId = project.name` for `smali`, else `'smali-' + name` → intended `com.android.tools.smali:smali`, `:smali-baksmali`, `:smali-dexlib2`, `:smali-util` |
| Deps (root `build.gradle`) | `com.google.guava:guava:31.1-android`, `com.google.code.findbugs:jsr305:1.3.9`, `org.antlr:antlr-runtime:3.5.2` |

**⚠️ The intended coordinates are NOT on Maven Central.** Verified three ways:
- `https://repo1.maven.org/maven2/com/android/tools/smali/` → **404**
- `search.maven.org` `q=g:"com.android.tools.smali"` → `numFound: 0`
- `https://repo1.maven.org/maven2/org/smali/` → exists, but **frozen at 2.5.2** (`baksmali`, `dexlib2`, `smali`, `util`; full version list ends `…2.4.0, 2.5.0, 2.5.1, 2.5.2`)

**What actually works in 2026 — JitPack, via apktool's own fork.** `apktool-lib:3.0.3` itself depends on JitPack coordinates, and I verified the artifacts exist and are correct:

```
https://jitpack.io/api/builds/com.github.iBotPeaches/smali
  ->  "b6365a84f4" : "ok", "403e90375e" : "ok", "d856bad65f" : "ok", "v2.4.0" : "ok", "2.4.0" : "ok"
GET https://jitpack.io/com/github/iBotPeaches/smali/b6365a84f4/smali-b6365a84f4.pom   -> 200 (1110 bytes)
build.log lists: smali-baksmali-<v>.jar/.module/.pom, smali-util-<v>.jar...
```

I downloaded `smali-dexlib2-b6365a84f4.jar` (1,194,528 bytes, **1,034 entries / 988 classes**) and inspected it directly:

```
package prefixes:  com/android/tools/smali/dexlib2  (943 classes)
                   com/android/tools/smali/util     (45 classes)
present: iface/DexFile, immutable/ImmutableDexFile, writer/builder/DexBuilder,
         writer/pool/DexPool, rewriter/DexRewriter, dexbacked/DexBackedDexFile, Opcodes
```

So **the 3.x package is `com.android.tools.smali.dexlib2`** (not `org.jf.dexlib2`). The 2.5.2 artifacts on Central use **`org.jf.dexlib2`**. Pick one line and be consistent — the package rename silently breaks copy-pasted snippets.

```gradle
// buildable 2026 recipe (verified artifacts)
repositories { maven("https://jitpack.io") }
dependencies {
    implementation("com.github.iBotPeaches.smali:smali-dexlib2:b6365a84f4") // com.android.tools.smali.dexlib2.*
    implementation("com.github.iBotPeaches.smali:smali:b6365a84f4")         // assembler
    implementation("com.github.iBotPeaches.smali:smali-baksmali:b6365a84f4")
    // legacy alternative, Maven Central, package org.jf.dexlib2:
    // implementation("org.smali:dexlib2:2.5.2")
}
```

**Multidex:** yes, dexlib2 handles it. `com.android.tools.smali.dexlib2.DexFileFactory` exposes:
```java
public static DexBackedDexFile loadDexFile(String path, @Nullable Opcodes opcodes)
public static DexEntry<? extends DexBackedDexFile> loadDexEntry(...)
public static MultiDexContainer<? extends DexBackedDexFile> loadDexContainer(...)   // <-- classes.dex, classes2.dex, ...
public static void writeDexFile(String path, DexFile dexFile)
```

**Programmatic write APIs (verified signatures from source):**
```java
// writer/pool/DexPool.java
public class DexPool extends DexWriter<...> {
    public static void writeTo(@Nonnull DexDataStore dataStore, @Nonnull DexFile input)
    public static void writeTo(@Nonnull String path,      @Nonnull DexFile input) throws IOException
    public void internClass(ClassDef classDef)
}
// immutable/ImmutableDexFile.java
public ImmutableDexFile(@Nonnull Opcodes opcodes, @Nullable Collection<? extends ClassDef> classes)
// writer/builder/DexBuilder.java
public DexBuilder(@Nonnull Opcodes opcodes)
public BuilderClassDef internClassDef(String type, ...)
public BuilderMethod internMethod(String definingClass, ...)
public BuilderStringReference internStringReference(String string)
// smali/Smali.java
public static boolean assemble(final SmaliOptions options, String... input)
public static boolean assemble(final SmaliOptions options, List<String> input)
```

**On-Android viability — measured, not guessed.** I scanned every class in both jars for JRE-only API references:

| Jar | classes | `java.nio.file` | `java.awt` | `javax.imageio` | `javax.xml` | `java/lang/management` | `java.util.function` | `java.util.stream` |
|---|---|---|---|---|---|---|---|---|
| `smali-dexlib2-b6365a84f4.jar` | 988 | 0 | 0 | 0 | 0 | 0 | 81 | 16 |
| `apksig-9.4.1.jar` | 267 | 0 | 0 | 0 | 0 | 0 | 17 | 6 |

✅ **dexlib2 runs on Android** (needs `java.util.function`/`java.util.stream` → **API 24+**, or core-library desugaring below that; `guava:31.1-android` is Android-safe). ✅ **apksig runs on Android** (same API 24+ floor). ❌ apktool does not (see §2.1).

### 2.3 Binary `AndroidManifest.xml` / ARSC editing

| Library | Coordinates | Version | License | Read/Write | Notes |
|---|---|---|---|---|---|
| **WindySha ManifestEditor** | **JitPack** `com.github.WindySha:ManifestEditor:v2.0` (not on Central) | v2.0 (2024-05-17) | Apache-2.0 | **Read + Write** | Best-in-class for *writing* attributes the platform actually resolves. Depends on a shaded `pxb.android.axml.{AxmlParser,…}` (from Sable/axml); LSPatch vendors the whole submodule as source rather than a jar. |
| `pxb.android.axml` (`Sable/axml`) | not on Maven Central **UNVERIFIED** for JitPack | — | — | Read + Write | The origin of ManifestEditor. `AxmlParser` used by LSPatch's `ManifestParser`. |
| `net.dongliu:apk-parser` | Maven Central | **2.6.10** (58 versions; also `net.dongliu.apk-parser:apk-parser:1.4.1`) | Apache-2.0 **UNVERIFIED** | **Read-only** | Parses manifest/resources for inspection; cannot modify. Fine for extracting `packageName`/`versionCode`. |
| `com.github.iyxan23:apkparser` | — | — | — | — | **UNVERIFIED / could not confirm existence** on Central or JitPack in this pass. Do not depend on it. |
| `brut.j.common` / `brut.j.util` / `brut.j.dir` / `brut.j.xml` | `org.apktool:*` | **3.0.3** | Apache-2.0 | both (desktop) | Published separately (confirmed `<release>3.0.3</release>` for `brut.j.common` and `brut.j.dir`). Same Android-hostility caveats as apktool. |
| **apkzlib** | `com.android.tools.build:apkzlib` on **Google Maven** | latest stable **9.4.1** (newest `9.5.0-alpha10`, metadata lastUpdated `20261015`) | Apache-2.0 | **Write** (zip+sign) | This is what LSPatch vendors. `ZFile`, `ZFileOptions`, `AlignmentRules`, `sign.SigningExtension`, `sign.SigningOptions`, `zip.StoredEntry`. |

**The right way to modify a binary manifest for injection** (what both Xpatch and LSPatch converge on): *do not* re-encode the whole AXML. Parse it with `AxmlParser`, and use ManifestEditor's `ModificationProperty` to add/modify only the needed nodes, keeping the original string pool and resource IDs intact:

```java
ModificationProperty p = new ModificationProperty();
p.addApplicationAttribute(new AttributeItem("appComponentFactory", STUB_ACF));   // String -> TYPE_STRING
p.addMetaData(new ModificationProperty.MetaData("mypayload", base64Json));
p.addUsesPermission("android.permission.SYSTEM_ALERT_WINDOW");                   // for a floating window
p.addUsesSdkAttribute(new AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, "28"));
new ManifestEditor(manifestIn, manifestOut, p).processManifest();
```
For a floating-window payload you additionally need `SYSTEM_ALERT_WINDOW`; on API 23+ it is a **special permission** granted via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (`Settings.canDrawOverlays()`), not a runtime prompt — as the *payload* you should request it at runtime, or use a `TYPE_APPLICATION_OVERLAY` window only after the user grants it.

### 2.4 Signing

**apksig vs apksigner.** `apksigner` is the build-tools CLI wrapper around the same library. The library is what you want:

| | |
|---|---|
| Coordinate | **`com.android.tools.build:apksig`** from **Google Maven** (`https://dl.google.com/dl/android/maven2/`) |
| Latest stable | **9.4.1**; newest published `9.5.0-alpha10` (metadata lastUpdated `20261015`). Stable ladder includes 8.11.2, 8.12.0–8.12.3, 8.13.0–8.13.2, 9.0.0, 9.0.1, 9.1.0, 9.1.1, 9.2.0, 9.2.1, 9.3.0–9.3.3, 9.4.0, 9.4.1 |
| **Trap** | `com.android.tools.build:apksig` **on Maven Central is frozen at 2.3.0 (2017-03-06)** — if you only declare `mavenCentral()` you will silently get a 9-year-old artifact. Always add `google()`. |
| License | Apache-2.0 (POM `<name>The Apache Software License, Version 2.0</name>`) |
| Transitive deps | **NONE** — `apksig-9.4.1.pom` has **no `<dependencies>` block**. Ideal for Android. |

API (verified against AOSP `platform/tools/apksig` `main`):
```java
com.android.apksig.ApkSigner
  public static class SignerConfig { public static class Builder {
      public Builder(String name, PrivateKey privateKey, List<X509Certificate> certificates)
      public Builder(String name, KeyConfig keyConfig, List<X509Certificate> certificates)
      public SignerConfig build() } }
  public static class Builder {
      public Builder(List<SignerConfig> signerConfigs)
      public Builder setInputApk(File inputApk)
      public Builder setOutputApk(File outputApk)
      public Builder setMinSdkVersion(int minSdkVersion)
      public Builder setV1SigningEnabled(boolean enabled)
      public Builder setV2SigningEnabled(boolean enabled)
      public Builder setV3SigningEnabled(boolean enabled)
      public Builder setV4SigningEnabled(boolean enabled)
      public Builder setV4SignatureOutputFile(File v4SignatureOutputFile)
      public Builder setSigningCertificateLineage(SigningCertificateLineage lineage)
      public Builder setLibraryPageAlignmentBytes(int libraryPageAlignmentBytes)   // <-- 16 KB page support
      public Builder setAlignmentPreserved(boolean alignmentPreserved)
      public Builder setDebuggableApkPermitted(boolean permitted)
      public ApkSigner build() }
  public void sign() throws ...      // throws SignatureException, IOException, ...
```
**v3/v4:** v3 is enabled with `setV3SigningEnabled(true)` and is what carries **signing-certificate rotation** (`SigningCertificateLineage`, `apksigner rotate ... --rotation-min-sdk-version`). **v4 produces a separate `<apk>.idsig` file and does not modify the APK**; `apksigner --v4-signing-enabled <true|false|only>` where `only` writes just the v4 signature — it is used for incremental-install/`adb install --fastdeploy` style flows, and is **not needed** for normal installability. ([apksigner docs](https://developer.android.com/tools/apksigner))

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** is what you get when the installed app's signing certificate differs from the new APK's: the platform refuses the update. Consequences for a repackaging tool:
- The patched APK **cannot** be installed over the original — the user must **uninstall the original first** (which destroys its data, unless it uses `allowBackup`/`adb backup` or you extract data via root/Shizuku).
- The patched app **can** be upgraded over *previous patched* builds, as long as you sign with the **same** keystore every time. Therefore: **generate the keystore once, persist it, never lose it.**
- The original app can no longer self-update from Play.

Official apksig docs: https://developer.android.com/tools/apksigner · scheme history: https://source.android.com/docs/security/features/apksigning

### 2.5 zipalign, and why v2+ changed the game

- **There is no `com.android.tools.build:zipalign` artifact.** `https://dl.google.com/dl/android/maven2/com/android/tools/build/zipalign/maven-metadata.xml` returns an error page, not metadata. Confirmed absent.
- The **v1-era** reason for zipalign: uncompressed entries (notably `resources.arsc` and stored files) must start on a 4-byte boundary so `mmap()`-based readers can access them directly. **v2+ signing preserves alignment**: the APK Signing Block sits *before* the central directory, and signers are required to keep the alignment of every entry intact — so alignment is not "broken" by signing, but you must still *produce* alignment, because apksig does not re-align content for you by default.
- **How the modern tools do it:**
  - **apkzlib** (LSPatch's path): `ZFileOptions.setAlignmentRule(AlignmentRules.compose(AlignmentRules.constantForSuffix(".so", 4096), AlignmentRules.constantForSuffix("assets/lspatch/origin.apk", 4096)))` and `ZFile.realign()`. Verified API: `AlignmentRules.constant(int)`, `AlignmentRules.constantForSuffix(String suffix, int alignment)`, `AlignmentRules.compose(AlignmentRule...)`, and `AlignmentRule.NO_ALIGNMENT == 1`.
  - **apksig** (for v2+): `ApkSigner.Builder.setLibraryPageAlignmentBytes(int)` — this is the knob that aligns uncompressed `.so` entries when signing. For 16 KB devices use **16384**.
  - **Xpatch**: forked/exec'd a prebuilt `zipalign` binary. Don't do this.
- **16 KB page sizes — the real 2026 requirement** ([official doc](https://developer.android.com/guide/practices/page-sizes)):
  - *"Beginning with Android 15, AOSP supports devices that are configured to use a page size of 16 KB (16 KB devices)."*
  - *"**Google Play compatibility requirement**: To ensure your app works correctly on the latest versions of Android, all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play."*
  - Verify with: `zipalign -c -P 16 -v 4 APK_NAME` (and from Studio, `zipalign -v -c -P 16 4 app.apk`).
  - Native code must be linked 16 KB-aligned: for NDK r27 and lower, `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`.
  - *"If your app doesn't extract native libraries to the file system (`extractNativeLibs` set to `false`), you'll likely notice a slight increase in your app's binary size after compiling using a 16 KB ELF alignment."*
  - Android 16 adds a **16 KB page size compatibility mode** *"allowing some apps built for 4 KB memory pages to run on a device configured for 16 KB memory pages."*
  - **Tool implication:** if you inject any `.so` (or preserve a target's `.so` uncompressed), align `lib/**.so` and every stored entry to **16384** and call `setLibraryPageAlignmentBytes(16384)`, otherwise the patched APK will fail to load native libs on 16 KB devices even though it installs. Note LSPatch used only **4096** (its 2023 code predates the requirement) — that is a concrete bug you must not inherit.

### 2.6 Keystore generation on-device (no `keytool` on Android)

`keytool` is a JDK tool and is not present on Android. Generate the keypair + self-signed cert in-process:

| Artifact | Version | License | Android |
|---|---|---|---|
| `org.bouncycastle:bcpkix-jdk18on` | **1.86** (2026-09-11) | **"Bouncy Castle Licence"** (MIT-style permissive) | depends on `bcutil-jdk18on:1.86` (+ `bcprov-jdk18on`) |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | same | |
| `org.bouncycastle:bcpkix-jdk15to18` | **1.86** (same version ladder 1.81→1.86) | same | **Use this line on Android** — the `jdk15to18` builds target Java 5–8 APIs and avoid the Java 9+ `java.naming`/module assumptions that make the `jdk18on` line fragile on ART. If you prefer `jdk18on`, pin it and test `KeyStore.getInstance("PKCS12")` + the `BouncyCastle`/`BC` provider name on Android 14/15/16. |
| `com.madgag.spongycastle` | — | — | **Abandoned** (superseded by Bouncy Castle's own `jdk15to18` line). Do not use. |

LSPatch's expectation (from `MyKeyStore.kt` + `LSPatch.keystoreArgs` defaults): **store password `123456`, alias `key0`, alias password `123456`**; it stores a **BKS** file at `filesDir/keystore.bks` for *custom* keystores and otherwise ships a default keystore as an asset. It never generates one — you should.

### 2.7 Loading extra dex + native code at runtime — verified approaches

**A. Load the injected dex (works; LSPatch's exact mechanism).**
`dalvik.system.InMemoryDexClassLoader(ByteBuffer, ClassLoader parent)`:
```java
byte[] dex = readAsset("assets/payload/loader.dex");
var cl = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), getClassLoader()); // parent = patched APK's PathClassLoader
Class<?> entry = Class.forName("com.example.payload.Entry", true, cl);
entry.getMethod("onLoad").invoke(null);
```
JPMS-free on Android; `InMemoryDexClassLoader` is **API 26+**. (`DexClassLoader`/`PathClassLoader` with a `dexPath` of `apk!/…` also works when the dex is a *separate extracted file*; alternatively append to the existing `BaseDexClassLoader`'s `pathList.dexElements` via reflection, which is what many packers do and is far more fragile.)

**B. Make your code the `Application` — two mechanisms, in order of preference:**

1. **`android:appComponentFactory` (recommended, API 28+).** This is what LSPatch/Xpatch/NPatch all do. You do **not** replace the app's `Application`; you install a subclass of `android.app.AppComponentFactory` whose `instantiateApplication(cl, className)` can return *your* `Application` (or defer to `super`). It runs before `Application` creation and before `ContentProvider`s. LSPatch saves the original `appComponentFactory` into `PatchConfig` and restores it at runtime so app behaviour is unchanged.
   ```java
   public class StubACF extends AppComponentFactory {
       @Override public Application instantiateApplication(ClassLoader cl, String className)
               throws InstantiationException, IllegalAccessException, ClassNotFoundException {
           bootstrap();                       // load .so + loader dex, install hooks
           return super.instantiateApplication(cl, className);   // the app's real Application
       }
   }
   ```
   Note the LSPatch detail: `appComponentFactory` was added in **API 28**, which is exactly why LSPatch forces `uses-sdk minSdkVersion=28`.

2. **Replace `application@android:name` with a stub `Application` (works on older APIs).** Point `<application android:name>` at `com.example.StubApp`, which in `attachBaseContext()` (a) loads the payload, then (b) reflectively instantiates the *original* Application name you saved into the `lspatch`-style meta-data and calls its `attachBaseContext`/`onCreate`. This is the `-an`/`SaveOriginalApplicationNameTask` path in Xpatch. More invasive: breaks if the original `Application` is referenced by class name elsewhere or is expected to be created by the framework.

Both need the manifest edit from §2.3. In smali (if you inject by assembling smali) the stub is just:
```smali
.class public Lcom/example/StubACF;
.super Landroid/app/AppComponentFactory;
.method public instantiateApplication(Ljava/lang/ClassLoader;Ljava/lang/String;)Landroid/app/Application;
    .locals 0
    invoke-static {}, Lcom/example/Bootstrap;->init()V
    invoke-super {p0, p1, p2}, Landroid/app/AppComponentFactory;->instantiateApplication(Ljava/lang/ClassLoader;Ljava/lang/String;)Landroid/app/Application;
    move-result-object p0
    return-object p0
.end method
```
(assemble with `com.android.tools.smali.smali.Smali.assemble(SmaliOptions, …)`).

**C. Injecting native libs.** Two viable placements:
- **`lib/<abi>/libfoo.so`, stored uncompressed.** This is the standard Android layout; whether the loader can `dlopen` it *from inside the APK* depends on `android:extractNativeLibs`. With `extractNativeLibs="false"` (the AGP default when `useLegacyPackaging=false`, and required for 16 KB-aligned mmap of the lib) the lib is mmapped from the APK and must be **uncompressed and page-aligned**. Set it explicitly via ManifestEditor's `-e 0` / `AttributeItem(NodeValue.Application.EXTRACT_NATIVE_LIBS, false)` and align to 16384.
- **`assets/<abi>/libfoo.so`, uncompressed, loaded by absolute APK-internal path** — what LSPatch does, because it hit an *x86 native bridge* crash with `lib/`. `System.load(path)` accepts `<apk>!/assets/...`:
  ```java
  String path = cl.getResource("assets/so/arm64-v8a/libpayload.so").getPath().substring(5); // strip "file:"
  System.load(path);
  ```
  `System.loadLibrary` will **not** find an asset — it only searches `lib/<abi>` (and `LD_LIBRARY_PATH`). So choose: `lib/` + `loadLibrary` (idiomatic, needs alignment/extractNativeLibs correct) or `assets/` + `System.load` (robust, non-idiomatic). Also add `androidResources { noCompress.add(".so") }` in your own app so the *manager's* copies are loadable.

---

## 3. Legal / practical constraints

### 3.1 What re-signing breaks (all of this is inherent, not a bug)

1. **Self-update and over-install.** A different signing certificate ⇒ the platform refuses to update the existing app: **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**. The original must be uninstalled first (data loss), and the app can no longer update itself from Play. Sign consistently after that first patch and patched→patched upgrades work (§2.4).
2. **In-app signature checks.** `PackageManager.checkSignatures`, `PackageInfo.signatures`, and (API 28+) `PackageInfo.signingInfo` / `SigningInfo.getApkContentsSigners()` all expose the new cert. LSPatch defeats the common Java-level forms (§3.3). An app that stores a hash of its own APK file, or verifies inside native code, is harder.
3. **Play Integrity API (the real wall).** SafetyNet Attestation is deprecated in favour of Play Integrity; the official overview states the **`appIntegrity` verdict** lets a developer *"determine whether you're interacting with your unmodified binary that Google Play recognizes."* This is a **server-side** verdict — you cannot patch it out of the client. Verdict labels (from the official docs):
   - `deviceIntegrity`: `MEETS_BASIC_INTEGRITY`, `MEETS_DEVICE_INTEGRITY`, and opt-in `MEETS_STRONG_INTEGRITY` ("genuine and certified Android device with a recent security update", Android 13+).
   - `MEETS_VIRTUAL_INTEGRITY` — **note the correction:** this is *not* about app-virtualization containers. The docs define it as *"The app is running on an Android-powered emulator with Google Play services"*, a conditional label for **Google Play Games on PC**. Do not cite it as "virtualization containers pass integrity".
   - Practical upshot: **a repackaged build of an app that calls Play Integrity with enforcement will be detected and can be refused service.** LSPatch/NPatch explicitly do not solve this. Plan for target apps that *don't* enforce it, and say so in your UX.
4. **Tamper/anti-repack detection** beyond signatures: APK content hashing, CRC/DEX checksum checks, `PackageManager.getPackageInfo(...).firstInstallTime`/`installerPackageName` inspection, R8/integrity SDKs, and packers that refuse to run if the dex is modified. Xpatch's own README admits: *"If the apk dex files are protected, dex2jar cannot effect on the dexs, then this tool will not work."*

### 3.2 Install restrictions on Android 14 / 15 / 16 (official, quoted)

| Android | Rule | Error |
|---|---|---|
| **14 (API 34)** | *"Starting with Android 14, apps with a targetSdkVersion lower than 23 can't be installed."* Upgrading devices keep already-installed apps. | `INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 23, but found 7` |
| **15 (API 35)** | *"In Android 15, apps with a targetSdkVersion lower than 24 can't be installed."* | `INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 24, but found 7` |
| **16 (API 36)** | `behavior-changes-all` contains **no new minimum installable target API level** — the floor stays at 24. Android 16 adds the 16 KB compatibility mode. |

Sources: [Android 14 behavior changes (all apps)](https://developer.android.com/about/versions/14/behavior-changes-all) · [Android 15](https://developer.android.com/about/versions/15/behavior-changes-all) · [Android 16](https://developer.android.com/about/versions/16/behavior-changes-all) · [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

**`minSdkVersion` vs `targetSdkVersion` — do not confuse them, because LSPatch's `minSdkVersion=28` edit is NOT about this rule.** `uses-sdk@minSdkVersion` is the *minimum* API the app will run on (the platform refuses to install below it, and ART uses it for verification/`dex2oat` decisions). `uses-sdk@targetSdkVersion` is the *behaviour contract* (which compatibility switches are on). The Android 14/15 install floor is on **`targetSdkVersion`**, not `minSdkVersion`. LSPatch raised **`minSdkVersion`** to 28 purely because `appComponentFactory` requires API 28 — that edit can never fix a low `targetSdkVersion`. **If your patcher encounters `targetSdkVersion < 24`, it must either raise `targetSdkVersion` (which changes app behaviour and can break the target) or install with the bypass flag below.** Prefer: detect and warn.

**Install flags (verified in AOSP `PackageManagerShellCommand.java`):**
- `--bypass-low-target-sdk-block` → `PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK` (line 3584 of `services/core/java/com/android/server/pm/PackageManagerShellCommand.java`). Works for both `pm install` and `adb install`. This is the documented way around the low-target block.
- `pm install -r` (reinstall/keep data), `-d` (allow version downgrade), `-t` (allow test packages), `--user <id>`, `-g` (grant all runtime permissions).
- Session flow (what an in-app installer should use): `pm install-create` → `pm install-write` → `pm install-commit`, i.e. `PackageInstaller.Session` params → `openWrite` → `commit(IntentSender)`.
- Sub-commands present in AOSP: `install`, `install-commit`, `install-create`, `install-write`, `install-existing`.

**Installing without a PC (what your pipeline must do):**
- A normal app cannot silently install. You need `REQUEST_INSTALL_PACKAGES` ("Allows an application to request installing packages", added API 23) **and** the user must grant *"install unknown apps"* for your app — `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`), checkable with `PackageManager.canRequestPackageInstalls()`.
- Then launch `ACTION_INSTALL_PACKAGE` / `ACTION_VIEW` with `application/vnd.android.package-archive`, or use a `PackageInstaller.Session` + `IntentSender` for a smoother flow. **The user confirmation dialog is unavoidable without root/Shizuku.**
- **Shizuku is the practical escape hatch** and is what the maintained forks use: JingMatrix/LSPatch's README says *"With it, the manager installs patched apps silently; without it, grant 'install unknown apps' once and install by hand."* LSPatch's manager depends on `dev.rikka.shizuku:api:13.1.2` + `dev.rikka.shizuku:provider:13.1.2`.

### 3.3 How LSPatch deals with apps that verify their own signature (verified from source)

Two escalating levels, chosen by `-l 0|1|2` ("Signature bypass level. 0 (disable), 1 (pm), 2 (pm+openat)"):

**Level 1 — `SIGBYPASS_LV_PM`, pure Java.**
```java
XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
    @Override protected void afterHookedMethod(MethodHookParam param) {
        var packageInfo = (PackageInfo) param.getResult();
        if (packageInfo == null) return;
        replaceSignature(context, packageInfo);      // patches signatures[0] and signingInfo
    }});
// PLUS a Parcelable.Creator<PackageInfo> proxy installed into PackageInfo.CREATOR,
// so PackageInfos arriving over Binder are patched too:
XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", proxiedCreator);
// and the Parcel creator caches must be cleared or they keep returning the old creator:
mCreators.clear();          // Parcel.mCreators
sPairedCreators.clear();    // Parcel.sPairedCreators
```
The *replacement* value is the original cert, decoded at runtime **from the patched app's own manifest**:
```java
String encoded = context.getPackageManager().getApplicationInfo(packageName, GET_META_DATA)
                        .metaData.getString("lspatch");
replacement = new Gson().fromJson(new String(Base64.decode(encoded, DEFAULT), UTF_8), PatchConfig.class).originalSignature;
```
That is why the patcher embeds `originalSignature` (hex of the cert, produced by `ApkSignatureHelper.getApkSignInfo`) into `<meta-data android:name="lspatch">` **and** keeps `packageInfo.signatures[0] = new Signature(replacement)` + `signingInfo.getApkContentsSigners()[0] = new Signature(replacement)`.

**Level 2 — `SIGBYPASS_LV_PM_OPENAT`, native.** Defeats apps that read their own APK bytes and verify offline. Hooks libc and redirects the open of the *patched* APK to the extracted *original*:
```cpp
CREATE_HOOK_STUB_ENTRY("__openat", int, __openat, (int fd, const char* pathname, int flag, int mode), {
    if (pathname == apkPath) { LOGD("redirect openat"); return backup(fd, redirectPath.c_str(), flag, mode); }
    return backup(fd, pathname, flag, mode); });
LSP_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook, jstring origApkPath, jstring cacheApkPath) {
    auto sym_openat = SandHook::ElfImg("libc.so").getSymbAddress<void*>("__openat");
    HookSymNoHandle(handler, sym_openat, __openat);
    ...
}
```
and Java-side it passes `context.getPackageResourcePath()` (patched APK) and the extracted-original path computed from the nested zip entry's CRC.

**Why level 1 alone is insufficient:** (a) it only covers `PackageInfo` objects that pass through `PackageParser.generatePackageInfo` or `Parcel` — a native library reading `/data/app/.../base.apk` directly sees the *patched* bytes; (b) **it cannot touch Play Integrity**, which is evaluated server-side by Google; (c) it does not defeat content hashing of the APK file, only certificate reporting.

**2026 status:** the *mechanism* still works (NPatch claims Android 17 / SDK 37), but it is whack-a-mole against ART/hidden-API changes. The maintained forks keep patching LSPlant/LSPlt ART trampoline details — see NPatch's notes about *"avoids a transient unmapped GOT/PLT region while installing hooks… prevents dex2oat/zygote crashes"* and LSPlant *"aligns hook trampolines correctly for improved ART hook compatibility."* Budget for ongoing maintenance.

### 3.4 Legal framing (research, not legal advice)

- **GPL exposure is the sharpest practical risk.** Reusing LSPatch, NPatch, or Vector code makes your project **GPL-3.0** (they are all GPL-3.0). **LSPlant is LGPL-3.0** — you must publish changes to LSPlant itself and permit relinking, but a proprietary app may link it dynamically. **Xpatch and ManifestEditor are Apache-2.0**, apktool Apache-2.0, smali BSD-3-Clause, apksig/apkzlib Apache-2.0, Bouncy Castle MIT-style, Guava Apache-2.0. A commercially-licensed product should therefore build on the Apache/BSD/MIT stack and **not** copy GPL sources.
- **Google Play policies**: apps that modify other apps, impersonate them, or interfere with device/network operation fall under Deceptive Behaviour / IP / Device-and-Network-Abuse provisions. A repackaging *tool* is not itself a Play-distributed target app, but distributing repackaged APKs, or shipping a tool whose primary purpose is tampering with third-party apps, is squarely the kind of thing those policies address. **I did not fetch and quote the exact policy clauses in this pass — treat the specific clause text as UNVERIFIED and read https://support.google.com/googleplay/android-developer/answer/9888379 and the Developer Program Policy Center before shipping.**
- **Interoperability defences** (DMCA §1201 reverse-engineering exceptions, Sega v. Accolade, Google v. Oracle) are the argument the Xposed/LSPosed ecosystem implicitly leans on for *modifying software you own for interoperability*. They are not a blanket permission to redistribute modified copies of someone else's app. Note also that the GPL-3.0 projects here are *not* making a legal claim — they simply ship under GPL-3.0.
- **VirtualApp is the clearest legal red flag in this space**: its README asserts proprietary ownership, requires a paid business license, and states the vendor has pursued criminal/civil action against unlicensed users. Avoid.
- **You must document** for your users: patched apps fail Play Integrity, lose self-update, may be detected as tampered, and **cannot be installed over the original** (§3.1). Also warn that patching an app you do not own may violate that app's ToS and/or local law.

---

## 4. Practical on-device viability (no PC)

**Verdict: fully viable. LSPatch's manager app proves it** — `manager/src/main/java/org.lsposed/lspatch/Patcher.kt` invokes the *same pure-Java patcher library* in-process on the device:

```kotlin
suspend fun patch(logger: Logger, options: Options) = withContext(Dispatchers.IO) {
    LSPatch(logger, *options.toStringArray()).doCommandLine()   // org.lsposed.patch.LSPatch
    ...
}
```
No subprocess, no `aapt2`, no `zipalign` binary, no `keytool`. The manager simply ships `org.lsposed.patch` + vendored apkzlib + ManifestEditor and runs them on ART.

### What runs on ART, measured

| Component | On Android? | Evidence |
|---|---|---|
| **apkzlib** (zip repack + v1/v2 sign from a `ZFile`) | ✅ | LSPatch's manager runs it on-device. Needs Guava (Android-safe). |
| **ManifestEditor** (binary AXML write) | ✅ | Vendored as pure Java source into LSPatch's `patch` module, which runs on-device. |
| **dexlib2 3.x** | ✅ | 988 classes, **zero** `java.nio.file`/`java.awt`/`javax.imageio`/`javax.xml`; needs `java.util.function`/`stream` ⇒ **API 24+** |
| **apksig 9.4.1** (v1/v2/v3/v4) | ✅ | 267 classes, zero JRE-only refs; **zero transitive deps**; needs API 24+ |
| **BouncyCastle `bcpkix-jdk15to18`** | ✅ (use this line) | pure Java; avoid the `jdk18on` line's JDK9+ assumptions on ART |
| **apktool-lib 3.0.3** | ❌ | 129 `java.nio.file`, 17 `javax.xml`, 6 `java.awt` (`ResNinePatchStreamDecoder`), 3 `javax.imageio`; shells out to aapt2 |
| **zipalign binary** | ⚠️ only if you ship per-ABI binaries | Xpatch did exactly this (`assets/zipalign_arm*`). Prefer apkzlib `realign()` + apksig `setLibraryPageAlignmentBytes`. |

### What MUST be native (C/C++)

Only the **runtime side**, and only for things Java cannot do:
1. **ART inline hooking** — the core of module injection. This is **LSPlant** (LGPL-3.0), which needs ART symbol resolution (`libart.so`) and trampoline management. LSPatch's `CMakeLists.txt` does `add_subdirectory(${CORE_ROOT} core)` and `target_link_libraries(lspatch core log)`; it also needs AOSP headers, vendored at `patch-loader/src/main/jni/include/art/runtime/{oat_file_manager.h,jit/profile_saver.h}` for `DisableBackgroundVerification`/`DisableInline`.
2. **`JNI_OnLoad` bootstrap** — create the `InMemoryDexClassLoader` and call the entry class before Java-level control exists.
3. **libc `__openat` hook** for sig-bypass level 2 (`SandHook::ElfImg("libc.so")` + `HookSymNoHandle`).
4. **`System.load` of the stub `.so` itself** — inherently native.

Nothing in the *patching* half must be native. **You do not need to write C++ for the repackaging pipeline**; you need a prebuilt LSPlant-based `.so` (or your own equivalent) for the runtime payload.

### Realistic pipeline, footprint, performance

```
 1. pick target APK (SAF / MediaStore)                                  [Java]
 2. open output ZFile with AlignmentRules (.so -> 16384, origin.apk -> 16384)
    addNestedZip("assets/<x>/origin.apk", srcApk)                       [apkzlib, streaming]
 3. register SigningExtension (v1 off, v2 on, minSdkVersion 28)          [apkzlib]
    -- or: write unsigned with apkzlib, sign with apksig for v2+v3 --    [apksig]
 4. read original AndroidManifest.xml from nested zip (a few KB)         [AxmlParser]
    capture originalSignature (APK Signing Block parse) + original
    appComponentFactory + minSdkVersion/targetSdkVersion
 5. ManifestEditor: set appComponentFactory=stub,
    add <meta-data name="..."> = base64(config),
    add uses-permission (SYSTEM_ALERT_WINDOW for a floating window),
    optionally debuggable / extractNativeLibs=false                     [ManifestEditor]
    -> dstZFile.add("AndroidManifest.xml", bytes)
 6. dstZFile.add("classes.dex", stubDex)        // assembler-made stub
    dstZFile.add("assets/<x>/loader.dex", payloadDex)
    dstZFile.add("assets/<x>/config.json", json)
    dstZFile.add("assets/<x>/modules/<pkg>.apk", moduleApk)   // optional
    for abi in [arm64-v8a, armeabi-v7a, x86, x86_64]:
        dstZFile.add("assets/<x>/so/$abi/libpayload.so", is, /*compress*/false)
 7. for each original entry except classes*.dex, AndroidManifest.xml,
    META-INF/*.{SF,MF,RSA}:  srcZFile.addFileLink(name, name)   // no copying
 8. dstZFile.realign();  [close -> written + signed]
 9. install via PackageInstaller.Session (+Shizuku if available)
```

**Footprint / performance notes:**
- **Disk:** the dominant cost is the nested original APK (≈ the source size) plus your payload. With apkzlib **zip links**, the extracted/unlinked payload is *not* duplicated — the output APK is roughly `source size + payload size + central directory`. Budget ~2.2× the source APK transiently (output + the original already on disk).
- **RAM:** apkzlib streams, so peak RSS is bounded by the deflate buffers and `ZFileOptions`' byte-storage policy, not by APK size. **But** two places are memory-hungry and must be tuned: (a) `LSPatch` reads the *manifest only* (tiny) — good; (b) the **runtime** side holds the loader dex as a Java `byte[]` and module dexes as `SharedMemory` — LSPatch's `ModuleLoader` deliberately uses `SharedMemory` + `PROT_READ` instead of heap arrays precisely to avoid duplicating module dexes on the Java heap. Copy that.
- **CPU:** deflate dominates. Use `ZFileOptions`' compressor policy (apkzlib `BestAndDefaultDeflateExecutorCompressor`) and, if you control signing, parallelise digests with apksig's `setExecutor(RunnablesExecutor)`.
- **Time:** no published numbers verified. Expect tens of seconds for a 100 MB APK on a mid-range 2026 device, dominated by deflate + signature digest computation. **UNVERIFIED benchmark — measure it.**
- **Thermals/foreground:** run it in a foreground service or WorkManager with a progress notification; LSPatch runs it on `Dispatchers.IO` inside a Compose UI and warns the user.
- **SAF gotcha (real, from LSPatch's code):** the output must be streamed to a `DocumentFile` obtained from `ACTION_OPEN_DOCUMENT_TREE`, and LSPatch deletes pre-existing `*-lspatched.apk` siblings before writing, because SAF will not overwrite in place.

---

## 5. RECOMMENDATION — a concrete, buildable on-device pipeline

### 5.1 Architecture: copy LSPatch's *shape*, not its *code* (license!) and fix its two dated bugs

Steal the design (manifest `appComponentFactory` stub → `classes.dex` replaced by a tiny stub → original APK nested in `assets/` → original dexes never touched → zip links → repack+sign in one streamed pass). Do **not** copy GPL-3.0 sources. Build on the permissive stack. And fix:

1. **Align to 16384, not 4096.** LSPatch (2023) hardcodes `constantForSuffix(".so", 4096)`. On Android 15+ 16 KB devices that will fail to mmap native libs. Use `16384` for `.so` and stored entries, and `ApkSigner.Builder.setLibraryPageAlignmentBytes(16384)`.
2. **Don't raise only `minSdkVersion`; also *check* `targetSdkVersion`.** Detect `targetSdkVersion < 24` (Android 15+ install floor) and warn / offer `--bypass-low-target-sdk-block`, because silently raising it can change target behaviour.

### 5.2 Gradle coordinates (exact; all licenses permissive)

```kotlin
repositories {
    google()                                   // apksig, apkzlib, AGP
    mavenCentral()                             // commons-io, gson, guava, bouncycastle
    maven("https://jitpack.io")                // smali 3.x + ManifestEditor (NOT on Central!)
}

android {
    compileSdk = 36
    defaultConfig {
        minSdk = 28                            // appComponentFactory needs 28; apksig/dexlib2 need 24
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    androidResources { noCompress.add(".so") } // so the manager's own .so assets are mmappable
}

dependencies {
    // ---- repackaging ----
    implementation("com.android.tools.build:apkzlib:9.4.1")        // Apache-2.0  (Google Maven)
    implementation("com.android.tools.build:apksig:9.4.1")         // Apache-2.0  (Google Maven) ZERO deps
    implementation("com.google.guava:guava:33.6.0-android")        // Apache-2.0  (apkzlib needs it; use -android on ART)
    // ---- dex ----
    implementation("com.github.iBotPeaches.smali:smali-dexlib2:b6365a84f4")  // BSD-3-Clause  com.android.tools.smali.dexlib2.*
    implementation("com.github.iBotPeaches.smali:smali:b6365a84f4")          // BSD-3-Clause  assembler
    // ---- binary AndroidManifest.xml ----
    implementation("com.github.WindySha:ManifestEditor:v2.0")      // Apache-2.0
    // ---- keystore gen ----
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")       // Bouncy Castle Licence (MIT-style)
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    // ---- misc ----
    implementation("commons-io:commons-io:2.22.0")                 // Apache-2.0
    implementation("com.google.code.gson:gson:2.13.1")             // Apache-2.0   (VERIFY version if you bump)
    // optional, for silent install:
    implementation("dev.rikka.shizuku:api:13.1.2")
    implementation("dev.rikka.shizuku:provider:13.1.2")
    // ---- runtime payload (your own .so built with the NDK) ----
    // no Gradle dep; ship libpayload.so per ABI, built against LSPlant (LGPL-3.0, dynamic link only)
}
```

**License flags:** everything above is permissive. **Do not** add `LSPatch`/`NPatch`/`Vector` (GPL-3.0) or `VirtualApp` (proprietary) as dependencies. `LSPlant` is **LGPL-3.0** — if you use it, link it as a separate `.so`, publish your LSPlant modifications, and keep your own code out of the LGPL surface. If LGPL is unacceptable, you must write your own ART hooking layer, which is a large, version-fragile project.

### 5.3 Pipeline stages with the exact APIs

**Stage 0 — keystore, generated once and persisted** (do this first; losing it strands every patched app).

```kotlin
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date

private const val PW = "123456"; private const val ALIAS = "key0"   // matches LSPatch's expectations

fun createOrLoadKeyStore(file: java.io.File): KeyStore {
    val ks = KeyStore.getInstance("PKCS12")            // LSPatch uses BKS for custom stores; PKCS12 is fine & standard
    if (file.exists()) { file.inputStream().use { ks.load(it, PW.toCharArray()) }; return ks }
    ks.load(null, PW.toCharArray())

    val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
    val kp = kpg.generateKeyPair()
    val now = System.currentTimeMillis()
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
    val cert = JcaX509CertificateConverter().getCertificate(
        JcaX509v3CertificateBuilder(
            X500Name("CN=RegTrail Payload, O=RegTrail, C=US"),        // NB: real issuer/subject names differ
            BigInteger(64, SecureRandom()),
            Date(now - 86_400_000L),                                 // notBefore: yesterday (clock skew)
            Date(now + 30L * 365 * 86_400_000L),                     // notAfter: ~30y
            X500Name("CN=RegTrail Payload, O=RegTrail, C=US"),
            kp.public
        ).build())
    ks.setKeyEntry(ALIAS, kp.private, PW.toCharArray(), arrayOf(cert))
    file.outputStream().use { ks.store(it, PW.toCharArray()) }
    return ks
}
```
Long validity matters: a self-signed cert that expires makes future updates of already-patched apps fail. (Add a `BasicConstraints(false)`/`KeyUsage(digitalSignature)` extension if you want to be strict — omitted here for brevity.)

**Stage 1 — open the output as a `ZFile`, nest the original, plan alignment.**

```kotlin
val Z_OPTS = ZFileOptions().setAlignmentRule(
    AlignmentRules.compose(
        AlignmentRules.constantForSuffix(".so", 16384),            // 16 KB, NOT LSPatch's 4096
        AlignmentRules.constantForSuffix(ORIGIN_ASSET, 16384)))

ZFile.openReadWrite(outApk, Z_OPTS).use { dst ->
    dst.addNestedZip({ ORIGIN_ASSET }, srcApk, false).use { src ->
        /* stages 2–6 */
        dst.realign()                                              // stages 5–6 already wrote new entries
    }
}
```

**Stage 2 — read the facts you need from the original, before rewriting.**

```kotlin
val manifestEntry = src.get("AndroidManifest.xml") ?: error("not an APK")
val (origAcf, minSdk, targetSdk, pkg) = parseManifest(manifestEntry.open())   // AxmlParser / ManifestParser
val originalSigHex = ApkSignatureHelper.getApkSignInfo(srcApk.path)          // v2 block parse, hex cert
require(targetSdk >= 24) { "targetSdk=$targetSdk < 24 cannot install on Android 15+" }
```

**Stage 3 — rewrite the manifest with ManifestEditor.** (Verified property API; `Boolean` arg ⇒ `TYPE_INT_BOOLEAN`.)

```kotlin
val cfg = gson.toJson(PatchConfig(origAcf, originalSigHex, mode, /*...*/))
val prop = ModificationProperty()
    .addApplicationAttribute(AttributeItem("appComponentFactory", STUB_ACF))          // String -> TYPE_STRING
    .addMetaData(ModificationProperty.MetaData(META_KEY, Base64.encodeToString(cfg.toByteArray(), NO_WRAP)))
    .addUsesPermission("android.permission.SYSTEM_ALERT_WINDOW")                     // floating window
    .addUsesSdkAttribute(AttributeItem(NodeValue.UsesSDK.MIN_SDK_VERSION, "28"))
    .addApplicationAttribute(AttributeItem(NodeValue.Application.EXTRACT_NATIVE_LIBS, false))  // Boolean!
val out = ByteArrayOutputStream()
ManifestEditor(manifestEntry.open(), out, prop).processManifest()
dst.add("AndroidManifest.xml", ByteArrayInputStream(out.toByteArray()))
```

**Stage 4 — assemble the stub and write all injected entries.** Prefer writing the stub in **smali** and assembling it (far more maintainable than hand-building dexlib2 instruction objects):

```kotlin
// dex from smali text (verified API: Smali.assemble(SmaliOptions, List<String>))
val opts = SmaliOptions().apply { outputDexFile = stubDexFile.path; apiLevel = 28 }
check(Smali.assemble(opts, listOf(stubSmaliFile.path)))

dst.add("classes.dex", stubDexFile.inputStream())                        // the ONLY dex at the root
dst.add("assets/payload/loader.dex", payloadDex.inputStream())
dst.add("assets/payload/config.json", ByteArrayInputStream(cfg.toByteArray()))
dst.add("assets/payload/modules/$modulePkg.apk", moduleApk.inputStream())
for (abi in ABIS)                                                        // uncompressed: required for System.load
    dst.add("assets/payload/so/$abi/libpayload.so", soFor(abi).inputStream(), false)
```

**If you genuinely need programmatic class creation/merging** (e.g. adding a class into an *existing* dex), dexlib2 exposes both a low-level builder and a whole-dex writer. Verified names for 3.x (`com.android.tools.smali.dexlib2`; swap to `org.jf.dexlib2` for 2.5.2):

```java
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;

// (a) read + merge classes from several dexes (multidex-aware)
var container = DexFileFactory.loadDexContainer(apkFile, Opcodes.getDefault());
var merged = new ArrayList<ClassDef>();
for (String name : container.getDexEntryNames())          // classes.dex, classes2.dex, ...
    merged.addAll(container.getEntry(name).getDexFile().getClasses());

// (b) add one class definition (build it with DexBuilder for real instruction bodies)
var builder = new com.android.tools.smali.dexlib2.writer.builder.DexBuilder(Opcodes.getDefault());
builder.internClassDef("Lcom/example/Injected;", /*accessFlags*/ 0x1, "Ljava/lang/Object;", null, null,
                       null, null, null, null);           // see internClassDef's full parameter list in source

// (c) write a standalone dex (verified static methods)
DexPool.writeTo("/tmp/merged.dex", new ImmutableDexFile(Opcodes.getDefault(), merged));
// or: DexFileFactory.writeDexFile("/tmp/merged.dex", new ImmutableDexFile(Opcodes.getDefault(), merged));
```
> **Note:** `internClassDef` / `internMethod` signatures are long and version-sensitive — I verified they *exist* with those names in 3.0.10 source but did **not** compile a full builder example. For anything beyond trivial classes, **generate smali text and use `Smali.assemble`**; that path is stable, readable, and is what apktool itself relies on. And remember the LSPatch insight: **you usually don't need to touch the app's dexes at all.**

**Stage 5 — link the untouched originals (this is the performance win).**

```kotlin
for (e in src.entries()) {
    val n = e.centralDirectoryHeader.name
    if (n.startsWith("classes") && n.endsWith(".dex")) continue           // real dexes stay inside origin.apk
    if (n == "AndroidManifest.xml") continue
    if (n.startsWith("META-INF") && (n.endsWith(".SF")||n.endsWith(".MF")||n.endsWith(".RSA"))) continue
    if (dst.get(n) != null) continue
    src.addFileLink(n, n)                                                 // zero-copy
}
```

**Stage 6 — sign. Two options; I recommend (B).**

*(A) apkzlib, in-stream — what LSPatch does; v1/v2 only.*
```kotlin
SigningExtension(SigningOptions.builder()
    .setMinSdkVersion(28).setV1SigningEnabled(false).setV2SigningEnabled(true)
    .setCertificates(*entry.certificateChain as Array<X509Certificate>)
    .setKey(entry.privateKey).build()).register(dst)
```
`SigningOptions` has **no v3 support** (verified: only `setV1SigningEnabled`/`setV2SigningEnabled`) — that is the price of streaming.

*(B) apkzlib writes unsigned, then apksig signs v2+v3 with 16 KB alignment — recommended.*
```kotlin
// after dst.close(): outUnsigned exists
val signerCfg = ApkSigner.SignerConfig.Builder(
        "CERT", entry.privateKey, entry.certificateChain.map { it as X509Certificate }).build()

ApkSigner.Builder(listOf(signerCfg))
    .setInputApk(outUnsigned)
    .setOutputApk(outSigned)
    .setMinSdkVersion(28)                      // 28 so v3 is used, v1 skipped
    .setV1SigningEnabled(false)                // v1 unnecessary for API 28+; keep false unless minSdk<24
    .setV2SigningEnabled(true)                 // Android 7–8
    .setV3SigningEnabled(true)                 // Android 9+, carries rotation lineage
    .setV4SigningEnabled(false)                // no .idsig needed for plain install
    .setLibraryPageAlignmentBytes(16384)       // <-- the 16 KB requirement
    .setAlignmentPreserved(true)               // keep apkzlib's alignment
    .build()
    .sign()
```
Trade-off: (B) costs one extra full write/copy of the APK but gives **v3 signing**, **16 KB page alignment**, and **signing-certificate rotation** support — all of which matter for a 2026 product. If you need true single-pass streaming (very large APKs, low storage), use (A) and accept v2-only + do the alignment yourself in `ZFileOptions` (as shown). Do **not** shell out to `zipalign` (Xpatch's 2019 approach) and do **not** ship per-ABI `zipalign` binaries.

**Stage 7 — install.**
```kotlin
if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
    // silent: pm install via Shizuku (or PackageInstaller.Session through the Shizuku binder)
} else {
    // needs REQUEST_INSTALL_PACKAGES + user-granted "install unknown apps"
    if (!packageManager.canRequestPackageInstalls())
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, appUri))
    // then PackageInstaller.Session -> commit(IntentSender)  (user sees one confirm dialog)
}
```
Warn the user **before** patching that the original app must be uninstalled (new signature ⇒ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and that patched apps fail Play Integrity and lose self-update.

### 5.4 Runtime payload — what the injected stub must do (mirroring LSPatch, minus GPL code)

```
classes.dex  = your StubACF (AppComponentFactory)                     [assembled smali]
   static { read assets/payload/config.json; VMRuntime.vmInstructionSet() -> abi;
            System.load(<apk>!/assets/payload/so/<abi>/libpayload.so); }   // your own C++, or LSPlant-linked
JNI_OnLoad -> your native bootstrap:
   - read the loader dex bytes handed over from Java
   - new InMemoryDexClassLoader(ByteBuffer, stubClassLoader)
   - Class.forName("com.example.payload.Entry", true, thatLoader).onLoad()
Entry.onLoad():
   - show the floating window (needs SYSTEM_ALERT_WINDOW / Settings.canDrawOverlays())
   - optionally: extract assets/payload/origin.apk to cache, repoint ApplicationInfo.sourceDir,
     rebuild LoadedApk via ActivityThread.getPackageInfoNoCheck, swap AppBindData.info,
     fix ActivityRecord packageInfo — i.e. LSPatch's LSPApplication.onLoad, reimplemented
   - install signature-reporting bypass for the classes of check you care about
```
The floating window itself is ordinary Android: `WindowManager.addView(view, LayoutParams(TYPE_APPLICATION_OVERLAY, ...))` from a `Service` (API 26+ requires `TYPE_APPLICATION_OVERLAY`, not `TYPE_PHONE`), with `SYSTEM_ALERT_WINDOW` declared **and** granted via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. If you can accept confinement to your own process, you can avoid the special permission by adding the view to the app's own window — but a *global* floating window needs the overlay permission.

---

## 6. Sources

**Reference implementations**
- https://github.com/LSPosed/LSPatch · https://github.com/LSPosed/LSPatch/releases · raw sources at `https://raw.githubusercontent.com/LSPosed/LSPatch/master/<path>` (read locally from the `master` tarball)
- https://github.com/JingMatrix/LSPatch · https://github.com/JingMatrix/LSPatch/releases
- https://github.com/7723mod/NPatch · https://npatch.nkbe.top/en/
- https://github.com/WindySha/Xpatch · https://github.com/WindySha/ManifestEditor
- https://github.com/JingMatrix/Vector · https://github.com/HSSkyBoy/Vector · https://github.com/LSPosed/LSPlant · https://github.com/JingMatrix/LSPlant
- https://github.com/asLody/VirtualApp · https://github.com/android-hacker/VirtualXposed · https://github.com/ganyao114/SandHook · https://github.com/asLody/whale
- https://github.com/Sable/axml

**Toolchain**
- https://github.com/iBotPeaches/Apktool · https://repo1.maven.org/maven2/org/apktool/apktool-lib/maven-metadata.xml · .../apktool-lib/3.0.3/apktool-lib-3.0.3.pom · .../brut.j.common/maven-metadata.xml · .../brut.j.dir/maven-metadata.xml
- https://github.com/google/smali · https://repo1.maven.org/maven2/org/smali/ · https://jitpack.io/api/builds/com.github.iBotPeaches/smali · https://jitpack.io/com/github/iBotPeaches/smali/b6365a84f4/build.log
- https://jitpack.io/api/builds/com.github.WindySha/ManifestEditor
- https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/maven-metadata.xml · .../apksig/9.4.1/apksig-9.4.1.pom · .../apkzlib/maven-metadata.xml
- https://repo1.maven.org/maven2/com/android/tools/build/apksig/maven-metadata.xml (the 2.3.0 trap) · https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk18on/maven-metadata.xml · .../bcpkix-jdk15to18/maven-metadata.xml
- https://android.googlesource.com/platform/tools/apksig/ (+ `/+/refs/heads/main/src/main/java/com/android/apksig/ApkSigner.java?format=TEXT`)
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java?format=TEXT
- https://android.googlesource.com/platform/tools/apkzlib

**Official Android docs**
- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [Android 14 behavior changes (all apps)](https://developer.android.com/about/versions/14/behavior-changes-all) · [Android 15](https://developer.android.com/about/versions/15/behavior-changes-all) · [Android 16](https://developer.android.com/about/versions/16/behavior-changes-all)
- [apksigner](https://developer.android.com/tools/apksigner) · [APK signature scheme](https://source.android.com/docs/security/features/apksigning)
- [Play Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts) · [Play Integrity overview](https://developer.android.com/google/play/integrity/overview) · [SafetyNet Attestation deprecation timeline](https://developer.android.com/privacy-and-security/safetynet/deprecation-timeline)
- [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager) · [PackageInfo.signingInfo](https://developer.android.com/reference/android/content/pm/PackageInfo#signingInfo) · [Manifest.permission.REQUEST_INSTALL_PACKAGES](https://developer.android.com/reference/android/Manifest.permission#REQUEST_INSTALL_PACKAGES)
- [JcaX509v3CertificateBuilder javadoc](https://downloads.bouncycastle.org/lts-java/docs/bcpkix-lts8on-2.73.5-javadoc/org/bouncycastle/cert/jcajce/JcaX509v3CertificateBuilder.html)

## 7. Open items / UNVERIFIED

1. `com.github.iyxan23:apkparser` — could not confirm it exists on Maven Central or JitPack. **Do not depend on it.**
2. `REAndroid/APKEditor` — not investigated; it may be a useful alternative manifest/ARSC editing core. **Follow up.**
3. `ApkMod` / `fuckapk` — no canonical maintained repository located; treat as folklore.
4. Exact Google Play Developer Policy clause text on modifying third-party apps was not fetched. **Read before shipping.**
5. SafetyNet Attestation's precise shutdown dates rendered only as i18n links when scraped; the deprecation page exists, but confirm dates by reading it in a browser.
6. `SandVXposed` activity/state in 2024–2026 not verified.
7. `SmaliOptions` field names (`outputDexFile`, `apiLevel`) were **not** verified field-by-field — check `com.android.tools.smali.smali.SmaliOptions` in the jar you resolve before compiling.
8. No end-to-end benchmark of the pipeline on real hardware was run; the performance figures in §4 are reasoned, not measured.
