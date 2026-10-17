# RegTrail

安卓逆向与实时分析工作台 —— Root + LSPosed。

> **状态：P1（注入闭环）完成。** App 已是一个合法的 LSPosed 模块；悬浮窗可注入目标应用；
> 终端、实时调试与 Agent 已实现。注入持久化（免 Root 导出包）尚未实现。详见 [docs/PLAN.md](docs/PLAN.md)。

## 这是什么

RegTrail 让 Android 上的逆向分析变成一件"带着工具进场"的事：一个设置端 App
负责选目标、配色、注入编排，一个极小的 payload 注入目标进程并提供悬浮窗与
"进程内"的对象/方法访问能力。

两条运行路径：

| 模式 | 前提 | 悬浮窗机制 |
|---|---|---|
| **A. LSPosed 模块**（主路径） | Root + LSPosed | 免权限、应用级常驻（Hook `PhoneWindowManager#checkAddPermission`） |
| **A′. 降级** | 同上，但 `system` 作用域未授权或 ROM 结构不同 | 自动回退到 Activity DecorView 内嵌（无需任何权限，但会被系统栏裁剪） |

## 已实现

| 模块 | 能力 |
|---|---|
| **模块声明** | 现代 API 102 契约：`META-INF/xposed/{java_init.list,module.prop,scope.list}`，LSPosed 管理器可直接识别 |
| **悬浮窗** | WebView UI（资源来自模块 APK，零网络）、可拖动/折叠、主题色与字号由设置端下发为 CSS 变量 |
| **目标与作用域** | 应用列表（`QUERY_ALL_PACKAGES`）、批量申请/移除作用域、逐应用悬浮窗开关 |
| **终端** | 4 种模式（Root / 应用沙箱 / 宿主上下文 / 联网工作区）、持久 shell、内置 `cd`/`pwd`/`help` |
| **实时调试** | 类与方法搜索、挂 Hook、跟踪调用、**改写返回值**（原方法仍执行，副作用保留）、Hook 列表与命中计数 |
| **Agent** | OpenAI 兼容协议的 ReAct 循环，11 个工具（含 `run_shell`），工具在目标进程内执行 |
| **环境自检** | Root 管理器识别（含 APatch）、LSPosed 版本、Zygisk 三态判定、ABI 匹配 |

## 能力边界（重要）

- **Java Hook 看不到 CPU 寄存器**，这是 ART 的构造限制，不是实现缺陷。可读写的是
  方法参数与返回值。寄存器级工作需要 Dobby/Frida 一类 native 方案。
- 终端是**行模式**而非完整 PTY：不支持 `top`/`vi` 这类全屏程序（需要 native `forkpty`）。
- 免权限悬浮窗依赖 **`system` 作用域**；未授权时会自动降级为 DecorView 内嵌模式。

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin + **AndroidX Compose + Material 3**（不使用 Compose Multiplatform） |
| 构建 | Gradle **9.6.1** + AGP **9.4.1** + Kotlin **2.4.21** |
| SDK | compileSdk **37** / targetSdk **36** / minSdk **27** |
| 框架 | **LSPosed v2.2.x**，现代 Xposed API **102**（`io.github.libxposed:api`，**`compileOnly`**） |
| Root | libsu **6.0.0**（JitPack） |
| 包名 | `io.github.wg1337.RegTrail`（代码包名 `io.github.wg1337.regtrail`） |
| 许可 | AGPL-3.0-or-later |

## 目录结构

```
RegTrail/
├── app/       com.android.application —— 组件、特权层、模块实现、终端、调试引擎、Agent
│              └─ xposed/  注入端：模块入口 / 权限 Hook / 悬浮窗 / 反射桥 / 调试引擎
├── ui/        com.android.library     —— Compose 界面、主题、选色盘、各功能页面
├── shared/    com.android.library     —— 数据模型、配置契约、桥接协议（无 UI 依赖）
└── docs/      策划书与四份专项研究报告
```

依赖方向严格单向：`app → ui → shared`。

## 构建

需要 **JDK 21**（系统 `java` 若是 Java 8 会失败）与 Android SDK（platform 37、build-tools 37.0.0、NDK r28c）。

`local.properties`（**不入版本库**）：

```properties
sdk.dir=F\:\\Android\\androidsdk
org.gradle.java.home=C:/jdk21
```

```bash
./gradlew :app:assembleDebug
```

## 使用步骤

1. 安装 APK，在 **LSPosed 管理器**中启用「RegTrail」模块（模块已正确声明，应能直接列出）。
2. 在管理器中给它勾选 **`system`** 作用域 —— 这是免权限悬浮窗的前提。
3. 重启设备（或软重启框架）。
4. 打开 RegTrail →「目标」页：连接成功后勾选要注入的应用的「悬浮窗」，再按「申请已勾选」。
5. 启动目标应用，悬浮窗应出现在其上。
6. 「设置 → 调试」里可调 Hook 默认行为、自动 deoptimize 与日志上限；「设置 → Agent」里填
   Base URL / Key / 模型名；「设置 → 外观」里选应用主题色与悬浮窗主题色。
7. 需要终端或打包时，从「总览」的快捷入口一键进入。

排障：`logcat -s RegTrail` 会打出模块在目标进程内的日志，包含悬浮窗被拒的确切原因。

## 界面结构

底部只保留 4 个入口，其余全部收进「设置」：

```
总览   ─┬─ 快捷入口：终端 / 实时调试 / 打包 / 环境自检 / 设置
        ├─ 目标（Tab）
        ├─ 设置（Tab）
        │    ├─ 外观：应用主题色、悬浮窗主题色（两个选色盘 + 预设色 + 实时预览）、
        │    │        配色方案、深色模式、悬浮窗字号 / 圆角 / 不透明度 / 默认尺寸
        │    ├─ 悬浮窗：注入方式（含风险确认）、随应用启动显示、悬浮窗目标应用
        │    ├─ 终端：默认模式、工作区路径、字号 → 打开终端
        │    ├─ 调试：Hook 默认行为、自动 deoptimize、日志上限 → 打开实时调试
        │    ├─ Agent：Base URL / API Key / 模型 / 工具轮数 → 打开对话
        │    ├─ 打包：免 Root 打包
        │    ├─ 环境：环境自检
        │    └─ 高级 / 危险：system 作用域状态、重置所有设置
        └─ Agent（Tab）
```

「终端」「实时调试」「打包」仍是完整界面，只是不再占用底部 Tab：从「总览」一键可达，
相关配置则集中在「设置」的对应分组里。

## 尚未实现

- **注入持久化**：把悬浮窗写进原版 APK 并导出免 Root 安装包（P6）
- **脚本录制**：把调试操作序列化并在指定时机自动执行
- **内存搜索**：`/proc/<pid>/mem` 读写
- **完整 PTY 终端**：需要 native `forkpty`

> ⚠️ 当前是 **debug 构建**，未启用 R8，因此有 25 个 dex。注入目标进程时框架需加载全部
> dex，会带来可感知的启动开销。正式发布应启用 R8 并保留 `META-INF/xposed/` 适配规则
> （见 `app/proguard-rules.pro`）。

## 文档

[docs/README.md](docs/README.md) 是全部文档的索引。核心是
[docs/PLAN.md](docs/PLAN.md)（策划书），另有三份一手研究：
LSPosed 生态、Root 与终端、APK 重打包。

## 许可

AGPL-3.0-or-later，见 [LICENSE](LICENSE)。
