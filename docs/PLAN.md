# RegTrail 项目策划书（定稿）

> 安卓逆向与实时分析工作台 · Root + LSPosed
> **对外包名：`io.github.wg1337.RegTrail`**（代码包名 `io.github.wg1337.regtrail`，见 §4.5.1）
> **兼容范围：仅支持 LSPosed v2.2.x**（不做多框架适配，见 §4.5.2）
> **技术栈：纯 Android + AndroidX Compose + Material 3**（不使用 Compose Multiplatform，见 §3.1）
> 版本：**v1.2** · 基于 2026-10-17 实测环境、官方发布包解剖与四路并行研究
> 配套资料：[版本矩阵](version-matrix-2026-10-17.md) · [LSPosed 生态报告](lsposed-ecosystem-2026.md) · [Root 与终端报告](ROOT_TERMINAL_RESEARCH_2026.md) · [APK 重打包报告](apk-repackaging-2026.md)

---

## 0. 定位与核心判断

RegTrail 是一个安卓逆向与实时分析工作台，由**两条运行路径 + 一个共享设置端**组成：

- **设置端 App**（AndroidX Compose + Material 3）：选目标、配色、注入编排、Agent、终端、实时调试、持久化打包。
- **注入端 Payload**（极小 dex + WebView 悬浮窗）：跑在**目标 App 进程内**，悬浮于所有 Activity 之上，
  并提供"进程内"对象/方法级访问能力。
- **两条运行路径**（都复用同一个 payload，见 §4.5.3）：
  - **模式 A（Root + LSPosed）**：项目的主路径。
  - **模式 B（免 Root 持久化包）**：需求 ④ 导出的重打包产物，供没有 Root 的人使用。

四个决定全局的核心判断：

1. **只支持 LSPosed**（已定）。框架目标是官方 **LSPosed v2.2.x**，现代 API 102。
   不做 Vector/其他 fork/旧版 Xposed 的兼容层，不做 Shizuku 降级档（详见 §4.5.2）。
2. **重留在设置端，轻注入目标端。** UI、Agent、终端、重打包都留在有 Root 的设置端；目标进程只放一个几十 KB 的 payload。理由：宿主 dex 方法引用池经常已满（实测 `xuelemod` 的 `classes.dex` 顶到 `method_ids_size=65536`），塞 Compose 必爆。
3. **免权限悬浮窗用一次 system_server Hook 解决**——**但只在模式 A 成立**。已从 AOSP 源码逐行确证（见 §2.1）：无需目标 App 持有 `SYSTEM_ALERT_WINDOW`，天然跨 Activity 常驻。模式 B 没有 system_server hook，必须换机制（见 §4.5.3）。
4. **实时调试以 Java 方法 Hook 为主力，native 寄存器级为可选插件。** 已确证 Java Hook **永远看不到 CPU 寄存器**；寄存器观测要另走硬件断点。这一点必须在设计里说清楚，不能含糊承诺。

---

## 1. 本机环境（已实测）

### 1.1 工具链（齐备）

| 组件 | 位置 | 版本 |
|---|---|---|
| Android SDK | `F:\Android\androidsdk` | platforms android-30/34/36/36.1/**37.0** |
| build-tools | 同上 | 33.0.1 / 34.0.0 / 35.0.0 / 36.0.0 / 36.1.0 / **37.0.0** |
| NDK | 同上 | **28.2.13676358 (r28c)**，含 llvm `windows-x86_64` |
| JDK 21 | `C:\jdk21`（另 `C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot`） | 21.0.10 LTS |
| JDK 17 | `F:\Android\AndroidJDK` | 17.0.19 |
| Gradle 缓存 | `~\.gradle\wrapper\dists` | **9.4.1 / 9.6.1**（另 `F:\Android\gradle\wrapper\dists` 有 8.0/9.2.1/9.4.1） |
| Git / Python | `C:\Program Files\Git` · `C:\Python314` | 3.14.4 |
| Android Studio | `F:\Android\AS`（JBR = JDK 25） | 可用 |

**依赖缓存已预热**（158 个 group）：`com.android.tools.build` 8.13.0/9.2.1/9.3.1/**9.4.1**、`org.jetbrains.kotlin` 2.2.10/2.3.0/2.3.21、`androidx.compose.material3:material3:1.4.0`、`org.jetbrains.compose.*`。

### 1.2 缺口（**已于 P0 补齐**）

- ✅ **CMake 已装**：`F:\Android\androidsdk\cmake\3.31.6`（P3 需要 JNI 时可用）。
- ✅ **cmdline-tools 已装**：`F:\Android\androidsdk\cmdline-tools\latest`（`sdkmanager` 19.0 可用）。
- ⚠️ 设备 `5xiv45nv4d7tjb8l` 处于 `unauthorized`。**用户明确表示不需要 adb 调试**
  → 设备信息改为**由 App 内的「环境自检」页面自报**（已实现，见 §6 P0）。
- ℹ️ `sdkmanager` 需要 JDK 17+（本机 PATH 上的 `java` 是 Java 8）→ 调用前须设
  `JAVA_HOME=C:\jdk21`。

### 1.2b P0 实测修正（写进构建脚本的坑）

以下几处与"照文档写"不同，均已在真实构建中踩到并修正：

1. **AGP 9 的 `CommonExtension` 不再接受泛型参数** → 根构建脚本里那种
   `extensions.configure<CommonExtension<*,*,*,*,*,*>>` 的"共享配置"写法编译不过。
   改为**每个模块各自声明** `compileSdk` / `buildToolsVersion` / `minSdk` / Java 级别。
2. **类型安全项目访问器需显式开启** → `settings.gradle.kts` 里必须有
   `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`，否则 `projects.shared` 无法解析。
3. **`buildConfig` 默认关闭**（AGP 9）→ 需要 `BuildConfig.APPLICATION_ID` 的模块必须显式开启。
4. **`implementation` 依赖不会传递** → `:shared` 把 `kotlinx-serialization-json` 声明为
   `implementation`，`:app` 里就要再声明一次，否则 `Json { }` 无法解析。
5. **`TabRow` 在 Material3 1.4.0 已废弃** → 用 `PrimaryTabRow` / `SecondaryTabRow`。
6. 实测结果：`clean :app:assembleDebug` **41 秒**、**0 warning**，产物
   `app-debug.apk`（18.44 MB，debug 未裁剪）；`aapt2 dump badging` 确认
   `package='io.github.wg1337.RegTrail'`、`minSdk 27` / `targetSdk 36` / `compileSdk 37`。

### 1.3 可继承的既有资产（重要）

`F:\Android\AndroidDev\project\xuelemod` 已有一套**跑通的离线 APK 注入流水线**，经验直接继承：

```
apktool d → 改 smali/Java → javac + d8 → 注为 classesN.dex → zipalign -f 4 → apksigner(v1/v2/v3)
```

已实测的坑（务必写进本项目规范）：

1. `apktool b -f -j 1` —— **`-j 1` 必需**，否则并发解析器竞态。
2. `d8` 用 **build-tools 36 的 `lib/d8.jar` + JDK17+** 直接 `java -cp` 调用；build-tools 33 的 `d8.bat` 在系统 Java 8 下触发 R8 内部 NPE。
3. **方法引用池可满**：凡 `method_ids_size` 已达 65536 的 dex **不能新增任何方法引用** → 注入代码改用 `sget/sput` 读静态字段；新 payload **必须独立成新 `classesN.dex`**（新 dex 天然有余量）。
4. 已验证可用的注入时机：`Application`/`LoginActivity.onCreate`、`SettingActivity.initViews`、`MainActivity.onResume`、任意方法入口 return 覆盖。
5. 既有 `xlmod.keystore` + `sign_config.py`（ECDSA P-256 签名校验）可复用为脚本签名的参考实现。

---

## 2. 关键技术验证（已确证，非推测）

### 2.1 ★ 免权限、跨 Activity 常驻悬浮窗

已下载 AOSP `PhoneWindowManager.java`（android14 / android15 / android16 三个 release 分支）与 `WindowManagerService.java` 逐行核对：

```
WindowManagerService.addWindow(...)
  └─ mPolicy.checkAddPermission(attrs.type, ..., appOp)   // ★ 服务端唯一闸门
       if (res != ADD_OKAY) return res;
```

`PhoneWindowManager.checkAddPermission(...)`（A14 L2695 / A15 L3066 / A16 L3256，**签名三版一致**）：

```java
if (type < FIRST_SYSTEM_WINDOW || type > LAST_SYSTEM_WINDOW) return ADD_OKAY;  // 应用级窗口放行
if (!isSystemAlertWindowType(type)) { /* TYPE_TOAST 等分支 */ }
// ↓ TYPE_APPLICATION_OVERLAY 落到这里
outAppOp[0] = OP_SYSTEM_ALERT_WINDOW;
if (mContext.checkCallingOrSelfPermission(SYSTEM_APPLICATION_OVERLAY) == PERMISSION_GRANTED)
    return ADD_OKAY;                       // 系统签名权限，普通 App 无
final int mode = mAppOpsManager.noteOpNoThrow(outAppOp[0], callingUid, packageName, ...);
switch (mode) {
    case MODE_ALLOWED:
    case MODE_IGNORED:  return ADD_OKAY;   // ★ 只要不是 ERRORED 就放行
    ...
}
```

**做法**：Hook `com.android.server.policy.PhoneWindowManager#checkAddPermission`，
当 `type == TYPE_APPLICATION_OVERLAY` 且 `packageName` 属于被注入的宿主包时，
把 `outAppOp[0]` 置为 **`OP_NONE`** 并 `setResult(ADD_OKAY)` 直接返回。
`OP_NONE` 让窗口**不被 appop 隐藏**（若只改 mode 会因 `AppOpsManager.MODE_IGNORED` 让 `WindowState` 把 opacity 置 0 而不可见）。

- 客户端无校验：A14 `WindowManagerGlobal.java` 中**无** `SYSTEM_ALERT_WINDOW`/`AppOps`/`checkPermission`。
- 添加窗口时用 `Token.TOKEN_INVALID` → WMS 走 `attrs.token = null` 分支按顶层窗口处理 → **天然跨 Activity 常驻**。
- 风险：部分 ROM（MIUI/HyperOS/ColorOS）自改 `PhoneWindowManager`。降级链：
  **B** 挂到当前 Activity `android.R.id.content`（随 Activity 重挂）；**C** Hook `ActivityThread.performLaunchActivity` 捕获 `ActivityClientRecord.token`，用 `TYPE_APPLICATION_OVERLAY + 该 token` 作子窗口。

### 2.2 ★ 目标框架：原版 **LSPosed v2.2.1 (7912)**（**已实测发布包，纠正早期误判**）

> ⚠️ **本节经过一次重大更正。** 早期研究只看 GitHub 的 `master` 分支与 `releases/latest` 接口，
> 得出"LSPosed 已死"的结论。**用户提供的官方发布包证伪了这一点**——LSPosed 仍在积极更新，
> 只是分发渠道转移到了 Telegram + `lsposed.zip`，GitHub 仓库不再是发布入口。

**实测对象**：`H:\telegram desktop\LSPosed-v2.2.1-7912-release.zip`
（6.13 MB，SHA-256 `ED37DE2D…11E74`，2026-10-17）

`module.prop` 原文（**权威**）：

```properties
id=zygisk_lsposed
name=LSPosed
version=v2.2.1 (7912)
versionCode=7912
author=LSPosed Developers
description=Another enhanced implementation of Xposed Framework. Supports Android 9 ~ 17. Requires Zygisk enabled.
updateJson=https://lsposed.zip/update.json
actionIcon=launcher.png
```

**从真实制品中确证的事实**：

| 事实 | 证据 | 对项目的影响 |
|---|---|---|
| **框架活着**：v2.2.1 (7912)，**支持 Android 9 ~ 17**，要求启用 **Zygisk** | `module.prop` + `README.md` | ✅ 正常针对它开发 |
| **Manager 包名仍是 `org.lsposed.manager`** | 直接解析 `manager.apk` 的 binary `AndroidManifest.xml`：`package = org.lsposed.manager`、`versionName = 2.2.1`、`versionCode = 0x1ee8 = 7912` | ✅ **早期研究说的 `org.matrix.vector.manager` 是错的** —— 那是某个 fork 的改名 |
| **现代 API = `io.github.libxposed`**，`framework.dex` 里含完整 `io/github/libxposed/api/XposedModule`、`XposedInterface$Chain`、`$Hooker`、`$HookBuilder`、`$HookHandle`、`$CtorInvoker`、`$ExceptionMode`、`XposedModuleInterface$PackageLoadedParam` 等 | 对 `framework.dex` 逐字符串扫描 | 用现代 API |
| Maven Central 上 `io.github.libxposed:api` 现有 **101.0.0 / 101.0.1 / 102.0.0**（最新 **102.0.0**）；`service` / `interface` 同为 102.0.0；`annotation` 1.0.0；**`helper` 不存在** | `maven-metadata.xml` 原始响应 | 定版 **102.0.0** |
| **现代 API 与 legacy 并存** | `framework.dex` 同时含 `de/robv/android/xposed/IXposedHookLoadPackage`、`IXposedHookZygoteInit`、`IXposedHookInitPackageResources`、`IXposedHookCmdInit` | 双代 API 都可用；新项目用现代 API |
| 官方**新增 `lspctl` 命令行工具** | `lspctl`（shell wrapper → `org.lsposed.lspd.cli.CliMain`）；`daemon.apk` 内含 `CliCommand$Module`、`CliCommand$HookDebug` / `HookDebugEnable` / `HookDebugDisable` / `HookDebugDump` 等命令类 | ⚠️ 见下方"重要警告" |
| 模块安装路径 `/data/adb/modules/zygisk_lsposed/`；KernelSU/APatch 下会把 `lspctl` 链接进 root `PATH` | `customize.sh` / `uninstall.sh` | 自检页可据此探测 |
| 官方 manager 有**在线许可请求**步骤 | `customize.sh` 里 `sign_tool -l package.pb /data/adb/lspd/license.pb`（离线设备需 `skip_online_request`） | 安装 LSPosed 本身需要能联网要许可 |
| 支持 ABI：**arm64-v8a / armeabi-v7a / x86_64 / riscv64**（含 riscv64！） | zip 内 `lib/`、`bin/` 四个 ABI 目录 | 自检页按 ABI 校验 |

⚠️ **重要警告：不要用 `lspctl` 做作用域管理。** 官方 README 原文：

> `lspctl` is an interactive debugging tool for humans and AI agents working from a computer,
> **not a stable control protocol**. Its commands, options, output, exit codes, and daemon
> communication protocol may change without notice. **Do not build apps or unattended
> integrations against it.**
>
> Every command requires UID 0. **Mutation commands are additionally restricted to root shells
> originating from ADB**; this is a deliberate barrier against rooted Xposed apps using the
> supported CLI to rewrite their own scope.

→ 双重原因不用它：(1) 官方明确禁止 app 集成，协议随时变；
(2) **变更类命令只接受来自 ADB 的 root shell**，我们的 App/模块发起的 root 调用会被拒。
→ 作用域仍走 §2.3 的 **`XposedService.requestScope()`**（官方受支持通道）。

**仍然成立的既有结论**（来自源码分析，未受本次更正影响）：

| 事实 | 结论 |
|---|---|
| `libxposed` GitHub 组织**被 GitHub 封禁** | 官方 README 确认："can download … from the Maven Central Repository" → **只从 Maven 取** |
| `LSPosed/LSPosed-Module-Template` **已 404**；社区 `luolong47` 模板 ship `targetApiVersion=100` | ⚠️ **API 100 已被拒绝加载**；不要抄现成模板 |
| `targetApi >= 101` → MODERN（`META-INF/xposed/java_init.list`），否则回落 `assets/xposed_init` → LEGACY | 新项目用 **API 102 + `META-INF/xposed/*`** |
| `XposedHelpers.setHiddenApiExemptions` **已移除** | 改用 `org.lsposed.hiddenapibypass:hiddenapibypass:6.2` |
| `android:debuggable` / `sharedUserId` / `<meta-data name="xposedmodule">` **均不需要** | 简化 manifest |
| **API 类必须 `compileOnly`**，否则框架**拒绝加载**模块 | 构建期加校验 |
| `staticScope=true` 在 daemon 层强制，`requestScope()` 也无法越界 | 本项目**不要**设 `staticScope`（要允许用户自由选目标） |

> 关于 `JingMatrix/Vector`：它是同名生态里的一个 fork（把包名改成了 `org.matrix.vector.*`）。
> 本项目**以官方 LSPosed 为准**；若日后要兼容 Vector，只需注意包名差异与 `module.prop` 的 `staticScope` 语义。
> 这不影响 `META-INF/xposed/*` 模块契约（现代 API 是跨实现的标准）。

**模块契约（现代 API 102）**，文件放 `src/main/resources/META-INF/xposed/`：

| 文件 | 内容 |
|---|---|
| `java_init.list` | 入口类全限定名，一行一个，`#` 可注释 |
| `native_init.list` | native 库名 |
| `scope.list` | 包名一行一个；**`system` 代表 system_server** |
| `module.prop` | Java Properties：`minApiVersion` / `targetApiVersion` / `staticScope` / `autoHotReload` / `exceptionMode` |

⚠️ **API 类必须 `compileOnly`** —— 框架会主动拒绝加载把 API 打进 APK 的模块
（`"The Xposed API classes are compiled into the module's APK."` → `return false`）。
⚠️ **一个 APK 只能选一代 API** —— API 102 模块对 `de.robv.*` 抛 `ClassNotFoundException`。
⚠️ **`staticScope=true` 在 daemon 层强制**，`requestScope()` 也无法越界。

### 2.3 ★ 作用域可编程申请（直接命中需求「可唤起 LSP 开启作用域」）

- **没有**"打开某包作用域页"的 deep link（已全树搜索确认）。想"唤起 LSPosed"只能启动 Manager：
  包名 **`org.lsposed.manager`**（已从 `manager.apk` 实测确证）；框架的 `action.sh` 走的是
  秘码广播 `am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android`。
  → 实现时两种都试（发秘码广播 / 直接 `am start` manager），以真机为准。
- **正确做法（作用域本身）**：用现代 service API 申请作用域，框架弹**一次**通知提示（1 分钟超时）：

```java
// io.github.libxposed:service:102.0.0
XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
    @Override public void onServiceBind(XposedService service) {
        service.requestScope(List.of(targetPkg), new XposedService.OnScopeEventListener() {
            @Override public void onScopeRequestApproved(List<String> approved) { /* 生效 */ }
            @Override public void onScopeRequestFailed(String message) { /* 提示用户 */ }
        });
    }
    @Override public void onServiceDied(XposedService service) { }
});
```

语义：整表一次询问；会自动排序去重；空请求自动通过；用户可屏蔽模块（此后报
`"Scope request blocked by user configuration"`）。

### 2.4 实时调试的能力边界（**必须诚实**）

| 能力 | 手段 | 可行性 |
|---|---|---|
| 搜索类/方法、看/改**方法参数与返回值**、抛异常 | `XposedInterface.hook(Executable)`（现代 API，OkHttp 风格 `Hooker.intercept(Chain)`） | ✅ **稳**，且**无需重编译模块**——传任意 `java.lang.reflect.Executable` 即可 |
| Hook **已加载**的类 | 同上 + `deoptimize(Executable)` 破内联 | ✅ |
| 搜索/修改**进程内存**（`/proc/<pid>/mem`） | Root 读写 + `/proc/<pid>/maps` | ✅ 但每次读是 IPC 绑定，适合定向读而非全堆扫描 |
| **CPU 寄存器**观测 | `perf_event_open` + `PERF_TYPE_BREAKPOINT`（内核写调试寄存器，**故意不打 sigtrap 位** → 不停目标、不 ptrace、无 SIGTRAP） | ⚠️ 可行但有限：**JNI 帧无法回溯到 Java 调用者**，部分内核返回零值 |
| **寄存器/指令修改** | Dobby（`JingMatrix/Dobby`，Apache-2.0，无稳定 Maven 制品）或 Frida 17.24.0 gadget | ⚠️ 重量级，列为可选"专业模式" |

**明确结论**：**Java Hook 永远看不到 CPU 寄存器**。项目对外表述用
"方法级实时调试（可搜/可看/可改返回值与参数）+ 进程内存读写 + 可选 native 插桩"，
不宣称"任意寄存器修改"。

引擎选型：**主用框架自带的 `hook(Executable)`**，不需要第三方引擎。
（YAHFA 2022 停更、SandHook 2021 停更且无 Maven 制品、Pine 卡在 Android 15 Beta 4 且是 Anti-996 许可 → 全部排除。）

## 2.10 ★ P1 真机验证记录（华为 AGS5Z-AL00 / Android 12 / APatch 1.12.2 / LSPosed 2.2.1）

首次在真实设备上端到端跑通。以下全部是**只有真机会暴露**的问题与结论，均已修复。

### 2.10.1 已验证可用的完整链路

```
RegTrail 设置端（root）
  ├─ 枚举 249 个已安装应用 → 过滤出 52 个可注入目标
  ├─ 发布配置 → /data/local/tmp/regtrail/config.json (0644)
  └─ requestScope([目标包]) → LSPosed 通知 → 批准 → 作用域生效
                    ↓
目标进程（LSPosed 注入）
  ├─ onModuleLoaded              ✓
  ├─ 读 config.json              ✓ targets=1, mechanism=DecorView
  ├─ Hook Application.attach     ✓ 拿到目标 Context
  ├─ DecorView 悬浮窗挂载         ✓ 见截图
  ├─ WebView UI（资源来自模块 APK）✓ 主题色 / CSS / JS 桥全部生效
  └─ Activity 生命周期追踪        ✓ LoginActivity 正常切换
```

截图证据：悬浮窗正常显示在 Aiclass 登录页之上，显示宿主包名、窗口机制、
框架版本、**API 102**、作用域，以及「刷新配置 / 隐藏」按钮。

### 2.10.2 真机暴露的 8 个 bug（全部已修）

| # | 问题 | 表现 | 根因 |
|---|---|---|---|
| 1 | 配置从未下发 | 悬浮窗永不出现且无报错 | `start()` 中 `scopeManager.register()` 抛异常会静默中断后续所有步骤；publish 失败只返回 Result 不打印 |
| 2 | libsu 缓存了非 root shell | 授权后仍报"无法获取 Root shell" | 终端抢先请求 root，APatch 提示未响应，libsu 缓存了无 root 的 shell。实测 `status=0`（ROOT_SHELL）但 `isRoot=false` |
| 3 | 回读配置总失败 | 实际已写入却报"不存在或为空" | 用 `(base64 FILE \|\| toybox base64 FILE)` 回读，实测返回空输出。改为 App 直接读文件（0644） |
| 4 | APatch 被误判为 Magisk | Root 管理器识别错误 | APatch 的 su 复用 `u:r:magisk:s0`；且 `ksud -V` 排在 `apd --version` 之前。改以 `su -v` 里的 `APatch` 为最高优先判据 |
| 5 | 应用列表为空 | 只有系统条目 | `ApplicationInfoFlags.of(0)` 是 **API 33+**，设备是 API 31，抛错被 `runCatching` 吞成空列表 |
| 6 | 华为额外隐私门 | 即使有 `QUERY_ALL_PACKAGES` 也只有 1 个应用 | EMUI 13 弹「是否允许读取已安装应用列表」，**批准后还需重启应用** |
| 7 | 模块被判"无启动 Activity"而跳过 | 模块加载了但不挂窗 | 手写的 `FLAG_HAS_ACTIVITIES = 1 shl 0` 其实是 `FLAG_SYSTEM`，且该常量并不公开。改为不做启发式判断 |
| 8 | 悬浮窗被宿主内容覆盖 | 已挂载但不可见 | 在 `onActivityCreated` 挂载早于宿主 `setContentView`，宿主视图后添加盖住了我们。改到 **`onActivityResumed`** + `bringToFront()` |

另有一个 UI 通用缺陷：Compose 只在首次组合时读一次平台状态，导致"Root 已授权但
横幅仍在"、"作用域批准后不刷新"。已改为 `stateRevision` 计数器 + 定期重读。

### 2.10.3 关键运维结论：模块 APK 内容变化才需要重启

实测细化（修正了最初的粗判）：

- LSPosed daemon 在开机时把模块 dex 载入内存，并**按 APK 路径**缓存。
- **重装内容相同的 APK**（`adb install -r` 同一份文件）→ 路径不变 → **不需要重启**，
  模块继续正常注入（已实测：重装后目标应用仍挂上悬浮窗）。
- **APK 内容变化**（重新构建）→ 包路径变化 → 注入链路仍持旧 dex →
  模块**不再加载且 `logcat -s LSPosedFramework` 无任何输出** → **必须重启设备**。

所以开发期改完模块代码后要重启；只是重装同一份包则不必。

### 2.10.4 CorePatch 验证（为 P6 持久化注入铺路）

P6 需要重签名后安装目标 APK，而默认情况下签名变化会被系统拒绝。为此安装并启用了
**CorePatch**（`org.lsposed.corepatch` 1.0，LSPosed 官方发布），作用域 `system`。

**行为验证**（而非仅看配置）：

1. 用一次性 keystore 重新签名本项目的 APK（证书 `CN=Test2`，与被安装的
   `CN=Android Debug` 不同）。
2. `adb install -r` 覆盖安装 → **Success**。
3. 对照：无 CorePatch 时该操作必然返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

→ **CorePatch 已正确注入 system_server 且签名校验绕过生效**，P6 的安装环节因此可用。

> 注意：LSPosed 管理器在本机未安装，因此启用 CorePatch 是通过
> 「停 daemon → 改 `modules_config.db`（`modules_state.enabled=1` +
> `scope='system'`）→ 修正 SELinux 上下文为 `system_file` → 重启」完成的，
> 原库已备份为 `modules_config.db.bak`。后续如需在 UI 里操作，应先装管理器。

### 2.10.5 APatch 的 Zygisk 事实（修正）

- APatch 没有 `magisk.db`，因此 `libsu.isAppGrantedRoot()` 在 APatch/KernelSU 上
  **恒为 false**（实测授权后仍 false，但 `shell.isRoot=true`）。
- APatch 的 Zygisk 由自身提供（`/data/adb/zygisksu`），与 Magisk 的
  `settings.zygisk` 无关。
- **不要用 SELinux 上下文判断 Root 管理器**：APatch 的 su 显示 `u:r:magisk:s0`。

### 2.10.6 华为设备注意事项

- `QUERY_ALL_PACKAGES` 在 EMUI 上还会被一层系统隐私开关拦截，
  首次调用 `getInstalledApplications` 时弹窗，**批准后须重启应用**。
  若枚举结果为 1，说明该开关未生效，而不是代码问题。
- **LSPosed 注入时 SystemUI 可能崩溃**（本机已复现）。这是设备/框架生态问题：
  任何作用域含 `system`（或 `com.android.systemui`）的模块出错都会拖垮 SystemUI。
  排查顺序：先在 LSPosed 里把其它模块的作用域收缩到最小，再逐步放开。
  RegTrail 自身**不需要 `system` 作用域**（默认 DecorView 机制），
  不要为了它去授权 `system`。

### 2.10.7 工程实践：不要用 PowerShell 的 Set-Content 改带中文的 .kt

本仓库的 Kotlin 源码含大量中文注释。用
`Get-Content -Raw` + `Set-Content` 做批量替换时，PowerShell 会按 ANSI(GBK)
回写，**把整个文件变成非法 UTF-8**，Kotlin 编译器随后报出完全无关的语法错误
（本次事故：`OverlayController.kt` 被写坏，编译器报第 134 行 `Expecting '"'`）。

- 改代码**只用编辑器工具**（精确字符串替换），不要用 shell 重写整文件。
- 若已损坏：按 `cp1252` 逆向映射回字节后再以 UTF-8 容错解码可救回
  ASCII 代码部分（本次仅 1 行注释受损）。但**这是补救，不是方案**。
- 项目当前**没有 git**，写坏就没有版本可回退——这也是为什么值得初始化仓库。

---

### 2.11 Root 与终端（已确证）
- **libsu 只在 JitPack**：`com.github.topjohnwu.libsu:core|service|nio:6.0.0`（Apache-2.0）。
  **无 `-ktx`、无 coroutine API、无 `libsu-extension`、无 `ShellService` 类**；
  常驻守护是 `com.topjohnwu.superuser.ipc.RootService` + `CATEGORY_DAEMON_MODE`。协程桥要自己写。
- **Shizuku 不提供 Root**：`Shizuku#getUid()` 为 0 仅当用户已用 root 启动它；ADB 档是 UID 2000，
  **且读不到 `/data/user/0/<pkg>`**。
  → **本项目已决定不引入 Shizuku**（见 §4.5.2）：需求是 Root + LSPosed 双条件，Shizuku 既给不了
  Root，也与"只支持 LSPosed"的范围界定冲突。
- **破除迷信**："Android 10+ 因 SELinux MLS 分类导致 root 读不了 `/data/data/<pkg>`" —— **对 Magisk 是假的**。
  Magisk 自身 sepolicy 有 `mlstrustedsubject` + `allow(..., all, all)` + `permissive(...)`，
  MLS 只卡非豁免域（如 ADB `shell` 域）。→ **不要**把 `chcon`/`restorecon` 当主路径（Magisk 上多余，stock 策略里是 `neverallow`，且重打标签会破坏目标 App）。
- **Root 检测**：不能用 `File("/system/bin/su").exists()`——KernelSU/APatch 在**内核里伪造** `su`（只 hook `faccessat`/`execve`），存在性检查可能为假但 exec 可用。
  正确做法：构造 Shell 后判 `shell.isRoot()`，再以 `su -v`（含 `:MAGISKSU`）、`magisk -v`、`ksud -V`、`apd --version` 及 `/data/adb/{magisk,ksu,ap}` 作为 UI 提示。
- **安装限制**：Android 14 拦 `targetSdk < 23`，**Android 15 抬到 24**
  （`INSTALL_FAILED_DEPRECATED_SDK_VERSION`，覆盖开关 `--bypass-low-target-sdk-block` = `0x01000000`）。
  ⚠️ **合规提示**：静默使用该开关等于绕过平台恶意软件防护，必须**显式告知用户 + 记审计日志**。
- **终端**：`termux-app` 是 GPLv3，但 `terminal-emulator` / `terminal-view` 两个模块有**明确的 Apache-2.0 例外**，
  且 `TerminalSession` / `JNI` / 整个 VT 模拟器都在其中
  （JitPack：`com.github.termux.termux-app:terminal-emulator|terminal-view:0.118.3`）。
  ⚠️ 残余风险：这些文件**无逐文件许可头** → 备选 **xterm.js 6.0.0（MIT）跑在 WebView**（许可最干净）。
- **PTY 关键细节**（Termux `termux.c` 真实做法，**不是 `forkpty`**）：
  `open("/dev/ptmx")` → `grantpt`/`unlockpt`/`ptsname_r` → 设 `IUTF8`、清 `IXON|IXOFF` → `TIOCSWINSZ`
  → `fork` → 子进程 `setsid()`、打开 pts、`dup2` 到 0/1/2、关 fd>2、**`clearenv()` + `putenv(env)` + `chdir(cwd)` + `execvp`**。
  → **`PATH` 必须显式放进 env 数组**，否则 `execvp("su")` 找不到（最常见的坑）。
  `JNI` 类是包私有的，必须用公开的 `TerminalSession(...)` 构造 + `initializeEmulator/updateSize/write` 驱动。
- **stock Android 没有 `script`**（这正是 Magisk 终端要调自己 busybox 的原因），
  也**没有 `iptables`/`ip`/`strace`** → 工作区隔离**不能**基于 `iptables -m owner`。
  有 `unshare`/`nsenter`/`chroot`/`setenforce`/`chcon`/`ls -Z`/`runcon`。
- **busybox 不必打进 APK**：直接复用管理器自带的
  （Magisk `/data/adb/magisk/busybox`、KernelSU `/data/adb/ksu/bin/busybox`，均 busybox 1.36.1）→ 零体积、无 GPL 再分发义务。
- **`run-as` 需要目标可调试** → 不是通用的跨应用读取原语；跨应用数据读靠 **root**。

---

### 2.5 ★ P1 实测确认（模块声明与注入机制落地后）

从官方发布包与 `:app` 的真实构建产物中确认的补充事实：

| 事实 | 证据 | 影响 |
|---|---|---|
| **框架中没有 `AssetsUtils`**（对 `framework.dex` 全字符串扫描 0 命中） | 官方包 `framework.dex` | system_server 里**无法读模块自己的 assets** → 悬浮窗 UI 资源必须由**目标应用进程**自己从模块 APK 读取（`getModuleApplicationInfo().sourceDir` 开 ZipFile），不能指望框架代劳 |
| `XposedInterface` 提供 `getModuleApplicationInfo()` / `getRemotePreferences()` / `listRemoteFiles()` / `openRemoteFile()` | `io.github.libxposed:api:102.0.0` 的 `javap` 输出 | 模块能安全拿到**自己的 APK 路径**与框架托管的远端 prefs，无需反射猜 |
| `XposedModule` 是**无参抽象类**，生命周期回调全部是 `default` 方法 | 同上 | 入口类写法简单；**未覆写 `onPackageReady` 也能正常工作** |
| `io.github.libxposed:service` 的 AAR **自带 provider 声明**，authority 为 `${applicationId}.XposedService` | 解压 AAR 的 `AndroidManifest.xml` | 依赖一加，provider 自动合并；实测本机为 `io.github.wg1337.RegTrail.XposedService` |
| `XposedServiceHelper.registerListener(...)` 是**静态方法**，绑定后拿到 `XposedService` | `javap` 输出 | 设置端申请作用域、枚举运行中目标走它，不需要 AIDL |
| `HookedTarget` 只有 `getUid/getPid/getProcessName/getState/getLoadedVersionCode` | 同上 | "当前被注入的应用"只能拿到这些维度 |
| **APatch 下 Zygisk 不存在 magisk.db 开关** | 用户实测（APatch 1.12.2）：Zygisk 状态本来就读不到 | 探测改为三态 + 以"LSPosed 模块目录存在且带版本号"作为注入链路可用的证据，而不是报 `unknown` 吓用户 |

> **模块不可被识别的根因（已修复）**：现代 API 模块的识别**完全依赖 APK 内的
> `META-INF/xposed/` 目录**。本项目 P0 阶段只有 `<category MODULE_SETTINGS>` 与
> `xposedminversion` 之类的传统痕迹——而框架**根本不读 `xposedmodule`**，也**不把
> `<category>` 当作模块声明**（它只决定管理器是否提供"打开模块设置"入口）。
> 必须同时具备：
> - `META-INF/xposed/java_init.list`（入口类）
> - `META-INF/xposed/module.prop`（`targetApiVersion >= 101`，**100 会被直接拒绝**）
>
> 两者缺一，框架的 `loadModule()` 判定为 `NONE`，模块不会出现在管理器列表里。

### 2.6 悬浮窗注入的落地选择（P1 实现）

- **窗口**：`TYPE_APPLICATION_OVERLAY` + **`token = null`** → WMS 用客户端 binder 建 token，
  窗口成为**应用级**（跨 Activity 常驻）。权限由 §2.1 的 Hook 放行。
- **UI**：WebView，页面用 `https://regtrail.local/` 这个**虚拟 origin**，
  由 `WebViewClient.shouldInterceptRequest` 从模块 APK 的 `assets/regtrail/` 直接喂给 WebView
  （真实 origin 才能让 `localStorage`、脚本加载、CSP 正常；同时**零网络**，非白名单请求一律 403）。
- **主题**：设置端把 `overlayPrimary` 等以 JSON 下发；页面把它们写成 CSS 变量
  （`--regtrail-primary` / `--regtrail-font-scale` / `--regtrail-alpha` / `--regtrail-corner`），
  因此换色换字号**不需要改任何 native 代码**。
- **为什么不用 `Context.getAssets()`**：模块的 class 由框架从内存 dex 加载，
  没有任何 `Context` 拥有我们的 APK，模块 classloader 也没有 APK 路径。
  必须走 `getModuleApplicationInfo().sourceDir` 自己开 zip（见 §2.6 第 1 行）。

### 2.7 设置端 ↔ 注入端的数据通道（P1 实现）

**结论：用 root 写一个世界可读的 JSON 文件，而不是 AIDL/bindService。**

理由：模块所在的第三方进程既读不到设置端的私有目录，设置端也拿不到指向它的 Binder 句柄；
两侧唯一共同拥有的是 **root shell**。

```
设置端（root）──写入──▶ /data/local/tmp/regtrail/config.json (0644, 原子 mv)
目标进程模块 ──读取──▶ 只在 mtime/size 变化时重新解析
```

- base64 编码后交给 shell 解码（`base64 -d` → 回退 `toybox base64 -d`），
  **payload 永不经过 shell 解析**，规避所有引号/转义风险。
- 写入后**回读校验**，不信任退出码。
- 好处：不依赖设置端常驻、不依赖框架服务、**导出的免 Root 包可以复用同一条路径**。

## 3. 版本矩阵（已确认，构建前请复核）

### 3.1 ★ 已放弃 KMP → AGP 上限解除（v1.2 变更）

**决策：本项目是纯 Android 软件，不做 desktop/web 目标，因此彻底不用 Kotlin Multiplatform。**
`:shared` 退化为普通 `com.android.library`。

这条决策**解除了一个硬性版本枷锁**。Kotlin 官方兼容表原本把 KMP 项目卡住：

| Kotlin | 支持 Gradle | 支持 AGP | 说明 |
|---|---|---|---|
| 2.4.20 – 2.4.21 | 7.6.3 – 9.7.0 | 8.5.2 – **9.3.1** | ⚠️ **这是 KMP 的约束** |
| 2.4.20 – 2.4.21（非 KMP 的普通 Android 项目） | AGP 自带的 Gradle/AGP 组合即可 | **9.4.1 可用** | ✅ 本项目走这条 |

→ **定版：**

```
Gradle 9.6.1（本机已缓存） + AGP 9.4.1（本机依赖缓存里已有） + Kotlin 2.4.21
```

- AGP 9.4 要求 Gradle **≥ 9.6.0** → 9.6.1 满足（且**已在 `~\.gradle\wrapper\dists` 缓存**，免下载）。
- AGP **9.4.1 已在依赖缓存** `com.android.tools.build:gradle` 中（缓存里有 8.13.0 / 9.2.1 / 9.3.1 / **9.4.1**）。
- 不再需要 `com.android.kotlin.multiplatform.library` 插件，`settings.gradle.kts` 与根构建脚本随之简化。

> ⚠️ 但 **AGP 9 的其余硬约束仍然适用**（见 §3.4）——尤其"**禁止 `org.jetbrains.kotlin.android`**"
> 与"**`buildConfig` 默认关闭**"这两条，与是否用 KMP 无关。

### 3.2 完整选型

| 项 | 版本 | 备注 |
|---|---|---|
| Gradle | **9.6.1** | 已缓存 |
| AGP | **9.4.1** | 已缓存；KMP 移除后不再受 9.3.1 上限约束 |
| Kotlin | **2.4.21** | Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` 必须同版 |
| KSP | 2.3.12 | 注意：已改为纯 semver（不再是 `<kotlin>-<ksp>`）。**P0 若不用注解处理器可暂不引入** |
| Compose BOM | **2026.09.00** | → runtime/ui/foundation 1.12.1，**material3 1.4.0 稳定** |
| Material 3 | **androidx.compose.material3:material3:1.4.0** | ⚠️ 见 §3.3 |
| ~~Compose Multiplatform~~ | **不使用** | 见 §3.1、§3.3 |
| Xposed API | **`io.github.libxposed:api:102.0.0`** | **`compileOnly`**，Maven Central |
| libxposed service | `io.github.libxposed:service:102.0.0` | 提供 `${applicationId}.XposedService` provider |
| hiddenapibypass | `org.lsposed.hiddenapibypass:hiddenapibypass:6.2` | Android 1.0–17 |
| libsu | `com.github.topjohnwu.libsu:core|service|nio:6.0.0` | **仅 JitPack** |
| Termux 终端 | `com.github.termux.termux-app:terminal-emulator|terminal-view:0.118.3` | Apache-2.0 例外；**P3 才引入** |
| OpenAI 兼容 | `com.aallam.openai:openai-client:4.1.0` | 可指向 OpenAI/OpenRouter/vLLM/Ollama；**P5 才引入** |
| 协程/序列化 | kotlinx-coroutines **1.11.0**、serialization-json **1.11.0** | |
| 配色 | `com.materialkolor:material-color-utilities:6.0.0` | **Compose-free**，用于 Material You 取色（可选） |
| NDK | **28.2.13676358** | ⚠️ sysroot 上限 **API 35**；需要 36/37 符号则装 `ndk;30.0.16248370` |
| CMake | **3.31.6** | 需先装 `cmdline-tools`；**P3 才用到** |
| compileSdk / targetSdk | **37 / 36** | android-37.0 已装且 `IsBaseSdk=true`、`PreviewSdkInt=0` → 正式版 |
| minSdk | **27** | LSPosed 下限（Android 9.0 起；官方支持 9 ~ 17） |
| Java | **21** | AGP 最低 17 |

### 3.3 为什么 Android UI 不用 CMP 的 Material3（历史结论，保留备查）

即使当初考虑 CMP 时，其 Material3 也不可用，这独立于 §3.1 的决策：

- CMP 1.12.2 的 `compose.material3` → **`1.12.0-alpha03`** → 其 Android 变体依赖 **`androidx.compose.material3:1.5.0-alpha22`**。
- `org.jetbrains.compose.material3:material3-android` **从未有过稳定版**（只有 1.12.0-alpha03 / 1.13.0-alpha0x）。
- **Gradle 取最高版**：`1.5.0-alpha22 > 1.4.0` → 只要 CMP M3 在 classpath 上，pin 的稳定版**也会被静默升级成 alpha**。

→ **结论：UI 全部用 AndroidX Compose + Material 3 稳定版（BOM 2026.09.00，m3 1.4.0）**，
可享 Material You `dynamicLightColorScheme`（API 31+），并以 `material-color-utilities` 做降级取色。

### 3.4 AGP 9 的硬性约束（不遵守就构建不过）

1. **`org.jetbrains.kotlin.android` 在 AGP 9 上是错误**（`org.jetbrains.kotlin.multiplatform` 同理已不适用）。
   Kotlin 由 **AGP 内置**提供；版本靠**根 `build.gradle.kts` 里
   `alias(libs.plugins.kotlin.compose) apply false`（及 kotlin 插件）apply false** 钉住 buildscript classpath，
   否则会报 "already on the classpath with an unknown version"。
2. ~~AGP-KMP 插件相关约束~~ —— **已随 KMP 移除而失效**（原约束：KMP 模块必须用
   `com.android.kotlin.multiplatform.library`、DSL 为 `kotlin { android { } }`、源码在 `src/androidMain`、
   且该插件**不支持 `externalNativeBuild`**）。现在 `:native` 与 `:shared` 都是普通
   `com.android.library`，**native 模块的限制一并消失**。
3. **`buildConfig` 默认关闭**（`android.defaults.buildfeatures.buildconfig` 属性已在 AGP 9 移除）
   → 需要 `BuildConfig` 的模块必须显式 `buildFeatures { buildConfig = true }`。
4. R8 规则必须含 **`-adaptresourcefilecontents META-INF/xposed/java_init.list`**
   （否则混淆后入口类名不同步 → 模块加载失败）。
5. `getDefaultProguardFile("proguard-android.txt")` 已不可用，只能用 `proguard-android-optimize.txt`。
6. `packaging { resources { merges += "META-INF/xposed/*" } }` —— 保证 `META-INF/xposed/*` 进入 APK 根。

---

## 4. 架构设计

### 4.1 进程与职责

```
┌──────────── 设置端 App「RegTrail」进程（io.github.wg1337.RegTrail）────────────┐
│  AndroidX Compose + Material 3（稳定版）                                      │
│  ① 总览  ② 目标选择+作用域  ③ 外观/配色  ④ 实时调试                           │
│  ⑤ 终端  ⑥ Agent  ⑦ 持久化注入  ⑧ 环境自检                                  │
│  特权层：libsu RootService 常驻 · APK 重打包流水线 · 脚本库                    │
└───────────────────────────────┬───────────────────────────────────────────────┘
        AIDL bindService         │ ← 配置下发 / 调试指令 / 日志回传(oneway)
   action: io.github.wg1337.RegTrail.action.BRIDGE
┌───────────────────────────────┴────────── 目标 App 进程 ─────────────────────┐
│  RegTrail Payload（极小 dex，纯 Java，无 Compose/AndroidX，两条路径共用）      │
│  ├─ 入口：模式 A = XposedModule（现代 API 102）                               │
│  │        模式 B = stub 反射调用同一静态入口                                  │
│  ├─ 窗口（★两模式不同，见 §4.5.3）：                                          │
│  │    模式 A：Hook system_server 的 PhoneWindowManager#checkAddPermission      │
│  │            → TYPE_APPLICATION_OVERLAY + OP_NONE → 免权限、跨 Activity      │
│  │    模式 B：挂 Activity DecorView + registerActivityLifecycleCallbacks 重挂  │
│  ├─ 视图：WebView（UI 资源随 patch 打包，本地加载）+ JsBridge                  │
│  ├─ 桥接：进程内反射桥（对象图/字段/方法/Hook 注册）                           │
│  └─ 生命周期：Application 启动即拉起，Activity 变化不影响窗口                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

**为什么注入端用 WebView？** 见 §0 判断 1：宿主 dex 池常满 → payload 必须极小。
WebView 方案 payload dex 仅几十 KB；UI 用 HTML/CSS/JS，终端（xterm.js）、日志、表格极省力，渲染一致。

### 4.2 Gradle 模块布局（**纯 Android，无 KMP**）

```
RegTrail/
├── settings.gradle.kts        # google() + mavenCentral() + jitpack.io
├── build.gradle.kts           # AGP 9 内置 Kotlin 的 classpath 钉版（apply false）
├── gradle/libs.versions.toml  # 版本目录（唯一真相源）
├── gradle/wrapper/            # gradle-9.6.1-bin
├── shared/    com.android.library
│              → 数据模型 / 配置 schema / 主题色 / 脚本 DTO / AIDL 契约
├── native/    com.android.library + externalNativeBuild/CMake + ndkVersion
│              → JNI：PTY · /proc/pid/mem · 可选 native 插桩   （P3 起启用）
├── xposed/    com.android.library
│              → XposedModule 实现（现代 API 102）+ src/main/resources/META-INF/xposed/*
│                + compileOnly(io.github.libxposed:api)
├── ui/        com.android.library
│              → AndroidX Compose + Material 3、主题、选色盘、各功能页面
└── app/       com.android.application
               → Activity / Application / DI / 特权层 / 打包流水线
```

**依赖方向（严格单向，避免循环）**：

```
app ──▶ ui ──▶ shared
 │                ▲
 ├──▶ xposed ─────┘        (xposed 只用 shared 的契约 + compileOnly 的 libxposed api)
 └──▶ native               (app 直接依赖 native；native 不依赖任何本项目模块)
```

要点：
- **先建 `:app` + `:ui` + `:shared` 三个模块即可让 P0 跑起来**；
  `:xposed`（P1）与 `:native`（P3）按里程碑增量加入，避免一开始就背上用不到的复杂度。
- `:xposed` 与 `:app` **保持在同一个 APK 里**（折叠为库模块被 `:app` 消费）：
  模块就是设置端本身，这样一个 APK 既是 LSPosed 模块又是设置界面，
  也才能拿到 `${applicationId}.XposedService` 这个 provider。
- 本机已缓存的 `androidx.compose.material3:material3:1.4.0` 会被 `:ui` 使用，**首次构建可省一次下载**。

### 4.3 IPC

- **设置端 ↔ 注入端**：`bindService` + AIDL。
  - 下行：`applyConfig(themeJson, fontSize, panels)` / `debugCommand(...)` / `execHook(scriptId)`
  - 上行：`oneway onLog(entry)` / `onHookHit(...)` / `onDebugResult(txnId, payload)`
  - 大数据（截图/大对象）**走共享文件 + 传路径**，避开 1MB Binder 事务上限。
- **降级**：设置端未运行时，注入端仍以「打包内嵌配置 + 内嵌 UI 资源」独立工作。

### 4.4 悬浮窗 UI 资源如何进目标进程

WebView 需要一个 URL。三种方案，**推荐按序降级**：

1. **随 patch 打包进 assets** + 自建极简 `ServerSocket` HTTP 服务（保证导出包自包含，无外部依赖）。
2. `WebViewAssetLoader`（`androidx.webkit`）—— 更省事的本地域，但会给宿主引入 AndroidX webkit 依赖。
3. 由设置端在 `127.0.0.1` 上服务（payload 最小，但需要设置端常驻）。

> 目标：**导出的免 Root 包必须自包含** → 默认方案 1。

### 4.5 ★ 身份与兼容范围（已定，影响全局）

#### 4.5.1 包名 / applicationId

**用户指定：`io.github.wg1337.RegTrail`。** 采用"`namespace` 小写 + `applicationId` 保留你的写法"方案
（两者本就可以不同，这是 Android 官方支持的常规做法）：

```kotlin
android {
    namespace = "io.github.wg1337.regtrail"        // 代码包名（Java/Kotlin package，全小写符合规范）
    defaultConfig {
        applicationId = "io.github.wg1337.RegTrail" // ★ 用户指定，对外身份，安装后包名即此
    }
}
```

**连带影响（必须同步，否则功能静默失效）**：

| 项 | 值 | 说明 |
|---|---|---|
| **APK 安装后包名** | `io.github.wg1337.RegTrail` | 用户在系统设置里看到、以及 LSPosed 作用域列表里显示的 |
| XposedService provider authority | **`io.github.wg1337.RegTrail.XposedService`** | 由 `io.github.libxposed:service` 自动合并，authority 规则是 `${applicationId}.XposedService`。**跟 applicationId 走，不是 namespace** |
| 模块作用域申请 | 走上面的 provider | §2.3 的 `requestScope()` 依赖它 |
| AIDL 自定义 action | `io.github.wg1337.RegTrail.action.BRIDGE` | 设置端 ↔ 注入端 |
| 代码 import 前缀 | `io.github.wg1337.regtrail.*` | 全小写 |
| 终端/日志根目录 | `/data/local/tmp/regtrail/`、`/data/adb/regtrail/` | 小写路径 |

⚠️ **必须注意的两个坑**：

1. **`BuildConfig.APPLICATION_ID` 才是 `io.github.wg1337.RegTrail`**，而 `BuildConfig.class.getPackage().getName()`
   或硬编码的代码包名是 `io.github.wg1337.regtrail`。
   → **凡是要拼 provider authority / 组件名 / 包名判断的地方，一律用 `BuildConfig.APPLICATION_ID`,
   绝不要用硬编码字符串或 `packageName` 反射**。这是本方案唯一的新增风险点。
2. Q：模块作用域列表里显示的是哪个？→ **`applicationId`**（`io.github.wg1337.RegTrail`），
   因为 PackageManager 认的是 applicationId。用户看到的就是你要的这个包名。
3. **构建产物文件名**用默认规则会变成 `app-release.apk`（不含包名），若需要在 CI 里区分，
   用 `applicationVariants` 显式改名——**但脚本里不要依赖大小写**（Windows 不区分、Linux 区分）。

#### 4.5.2 只支持 LSPosed（不做多框架适配）

**已决定：本项目仅支持 LSPosed**，不做 Vector / 其他 fork / 旧版 Xposed 的兼容层。

由此**简化掉**的东西：
- ❌ 不再需要 `modern` / `legacy` **两个 product flavor**，也不需要在二者间抽 hook 接口。
  → 只用**现代 API 102**：入口继承 `io.github.libxposed.api.XposedModule`（**无参构造**），
  元数据放 `META-INF/xposed/java_init.list` + `module.prop`（`targetApiVersion=102`）。
- ❌ 不打包 `de.robv.android.xposed:api:82` legacy stub。
- ❌ 不引入 **Shizuku**（`dev.rikka.shizuku:*`）——它本来也不提供 Root，且需求是 Root + LSPosed。
  → **P6 的安装环节改为 `PackageInstaller.Session`**（普通用户确认安装），不做静默安装。
- ❌ 不引入 **Frida / YAHFA / SandHook / Pine** 等第三方 hook 引擎（见 §2.4：主流需求由框架自带的
  `hook(Executable)` 覆盖）。
- ⚠️ 保留**可选**的 `org.lsposed.lsplant:lsplant:6.4`（LGPL-3.0，独立 `.so`）——仅在 P4 若真需要
  ART 内联 hook 时启用；这属于"同一个 LSPosed 生态"，不算多框架适配。

自检页据此**只检测 LSPosed**：`/data/adb/modules/zygisk_lsposed/module.prop`（读 `version`/`versionCode`）、
`Zygisk` 是否启用、四个 ABI 是否匹配、`io.github.libxposed:service` 能否 bind 上。

#### 4.5.3 ⚠️ 重要区分：两种运行模式的悬浮窗机制**不同**

"只支持 LSPosed" 与需求 ④（导出免 Root 包）会**同时存在**，但它们的悬浮窗实现不一样：

| 模式 | 前提 | 悬浮窗机制 | 是否需要权限 |
|---|---|---|---|
| **A. LSPosed 模块模式** | 有 Root + LSPosed + Zygisk，模块作用于目标 | Hook `system_server` 的 `PhoneWindowManager#checkAddPermission` → `TYPE_APPLICATION_OVERLAY` + `OP_NONE` | ✅ **完全免权限**，天然跨 Activity 常驻 |
| **B. 免 Root 持久化模式**（需求 ④ 导出的包） | 目标 App 被重打包，**没有** LSPosed、`system_server` 未被 hook | ① 挂到当前 Activity `android.R.id.content`，用 `Application.registerActivityLifecycleCallbacks` 在新 Activity 上重挂；② 或清单声明 `SYSTEM_ALERT_WINDOW` 引导用户授权后走 overlay | ⚠️ **拿不到免权限 overlay**——penalty 是必须用方案 ①/② |

> 也就是说：**§2.1 那个"一次 Hook 免权限"的漂亮结论只在模式 A 成立。**
> 模式 B 因为不 hook system_server，只能退到"挂 DecorView + 生命周期重挂"（视觉上仍是常驻，
> 但窗口层级在宿主内容之上、系统栏之下），或请用户授予悬浮窗权限。
> 这一点必须在 P6 的 UX 里讲清楚，不能让用户以为导出的包也有同样的窗口层级。

---

## 5. 功能设计（逐条对照需求）

### ① 内置 Agent 工具（对目标软件实时分析）

Agent 跑在设置端（Compose UI），工具经 AIDL 驱动**目标进程内**执行：

| 工具 | 执行位置 | 说明 |
|---|---|---|
| `list_activities` / `dump_view_tree` | 目标进程 | Activity/Fragment 栈、View 树 |
| `list_classes` / `find_class` / `dump_class` | 目标进程 | 遍历已加载类、按包名/正则匹配、导出成员 |
| `get_field` / `set_field` | 目标进程 | 实例/静态字段读写 |
| `call_method` | 目标进程 | 反射调用，按签名装箱 |
| `find_instances` | 目标进程 | 堆内实例查找（开销大，默认关） |
| `hook_method` / `unhook` | 目标进程 | 运行时挂 Hook，读参数/改返回值 |
| `read_mem` / `write_mem` | 目标进程（或设置端 Root） | `/proc/<pid>/mem` 定向读写 |
| `logcat` / `dumpsys` | 设置端（Root） | 常规观测 |
| `exec_shell` | 设置端（Root/工作区） | 见 ② |
| `decompile` / `read_file` / `write_file` | 设置端 | 反编译、目标数据目录读写 |

循环：ReAct / function-calling，LLM 走 OpenAI 兼容 HTTP（可配 baseUrl/key/model），
每步破坏性操作**必须用户可见确认**。

> **不做多 LLM 后端适配**：只走 OpenAI 兼容协议（用户已确认自备 key）。
> `com.aallam.openai:openai-client:4.1.0` 本身可指向 OpenAI / OpenRouter / vLLM / Ollama，
> 因此"只支持一种协议"不等于"只支持一家厂商"。本地模型（llama.cpp）列为后续可选，不进 P5 范围。

### ② 内置终端（多模式 + 联网工作区）

| 模式 | UID / 上下文 | 实现 |
|---|---|---|
| 应用沙箱 | 设置端 UID | `TerminalSession("/system/bin/sh", cwd, ["sh","-i"], env, rows, client)` |
| **Root** | UID 0 | `su` 或 `sh -c "exec su -M"`；libsu `RootService` 常驻 |
| 宿主上下文 | 目标 UID | `su [-t <pid>] [-Z <ctx>] <uid> -c '<cmd>'` |
| 工作区·联网 | UID 0 + 指定 cwd | 专用目录，不隔离网络 |
| 工作区·隔离 | 受限 | **`unshare -n`（不是 iptables，stock 无 iptables）** + 只读挂载 |

要点：`PATH` 必须显式入 env；busybox 复用管理器自带的；PTY 走 native（`:native` 模块）；
许可优先 Termux 两模块（Apache-2.0 例外），若不接受则 **xterm.js 6.0.0（MIT）**。

> ℹ️ **不要用 LSPosed 自带的 `lspctl` 做作用域/模块管理。** 官方 README 明确禁止 app 与无人值守集成
> （"commands, options, output, exit codes … may change without notice"），
> 且**变更类命令只接受来自 ADB 的 root shell**，我们的 App 发起的 root 调用会被拒。
> 唯一例外：把它当作**人工排障时的参考命令**（`lspctl --help` 看可用命令，`--json` 便于人读）。
> 已知其内含 `CliCommand$Module` 与 `CliCommand$HookDebug{,Enable,Disable,Dump}` 等命令族——可作能力参考。

### ③ 实时调试 + 脚本化 + 时机自动执行

1. **Hook 管理器**：搜类/方法 → 生成 Hook（记日志/改返回值/改参数）→ **立即生效，无需重启宿主**。
2. **返回值改写表**：`{class, method, signature, mode: FIXED|EXPR, value}`。
3. **内存搜索**：Root 读 `/proc/<pid>/mem` + `maps`，支持 `int/long/float/double/string/hex`，
   首次扫描 + 二次过滤（更好/更小/变化了），写回并记录原值以便还原。
4. **脚本录制**：操作序列化为 `RegTrail Script v1`（JSON），可命名/编辑/导入导出。
5. **自动执行时机**（需求原话）：`APP_ATTACH`（`attachBaseContext`）、`APP_ON_CREATE`、
   `ACTIVITY_ON_CREATE` / `ACTIVITY_ON_RESUME`（按类名匹配）、`CLASS_LOADED`（Hook `ClassLoader.loadClass`）、
   `METHOD_ENTER`（首个目标方法被调用）、`MANUAL`（悬浮窗按钮）。注入端启动读脚本清单按 `trigger` 注册。

### ④ 注入持久化（写进原版安装包并导出，免 Root 使用）

> 本节已由第 4 路研究落地为可执行方案（详见 `_research/REPORT.md`，866 行）。

#### 4.1 现成实现与许可地雷（**必须先看**）

| 项目 | 状态 | 许可 | 可用性 |
|---|---|---|---|
| `LSPosed/LSPatch` | **已归档**（2023-12-13，末版 v0.6） | GPL-3.0 | ❌ 死 |
| `JingMatrix/LSPatch` | **v1.2 (2026-08-23)** | **GPL-3.0** | ⚠️ 活，但传染 |
| `7723mod/NPatch` | **v1.0.9 (2026-10-13)**，自称支持到 **Android 17 / SDK 37** | **GPL-3.0** | ⚠️ 活，但传染 |
| `WindySha/Xpatch` | v6.0 (2024-11-04)，末次提交 2026-01-04 | **Apache-2.0** | ✅ **唯一宽松许可的完整实现** |
| `asLody/VirtualApp` | 2017-12 起停更 | **无 OSS 许可**，README 要求付费商业授权、并称已起诉/报警 | ❌ **法务上不可用** |
| `android-hacker/VirtualXposed` | 2022-06-07 停更 | GPL-3.0 | ❌ |

**关键结论：照抄 LSPatch 的形状，但不要照抄它的代码**（GPL-3.0 会传染本项目）。
本项目许可已定为 AGPL-3.0，与 GPL-3.0 兼容，但**仍不建议引入 LSPatch 代码**——
改用下表全宽松许可的替代件自行实现同一条流水线。

#### 4.2 依赖选型（全部宽松许可，且**已实测能在 Android 上跑**）

| 依赖 | 版本 | 仓库 | 许可 | 实测 |
|---|---|---|---|---|
| `com.android.tools.build:apkzlib` | **9.4.1** | **Google Maven** | Apache-2.0 | `ZFile` / `ZFileOptions` / `AlignmentRules` / `SigningExtension`；**无 v3 签名** |
| `com.android.tools.build:apksig` | **9.4.1** | **仅 Google Maven** | Apache-2.0 | **零传递依赖**；v1/v2/v3/v4 + `setLibraryPageAlignmentBytes`。⚠️ **Maven Central 上冻结在 2.3.0 (2017)，是陷阱** |
| `com.github.iBotPeaches.smali:smali-dexlib2` | **b6365a84f4** | JitPack | BSD-3 | 包名 `com.android.tools.smali.dexlib2`；988 类，`java.nio.file`/`java.awt`/`javax.xml` **零引用** → Android 可跑 |
| `com.github.iBotPeaches.smali:smali` / `:smali-baksmali` | b6365a84f4 | JitPack | BSD-3 | `Smali.assemble(SmaliOptions, List<String>)` |
| `com.github.WindySha:ManifestEditor` | **v2.0** | JitPack | **Apache-2.0** | binary AXML 读写；Xpatch 与 LSPatch 都在用 |
| `org.bouncycastle:bcpkix-jdk15to18` / `bcprov-jdk15to18` | **1.86** | Central | BC（MIT 式） | ⚠️ Android 上用 **jdk15to18** 线，不是 `jdk18on` |
| `com.google.guava:guava` | 33.6.0-android | Central | Apache-2.0 | apkzlib 需要 |

⚠️ **smali 3.x 不在 Maven Central**（`com.android.tools.smali` → 404；Central 上只有 `org.smali:*` 冻结在 2.5.2 且是**旧包名 `org.jf.dexlib2`**）。apktool-lib 3.0.3 自己也是从 JitPack 取。

#### 4.3 ⚠️ apktool 不能在 Android 上运行（已量化）

`apktool` 有 **129 处 `java.nio.file`、17 处 `javax.xml`、6 处 `java.awt`**（`ResNinePatchStreamDecoder` 用 `java.awt.image.BufferedImage`）、3 处 `javax.imageio`，且会外部调用 `aapt2`。
→ **apktool 只能留在 PC 侧**（既有 `xuelemod` 流水线用得对），**设备端重打包走 dexlib2 + ManifestEditor + apkzlib 路线**。

⚠️ **没有 `zipalign` 的 Maven 制品**（Google Maven 404）。Xpatch 是 fork/exec 预编译的 `zipalign` 二进制；
现代替代 = **apkzlib `ZFile.realign()` + `AlignmentRules`**（+ apksig 的 `setLibraryPageAlignmentBytes`）。

#### 4.4 流水线（LSPatch 形状，修掉它两个过时 bug）

```
1. ZFile.openReadWrite(out, ZFileOptions().setAlignmentRule(
       AlignmentRules.compose(constantForSuffix(".so", 16384),      // ★ 不是 4096
                              constantForSuffix("assets/payload/origin.apk", 4096))))
2. 把原始 APK 整包嵌套进 assets/payload/origin.apk
3. ManifestEditor 改写：
     application@appComponentFactory = <我们的 stub>      // API 28+
     <meta-data name="regtrail" value=base64(json 配置)>  // 脚本/主题/原始签名
     <uses-permission SYSTEM_ALERT_WINDOW>                // 兜底（主路径靠 Hook 免权限）
     extractNativeLibs=false
     uses-sdk minSdkVersion ≥ 28
4. dstZFile.add("classes.dex", stub.dex)                   // 根 dex 只有极小 stub
5. assets/payload/{loader.dex, config.json, ui/(WebView 资源), so/<abi>/libpayload.so}
     （.so 与 origin.apk 以 STORED 且不压缩方式加入；**故意不放 lib/** —— LSPatch 注释称 x86 native bridge 会崩）
6. 其余原条目全部 addFileLink(name, name)（apkzlib zip link，内容不重复）
     排除：classes*.dex、AndroidManifest.xml、META-INF/*.{SF,MF,RSA}
7. dstZFile.realign()
8. apkzlib 写出**未签名**包 → **apksig 负责签名**：
     setMinSdkVersion(28) + setV2SigningEnabled(true) + setV3SigningEnabled(true)
     + setLibraryPageAlignmentBytes(16384)
9. 安装：**`PackageInstaller.Session`**（普通用户确认安装；已决定不引入 Shizuku 做静默安装）
```

**运行期（真机上的关键机制）**：stub 静态初始化读 `config.json` → `VMRuntime.vmInstructionSet()` 取 ABI
→ `System.load("<apk>!/assets/payload/so/<abi>/libpayload.so")`
→ `JNI_OnLoad` 里 `art::DisableInline` + `DisableBackgroundVerification`
→ **`InMemoryDexClassLoader(ByteBuffer, stubClassLoader)`**（parent = 被 patch 包的 `PathClassLoader`）
→ 反射调 `PayloadEntry.onLoad()`；它把内嵌原 APK 释放到 cache、**把 `appInfo.sourceDir/publicSourceDir` 重指过去**、
还原原始 `appComponentFactory`、重建 `LoadedApk`、切 classloader。

**两个不要继承的 LSPatch bug**：
1. 它硬编码 `.so` 对齐 **4096** → 必须改 **16384**（Play 对 targetSdk 35+ 的 16KB 页强制，死线 2027-02-01）。
2. 它只抬 `minSdkVersion`，**从不检查 `targetSdkVersion` 是否满足 Android 15 的 `>= 24` 安装底线**。

**keystore**：Android 无 `keytool` → 用 BouncyCastle `JcaX509v3CertificateBuilder` + `JcaContentSignerBuilder`
生成 **PKCS12** `KeyStore`（别名 `key0`），**有效期给足 ~30 年**，
**并且必须永久保存**——证书一变就是 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，用户必须先卸载原版（丢数据）。

#### 4.5 签名自校验绕过（分两级，均已在对标项目里验证）

- **L1（Java 层）**：Hook `PackageParser.generatePackageInfo`；代理 `PackageInfo.CREATOR`；
  清 `Parcel.mCreators` / `sPairedCreators`；把 `signatures[0]` 与 `signingInfo.getApkContentsSigners()[0]`
  换成从 `<meta-data name="regtrail">` 里解出的**原始证书**。
- **L2（native 层）**：hook libc `__openat`，把对 patched APK 的 open 重定向到释放出来的原始 APK。
- ⚠️ **两级都无法绕过 Play Integrity** —— 那是**服务端**判定（"Google Play 认可的未修改二进制"），客户端无解。

#### 4.6 两种运行路径要统一

payload 必须能被**两种方式**拉起：
(a) **LSPosed 模块路径** —— 框架加载 module APK，payload 是 sibling class；
(b) **免 Root patch 路径** —— payload 是宿主新 dex 的 sibling class，由 stub 反射调用。
→ payload 入口设计成**不依赖 `XposedModule` 基类**的纯 Java 静态类，由模块入口/thunk 转发。

**必须用 native 的部分**：ART inline hooking（LSPlant，**LGPL-3.0**）、`JNI_OnLoad`/`InMemoryDexClassLoader` 自举、libc `__openat` hook、`System.load` 本身。
**打包那一半完全不需要 C++。**
> LGPL-3.0 合规：以**独立 `.so`** 方式链接 LSPlant、公开你对 LSPlant 的修改、自己的代码不碰 LGPL 表面 —— 或自研 ART hook 层。

#### 4.7 必须在 UX 里明确警示用户的四件事

1. **patch 后必然过不了 Play Integrity**（服务端判定，无法修复）。
2. **失去应用自更新**能力。
3. **必须先卸载原版**才能安装（数据会丢，需引导备份）。
4. **加固/加壳的 dex 会让重打包失败**（Xpatch 自己的 README 也承认）；native/APK 哈希完整性自检同样可能击穿。

安装限制见 §2.5；`--bypass-low-target-sdk-block` 需显式告知 + 审计日志。

---

## 6. 里程碑

| 阶段 | 目标 | 关键交付 |
|---|---|---|
| **P0 骨架** | 能跑的空壳 + 环境自检 | `:app` + `:ui` + `:shared` 三模块纯 Android 工程（`applicationId = io.github.wg1337.RegTrail`，`namespace = io.github.wg1337.regtrail`）、AGP 9.4.1/Gradle 9.6.1/Kotlin 2.4.21 跑通、M3 主题、导航、**HSV 选色盘**（Canvas 自绘）、**自检页**（Root 管理器 / **LSPosed 版本 + Zygisk + ABI** / Android 版本）、`assembleDebug` 通过 |
| **P1 注入最小闭环** | 悬浮窗出现在目标 App 上 | `:xposed` 模块（**仅现代 API 102**，无 legacy flavor）、`checkAddPermission` Hook、WebView 悬浮窗（拖动/缩放/折叠）、**`requestScope` 申请作用域** + 唤起 LSPosed Manager |
| **P2 配置打通** | 改色/改字号即时生效 | AIDL 通道（action `io.github.wg1337.RegTrail.action.BRIDGE`）、ThemeConfig 下发、悬浮窗 CSS 变量、目标选择页 |
| **P3 终端** | 多模式终端可用 | `:native` PTY、libsu RootService、流式输出、工作区（联网/`unshare -n` 隔离） |
| **P4 实时调试** | 方法 Hook + 内存搜索 + 脚本 | Hook 管理器、返回值改写、`/proc/pid/mem` 搜索、脚本录制与时机执行 |
| **P5 Agent** | 工具链驱动的分析 Agent | 工具 schema、ReAct 循环、LLM 配置页（仅 OpenAI 兼容协议）、会话历史 |
| **P6 持久化注入** | 导出免 Root 增强包 | 重打包流水线、dex/Manifest 改写、apksig 签名、split 处理、签名伪装、**模式 B 的 DecorView 悬浮窗方案**（见 §4.5.3） |

---

## 7. 风险登记册

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | ROM 自改 `PhoneWindowManager` | 悬浮窗拿不到 | 三级降级链（§2.1 B/C）；启动自检探测 |
| R2 | 目标 dex 方法引用池满 | 宿主崩溃 | payload 独立新 dex；注入前先读 `method_ids_size` 余量 |
| R3 | 目标自校验签名 / Play Integrity | patch 后崩溃 | 签名伪装选项；明确告知用户适用范围 |
| R4 | LSPosed 版本/契约漂移（分发在 Telegram，无 GitHub release 页可查） | 模块不加载 | 严格按 loader 契约；自检页显示框架版本与 API；不抄 luolong47 模板（它 ship 已被移除的 API 100） |
| R5 | 捆绑 Xposed API 类 | **模块被拒绝加载** | 强制 `compileOnly` + 构建期校验脚本 |
| R6 | R8 重命名入口类 | 加载失败 | `-adaptresourcefilecontents META-INF/xposed/java_init.list` |
| R7 | 寄存器级承诺过度 | 用户预期落空 | 对外只承诺方法级 + 内存级；native 为可选插件 |
| R8 | Termux 许可残余风险 | 合规 | 备选 xterm.js（MIT）；或在 NOTICE 复述例外条款 |
| R9 | 16KB 页对齐（Play 2027-02-01 死线） | 上架受阻 | NDK r28+ 默认对齐 + 显式 `-Wl,-z,max-page-size=16384` |
| R10 | NDK r28 sysroot 上限 API 35 | 需要 36/37 符号时受阻 | 需要时装 `ndk;30.0.16248370` |
| R11 | `web_fetch` 在本机不可用（DNS 非公网 IP / fake-IP 198.18.0.0/15） | 研究受阻 | 统一改用 `pwsh` + `Invoke-WebRequest` / `curl.exe`（已验证可行）；`codeload.github.com` 与 `*.atom` 不受限流 |
| R12 | 误引 GPL-3.0 代码（LSPatch / NPatch / Vector） | **本项目 AGPL-3.0 兼容但污染边界** | 只借鉴**形状**不抄代码；依赖全部走 §4.2 的宽松许可清单；LSPlant（LGPL-3.0）以独立 `.so` 链接 |
| R13 | `apksig` 从 Maven Central 取到 **2.3.0 (2017)** | 签名静默失败/行为异常 | **强制走 Google Maven**；构建期断言版本 = 9.4.1 |
| R14 | smali 包名从 `org.jf.dexlib2` 改为 `com.android.tools.smali.dexlib2` | 复制来的代码片段编译失败 | 只从 JitPack `b6365a84f4` 取；禁止与 Central 的 `org.smali:*:2.5.2` 混用 |
| R15 | 继承 LSPatch 的 4096 `.so` 对齐 | Play 16KB 要求不达标 | 一律 **16384**；`zipalign -c -P 16 -v 4 APK` 校验 |
| R16 | 用户误以为 patch 后仍能用 Play Integrity / 自更新 | 期望落空、数据丢失 | §4.7 四条警示必须在导出前弹窗确认；引导先备份数据 |
| R17 | 目标 dex 加固/加壳 | 重打包失败 | 打包前置检测（dex 头合法性、异常 entries）；失败即明确报错，不做无谓尝试 |
| R18 | keystore 丢失或变更 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，用户须卸载（丢数据） | 首次生成即提示导出备份；证书有效期给足 ~30 年 |
| R19 | **模式 B（免 Root 包）拿不到免权限 overlay** | 用户以为导出包也有同样的悬浮窗层级 | §4.5.3 已明确区分；P6 用 DecorView + 生命周期重挂方案，并在 UX 说明 |
| R20 | `namespace`（小写）与 `applicationId`（`RegTrail` 含大写）**不同** | 拼错组件名/authority → 作用域申请静默失效 | §4.5.1：**一律用 `BuildConfig.APPLICATION_ID`**，禁止硬编码或反射取包名 |
| R23 | `BuildConfig` 在 AGP 9 默认关闭 | 上面那条依赖 `BuildConfig.APPLICATION_ID` → 拿不到会编译失败（好过静默错） | 每个需要的模块显式 `buildFeatures { buildConfig = true }` |
| R21 | 只支持 LSPosed → 用户环境是其他 fork（如 Vector） | 模块不加载 | 自检页**明确报"未检测到 LSPosed"**并给出所需条件；不静默降级 |
| R22 | 目标 dex 加壳 / 完整性自校验 | 重打包失败或运行即崩 | 前置检测 + 明确报错；签名伪装为可选；不承诺"通杀所有 App" |

---

## 8. 已定决策与待核实项

### 8.1 已定（本轮确认，不再讨论）

| 项 | 决定 | 出处 |
|---|---|---|
| 包名（对外） | **`applicationId = io.github.wg1337.RegTrail`** | §4.5.1 |
| 代码包名 | **`namespace = io.github.wg1337.regtrail`**（小写） | §4.5.1 |
| 是否用 KMP | **不用**（纯 Android 软件，无 desktop/web 目标） | §3.1 |
| 工具链 | **Gradle 9.6.1 + AGP 9.4.1 + Kotlin 2.4.21** | §3.1 |
| 支持的框架 | **仅 LSPosed v2.2.x**（现代 API 102）；不做多框架适配、不做 Shizuku | §4.5.2 |
| 悬浮窗技术 | **WebView**（payload dex 仅几十 KB） | §4.4 / §4.5 |
| Material 3 落地 | **AndroidX Compose + M3 稳定版**（BOM 2026.09.00，m3 1.4.0），只在 `:ui` | §3.3 |
| Agent 模型 | **仅 OpenAI 兼容协议**，用户自备 key；不做多后端适配 | 功能① |
| 许可 | **AGPL-3.0-or-later** | 待补 LICENSE 文件 |
| 构建环境 | 安装 `cmdline-tools` + `cmake;3.31.6`；F 盘已清理 | §1.2 |

### 8.2 待核实

1. **设备的 Root 管理器与 LSPosed 版本** —— 用户表示不用 adb，改由 P0 自检页收集。
   （LSPosed 需要 Magisk/KernelSU/APatch + **启用 Zygisk**；自检页需判定 `/data/adb/modules/zygisk_lsposed/`、读其 `module.prop`，并核对 ABI ∈ {arm64-v8a, armeabi-v7a, x86_64, riscv64}。）
2. **`targetSdk` 取值**：建议 36（避开 16KB 死线的同时保留余量）。当前 adb 不可用，无法确认设备 Android 版本。
3. **`run-as` 依赖目标可调试** → 宿主上下文终端以 root 为主路径。
4. **打包侧两个待核实项**（研究者明确标注未验证）：
   - Google Play 关于"修改第三方应用"的政策条款原文 —— **上架前必须自行阅读确认**。
   - `SmaliOptions` 的字段名（微调 API 时需现场核对）。
5. **`lspctl` 是否值得在自检页只读展示** —— 它明确禁止 app 集成，故**只做"人读参考"**，
   不做程序化调用（§2.2 警告）。若不做，连展示也省掉。

---

## 附录 A：本机验证记录

- **★ LSPosed 官方发布包（本次最重要的证据来源）**：
  `H:\telegram desktop\LSPosed-v2.2.1-7912-release.zip`，6.13 MB，SHA-256 `ED37DE2D2097AA6BA871377E7BF4AC719B708484F9468928E6ABAE10AB311E74`，时间戳 2026-10-17 00:33。
  从该包实测确证：`module.prop`（`id=zygisk_lsposed`，v2.2.1/7912，Android 9~17，需 Zygisk）、
  `manager.apk` 包名 `org.lsposed.manager`（versionCode 7912）、
  `framework.dex` 含现代 API（`io/github/libxposed/api/*`）与 legacy API（`de/robv/android/xposed/*`）两套类、
  四个 ABI（含 riscv64）、`lspctl` 为 `org.lsposed.lspd.cli.CliMain` 的 shell wrapper、
  变更类命令仅限 ADB root shell（官方 README 明示）。
  > 解析备忘：该 zip 用 **zstd（method 95）** 压缩，.NET / Python `zipfile` 均读不了 → 用 7-Zip。
  > APK 内 binary `AndroidManifest.xml` 可用 ~40 行 Python 手工解 AXML chunk 读出 `package`。
- `io.github.libxposed:api` Maven Central 现有 **101.0.0 / 101.0.1 / 102.0.0**（最新 102.0.0）；
  `service` / `interface` 102.0.0；`annotation` 1.0.0；**`helper` 404（从未发布）**。
- `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.pom` → HTTP 200（Apache-2.0，2016 年 stub，仍可用但本项目不用）。
- AOSP `PhoneWindowManager.checkAddPermission` 在 android14/15/16 release 分支**签名一致**；
  `TYPE_APPLICATION_OVERLAY` 的 appop 闸门可被单方法 Hook 绕过。
- A14 `WindowManagerGlobal.java` 客户端侧**无权限校验**。
- NDK r28c 已装（满足 16KB 页对齐默认要求）。
- Android 17 = API 37 已确认为正式版（`IsBaseSdk=true`、`PreviewSdkInt=0`、`ExtensionLevel=22`）。
- `adb devices` → `5xiv45nv4d7tjb8l unauthorized`。
- 版本号均取自 `maven-metadata.xml` / `services.gradle.org/versions/current` 原始响应，非记忆。

## 附录 B：本次工作的自我更正记录（方法论教训）

策划过程中出现过一次**方向性误判**，记录在此以免重犯：

| 环节 | 错误结论 | 为何错 | 正确做法 |
|---|---|---|---|
| 判断框架是否活跃 | "LSPosed 已死" | 只看 `releases/latest` 与 `master` 分支 HEAD。而 LSPosed 的**分发渠道已迁到 Telegram + `lsposed.zip`**，仓库自然看起来冻结 | **优先检查已发布的制品本身**（这里是 `module.prop`），而不是仓库活跃度 |
| Manager 包名 | `org.matrix.vector.manager` | 研究时 clone 了同名生态里**另一个 fork**（Vector）的源码，把 fork 的改名当成了官方变更 | 从**官方 APK 的 binary AndroidManifest** 读取，不靠二手源码推断 |

**通用教训**：github.com 在本机被解析到 fake-IP（`198.18.0.0/15`）导致 `web_fetch` 失败时，
研究会不自觉地退化为"读能读到的仓库"，从而系统性偏向那些**恰好还在 GitHub 上**的项目。
在"分发已离开 GitHub"的场景下，这会得出完全相反的结论。
→ 凡涉及"某项目是否还在维护"，**必须**找到一个**已发布的制品/包**作为证据。
