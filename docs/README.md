# RegTrail 文档索引

> 全部内容基于 **2026-10-17** 的一手资料实测（`maven-metadata.xml` 原始响应、AOSP 源码、GitHub raw / git clone）。
> 未经验证的推断在各自报告中都有显式标注，不混入结论。

## 主文档

| 文件 | 内容 |
|---|---|
| **[PLAN.md](PLAN.md)** | **项目策划书（定稿）** —— 定位、环境、关键验证、版本矩阵、架构、模块布局、七个功能设计、里程碑、风险登记册、待确认项 |

## 专项研究报告（PLAN.md 的支撑材料）

| 文件 | 覆盖范围 | 关键结论摘要 |
|---|---|---|
| [lsposed-ecosystem-2026.md](lsposed-ecosystem-2026.md) | LSPosed/Xposed 生态现状、模块契约、运行时 Hook 引擎对比、内存/寄存器能力边界 | 原版 LSPosed 已死 → 目标是 **JingMatrix/Vector**；现代 API 102 契约（`META-INF/xposed/*`）；API 类必须 `compileOnly`；**Java Hook 看不到 CPU 寄存器** |
| [ROOT_TERMINAL_RESEARCH_2026.md](ROOT_TERMINAL_RESEARCH_2026.md) | libsu / Shizuku / 各 Root 管理器、SELinux 真相、PTY 实现、终端许可、工作区隔离、Agent 库 | **libsu 只在 JitPack**；**Shizuku 不是 Root**；"root 读不了 /data/data" 对 Magisk 是**迷信**；PTY 要手写 `cleartev`+`PATH`；**stock Android 没有 `script`/`iptables`** |
| [version-matrix-2026-10-17.md](version-matrix-2026-10-17.md) | 完整可构建版本矩阵、AGP9/KMP 约束、模块布局、可粘贴的 `libs.versions.toml` / 构建脚本 / CMakeLists | **KMP 把 AGP 卡在 9.3.1**；AGP 9 **禁止 `org.jetbrains.kotlin.android`**；KMP 模块必须用 `com.android.kotlin.multiplatform.library`；**APG-KMP 不支持 native** |
| [apk-repackaging-2026.md](apk-repackaging-2026.md) | LSPatch/NPatch/Xpatch/VirtualApp 对比、apkzlib+apksig+dexlib2+ManifestEditor 流水线、签名绕过、keystore 生成 | **LSPatch 已归档**；**VirtualApp 法务不可用**；**apktool 跑不了 Android**；**apksig 在 Central 是 2017 年的 2.3.0**；LSPatch 的 4096 对齐**是现在的 bug** |

## 原始素材与研究中间产物

### 一份**权威参照物**（比任何报告都可靠）

| 路径 | 说明 |
|---|---|
| `H:\telegram desktop\LSPosed-v2.2.1-7912-release.zip` | **官方 LSPosed v2.2.1 (7912) 发布包**（6.13 MB，SHA-256 `ED37DE2D…11E74`）。PLAN.md §2.2 的框架事实**全部由它实测得出**：`module.prop` 原文、`manager.apk` 的包名 `org.lsposed.manager`、`framework.dex` 里的现代 API 类名、四个 ABI、`lspctl` 及其 ADB-only 限制。**有疑问时优先相信这个包，而不是二手报告。** |

> 解剖方法备忘：该 zip 用了 **zstd（compress method 95）**，.NET `System.IO.Compression` 与
> Python `zipfile` 都读不了 → 用 `C:\Program Files\7-Zip\7z.exe x` 解；APK 内的 binary
> `AndroidManifest.xml` 用几十行 Python 手工解析 AXML chunk 即可读出 `package`。

### 研究报告与素材

`_research/` 目录保留了几件**已验证可直接使用**的构件（相对研究报告更"硬"的证据）：

| 文件 | 说明 |
|---|---|
| `apksig-9.4.1.jar` | 从 Google Maven 实测下载；确认 **267 类、零 `java.nio.file`/`java.awt`/`javax.xml` 引用** → Android API 24+ 可用 |
| `dexlib2-jitpack.jar` | JitPack `com.github.iBotPeaches.smali:smali-dexlib2:b6365a84f4`；确认 **988 类**在 `com.android.tools.smali.dexlib2` 包下 |
| `REPORT.md` | APK 重打包研究的原始完整版（与 `docs/apk-repackaging-2026.md` 同源，保留便于对照） |
| `lspatch.tar.gz` / `xpatch.tar.gz` / `smali.tar.gz` / `apktool.tar.gz.*` | 对标项目源码归档，供后续实现时对照（**注意许可：LSPatch 是 GPL-3.0，只可借鉴形状**） |
| `tree_*.json` | 各仓库文件树快照 |

## 本机环境备忘（详见 [PLAN.md §1](PLAN.md)）

- Android SDK `F:\Android\androidsdk`（platforms → android-37.0，build-tools → 37.0.0，NDK r28c）
- JDK21 `C:\jdk21` · Gradle 缓存含 **9.6.1**
- ⚠️ 缺 **CMake** 与 **cmdline-tools**（CMake 需先补 cmdline-tools 才能装）
- 参考项目 `F:\Android\AndroidDev\project\xuelemod` 有一整套**跑通的离线 APK 注入流水线**可继承
