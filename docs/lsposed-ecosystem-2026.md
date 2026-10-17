# LSPosed / Xposed Module Ecosystem — State of the World, 2026-10-17

> ## ⚠️ CORRECTION NOTICE (added after this report was written)
>
> **Two claims below are WRONG and were disproved by inspecting the official release artifact
> `LSPosed-v2.2.1-7912-release.zip` (2026-10-17, SHA-256 `ED37DE2D…11E74`):**
>
> 1. **"upstream `LSPosed/LSPosed` is abandoned" — FALSE.** LSPosed is **actively maintained**:
>    it is at **v2.2.1 (7912)** and its own `module.prop` states *"Supports Android 9 ~ 17.
>    Requires Zygisk enabled."* What misled this report is that **distribution moved off GitHub
>    releases** to Telegram + `https://lsposed.zip/update.json`, so `releases/latest` and
>    `master` both look frozen. Correct method: check the published module package, not the repo.
> 2. **"Manager package renamed to `org.matrix.vector.manager`" — FALSE.** Parsing the real
>    `manager.apk` binary `AndroidManifest.xml` gives **`package = org.lsposed.manager`**
>    (`versionName 2.2.1`, `versionCode 0x1ee8 = 7912`). `org.matrix.vector.*` is a *different
>    fork*, not the official build. Any code using `org.matrix.vector.manager` will fail on the
>    official framework.
>
> Claims that **remain valid** and were re-confirmed against the artifact: the `libxposed` GitHub
> org suspension (the framework README itself now tells users to fetch docs/sources from Maven
> Central); the modern module contract (`META-INF/xposed/java_init.list` + `module.prop`);
> API 100 being refused; API classes being `compileOnly`; the module template being gone; and the
> table of runtime hooking engines. `framework.dex` in the release confirms the modern API surface
> (`io/github/libxposed/api/XposedModule`, `XposedInterface$Chain|$Hooker|$HookBuilder|$HookHandle`)
> **and** the legacy surface (`de/robv/android/xposed/IXposedHookLoadPackage` etc.) coexist.
>
> **Where this report and [PLAN.md](PLAN.md) disagree, trust PLAN.md §2.2** — it is sourced from the
> actual release package. This document is kept unedited below (minus this notice) so the reasoning
> trail is auditable.

Research date: **2026-10-17** (UTC 2026-10-16). Every claim below was verified against primary
sources (GitHub API, `raw.githubusercontent.com`, Maven Central, cloned source trees, PyPI,
frida.re, developer.android.com) on that date. Method notes at the end.

> **The single most important finding:** upstream `LSPosed/LSPosed` is **abandoned** (last release
> 2023-10-11, `master` frozen 2024-01-07, README caps at Android 14). The live framework is
> **`JingMatrix/Vector`** (the repo formerly at `JingMatrix/LSPosed`, renamed at v2.0). Separately,
> the **entire `libxposed` GitHub org was suspended by GitHub**, so `libxposed/api`,
> `libxposed/service`, `libxposed/example` and `libxposed/helper` are all dead URLs — only the
> Maven Central artifacts and JingMatrix's mirrors survive.

---

## 1. Current releases and which fork to target

### 1.1 `LSPosed/LSPosed` (upstream) — DEAD

| tag | published | notes |
| :-- | :-- | :-- |
| v1.9.2 | 2023-10-11T15:11:13Z | **latest release** |
| v1.9.1 | 2023-08-30 | |
| v1.9.0 | 2023-08-29 | |
| v1.8.6 | 2023-01-16 | |
| v1.8.5 | 2022-11-11 | |
| v1.8.4 | 2022-09-14 | |
| v1.8.3 | 2022-05-07 | |
| v1.8.2 | 2022-04-17 | |
| v1.8.1 | 2022-04-09 | |
| v1.8.0 | 2022-03-23 | |

Source: `https://api.github.com/repos/LSPosed/LSPosed/releases`

- `master` HEAD = `df74d83eb03a44cc6ad268841ac2ada28d077c77`, committed **2024-01-07T13:23:32+08:00**
  ("set mount ns propagation to slave"). Verified by shallow clone + `git log -1`.
- README "Supported Versions": **Android 8.1 ~ 14**.
- Repo metadata reports `pushed_at = 2026-10-14T04:37:33Z`, but that is **not** work on `master` —
  `git ls-remote --heads` still shows `refs/heads/master = df74d83e`. Treat the project as frozen.
- **Verdict: do not target. No Android 15/16/17 support.**

### 1.2 `JingMatrix/LSPosed` → **`JingMatrix/Vector`** — THE LIVE FORK

`JingMatrix/LSPosed` now redirects to `JingMatrix/Vector` ("Modern Xposed Framework", 12 786 stars,
default branch `master`). Renamed at v2.0.

| tag | published | name |
| :-- | :-- | :-- |
| canary-3112 | 2026-10-01T12:54:59Z | Vector v2.2 canary 3112 (prerelease) |
| canary-3111 | 2026-09-07T06:18:00Z | Vector v2.2 canary 3111 (prerelease) |
| canary-3110 | 2026-09-02T21:26:39Z | Vector v2.2 canary 3110 (prerelease) |
| canary-3109 | 2026-09-02T21:04:32Z | Vector v2.2 canary 3109 (prerelease) |
| canary-3108 | 2026-09-01T11:48:22Z | Vector v2.2 canary 3108 (prerelease) |
| **v2.2** | **2026-08-04T09:38:29Z** | **Vector v2.2 — current stable** |
| v2.1 | 2026-08-02T06:02:00Z | Vector v2.1 (first libxposed API 101 release) |
| v2.0 | 2026-03-22T19:28:48Z | Vector v2.0 (rename; definitive API 100 release) |
| v1.11.0 | 2026-01-31T22:54:20Z | LSPosed v1.11.0 |
| v1.10.2 | 2025-07-12T09:43:55Z | LSPosed v1.10.2 |

Sources: `https://api.github.com/repos/JingMatrix/LSPosed/releases`,
`git ls-remote --tags https://github.com/JingMatrix/Vector.git`

- `master` HEAD = `ddeed8ca1ffedfaba7f4341b911089906862d5c4` (2026-10-01) = tag `canary-3112`.
- **Compatibility (README): "Vector supports devices running Android 8.1 through Android 17 Beta."**
  Requires Magisk or KernelSU with Zygisk (e.g. `JingMatrix/NeoZygisk`).
- Upstream API 100 was **dropped** (see §3.2).
- Release assets: `Vector-v2.2-3080-Release.zip` (9.3 MB), `Vector-v2.2-3080-Debug.zip` (36.9 MB).

### 1.3 The `libxposed` org suspension (verified)

Vector HEAD commit `ddeed8ca`, PR #989, 2026-10-01, message verbatim:

> **Update dependencies, mirror libxposed after upstream was suspended (#989)**
> `github.com/libxposed` (api and service) was suspended by GitHub, so the submodule URLs and the
> two doc links that referenced it are dead. Mirrored both repos' full history to
> `JingMatrix/libxposed-api` and `JingMatrix/libxposed-service` and retargeted the references here.

Independently confirmed: `GET /repos/libxposed/api` → **404**; `git ls-remote` on
`libxposed/api`, `libxposed/service`, `libxposed/example`, `libxposed/helper` → **all fail (exit 128)**.

**Consequence:** any tutorial telling you to add `github.com/libxposed/...` or to fetch the
`libxposed/helper` development kit is pointing at dead links. `io.github.libxposed:helper` is
**404 on Maven Central** — the "friendly development kit" promised by the wiki was never published.

### 1.4 Other maintained pieces

| project | status | last activity |
| :-- | :-- | :-- |
| `JingMatrix/Vector` (framework) | **active** | 2026-10-12 (repo), tags to 2026-10-01 |
| `JingMatrix/libxposed-api` | **active mirror** | 2026-08-01 (`39cac084`, API 102) |
| `JingMatrix/libxposed-service` | **active mirror** | 2026-06-13 (`33189408`, API 102 RFC) |
| `JingMatrix/LSPlant` / `LSPosed/LSPlant` | **active** | 2026-10-01 / 2026-10-06 |
| `JingMatrix/LSPatch` (rootless) | **active** | 2026-10-07 |
| `LSPosed/LSPatch` | **archived** | 2023-12-13 |
| `LSPosed/SandHook` | **archived** | 2021-05-03 |
| `LSPosed/LSPosed-Module-Template` | **GONE (404)** | — |

### 1.5 Answer: what should a NEW module target in 2026?

**Target `JingMatrix/Vector` v2.2 (stable) / canary-3112, and use the modern libxposed API 102**
via Maven Central `io.github.libxposed:api:102.0.0`. Ship a **legacy-API fallback** only if you must
support other/older Xposed forks (see §6 for the dual-path recipe). Do **not** target
`LSPosed/LSPosed`; it cannot run on Android 15+.

---

## 2. Modern Xposed module API surface

### 2.1 Legacy API — `de.robv.android.xposed` (still fully supported by Vector)

Authoritative source: `Vector/legacy/src/main/java/de/robv/android/xposed/*.java`
(read from the clone at commit `ddeed8ca`).

#### Hook interfaces

```java
package de.robv.android.xposed;

public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}

public interface IXposedHookZygoteInit extends IXposedMod {
    void initZygote(StartupParam startupParam) throws Throwable;

    final class StartupParam {
        public String modulePath;          // path to the module's APK
        public boolean startsSystemServer; // true on 32-bit ROMs; on 64-bit only for the
                                           // primary process that starts system_server
    }
}

public interface IXposedHookInitPackageResources extends IXposedMod {
    void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam)
            throws Throwable;
}
```

`XC_LoadPackage.LoadPackageParam` fields (mapped by `LegacyDelegateImpl.onPackageLoaded`):
`packageName`, `processName`, `classLoader`, `appInfo`, `isFirstApplication`.

#### `XposedBridge` — exact current signatures

```java
public final class XposedBridge {
    public static final ClassLoader BOOTCLASSLOADER = XposedBridge.class.getClassLoader();
    public static final String TAG = "VectorLegacyBridge";        // note: rebranded
    @Deprecated public static int XPOSED_BRIDGE_VERSION;          // declared, never assigned (== 0)

    public static final CopyOnWriteArraySet<XC_LoadPackage> sLoadedPackageCallbacks;

    public static int getXposedVersion();                         // returns XposedInterface.LIB_API == 102
    public static synchronized void log(String text);             // -> Log.i(TAG, text)
    public static synchronized void log(Throwable t);
    public static void deoptimizeMethod(Member deoptimizedMethod);

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback);
    public static void unhookMethod(Member hookMethod, XC_MethodHook callback);   // NOTE: void, not boolean
    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback);
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback);

    public static void hookLoadPackage(XC_LoadPackage callback);
    public static void hookInitPackageResources(XC_InitPackageResources callback);

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable;                                       // delegates to VectorInvocation
    public static void initXResources();
    public static volatile ClassLoader dummyClassLoader;
}
```

Two behaviours worth knowing:

- **`getXposedVersion()` returns 102** on Vector 2.2 (it returns `XposedInterface.LIB_API`), so a
  legacy module's `if (XposedBridge.getXposedVersion() >= 93)` gate passes.
- `XPOSED_BRIDGE_VERSION` is `@Deprecated` and is **never assigned** anywhere in Vector — it reads 0.
  Do not use it.

#### `XC_MethodHook` / `MethodHookParam`

```java
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook();                    // default priority
    public XC_MethodHook(int priority);

    protected void beforeHookedMethod(MethodHookParam<?> param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam<?> param)  throws Throwable {}

    public static final class MethodHookParam<T extends Executable> extends XCallback.Param {
        public Member   method;        // the hooked method/constructor
        public Object   thisObject;    // null for static methods
        public Object[] args;          // MUTABLE in place
        public Object   result   = null;
        public Throwable throwable = null;
        public boolean  returnEarly = false;

        public Object  getResult();
        public void    setResult(Object result);        // also clears throwable, sets returnEarly
        public Throwable getThrowable();
        public boolean hasThrowable();                  // Vector/LSPosed extension
        public void    setThrowable(Throwable t);       // also clears result, sets returnEarly
        public Object  getResultOrThrowable() throws Throwable;
    }

    public class Unhook implements IXUnhook<XC_MethodHook> {
        public Member getHookedMethod();
        public XC_MethodHook getCallback();
        public void unhook();
    }
}
```

> Difference from OG Xposed: `MethodHookParam` is **generic** (`<T extends Executable> extends
> XCallback.Param`), and `hasThrowable()` / `getThrowable()` / `getResultOrThrowable()` are
> additions. Code written against `api.xposed.info` that ignores the type parameter still compiles.

Also present: `XC_MethodReplacement` (for `replaceHookedMethod`), `XSharedPreferences`,
`android.content.res.XResources` / `XTypedArray`, `SELinuxHelper`, `XposedHelpers`.

#### `XposedHelpers` — key method signatures

Class lookups:

```java
public static Class<?> findClass(String className, ClassLoader classLoader);
public static Class<?> findClassIfExists(String className, ClassLoader classLoader);
public static final class ClassNotFoundError extends Error {}   // NOT ClassNotFoundException
```

Fields (`get`/`set`, instance and static, all primitives + `Object`):

```java
public static Field findField(Class<?> clazz, String fieldName);
public static Field findFieldIfExists(Class<?> clazz, String fieldName);
public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type);

public static void   setObjectField(Object obj, String fieldName, Object value);
public static void   setIntField(Object obj, String fieldName, int value);
public static void   setBooleanField / setByteField / setCharField / setDoubleField
                   / setFloatField / setLongField / setShortField (Object, String, <prim>);
public static Object getObjectField(Object obj, String fieldName);
public static int    getIntField(Object obj, String fieldName);   // + all other primitives
public static Object getSurroundingThis(Object obj);

public static void   setStaticObjectField(Class<?> clazz, String fieldName, Object value);
public static void   setStaticIntField / ... (Class<?> clazz, String fieldName, <prim>);
public static Object getStaticObjectField(Class<?> clazz, String fieldName);
public static int    getStaticIntField(Class<?> clazz, String fieldName);  // + all primitives
```

Methods and constructors:

```java
public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes);
public static Method findMethodExact(String className, ClassLoader cl, String methodName, Object... parameterTypes);
public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes);
public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes);
public static Method findMethodExactIfExists(String className, ClassLoader cl, String methodName, Object... parameterTypes);
public static Method[] findMethodsByExactParameters(Class<?> clazz, Class<?> returnType, Class<?>... parameterTypes);
public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>... parameterTypes);
public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args);
public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>[] parameterTypes, Object[] args);

public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes);
public static Constructor<?> findConstructorExact(String className, ClassLoader cl, Object... parameterTypes);
public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes);
public static Constructor<?> findConstructorExactIfExists(Class<?> clazz, Object... parameterTypes);
public static Constructor<?> findConstructorExactIfExists(String className, ClassLoader cl, Object... parameterTypes);
public static Constructor<?> findConstructorBestMatch(Class<?> clazz, Class<?>... parameterTypes);
public static Constructor<?> findConstructorBestMatch(Class<?> clazz, Object... args);
public static Constructor<?> findConstructorBestMatch(Class<?> clazz, Class<?>[] parameterTypes, Object[] args);

public static Class<?>[] getParameterTypes(Object... args);
public static Class<?>[] getClassesAsArray(Class<?>... clazzes);
public static int getFirstParameterIndexByType(Member method, Class<?> type);
public static int getParameterIndexByType(Member method, Class<?> type);
```

Invocation:

```java
public static Object callMethod(Object obj, String methodName, Object... args);
public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args);
public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args);
public static Object callStaticMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes, Object... args);
public static Object newInstance(Class<?> clazz, Object... args);
public static Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args);

public static final class InvocationTargetError extends Error {}   // wraps the real cause
```

Hook sugar:

```java
public static XC_MethodHook.Unhook findAndHookMethod(
        Class<?> clazz, String methodName, Object... parameterTypesAndCallback);
public static XC_MethodHook.Unhook findAndHookMethod(
        String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback);
public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback);
public static XC_MethodHook.Unhook findAndHookConstructor(
        String className, ClassLoader classLoader, Object... parameterTypesAndCallback);
```

Extra state, assets, utils:

```java
public static Object setAdditionalInstanceField(Object obj, String key, Object value);
public static Object getAdditionalInstanceField(Object obj, String key);
public static Object removeAdditionalInstanceField(Object obj, String key);
public static Object setAdditionalStaticField(Object obj, String key, Object value);   // keyed by obj's class
public static Object getAdditionalStaticField(Object obj, String key);
public static Object removeAdditionalStaticField(Object obj, String key);
public static Object setAdditionalStaticField(Class<?> clazz, String key, Object value);
public static Object getAdditionalStaticField(Class<?> clazz, String key);
public static Object removeAdditionalStaticField(Class<?> clazz, String key);
public static byte[] assetAsByteArray(Resources res, String path) throws IOException;
public static String getMD5Sum(String file) throws IOException;
public static int incrementMethodDepth(String method);
public static int decrementMethodDepth(String method);
public static int getMethodDepth(String method);
```

#### `XposedBridge.invokeOriginalMethod`

```java
public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
        throws Throwable
```

Throws `NullPointerException` (null receiver for non-static), `IllegalArgumentException`
(arity/receiver/argument mismatch), `InvocationTargetException` (method threw). Implemented as
`VectorInvocation.invokeOriginal((Executable) method, thisObject, args)`. `args == null` is
normalised to an empty array. This is the legacy equivalent of libxposed's
`getInvoker(m).setType(Origin.ORIGIN)`.

#### ⚠️ `XposedHelpers.setHiddenApiExemptions` NO LONGER EXISTS

`grep -r "setHiddenApiExemptions|VMRuntime" Vector/` → **zero matches**. It was a LSPosed 1.8.x-era
API and is **absent from Vector 2.2/canary-3112**. Use the standalone library instead (§3.4).

### 2.2 Modern API — `io.github.libxposed:api` (API 102)

Source of truth: `JingMatrix/libxposed-api` @ `39cac084` (2026-08-01), module `api`,
`libVersion = "102.0.0"`, `compileSdk = 37`, `minSdk = 26`, Java 17.
Docs: `https://raw.githubusercontent.com/wiki/LSPosed/LSPosed/Develop-Xposed-Modules-Using-Modern-Xposed-API.md`

#### Entry class

```java
package io.github.libxposed.api;

public abstract class XposedModule extends XposedInterfaceWrapper
        implements XposedModuleInterface {
    // EMPTY. No constructor. The framework instantiates it and calls attachFramework().
}

public class XposedInterfaceWrapper implements XposedInterface {
    @InternalApi
    public final void attachFramework(@NonNull XposedInterface base, @NonNull Runnable detachImpl);
    // "Modules must not call this method."

    @SinceApi(API_102)
    public final void detach();   // stop lifecycle callbacks for THIS entry; idempotent
}
```

> **Trap:** the wiki still says "`XposedModule` no longer receives `XposedInterface` and
> `ModuleLoadedParam` in its constructor; the framework calls `attachFramework(XposedInterface)`".
> The current signature takes **two** args `(XposedInterface, Runnable)`. And
> `JingMatrix/libxposed-example` (last pushed 2025-11-08) is **stale** — it still uses
> `class ModuleMain(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param)`,
> `io.github.libxposed.api.annotations.XposedHooker`, `BeforeHookCallback`/`AfterHookCallback` and
> `hook(method, MyHooker::class.java)`. **None of those exist in API 102.** Do not copy that repo.
> Write a no-arg constructor / `object` and override `onModuleLoaded`.

#### Lifecycle — `XposedModuleInterface`

```java
default void onModuleLoaded(@NonNull ModuleLoadedParam param);
@RequiresApi(Q) default void onPackageLoaded(@NonNull PackageLoadedParam param);
default void onPackageReady(@NonNull PackageReadyParam param);
default void onSystemServerStarting(@NonNull SystemServerStartingParam param);
@SinceApi(API_102) default boolean onHotReloading(@NonNull HotReloadingParam param);  // runs in OLD code
@SinceApi(API_102) default void onHotReloaded(@NonNull HotReloadedParam param);       // runs in NEW code
```

Param accessors:

| param | accessors |
| :-- | :-- |
| `ModuleLoadedParam` | `boolean isSystemServer()`, `String getProcessName()` |
| `PackageLoadedParam` | `String getPackageName()`, `ApplicationInfo getApplicationInfo()`, `boolean isFirstPackage()`, `@RequiresApi(Q) ClassLoader getDefaultClassLoader()` |
| `PackageReadyParam` extends `PackageLoadedParam` | `ClassLoader getClassLoader()`, `@RequiresApi(P) AppComponentFactory getAppComponentFactory()` |
| `SystemServerStartingParam` | `ClassLoader getClassLoader()` |
| `HotReloadingParam` | `Bundle getExtras()`, `void setSavedInstanceState(Object)` |
| `HotReloadedParam` extends `ModuleLoadedParam` | `Bundle getExtras()`, `Object getSavedInstanceState()`, `List<HookHandle> getOldHookHandles()` |

`onHotReloading` must return `true` for reload to proceed (default `false`), and before returning
`true` the module must have stopped its own threads, unregistered native hooks, released JNI global
refs and cleared module objects held by system/app classes.

#### Hooking — OkHttp-style interceptor chain

```java
public interface XposedInterface {
    int API_101 = 101;
    int API_102 = 102;
    int LIB_API = API_102;

    long PROP_CAP_SYSTEM        = 1L;        // can hook system_server
    long PROP_CAP_REMOTE        = 1L << 1;   // remote prefs + remote files
    long PROP_RT_API_PROTECTION = 1L << 2;   // blocks reflective/dynamic access to the API

    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST  = Integer.MIN_VALUE;
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    @NonNull HookBuilder hook(@NonNull Executable origin);
    @NonNull HookBuilder hookClassInitializer(@NonNull Class<?> origin);   // <clinit>
    boolean deoptimize(@NonNull Executable executable);
    @NonNull Invoker<?, Method> getInvoker(@NonNull Method method);
    @NonNull <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor);

    void log(int priority, @Nullable String tag, @NonNull String msg);
    void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr);

    @NonNull ApplicationInfo  getModuleApplicationInfo();
    @NonNull SharedPreferences getRemotePreferences(@NonNull String group);
    @NonNull String[]          listRemoteFiles();
    @NonNull ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException;

    default int getApiVersion() { return LIB_API; }
    @NonNull String getFrameworkName();
    @NonNull String getFrameworkVersion();
    long getFrameworkVersionCode();
    long getFrameworkProperties();
}
```

```java
interface HookBuilder {
    HookBuilder setPriority(int priority);
    HookBuilder setExceptionMode(@NonNull ExceptionMode mode);
    @SinceApi(API_102) HookBuilder setId(@Nullable String id);   // id => same-id hook replaced atomically
    @NonNull HookHandle intercept(@NonNull Hooker hooker);
}

interface Hooker {
    Object intercept(@NonNull Chain chain) throws Throwable;
}

interface Chain {
    @NonNull Executable getExecutable();
    Object getThisObject();
    @NonNull List<Object> getArgs();          // IMMUTABLE
    Object getArg(int index);
    Object proceed() throws Throwable;
    Object proceed(@NonNull Object[] args) throws Throwable;
    Object proceedWith(@NonNull Object thisObject) throws Throwable;
    Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable;
}

interface HookHandle {
    @NonNull Executable getExecutable();
    void unhook();                            // idempotent
    @SinceApi(API_102) @Nullable String getId();
    @SinceApi(API_102) @NonNull HookHandle replaceHook(@NonNull Hooker hooker);
}

enum ExceptionMode { DEFAULT, PROTECTIVE, PASSTHROUGH }

interface Invoker<T extends Invoker<T, U>, U extends Executable> {
    sealed interface Type permits Type.Origin, Type.Chain {
        Origin ORIGIN = new Origin();
        record Origin() implements Type {}                       // skip ALL hooks
        record Chain(int maxPriority) implements Type {          // enter mid-chain
            public static final Chain FULL = new Chain(PRIORITY_HIGHEST);
        }
    }
    T setType(@NonNull Type type);
    Object invoke(Object thisObject, Object... args)
            throws InvocationTargetException, IllegalArgumentException, IllegalAccessException;
    Object invokeSpecial(@NonNull Object thisObject, Object... args) throws ...;   // like JNI CallNonVirtual
}

interface CtorInvoker<T> extends Invoker<CtorInvoker<T>, Constructor<T>> {
    @NonNull T newInstance(Object... args) throws ...;
    @NonNull <U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args) throws ...;
}
```

#### Legacy vs modern — comparison

| axis | legacy (`de.robv.android.xposed`) | modern (`io.github.libxposed.api` 102) |
| :-- | :-- | :-- |
| entry manifest | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| native entry | `assets/native_init` | `META-INF/xposed/native_init.list` |
| module metadata | manifest `<meta-data>` (`xposedminversion`, …) | `META-INF/xposed/module.prop` + `scope.list` |
| module name/description | `xposeddescription` meta-data | `android:label` / `android:description` |
| hook model | before/after callbacks, mutable `param.args` | interceptor chain, immutable args + `proceed(newArgs)` |
| multiple hookers | priority-ordered before/after lists | explicit priority + atomic `setId` replacement |
| original call | `XposedBridge.invokeOriginalMethod(Member, this, args)` | `getInvoker(m).setType(Origin.ORIGIN).invoke(...)` |
| hook handle | `XC_MethodHook.Unhook` (`unhook()`) | `HookHandle` (`unhook()`, `getId()`, `replaceHook()`) |
| exception policy | framework always catches + logs | explicit `ExceptionMode` (DEFAULT/PROTECTIVE/PASSTHROUGH) |
| `<clinit>` hook | not supported | `hookClassInitializer(Class)` |
| deoptimize | `XposedBridge.deoptimizeMethod(Member)` | `deoptimize(Executable)` (returns boolean) |
| helpers | `XposedHelpers` (reflection sugar) | **removed** — "no `XposedHelpers` in the framework anymore"; use plain reflection / your own |
| resource hooks | `IXposedHookInitPackageResources`, `XResources` | **REMOVED — not supported at all** |
| module↔app data | `XSharedPreferences` | remote prefs, remote files, hot-reload `Bundle` |
| framework service | none | `io.github.libxposed:service` (scope request, hot reload, remote prefs/files) |
| min Android | 8.1 (Vector floor) | 8.0 platform-wise (`minSdk 26`) |
| API 102 interop | — | **API-102 libxposed modules are FORBIDDEN from calling legacy `de.robv.*`** |

The API-102 legacy ban is enforced in code, not just documented — `VectorModuleClassLoader.loadClass`:

```kotlin
if (blockLegacyApi && LEGACY_API_PREFIXES.any { name.startsWith(it) }) {
    throw ClassNotFoundException("$name is unavailable to modules targeting Xposed API 102 or higher")
}
```

`XposedInterface.API_102` javadoc: *"Libxposed modules can not call legacy `de.robv.android.xposed`
APIs."*

#### Should you support both?

**Yes — as two entry classes in one APK, not one class implementing both.** Because:

1. The load strategy is chosen per-APK by `targetApiVersion` **first**:
   `targetApi >= 101 → MODERN`, else `assets/xposed_init` present → `LEGACY`.
   A single APK with `targetApiVersion=102` will **never** take the legacy path, so a
   `de.robv.*` implementation in the same APK is dead code that also risks the loader ban.
2. Conversely, an APK with `assets/xposed_init` and **no** `module.prop` loads as legacy — so legacy
   support is free and does not need a modern entry at all.

**Recommended shape:** one APK that is *legacy-primary* (if you need to run on other Xposed forks
such as OG Xposed / older LSPosed / Pine's Xposed shim) with **no** `META-INF/xposed/module.prop`,
plus your own runtime capability detection. Or two Gradle product flavours producing two APKs, one
modern (`targetApiVersion=102`) and one legacy. Do **not** mix both APIs in one module
implementation class.

---

## 3. Android 14/15/16/17 constraints

### 3.1 Is `android:debuggable` required? `sharedUserId`?

**No, to both.** Verified by exhaustive grep of the Vector tree: `sharedUserId` appears **zero**
times, and the only `debuggable` hits are unrelated (`dex2oat` ART keys `kDebuggableKey` /
`kNativeDebuggableKey`, and JDWP `-Xrunjdwp` in the daemon helper launch script).

- `android:debuggable="true"` was required by **old** SandHook/YAHFA-era advice and by
  `SandHookConfig.DEBUG`. It is **not** required by LSPosed/Vector legacy or modern modules.
  Keep `debuggable=false` in release; it is a Play Integrity / anti-tamper liability.
- `android:sharedUserId` is **not** used by LSPosed modules. `PackageLoadedParam`'s javadoc merely
  notes that a process may load multiple packages via `sharedUserId` or
  `createPackageContext(..., CONTEXT_INCLUDE_CODE)` — that is about observing other packages, not
  about a requirement on yours.

### 3.2 Current module template — `LSPosed/LSPosed-Module-Template` is GONE

`GET /repos/LSPosed/LSPosed-Module-Template` → **404**; `git ls-remote` → exit 128. The official
template no longer exists. What follows is reconstructed from the **framework's own loader code**
(authoritative) plus two live community templates.

#### Loader decision logic (authoritative)

`Vector/daemon/src/main/kotlin/org/matrix/vector/daemon/data/FileSystem.kt`, `loadModule()`:

```kotlin
val hasLegacyFile = zip.getEntry("assets/xposed_init") != null
val strategy = when {
    targetApi >= 101  -> "MODERN"
    hasLegacyFile     -> "LEGACY"
    targetApi == 100  -> "UNSUPPORTED"   // API 100 is dropped
    else              -> "NONE"
}
"MODERN" -> readList("META-INF/xposed/java_init.list",   moduleClassNames)
            readList("META-INF/xposed/native_init.list", moduleLibraryNames)
"LEGACY" -> readList("assets/xposed_init",   moduleClassNames)
            readList("assets/native_init",   moduleLibraryNames)
```

- `module.prop` is read from **`META-INF/xposed/module.prop`** (Java `Properties` format).
- List files: one entry per line, trimmed; blank lines and lines starting with `#` are skipped.
- **API 100 modules are no longer supported** — a `module.prop` with `targetApiVersion=100` and no
  legacy file logs *"Module … uses API 100 which is no longer supported."* and is refused.
  (Note: the popular community template `luolong47/Lsposed-module-template` still ships
  `minApiVersion=100` / `targetApiVersion=100` — **it will not load on Vector 2.x.** Do not copy it
  verbatim.)

#### Metadata keys — exact, current

**Modern** (`META-INF/xposed/module.prop`, Java properties):

| key | format | optional | meaning |
| :-- | :-- | :-- | :-- |
| `minApiVersion` | int | no (per wiki); loader tolerates absent | minimum Xposed API version required |
| `targetApiVersion` | int | **de facto required** — selects the load strategy | target Xposed API version |
| `staticScope` | boolean | yes | module declares its scope is fixed; users may not widen it |
| `autoHotReload` | boolean | yes (API 102) | app update offers hot reload |
| `exceptionMode` | `protective` \| `passthrough` | yes (API 102) | module-wide default for `ExceptionMode.DEFAULT`; anything not `passthrough` keeps `PROTECTIVE` |

`staticScope=true` with an empty `scope.list` is treated as a packaging mistake and **ignored**
(both in `ModuleDetection` and `FileSystem.readStaticScope`).

**Modern** other files:

| file | content |
| :-- | :-- |
| `META-INF/xposed/java_init.list` | fully-qualified entry class names, one per line |
| `META-INF/xposed/native_init.list` | native library names to be `native_init`-invoked |
| `META-INF/xposed/scope.list` | package names, one per line; use `system` for system_server |

All of these go in `app/src/main/resources/META-INF/xposed/` (Gradle packages `resources/` to APK root).

**Legacy** (manifest `<meta-data>` under `<application>`):

| key | format | notes |
| :-- | :-- | :-- |
| `xposedminversion` | int **or** string | **This is the sole legacy-detection key.** `ModuleDetection`: `info.metaData?.containsKey("xposedminversion")`. Leading digits are parsed, so `"93 (for Android 9)"` works. |
| `xposedscope` | string-array **resource id** (int) **or** `;`-separated string | `android`/`system` are **swapped** to Vector's vocabulary |
| `xposeddescription` | string **or** string-resource id | legacy description lives here, *not* `android:description` |
| `xposedsharedprefs` | boolean | opt in to the SELinux safe-zone XSharedPreferences path (also implied by `xposedminversion > 92`) |

#### ⚠️ `xposedmodule` and `<application android:label>` rules

- **`<meta-data android:name="xposedmodule" android:value="true"/>` is NOT read by Vector at all.**
  Grep for `xposedmodule` across the whole Vector tree returns **zero** matches. It was required by
  rovo89's original Xposed; Vector keys legacy detection off `xposedminversion`. Keeping it is
  harmless (other forks may want it); relying on it alone means **your module will not be
  detected** by Vector.
- **Modern modules carry no module metadata at all.** Per the official wiki: *"Modern API does not
  use metadata anymore as well. Module name uses the `android:label` resource; module description
  uses the `android:description` resource."* So `android:label` and `android:description` on
  `<application>` are the module's display name/description. `ModuleDetection` reads
  `info.loadDescription(packageManager)` for modern modules and `xposeddescription` for legacy.
- The only `xposedmodule`-adjacent string Vector consumes is the category
  **`de.robv.android.xposed.category.MODULE_SETTINGS`** on an activity's intent-filter — that is
  what makes the manager offer "open module settings".

### 3.3 Scope: restrictions, "recommended" vs "force", and how to open the UI

- **Declared/recommended scope**: modern `META-INF/xposed/scope.list`; legacy `xposedscope`.
  In the manager UI this is a *recommendation* — the user picks which of those packages are actually
  in scope.
- **Forced scope**: `staticScope=true` in `module.prop`. Documented meaning:
  *"the module scope is fixed and users should not apply the module on apps outside the scope list."*
  Enforced in the **daemon**, not just the UI — `ModuleDatabase.setModuleScope` refuses targets
  beyond the claimed set, so the socket CLI, backup restore and the module's own `requestScope()`
  cannot walk past it. `requestScope()` beyond a static scope fails with
  *"This module declares a static scope, so X cannot be added"*.
- **`system` scope**: modern scope lists use `system` for system_server. Legacy modules use the
  **opposite** convention (their `"android"` means system_server, their `"system"` means the
  `android` package), and Vector **silently swaps** these two for legacy modules
  (`ModuleDetection.swapLegacyFrameworkNames`). This is the single most common scope bug when
  porting a legacy module.
- **framework scope / system_server**: allowed if the framework advertises `PROP_CAP_SYSTEM`
  (Vector sets `PROP_CAP_SYSTEM or PROP_CAP_REMOTE`, plus `PROP_RT_API_PROTECTION` when dex
  obfuscation is on). In system_server, in-APK native libs cannot be executed (`/data/app` is
  `apk_data_file`), so the daemon stages a copy under its own SELinux label — the loader puts
  `file.nativeLibraryDir` **first** on the search path for that reason.

#### Opening the manager / requesting scope programmatically

**Manager package name changed.** It is now **`org.matrix.vector.manager`**
(`build.gradle.kts`: `val defaultManagerPackageName = "org.matrix.vector.manager"`).
`org.lsposed.manager` is **completely gone** — the only occurrence of the string `org.lsposed.` in
the whole Vector tree is a comment about the unrelated Gradle plugin `org.lsposed.lsplugin.apksign`.
**Any 2023-era snippet using `org.lsposed.manager.LAUNCH_MANAGER` will not work.**

Launch mechanism (category-based, matched in `system_server` by `ParasiticManagerSystemHooker`,
which rewrites the resolved `ActivityInfo` so the manager code runs in its own process):

```bash
# from Vector/zygisk/module/action.sh — the canonical invocation
am start -c "${MANAGER_PACKAGE_NAME}.LAUNCH_MANAGER" "${INJECTED_PACKAGE_NAME}/.BugreportWarningActivity"
```

```xml
<!-- the manager's own intent-filter -->
<category android:name="org.matrix.vector.manager.LAUNCH_MANAGER" />
```

- There is **NO documented intent/deep link to open the scope page for a specific package.** I
  searched the entire Vector tree for `EXTRA_PACKAGE` / deep-link handling in the manager: the only
  relevant extra is `Intent.EXTRA_PACKAGES` (`android.intent.extra.PACKAGES`), and it is consumed by
  the manager's *package-broadcast receiver*, not by a scope deep link. The manager's activity
  intent-filter carries only `LAUNCHER` + `LAUNCH_MANAGER` + `APPLICATION_PREFERENCES`.
- **Scope must therefore be requested through the modern service API**, which raises a user-facing
  notification prompt (`NotificationManager.requestModuleScope`):

```java
// io.github.libxposed:service:102.0.0  — IXposedService.aidl
List<String> getScope() = 10;
oneway void requestScope(in List<String> packages, IXposedScopeCallback callback) = 11;
void removeScope(in List<String> packages) = 12;

// IXposedScopeCallback.aidl
oneway void onScopeRequestApproved(in List<String> approved) = 1;
oneway void onScopeRequestFailed(String message) = 2;
```

Java wrapper (`io.github.libxposed.service.XposedService`):

```java
public interface OnScopeEventListener {
    void onScopeRequestApproved(List<String> approved);
    void onScopeRequestFailed(String message);
}
public void requestScope(@NonNull List<String> packages, @NonNull OnScopeEventListener callback);
public void removeScope(@NonNull List<String> packages);
public List<String> getScope();
```

Semantics worth designing around: the whole list goes up as **one** prompt; Approve answers for all
of it; packages are sorted + deduplicated; an empty request is auto-approved; a user can block the
module (`PreferenceStore.isScopeRequestBlocked`) after which requests fail with
*"Scope request blocked by user configuration"*.

Timeouts (`XposedInterface` javadoc, "Wait for service"): 1 s for framework init info, 10 s for the
first module call, **1 min** for a scope request.

- `LSPatch` (rootless, `JingMatrix/LSPatch`, active 2026-10-07) embeds Vector into a patched APK —
  worth knowing as an alternative distribution channel, but it is the *framework*, not a
  scope-management API.

### 3.4 Hidden API / non-SDK interface restrictions

**What is gone:** `XposedHelpers.setHiddenApiExemptions(...)` — **removed** from LSPosed/Vector.
Also zero occurrences of `VMRuntime` in the tree.

**What Vector itself does (and why you cannot copy it):** `Vector/hiddenapi/` contains a `stubs`
module (`android/app/ActivityThread.java`, `android/content/res/Resources.java`, …) and a `bridge`
module (`hidden/HiddenApiBridge.java`) that calls e.g. `resources.setImpl(impl)`,
`applicationInfo.credentialProtectedDataDir`, `ActivityThread.currentPackageName()`. This works
because the **framework's own classes are loaded by a privileged classloader and exempted at
runtime** — it is not an API available to modules. Copying the `stubs` trick into a module does not
grant exemption; it only compiles.

**What still works for modules (2026):**

| technique | status | notes |
| :-- | :-- | :-- |
| **`org.lsposed.hiddenapibypass:hiddenapibypass`** | ✅ **recommended** | Badge: **"Android 1.0 – 17"**. Latest **6.2**, published **2026-10-10**. Pure Java. |
| `HiddenApiBypass` variant (uses `sun.misc.Unsafe`) | ✅ stable | README: "Unsafe and setHiddenApiExemptions are stable APIs" **for the library's own API** — not the framework's |
| `LSPass` variant (uses `Property.of()`) | ⚠️ faster but blockable | "can be blocked as easily as meta-reflection and cannot be used to access core platform api if core platform api restriction is enabled in future Android releases" |
| `dalvik.system.VMRuntime.setHiddenApiExemptions` directly | ❌ not viable | Absent from Vector; the method is `@UnsupportedAppUsage` + greylist/blocklist by SDK level; needs a separate bypass to even reach it |
| meta-reflection / `dexfile` tricks | ❌ blocked | README explicitly: does not rely on those "so it will not be blocked like meta-reflection or `dexfile`" |
| `sun.misc.Unsafe` directly | ⚠️ works but discouraged | LSPass exists precisely so you can avoid it |

Integration (from the library README — note the **Play-review** caveat):

```gradle
android { dependenciesInfo { includeInApk = false; includeInBundle = false } }
dependencies { implementation 'org.lsposed.hiddenapibypass:hiddenapibypass:6.2' }
```

```java
HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;", "Ldalvik/system", "Lx");
HiddenApiBypass.setHiddenApiExemptions("");           // exempt everything
Object o = HiddenApiBypass.invoke(ApplicationInfo.class, new ApplicationInfo(), "usesNonSdkApi");
var m = HiddenApiBypass.getDeclaredMethod(ApplicationInfo.class, "getHiddenApiEnforcementPolicy");
```

README warning verbatim: *"Google Play doesn't allow apps to use hidden APIs, reporting library
usage will cause your app to fail app review, you need to disable dependencies info reporting."*

**Known breakages / cautions:**
- Pine's README: *"Pine will disable hidden api policy on initialization by default. Due to an ART
  bug, if a thread changes hidden api policy while another thread is calling an API that lists
  members of a class, an out-of-bounds write may occur and causes crashes… initialize our library
  before other threads is started."* Initialise exemptions as early as `onModuleLoaded` /
  `initZygote`, never lazily from a hot thread.
- LSPosed release notes record a real regression class here: in v2.1, R8 merged `XResources` into a
  shared class that `XposedHelpers.findClass` touches, and **one permanent failure disabled every
  `findClass` in `system_server` for the rest of the boot**. Lesson: never let reflection failures
  latch a global disabled-state flag, and beware R8 class merging around framework-touching code.

### 3.5 Platform facts for targetSdk 34/35/36/37

- **Android 17 = API level 37.** Confirmed locally and independently:
  `F:\Android\androidsdk\platforms\android-37.0\source.properties` →
  `Pkg.Desc=Android SDK Platform 17`, `Platform.Version=17`, `AndroidVersion.ApiLevel=37.0`,
  `AndroidVersion.ExtensionLevel=22`, `AndroidVersion.IsBaseSdk=true`, `PreviewSdkInt=0` (a
  **released**, non-preview SDK). `build-tools/37.0.0` is also installed.
  `android-36` = Android 16 / API 36 (Ext 17); `android-36.1` = Android 16 / API 36.1 (Ext 20).
  NDK installed: `28.2.13676358`.
  Corroborated in-repo: `libxposed-api/api/build.gradle.kts` sets `compileSdk = 37`,
  `buildToolsVersion = "37.0.0"`; `LSPlant` README claims "Android 5.0 - 17 (API level 21 - 37)";
  Vector README claims "Android 8.1 through Android 17 Beta".
- **16 KB page sizes (Android 15+).** Official: *"Beginning with Android 15, AOSP supports devices
  that are configured to use a page size of 16 KB"* and *"all apps targeting Android 15 (API level
  35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. Starting
  **February 1, 2027**, if your app updates don't support 16 KB memory page sizes, you won't be able
  to release these updates."* Any NDK library you ship (or an SDK ships for you) must be rebuilt.
  This is the single most likely 2027 release blocker for a module with native code.
- Legacy (non-NDK) modules are unaffected by 16 KB page size; they are pure DEX.

---

## 4. Runtime hooking of arbitrary methods without recompiling (live-debugger feature)

### 4.1 The one that actually answers the requirement

**Yes — LSPosed/Vector can hook arbitrary methods at runtime via reflection.** You do not need a
third-party engine, and you do not need to know the method at compile time:

- Modern: `hook(Executable)` takes any `java.lang.reflect.Executable` obtained at runtime
  (`clazz.getDeclaredMethod(name, paramTypes)`), so a string method name from your debugger UI is
  enough.
- Legacy: `XposedBridge.hookMethod(Member, XC_MethodHook)` and
  `XposedHelpers.findAndHookMethod(String className, ClassLoader cl, String methodName, Object...)`
  do the same, and `XposedBridge.hookAllMethods(Class, String, XC_MethodHook)` hooks every overload
  of a name.
- Already-loaded classes are fine — you are hooking the `Method` object, and ART-level hooking
  deoptimizes as needed. `deoptimize(Executable)` / `deoptimizeMethod(Member)` exists to defeat
  inlining when a hooked callee is inlined into an already-compiled caller.
- Arguments and return values are fully modifiable: legacy mutates `param.args[i]` in place and calls
  `param.setResult(...)` / `param.setThrowable(...)`; modern passes `chain.proceed(newArgs)` and
  returns a replacement object.

**This is the correct backbone for a live debugger.** The engines below are alternatives or
complements, not replacements.

### 4.2 Comparison

| engine | level | modify args/return? | hook already-loaded classes? | **register values?** | Android support | ABIs | maintenance | license | distribution |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| **LSPlant** | Java (ART) | ✅ args via `Object[]`; return via callback | ✅ | ❌ (Java-level only) | **5.0 – 17 (API 21–37)** | v7a, arm64-v8a, x86, x86_64, **riscv64** | **ACTIVE** — repo 2026-10-06 (LSPosed) / 2026-10-01 (JingMatrix); latest tag **v6.4** | LGPL-3.0 | Maven `org.lsposed.lsplant:lsplant:6.4`, `:lsplant-standalone:6.4` (Prefab) |
| **Xposed/Vector runtime hooks** | Java (ART, via LSPlant) | ✅ | ✅ | ❌ | 8.1 – 17 | inherits framework | **ACTIVE** | GPL-3.0 (framework) | framework itself |
| **Pine** | Java (ART) | ✅ `CallFrame` args / `result` / `throwable` | ✅ | ❌ | 4.4 (ART only) – **15 Beta 4** | thumb-2 / arm64 | semi-active — last commit **2025-11-08** | **Anti-996** | Maven `top.canyie.pine:core:0.3.0` (2024-07-31), `:xposed`, `:enhances` |
| **YAHFA** | Java (ART) | ✅ via static hook/backup methods | ✅ | ❌ | 7.0 – **12 DP1** only (≤6.0 broken) | x86, x86_64, v7a, arm64-v8a | **DEAD** — last commit **2022-08-29** | GPL-3.0 | Maven `io.github.pagalaxylab:yahfa:0.11.1` (2020-12-30) |
| **SandHook** | Java + native inline | ✅ | ✅ | ⚠️ native part yes, Java part no | 4.4 (ART) – **11.0 dev-preview-1** | ARM64, ARM32(untested), Thumb-2 | **DEAD** — last commit **2021-06-15**; `LSPosed/SandHook` archived | — | ❌ **not on Maven Central** (`com.swift.sandhook:hooklib` → 404; Bintray dead) |
| **Dobby** | **native inline** | ✅ native args; trampoline calls original | ✅ (native) | ✅ **`DobbyInstrument` callback receives a register context** | ARM/ARM64/x86/x86-64 (not version-pinned; Android via NDK) | ARM, ARM64, x86, x86-64 | semi-stale — last commit **2024-03-14**; rolling tag `latest`; mirrors at `LSPosed/Dobby` (2025-06-05), `JingMatrix/Dobby` | Apache-2.0 | build from source / prefab; used inside LSPlant & Vector |
| **Frida** | Java + native, out-of-process agent | ✅ | ✅ | ✅ (native `Context`/`CpuContext`, incl. `pc`, `sp`, `x0..x28`) | **Android 16 supported since 17.1.4 (2025-06-10)**; continuous Android fixes through 17.24 | all Android ABIs | **VERY ACTIVE** — **17.24.0 released 2026-10-16** | wxWindows/LGPL-style (frida) | `pip install frida`, `frida-server`, or `frida-gadget` embedded |

### 4.3 Notes that change design decisions

- **YAHFA and SandHook are unusable in 2026.** YAHFA stops at Android 12 DP1 and has had no commits
  since 2022; SandHook stops at Android 11 DP1, has had none since 2021, its LSPosed fork is
  archived, and **its Maven coordinates 404 — `com.swift.sandhook:hooklib` and `:nativehook` are not
  on Maven Central** (the Bintray repo they lived on was shut down). Any plan that depends on either
  is dead on arrival.
- **Pine** is the closest thing to a standalone LSPlant alternative, but it declares Android **15
  Beta 4** as its ceiling, ships under the **Anti-996 license** (a real legal consideration for a
  commercial app), and its last Maven publish was **2024-07-31** while its last commit was
  2025-11-08. Its `top.canyie.pine:xposed` artifact does provide `XposedHelpers.findAndHookMethod`
  and `XposedBridge.hookMethod` plus `PineXposed.loadModule(...)`, but **"resources hook is not
  supported"** and it is "only java method hooking".
- **Dobby** is not a Java hooking engine — it is a native inline hook framework. It is the only
  standalone option here that gives you a **register context** (`DobbyInstrument`), and it is what
  LSPlant uses underneath. Dobby's own repo is 2.5 years stale, but both LSPosed and JingMatrix keep
  live mirrors, so pin to a mirror you trust.
- **Frida** is by far the most actively maintained (a release 1 day before this research) and the
  most capable, but it is an **external agent**, not an in-process module:
  - `frida-server` needs root and is trivially detectable (process name, port 27042, maps entry).
    Frida docs themselves suggest renaming the binary.
  - **gadget embedding on Android has hard packaging rules** (Frida docs verbatim): *"the package
    manager will only copy files from a non-debuggable application's `/lib` directory if their name
    matches the following conditions: It starts with the prefix `lib`… It ends with the suffix
    `.so`… It's gdbserver."* Hence the standard layout:
    ```
    lib/arm64-v8a/libgadget.so
    lib/arm64-v8a/libgadget.config.so     # JSON config: interaction, teardown, runtime, code_signing
    ```
    `code_signing: required` (used when the kernel enforces signed executable mappings) **disables
    the Interceptor API** — which is exactly the API a hooking debugger needs.
  - Android 16 gadget regressions are real and recent: frida/frida **issue #3700** *"Crash when
    overriding method using Frida Gadget 17.7 on Android 16 (works on Android 12)"*, and **issue
    #3516** *"How to use Gadget on Android for Frida17 and above?"* — Frida 17 changed gadget
    usage. Frida 17.1.5 (2025-06-13) was itself a *"Critical bug-fix: restores compatibility with
    Android 14–15 — accidentally broken in the last release while adding Android 16 support."*
    One good recent fix in the other direction: *"Android Gadget now supports loading assets from
    APKs when `extractNativeLibs` is set to false."*
  - Play Integrity / detection: Frida is a well-known signal. For an app that must pass integrity on
    the *same device*, an in-process LSPosed module (or Zygisk-denylist) is far more survivable than
    a running frida-server.

### 4.4 Reading/modifying registers vs Java arguments — the explicit answer

| capability | Java hook (LSPlant / Xposed / Pine) | Native inline hook (Dobby) | Frida | perf_event hardware BP |
| :-- | :-- | :-- | :-- | :-- |
| read/modify **Java arguments** (`Object[]`) | ✅ | ✗ (sees JNI-level args) | ✅ (`args[]` in `Java.perform` `Interceptor`) | ✗ |
| read/modify **Java return value** | ✅ | ✗ | ✅ | ✗ |
| read **native function args** | ✗ | ✅ | ✅ | ✗ |
| read **CPU registers** (`x0..x28`, `sp`, `pc`, `lr`, `nzcv`) | ❌ | ✅ via `DobbyInstrument` context | ✅ native `CpuContext` | ✅ `PERF_SAMPLE_REGS_USER` |
| **write** CPU registers mid-execution | ❌ | ✅ (writable context) | ✅ | ✗ (observe-only) |
| patch machine code in memory | ✗ | ✅ (inline patch) | ✅ (`Memory.patchCode`) | ✗ |
| requires root | no (framework does) | no (inside an injected process) | yes (`frida-server`) or repackaging (gadget) | yes |

**Bottom line: Java-level hooks can read/modify arguments and return values but can never see CPU
registers. Register access requires a native inline hook (Dobby/Frida) or a hardware breakpoint.**
They are different tools for different layers, not competitors.

---

## 5. Reading/modifying a running app's memory & registers

### 5.1 `/proc/<pid>/mem` with root

- With **root**, `/proc/<pid>/mem` can be `pread`/`pwrite` for both read and write, including into
  r-x code pages, subject to the target's page protections and SELinux.
- **A non-root app process cannot open another app's `/proc/<pid>/mem`** even with the same UID
  relationship — Android's SELinux policy and `ptrace_may_access` block it. A production-grade
  Android debug tool that I examined (`brain584/universal-debug-tool`, validated by its author on
  **Android 16**) works around this explicitly: *"the search engine runs in the app process
  (MemorySearch, MIT); the app process is not root and cannot directly open the target's
  `/proc/<pid>/mem`, therefore a new MemSu backend (`su` + `dd` pipe) is used to read/write target
  memory, and `maps` enumeration also goes through `su`."*
- Practical implications: every read is an IPC round-trip through `su`, so bulk scanning is slow;
  design a debugger around *targeted* reads (known field/array offsets) rather than whole-heap
  scanning.
- `maps` parsing (`/proc/<pid>/maps`) is how you classify regions (code app, code system, anonymous,
  java heap, cpp heap, stack, ashmem).

### 5.2 ptrace

- ptrace attach/injection **does** work on rooted/modern Android but is heavily constrained:
  SELinux domain rules, and the kernel Yama LSM. AOSP ships a `10-default-yama-scope.conf` in
  `platform/external/elfutils`, and the relevant knob is `/proc/sys/kernel/yama/ptrace_scope`
  (`1` = restricted to descendants). Root can set it to `0`, or a kernel module can hide `TracerPid`
  (cf. `JingMatrix/ko-undebug`, "Linux Kernel Module for Hiding TracerPid and maps entries").
- ptrace-based injection **briefly suspends the target process** during injection and resumes it
  afterwards. It is not a steady-state debugging mechanism.
- ptrace is inherently detectable: `TracerPid` in `/proc/self/status`, `PTRACE_TRACEME` behaviour,
  timing. Anti-tamper code checks all of these.

### 5.3 Hardware breakpoints without ptrace or stopping the target — the interesting result

The same Android 16-validated tool implements register observation **without ptrace and without
suspending the target**, using the kernel's perf subsystem:

- Breakpoints via **`perf_event_open` with `PERF_TYPE_BREAKPOINT`** — the kernel programs the CPU
  debug registers directly.
- All threads of the target are periodically rescanned and the breakpoint is installed on each.
- **The `sigtrap` bit is deliberately not set**, so *"the target process will not pause, will not be
  ptraced, and will not receive SIGTRAP."*
- On a hit, registers are collected with **`PERF_SAMPLE_REGS_USER`** (skipping the `regs_user_abi`
  field) and returned together with PC, thread ID and a stack chain; a **`PERF_SAMPLE_REGS_INTR`**
  fallback was added because some kernels report individual registers as 0.
- Reported limitations the author documents honestly: (1) stack results are native-frame only
  (PC + LR) — **JNI frames cannot be unwound back to Java callers**; (2) some kernels/devices still
  sample zeroed registers despite the fallback.

That is the realistic ceiling for *observation* without disturbing the target.

### 5.4 Register/instruction *modification* in practice

The same tool modifies machine code in place using **Capstone** (disassembly) + **asmjit** (assembly
write), e.g. rewriting an instruction to `nop` or to `cset w0, ne`. That is the honest shape of
"register-level modification": you patch or instrument **native** code. There is no supported path to
surgically rewriting ART interpreter/compiler registers for an arbitrary Java method in a release
Android build.

### 5.5 So what can a "real-time debugger" realistically offer?

| feature | feasible? | mechanism | caveats |
| :-- | :-- | :-- | :-- |
| Set a breakpoint on a Java method by name at runtime | ✅ | `hook(Executable)` / `XposedBridge.hookMethod` | must handle overloads; inlined callers need `deoptimize` |
| Inspect `this` and Java arguments on entry | ✅ | `Chain.getArgs()` / `param.args` | primitive boxing; boxing of wide types |
| Modify Java arguments before the body runs | ✅ | `chain.proceed(newArgs)` / mutate `param.args` | type must remain assignable |
| Modify / replace the Java return value | ✅ | return from `intercept` / `param.setResult(...)` | void methods ignore the return |
| Force a Java method to throw | ✅ | `param.setThrowable(...)` / throw from `intercept` | |
| Skip the original Java method entirely | ✅ | don't call `proceed` / `setResult` in before | |
| Call the original method bypassing all hooks | ✅ | `getInvoker(m).setType(ORIGIN)` / `invokeOriginalMethod` | |
| Read fields (incl. static/private) of live objects | ✅ | `XposedHelpers.getObjectField` / reflection + hidden-API bypass | hidden-API exemption needed for non-SDK members |
| Conditional breakpoints / step-like tracing | ✅ | hooks + your own state | hook latency per call; keep the callback cheap |
| "Step into" / single-step a Java method | ⚠️ hard | requires debugger-class control (JDWP) or per-line hooks | not what Xposed-style hooks give you |
| Inspect CPU registers of a **Java** method | ❌ | — | Java hooks are register-blind |
| Inspect CPU registers of a **native** function | ✅ | Dobby `DobbyInstrument`, Frida `CpuContext`, perf hw breakpoints | root or an injected process |
| Modify CPU registers / patch native code | ✅ | Dobby inline hook / Frida / Capstone+asmjit | root; breaks code-signing/integrity; 16 KB-page-safe rebuild needed |
| Watch memory writes | ⚠️ | perf breakpoints on addresses, or poll `/proc/pid/mem` | perf coverage is limited; polling misses transient values |
| Heap scan / find value by pattern | ⚠️ | `/proc/pid/mem` via `su`+`dd` | slow, IPC-bound, and unreliable on a moving GC heap |

**Architectural conclusion:** build the debugger on **Java hooks** (Xposed/Vector modern API) as the
primary mechanism — it gives you arguments, return values, field access and conditional logic with
no root round-trips and no integrity cost inside the hooked process. Add **Dobby-based native
instrumentation** only as an opt-in "advanced / native" mode when you genuinely need registers;
treat `/proc/pid/mem` scanning as a slow, best-effort extra.

---

## 6. RECOMMENDATION

### 6.1 Exact artifact coordinates

```kotlin
// settings.gradle.kts / build.gradle.kts
repositories {
    google()
    mavenCentral()
}

// app/build.gradle.kts
android {
    compileSdk = 37                 // Android 17 — verified present locally as platforms/android-37.0
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 26                 // libxposed floor
        targetSdk = 36              // 35+ triggers the 16 KB page-size Play rule (deadline 2027-02-01)
    }
    buildFeatures { prefab = true } // only if you use lsplant / native
    // If you ship hiddenapibypass, keep Play review in mind:
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}

dependencies {
    // ---- Modern module API (primary) ----
    compileOnly("io.github.libxposed:api:102.0.0")        // Maven Central; last published 2026-06-13

    // ---- Framework communication (scope requests, remote prefs/files, hot reload) ----
    implementation("io.github.libxposed:service:102.0.0") // ships the ${applicationId}.XposedService provider

    // Optional compile-time-only companions actually referenced by :api
    compileOnly("io.github.libxposed:annotation:1.0.0")
    // lint("io.github.libxposed:lint:1.0.0")             // lintPublish, optional

    // ---- Hidden / non-SDK API access ----
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")   // "Android 1.0 -- 17", 2026-10-10

    // ---- Only if you need native register-level instrumentation ----
    implementation("org.lsposed.lsplant:lsplant:6.4")                   // Prefab; needs prefab=true
    // or: implementation("org.lsposed.lsplant:lsplant-standalone:6.4") // bundles libc++_shared.so
    // Dobby: build from https://github.com/JingMatrix/Dobby (Apache-2.0) — no stable Maven artifact

    // ---- Legacy-only flavour (see 6.4) ----
    // compileOnly("de.robv.android.xposed:api:82")  // DO NOT use; see note below
}
```

> **`de.robv.android.xposed:api` is NOT on Maven Central and has not been for years.** The legacy
> classes are **provided by the framework at runtime**. The only correct way to compile against them
> is a `compileOnly` jar you build yourself from `JingMatrix/Vector`'s `legacy` module, or from the
> historical `rovo89/XposedBridge` sources. Never bundle them (see 6.3).

### 6.2 What to target

| decision | choice |
| :-- | :-- |
| Framework | **`JingMatrix/Vector` v2.2** (stable, 2026-08-04) or **canary-3112** (`ddeed8ca`, 2026-10-01) |
| API | **libxposed API 102** (`io.github.libxposed:api:102.0.0`) |
| Android range | 8.1 → 17 (API 27 → 37) |
| ABI | `arm64-v8a` first; add `armeabi-v7a` + `x86_64` only if you ship native code |
| Runtime hooking backbone | **Xposed/Vector `hook(Executable)`** — not a third-party engine |
| Native register work (optional) | **Dobby** (`JingMatrix/Dobby`) or **Frida 17.24.0** gadget for external sessions |
| Hidden API | `org.lsposed.hiddenapibypass:hiddenapibypass:6.2` |
| Manager launch | category `org.matrix.vector.manager.LAUNCH_MANAGER` (**not** `org.lsposed.manager`) |
| Avoid | `LSPosed/LSPosed` (dead), YAHFA (dead), SandHook (dead + unavailable), Pine (Anti-996, ≤A15β4) |

### 6.3 Build rules that will silently break you

1. **Xposed/libxposed API classes must be `compileOnly`.** Vector actively refuses to load a module
   that bundled them:
   ```java
   if (mcl.loadClass(XposedBridge.class.getName()).getClassLoader() != initLoader) {
       Log.e(TAG, "  Cannot load module: " + name);
       Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
       Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
       Log.e(TAG, "  For details, see: https://api.xposed.info/using.html");
       return false;
   }
   ```
2. **R8 rules** (from the libxposed README):
   ```proguard
   -dontwarn io.github.libxposed.annotation.**
   -adaptresourcefilecontents META-INF/xposed/java_init.list
   -keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
       public <init>();
   }
   ```
   `-adaptresourcefilecontents` is what rewrites `java_init.list` when `-obfuscation` renames your
   entry class. Ship it, and ship a release build that you actually load-tested.
3. **Pick ONE API generation per APK.** `targetApiVersion >= 101` wins and permanently disables the
   legacy path (and legacy classes are `ClassNotFoundException` for API-102 modules). Do not try to
   be both in one implementation.
4. **Do not ship `targetApiVersion=100`.** Vector 2.x refuses API 100 modules outright.
5. **Rebuild native code for 16 KB pages** if you ship any `.so` — required for Play updates from
   **2027-02-01** for `targetSdk >= 35`.
6. **Don't let R8 merge classes that framework reflection touches** — see the Vector 2.1
   `XResources`/`findClass` regression in §3.4.

### 6.4 If you must also support legacy-only forks

Produce **two APKs from one codebase via product flavours**, with the hooking logic behind an
interface:

- `modern` flavour: `module.prop` (`targetApiVersion=102`, `minApiVersion=102`) +
  `META-INF/xposed/java_init.list` + `scope.list`; entry extends `io.github.libxposed.api.XposedModule`.
- `legacy` flavour: **no** `module.prop`, `assets/xposed_init` listing the legacy entry class,
  manifest `<meta-data android:name="xposedminversion" android:value="93"/>` (and optionally
  `xposedmodule=true`, `xposeddescription`, `xposedscope`); entry implements
  `IXposedHookLoadPackage` / `IXposedHookZygoteInit`.

Do **not** put `module.prop` in the legacy flavour — its presence with `targetApiVersion` set would
change the strategy selection.

---

## 7. Code snippets

### 7.1 Minimal working modern-API module entry class (API 102) — **recommended**

`app/src/main/java/com/example/regtrail/ModuleMain.kt`

```kotlin
package com.example.regtrail

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class ModuleMain : XposedModule() {          // <-- NO-ARG constructor in API 102

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // Safe to initialise here. Do NOT do work in an init{} block: the framework has not
        // attached XposedInterface yet, and the wiki is explicit that modules "should not
        // perform initialization before onModuleLoaded() is called".
        log(Log.INFO, TAG, "loaded in ${param.processName} (systemServer=${param.isSystemServer})")
        log(Log.INFO, TAG, "framework=${frameworkName} ${frameworkVersion} api=${apiVersion}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != TARGET_PACKAGE) return

        val attach = Application::class.java
            .getDeclaredMethod("attach", Context::class.java)

        hook(attach)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("app-attach")                       // API 102: atomic replace on hot reload
            .intercept { chain ->
                val ctx = chain.getArg(0) as Context
                log(Log.INFO, TAG, "Application.attach($ctx) in ${param.packageName}")
                val result = chain.proceed()            // or chain.proceed(newArgs)
                result
            }
    }

    private companion object {
        const val TAG = "RegTrail"
        const val TARGET_PACKAGE = "com.example.target"
    }
}
```

`app/src/main/resources/META-INF/xposed/java_init.list`

```
com.example.regtrail.ModuleMain
```

`app/src/main/resources/META-INF/xposed/module.prop`

```properties
minApiVersion=102
targetApiVersion=102
staticScope=false
autoHotReload=true
exceptionMode=protective
```

`app/src/main/resources/META-INF/xposed/scope.list`

```
# one package per line; 'system' means system_server
com.example.target
```

### 7.2 Minimal legacy-API module entry class (for the legacy flavour)

`app/src/main/java/com/example/regtrail/legacy/LegacyEntry.java`

```java
package com.example.regtrail.legacy;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class LegacyEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "RegTrail";

    @Override
    public void initZygote(StartupParam startupParam) {
        // Framework/system classes only here. classLoader == null => boot class loader.
        XposedBridge.log(TAG + ": initZygote modulePath=" + startupParam.modulePath
                + " startsSystemServer=" + startupParam.startsSystemServer
                + " xposedVersion=" + XposedBridge.getXposedVersion()); // -> 102 on Vector 2.2
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!"com.example.target".equals(lpparam.packageName)) return;
        if (!lpparam.isFirstApplication) return;

        XposedHelpers.findAndHookMethod(
                Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam<?> param) {
                        XposedBridge.log(TAG + ": attach() this=" + param.thisObject
                                + " ctx=" + param.args[0]);
                        // param.args[0] = somethingElse;          // mutate arguments in place
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam<?> param) {
                        // param.setResult(replacement);           // replace the return value
                        // param.setThrowable(new RuntimeException());// force a throw
                        // if (param.hasThrowable()) { ... }        // Vector extension
                    }
                });
    }
}
```

`app/src/main/assets/xposed_init`

```
com.example.regtrail.legacy.LegacyEntry
```

### 7.3 Runtime dynamic hook (by name, no recompilation) — the live-debugger core

Works for **any** class/method discovered at runtime; this is what makes a live debugger possible
without rebuilding the module.

**Modern API (recommended):**

```kotlin
/** Hook an arbitrary method by name, discovered at runtime. */
fun hookByName(
    className: String,
    methodName: String,
    paramTypeNames: List<String>,
    classLoader: ClassLoader,
    onEnter: (args: List<Any?>) -> Unit = {},
    transformArgs: ((Array<Any?>) -> Array<Any?>)? = null,
    transformResult: ((Any?) -> Any?)? = null,
): XposedInterface.HookHandle {
    val clazz = Class.forName(className, false, classLoader)
    val paramTypes = paramTypeNames.map { name ->
        when (name) {
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            "void" -> Void.TYPE
            else -> Class.forName(name, false, classLoader)
        }
    }.toTypedArray()

    // getDeclaredMethod does not walk superclasses; for framework/OEM code prefer a
    // search up the hierarchy, and catch NoSuchMethodException to report "not present
    // on this ROM" rather than crashing the target process.
    val target = clazz.getDeclaredMethod(methodName, *paramTypes)
    target.isAccessible = true

    // If an already-compiled caller inlined this method, the hook will not fire:
    // deoptimize(target) forces the runtime to stop inlining it.
    deoptimize(target)

    return hook(target)
        .setPriority(XposedInterface.PRIORITY_DEFAULT)
        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH) // debugger: surface errors
        .setId("dyn:$className#$methodName")
        .intercept { chain ->
            onEnter(chain.args)
            val result =
                if (transformArgs != null) chain.proceed(transformArgs(chain.args.toTypedArray()))
                else chain.proceed()
            if (transformResult != null) transformResult(result) else result
        }
}
```

**Legacy API equivalent:**

```java
public static XC_MethodHook.Unhook hookByName(
        String className, ClassLoader cl, String methodName, Object... paramTypesAndCallback) {
    // XposedBridge.hookMethod works on any java.lang.reflect.Member obtained at runtime.
    java.lang.reflect.Method m =
            XposedHelpers.findMethodExact(className, cl, methodName, paramTypesAndCallback);
    XposedBridge.deoptimizeMethod(m);              // defeat inlining of already-compiled callers
    return XposedBridge.hookMethod(m, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam<?> p) {
            // p.args is mutable in place
        }
        @Override protected void afterHookedMethod(MethodHookParam<?> p) {
            // p.setResult(...) / p.setThrowable(...)
        }
    });
}

// Hook every overload of a name at once:
Set<XC_MethodHook.Unhook> handles =
        XposedBridge.hookAllMethods(targetClass, "doWork", callback);

// Call the original, bypassing all hooks:
Object r = XposedBridge.invokeOriginalMethod(m, thisObj, args);
```

**Native register-level instrumentation (optional advanced mode, Dobby):**

```c
// DobbyInstrument gives the callback a register context -> this is the ONLY way to
// touch CPU registers in-process. Used e.g. by brain584/universal-debug-tool's `hookCPU`.
#include "dobby.h"

static void instrument_cb(void *address, dobby_instrument_callback_t *ctx) {
    // ctx->reg_ctx / ctx->fp_ctx expose the register file at the hook point
    // (x0..x28, sp, pc, lr, nzcv on arm64), and are writable.
}

DobbyInstrument((void *)target_addr, instrument_cb);
```

### 7.4 Requesting scope programmatically (modern service API)

```kotlin
// In the MODULE APP (not the hooked process). Requires io.github.libxposed:service.
// Register early (e.g. Application.onCreate); the framework binds your provider.
XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
    override fun onServiceBind(service: XposedService) {
        service.requestScope(listOf("com.example.target")) { approved ->
            // one prompt, one answer, for the whole list
        }
        val current = service.getScope()
        // service.removeScope(listOf("com.example.other"))
        // service.hotReloadModule(target, bundle, callback)   // API 102
    }
    override fun onServiceDied(service: XposedService) { /* framework went away */ }
})
```

### 7.5 Module `AndroidManifest.xml`

**Modern flavour** — no module metadata at all; `label`/`description` are the module's name and
description. The `libxposed:service` provider is merged in from the AAR automatically
(`${applicationId}.XposedService`), so do not declare it yourself.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="@string/module_name"                  <!-- module NAME for modern API -->
        android:description="@string/module_description"     <!-- module DESCRIPTION for modern API -->
        android:icon="@mipmap/ic_launcher"
        android:supportsRtl="true">

        <!-- Settings UI. The MODULE_SETTINGS category is what makes Vector's manager
             offer an "open module settings" entry for your module. -->
        <activity
            android:name=".ui.SettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!-- NO <meta-data name="xposedmodule"> is read by Vector. NO <meta-data name="xposedminversion">
             is needed for a modern module. NO android:sharedUserId. NO android:debuggable. -->
    </application>

</manifest>
```

**Legacy flavour** — add the meta-data block; keep `assets/xposed_init`; do **not** ship
`module.prop` in this flavour.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:supportsRtl="true">

        <!-- THE key Vector keys legacy detection on. Legacy description lives here. -->
        <meta-data android:name="xposedminversion"  android:value="93" />
        <meta-data android:name="xposeddescription" android:value="@string/module_description" />
        <!-- Optional. Recognised by Vector but no longer the detection key; other forks want it. -->
        <meta-data android:name="xposedmodule"      android:value="true" />

        <!-- Declared/recommended scope. Either a ';'-separated string, or a string-array resource.
             CAUTION: legacy vocabulary is inverted vs modern — here 'android' means system_server
             and 'system' means the android package; Vector swaps them for you. -->
        <meta-data android:name="xposedscope"       android:value="com.example.target" />

        <!-- OPTIONAL: opt into the SELinux-safe-zone XSharedPreferences path.
             Also implied by xposedminversion > 92. -->
        <meta-data android:name="xposedsharedprefs" android:value="true" />

        <activity
            android:name=".ui.SettingsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

### 7.6 Getting the module's own Context / passing data between module part and app part

This is a real trap and the answers changed in the modern API.

**Can module code in the hooked process see the module's own classes?**

**Yes — directly, by reference.** Vector loads the module APK's DEX **in memory**
(`VectorModuleClassLoader extends ByteBufferDexClassLoader`, DEX read into `SharedMemory`) and the
entry class is loaded from that loader (`mcl.loadClass(moduleClassName)`). So `MyEntry::class.java`,
`MyHelper`, and companion objects are all reachable from module code with no reflection at all —
they are siblings in the same loader.

**What does NOT work** is looking up your own classes through the *target's* loader:

```java
// WRONG: lpparam.classLoader is the TARGET APP's loader; it cannot see your APK's classes.
XposedHelpers.findClass("com.example.regtrail.Helper", lpparam.classLoader);   // ClassNotFoundError
```

**Correct:** use your own class's loader — i.e. just reference the class. If you truly need a
dynamic lookup, `XposedHelpers.findClass(name, MyEntry.class.getClassLoader())` or
`Class.forName(name, false, MyEntry.class.getClassLoader())`.

Also note Vector's loader is **child-first for the module's own classes** but resolves
`java.*`/framework names via the boot loader first, and it overrides `findResource`/`findResources`
against the module APK (via `VectorURLStreamHandler(apkPath)`) — so the module's *resources* are
reachable through its own loader even though the APK is never installed as a package.

**Getting a `Context` for the module (legacy):**

```java
Application app = AndroidAppHelper.currentApplication();     // == ActivityThread.currentApplication()
ApplicationInfo ai = AndroidAppHelper.currentApplicationInfo();
String pkg = AndroidAppHelper.currentPackageName();          // target app's package
String proc = AndroidAppHelper.currentProcessName();
```

`android.app.AndroidAppHelper` (in Vector's `legacy` module, package `android.app` "for historical
reasons… it can't be moved without breaking compatibility") is exactly:
`currentApplication()` → `ActivityThread.currentApplication()`,
`currentApplicationInfo()` → `ActivityThread.currentActivityThread().mBoundApplication.appInfo`,
plus a deprecated `getSharedPreferencesForPackage(...)` that now just constructs an
`XSharedPreferences`. For a context that can reach *your* module's resources you generally need
`activity.createPackageContext(MODULE_PACKAGE, CONTEXT_INCLUDE_CODE)` — but note the modern wiki
states **"module apps are no longer hooked by themselves"**, so do not rely on your own package
being in scope.

**Passing data between the module part (inside the hooked process) and your app part — ranked:**

| technique | layer | notes |
| :-- | :-- | :-- |
| **Remote Preferences** (`getRemotePreferences(group)`) | modern | Backed by the LSPosed database; **read-only inside hooked apps**; supports `registerOnSharedPreferenceChangeListener` in the hooked process; write from the app via the service (`updateRemotePreferences`). `PROP_CAP_REMOTE`. Since v1.9.0. **Best default.** |
| **Remote Files** (`listRemoteFiles()` / `openRemoteFile(name)`) | modern | Stored at `/data/adb/lspd/modules/<user>/<module>`; **read-only** in the hooked app, no change listener, good for large blobs. `PROP_CAP_REMOTE`. Since v1.9.0. |
| **Hot-reload `Bundle`** (`onHotReloading.setSavedInstanceState`, `getExtras`) | modern, API 102 | Classloader-neutral values only (primitives, strings, arrays, framework `Bundle`s). Explicitly rejects objects created under the old module classloader. |
| **`XSharedPreferences`** | legacy | Works "Since v2.0.0". The framework redirects the module app's `ContextImpl.getPreferencesDir()` into a daemon-provisioned **SELinux-permissive safe-zone** (`u:object_r:xposed_data:s0`, `chmod 755`) and suppresses the `MODE_WORLD_READABLE` `SecurityException`. Requires `xposedminversion > 92` or `xposedsharedprefs=true`. Live updates via an `inotify` watcher. |
| **"New XSharedPreferences"** | legacy-ext | ⚠️ The official wiki comparison table marks it **"❌ Since v2.1.0 (postponed)"**. Treat as unavailable. |
| Binder/AIDL service of your own + a shared AAR | either | Works, but you must ship the same AAR into both the module APK and your app APK, and you own all the lifecycle problems. Only worth it if you need push-style, high-frequency, two-way data. |
| Static fields / singletons in a shared library | either | **Do not** rely on a static in a library that both the module and the app load — in the hooked process the module's copy lives in `VectorModuleClassLoader`, while any app-side copy lives in the app's loader. Two classloaders, two statics, no sharing. This is the classic "my shared state is always empty" bug. |

**The "shared library AAR" question, answered:** shipping a shared AAR is a legitimate pattern for
*code* reuse (interface + DTOs), but it does **not** give you shared *state* across the module/app
boundary and it does **not** give you a context. For state, use Remote Preferences / Remote Files
(modern) or `XSharedPreferences` (legacy). For a module `Context`, use
`AndroidAppHelper.currentApplication()` (which is the *target's* application, not yours) and
`getModuleApplicationInfo()` (modern) when you need your own APK path.

---

## 8. Every source URL used

**GitHub API (releases / repo metadata / rate limit)**
- https://api.github.com/repos/LSPosed/LSPosed/releases
- https://api.github.com/repos/JingMatrix/LSPosed/releases
- https://api.github.com/repos/JingMatrix/LSPosed/releases/tags/v2.2
- https://api.github.com/repos/JingMatrix/Vector
- https://api.github.com/repos/LSPosed/LSPosed
- https://api.github.com/orgs/LSPosed/repos
- https://api.github.com/users/JingMatrix/repos
- https://api.github.com/search/repositories?q=libxposed
- https://api.github.com/search/repositories?q=xposed+module+template
- https://api.github.com/rate_limit

**Repository existence probes (`git ls-remote`, `git clone`)**
- https://github.com/JingMatrix/Vector.git (HEAD `ddeed8ca`, 2026-10-01)
- https://github.com/LSPosed/LSPosed.git (HEAD `df74d83e`, 2024-01-07)
- https://github.com/JingMatrix/libxposed-api.git (HEAD `39cac084`, 2026-08-01)
- https://github.com/JingMatrix/libxposed-service.git (HEAD `33189408`, 2026-06-13)
- https://github.com/JingMatrix/libxposed-example.git (HEAD `87e9cb8c`, 2025-11-08 — stale)
- https://github.com/XposedKit/template.git (HEAD `37fdb2a0`, 2026-10-16)
- https://github.com/luolong47/Lsposed-module-template.git (HEAD `7f865f60`, 2026-01-07)
- https://github.com/rk700/YAHFA.git (HEAD 2022-08-29)
- https://github.com/canyie/pine.git (HEAD 2025-11-08)
- https://github.com/ganyao114/SandHook.git (HEAD 2021-06-15)
- https://github.com/jmpews/Dobby.git (HEAD 2024-03-14)
- https://github.com/LSPosed/LSPlant.git (HEAD 2026-10-06)
- https://github.com/JingMatrix/LSPlant.git (HEAD 2026-10-01)
- https://github.com/LSPosed/LSPlt.git (HEAD 2025-03-26)
- **404 / GONE:** `LSPosed/LSPosed-Module-Template`, `libxposed/api`, `libxposed/service`,
  `libxposed/example`, `libxposed/helper`, `canyie/pine-xposed`

**raw.githubusercontent.com**
- https://raw.githubusercontent.com/JingMatrix/libxposed-api/master/README.md
- https://raw.githubusercontent.com/JingMatrix/libxposed-service/master/README.md
- https://raw.githubusercontent.com/JingMatrix/libxposed-service/master/interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl
- https://raw.githubusercontent.com/JingMatrix/Vector/master/README.md
- https://raw.githubusercontent.com/LSPosed/AndroidHiddenApiBypass/master/README.md
- https://raw.githubusercontent.com/LSPosed/LSPlant/master/README.md
- https://raw.githubusercontent.com/canyie/pine/master/README.md
- https://raw.githubusercontent.com/jmpews/Dobby/master/README.md
- https://raw.githubusercontent.com/rk700/YAHFA/master/README.md
- https://raw.githubusercontent.com/ganyao114/SandHook/master/README.md
- https://raw.githubusercontent.com/brain584/universal-debug-tool/main/README.md
- https://raw.githubusercontent.com/wiki/LSPosed/LSPosed/Develop-Xposed-Modules-Using-Modern-Xposed-API.md
- https://raw.githubusercontent.com/frida/frida/main/README.md

**Local source trees read (commit `ddeed8ca` unless noted)**
- `Vector/legacy/src/main/java/de/robv/android/xposed/{XposedBridge,XposedHelpers,XC_MethodHook,XposedInit,IXposedHook*}.java`
- `Vector/legacy/src/main/java/android/app/AndroidAppHelper.java`
- `Vector/legacy/README.md`
- `Vector/daemon/src/main/kotlin/org/matrix/vector/daemon/data/{FileSystem,ConfigCache,ModuleDatabase}.kt`
- `Vector/daemon/src/main/kotlin/org/matrix/vector/daemon/ipc/{ModuleAppService,ManagerService}.kt`
- `Vector/manager-ui/src/main/kotlin/org/matrix/vector/ui/module/ModuleDetection.kt`
- `Vector/manager/src/main/AndroidManifest.xml`, `.../data/repository/LaunchShortcut.kt`
- `Vector/zygisk/src/main/kotlin/org/matrix/vector/ParasiticManagerSystemHooker.kt`
- `Vector/zygisk/module/action.sh`, `Vector/zygisk/README.md`, `Vector/.gitmodules`, `Vector/build.gradle.kts`
- `Vector/xposed/src/main/kotlin/org/matrix/vector/impl/utils/VectorModuleClassLoader.kt`
- `Vector/hiddenapi/{bridge,stubs}/...`
- `libxposed-api/api/src/main/java/io/github/libxposed/api/{XposedModule,XposedInterface,XposedInterfaceWrapper,XposedModuleInterface}.java`
- `libxposed-api/api/build.gradle.kts`, `libxposed-api/gradle/libs.versions.toml`
- `libxposed-service/{service,interface}/src/main/...`
- `libxposed-example/app/src/main/java/io/github/libxposed/example/ModuleMain.kt`

**Maven Central**
- https://repo1.maven.org/maven2/io/github/libxposed/ (annotation, api, interface, lint, service)
- https://repo1.maven.org/maven2/io/github/libxposed/api/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/service/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/interface/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/annotation/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/libxposed/lint/maven-metadata.xml
- https://repo1.maven.org/maven2/org/lsposed/hiddenapibypass/hiddenapibypass/maven-metadata.xml
- https://repo1.maven.org/maven2/org/lsposed/lsplant/lsplant/maven-metadata.xml
- https://repo1.maven.org/maven2/org/lsposed/lsplant/lsplant-standalone/maven-metadata.xml
- https://repo1.maven.org/maven2/top/canyie/pine/core/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/pagalaxylab/yahfa/maven-metadata.xml
- https://repo1.maven.org/maven2/com/swift/sandhook/hooklib/ (**404**)
- https://repo1.maven.org/maven2/com/swift/sandhook/nativehook/ (**404**)
- https://search.maven.org/solrsearch/select?q=g:io.github.libxposed

**Frida**
- https://pypi.org/pypi/frida/json — latest **17.24.0**, uploaded 2026-10-16T14:33:24
- https://frida.re/news/ — 17.1.4 (2025-06-10) "most notably Android 16 support"; 17.1.5 (2025-06-13)
  "restores compatibility with Android 14–15"; Android gadget/asset and ART fixes through 17.24.0
- https://frida.re/docs/gadget/ — `libgadget.so` + `libgadget.config.so`; interaction/teardown/
  runtime/code_signing; Android `/lib` naming rules; `code_signing: required` disables Interceptor
- https://frida.re/docs/android/ — frida-server workflow
- https://github.com/frida/frida/issues/3700 — "Crash when overriding method using Frida Gadget 17.7
  on Android 16 (works on Android 12)"
- https://github.com/frida/frida/issues/3516 — "How to use Gadget on Android for Frida17 and above?"

**Android platform**
- https://developer.android.com/guide/practices/page-sizes — 16 KB page sizes; AOSP support from
  Android 15; Play requirement for `targetSdk >= 35` on 64-bit; verbatim: *"Starting February 1,
  2027, if your app updates don't support 16 KB memory page sizes, you won't be able to release
  these updates."*
- https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html
- https://android-developers.googleblog.com/2025/07/transition-to-16-kb-page-sizes-android-apps-games-android-studio.html
- https://android.googlesource.com/platform/external/elfutils.git/+/refs/heads/oreo-m4-s1-release/config/10-default-yama-scope.conf
- https://android.googlesource.com/platform/art/+/refs/tags/android-security-16.0.0_r4/runtime/runtime.cc
  (core-platform-API restriction referenced by the HiddenApiBypass README)
- https://api.xposed.info/ , https://api.xposed.info/using.html (legacy API docs; the latter is the
  URL cited by Vector's own "API classes are compiled into the module's APK" error)

**Local verification** (`pwsh`)
- `F:\Android\androidsdk\platforms\` → `android-30, android-34, android-36, android-36.1, android-37.0`
- `F:\Android\androidsdk\platforms\android-37.0\source.properties` → Android SDK Platform 17, API 37.0
- `F:\Android\androidsdk\build-tools\` → `33.0.1, 34.0.0, 35.0.0, 36.0.0, 36.1.0, 37.0.0`
- `F:\Android\androidsdk\ndk\` → `28.2.13676358`

**Method / confidence notes**
- GitHub's REST API was rate-limited (60/hr, unauthenticated) partway through; release lists were
  captured before exhaustion, and everything else was re-verified via `git ls-remote`/`git clone`
  and `raw.githubusercontent.com`, which are not subject to that limit. `api.github.com` repo
  `pushed_at` values were cross-checked against actual branch HEAD commits because they diverge
  (see §1.1).
- `web_fetch` could not reach `github.com`, `frida.re`, `zenn.dev` or `android-developers.googleblog.com`
  (hostnames resolved to non-public IPs in this environment). `frida.re` and
  `developer.android.com` were fetched successfully with `pwsh`/`Invoke-WebRequest`; `github.com`
  HTML was unreachable, so GitHub content came from the REST API, `raw.githubusercontent.com`, and
  `git`.
- Two claims I could **not** fully verify and am flagging rather than asserting: (a) the exact
  contents of Frida issues #3700 / #3516 (only titles and dates are confirmed — GitHub issue bodies
  were unreachable); (b) whether `JingMatrix/Vector` `master` has commits after `canary-3112`
  (repo `pushed_at` is 2026-10-12 but branch HEAD is 2026-10-01, most likely non-`master` branch
  pushes).
- `libxposed-example` in the `JingMatrix` mirror is **stale** and does not compile against API 102;
  do not treat it as a template. The official `LSPosed/LSPosed-Module-Template` no longer exists.
