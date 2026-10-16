# AGENTS.md — AI 协作约定

> 本文件面向**任何接手本项目的 AI 代理**（Claude Code / Codex / Cursor / DSH 等）。
> 一进仓库先读本文件，再读 [`docs/HANDOVER.md`](docs/HANDOVER.md)（完整交接说明）。

## 项目一句话

Android 悬浮窗桌宠「鲸鱼娘桌宠」：Kotlin + 单 Activity + 前台服务（specialUse）承载系统悬浮窗；
由 **DSHA（DeepSeek Harness Android）驱动 deepseek-v4-flash** 编写并构建。

## 分工与目录归属（重要）

本项目由**两个维护方**并行推进，**各改各的目录，不要交叉**：

| 维护方 | 负责 | 目录 |
|---|---|---|
| **App 本体** | 悬浮窗/游动/交互、权限与 Shizuku、余额检测、台词、诊断、CI 与发布，以及**插件与 App 的兼容层** | `app/src/main/java/com/dsha/whalepet/*.kt`（不含 `plugin/`）、`res/`、`.github/` |
| **插件宿主 + 插件内容** | 插件引擎与沙箱、能力授权、敏感确认、审计、插件管理界面、插件文件 | `app/src/main/java/com/dsha/whalepet/plugin/**`、`plugins/**`、`docs/PLUGIN_SPEC.md`、`docs/probes/**` |

**两方之间只有三个接口**（改动接口需双方确认）：

1. `PluginAppBridge` —— App 提供给插件的基础能力（say / notify / vibrate / openUrl / openApp / shizuku）
2. `PluginEntry.onServiceStart / onServiceStop / onEvent` —— App 通知插件宿主（生命周期与事件）
3. `PluginEntry.showManager(activity)` —— 设置页打开插件管理界面

> 违反边界（例如直接改对方目录）会造成覆盖冲突；**动到宿主代码等于破坏插件安全模型**。

## 硬规则（不要违反）

1. **构建只能走 GitHub Actions**
   本机没有（也不建议装）Android SDK；`./gradlew assembleDebug` 只在 CI 上跑。
   本地改动 → push → 等 CI → 从 run 的 artifact 取 APK。
2. **发版规则（用户指定）**
   - 小迭代：`1.1.1 → 1.1.2 → …`，**不发 Release**，只交付 APK
   - 大迭代：`1.2 / 1.3 …`，**仅在用户明确指令时**发布 Release
   - Release 页**只保留最新版本**（旧版转 draft，页面不显示）
   - 版本号末位 `.0` 忽略显示（`1.1.0` 写作 `1.1`）
3. **每次改动都要升版本号**（`app/build.gradle.kts` 的 `versionName`），否则用户无法区分包。
   （**纯文档改动例外**——APK 没变就不需要升版本号，用户已明确。）
4. **不要提交密钥**：签名 keystore 只存在于 GitHub Actions Secrets（`KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_PASSWORD`）与用户自己的备份。
5. **权限改动要克制**：新增 Android 权限必须在 PR/commit 里说明用途。
6. **用户是唯一决策者**：涉及体验（动效、文案、交互）的改动，先问再改；不擅自扩大范围。

## 代码约定

- 全部中文注释，说明「为什么」而不是「做了什么」
- 不动已验证的逻辑（尤其下面这些坑，都是踩过的）
- 新增 UI 文案一律进 `res/values/strings.xml`，不硬编码

## 已踩过的坑（务必先读）

| 位置 | 现象 | 原因与现状 |
|---|---|---|
| `WhalePetService` 悬浮窗边界 | 鲸鱼在底部"完全静止"数秒 | 窗口坐标原点不在物理屏幕左上，而在**状态栏下方**（该机偏移 162px）。已用「WindowMetrics 安全区 + `getLocationOnScreen` 实测偏移」校准，勿改回纯 `displayMetrics` |
| 同上 | 触底后贴着底边平移 | 近边界软排斥 `softRepel` + 松手 `kickOffBoundary` 解决；`FLAG_LAYOUT_NO_LIMITS` 已移除，勿加回 |
| 同上 | 点击缩放被窗口裁剪 | 窗口比本体大 25%（`windowPx = sizePx * 1.25`），本体靠 padding 居中 |
| `whale-debug.txt` / `whale-plugins.log(.txt)` | Download 里堆积同名文件 | MediaStore 遇重名会加 `(n)` 后缀，旧逻辑"查不到就新建"。现按 `LIKE 前缀` 列出变体、只保留一个并尝试改回标准名 |
| `PluginEngine` | 执行时限曾被判定"不支持"而移除 | **假结论，勿再回退**。根因是看错依赖：`com.faendir.rhino:rhino-android:1.6.0` 只是 Android 适配层，真正的 Rhino 是它传递引入的 `org.mozilla:rhino-runtime:1.7.13`，该版本**有** `ContextFactory.observeInstructionCount`（protected 非 final）与 `Context.setInstructionObserverThreshold`，`Interpreter`（1.7.13 第 3268-3271 行）也会回调。已于 1.1.29 重新启用：单次上限 500 万条指令，超时抛 `java.lang.Error`——Rhino 只对 Error 走 `EX_NO_JS_STATE`，脚本的 `try/catch` 吞不掉 |
| `PluginEngine` | 插件"沙箱"曾缺 `ClassShutter` | 仅删 `Packages`/`java`/`getClass` 等全局**拦不住** `__bridge.getClass()`（实例方法，删全局无效）。1.1.29 起用 `ContextFactory` + `ClassShutter` 把脚本可访问的 Java 类限制为 `__bridge` 一个；**今后新增任何暴露给脚本的 Java 类型，必须同步加进白名单** |
| `PluginManager` | `JSONArray.add` 编译失败 | Android 的 `org.json.JSONArray` 只有 `put()` |
| Shizuku 自动授权 | 授权后仍需手动开悬浮窗 | binder **异步到达**，必须 `addBinderReceivedListener`；授权结果走 `addRequestPermissionResultListener`（不是 `onRequestPermissionsResult`） |
| `PluginLog` | 代码里提示 `whale-plugins.log`，真机上实际是 `whale-plugins.log.txt` | MediaStore 给 `text/plain` 自动补了 `.txt` 后缀。`PluginLog.filePath()` 与 UI 文案需与实际文件名一致（**待修**） |
| `MainActivity.authorizePlugin` | 一个能力都不勾也提示"已授权" | 空授权应直接拒绝或忽略（**待修**） |
| 插件能力授权 | 授权后若插件文件被改动，能力声明可能变化 | 授权按 `pluginId` 存储，不校验插件内容版本；插件升级后应重新确认能力（**可加固**） |

## 交付给用户的固定动作

每次完成一个迭代：

```bash
# 1) 升版本号后提交
git commit -m "..." && git push
# 2) 等 CI success（GitHub Actions）→ 下载 release artifact → 解压取 app-release.apk
# 3) 交付到手机：/app/export?path=<apk>   → 落在 Download/DSHA/
# 4) 告诉用户：文件名、字节数、SHA256、本版改了什么、怎么验证
```

**交付通道的硬约束（勿绕）**：

- 容器虽然 bind-mount 了 `/sdcard`，但 DSHA 约束「**Android 设备文件写入必须走受保护的设备 shell**」，
  所以**不要直接 `cp` 到 `/sdcard`**。
- 设备 shell 也**收不到**容器里的文件（没有 push）；`pm install` 被策略明确禁止
  （原话：`pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令`），`gzip`/`unzip`/`tar` 同样被拦。
  → **APK 安装只能由用户完成**，代理不得改用 UI 自动化绕过。
- `/app/export` 会自动 gzip（落地名带 `.gz`，连 `.json` 也照 gzip），用户需自行解压。

## 设备侧排查手段（很有用）

App 会把运行时状态写进 `Download/whale-debug.txt`（关键事件 + 每 30 秒一行），
插件调用写进 `Download/whale-plugins.log.txt`（MediaStore 给 `text/plain` 自动补 `.txt`）。
**这两个文件是定位真机问题的第一手证据**，
比猜测高效得多（历史上"底部静止""未触发提示"都是靠它定位的）。两文件均 1MB 自清除。

## 已确认的 backlog 决策（用户已答复，勿重复问）

- 发版规则维持不变：小迭代 `1.1.x` **不发 Release**，只交付 APK
- **允许**扩展 Shizuku 白名单命令（现 3 条，均限本应用包名）；新增每条都必须在
  `ShizukuCommandService.isAllowed()` 里做参数校验，不得放开任意 shell
- **允许**给插件新增 `network` 能力；实现时必须走完三处：`PluginCaps` 登记 +
  `PluginManager.handleCall` 授权/确认/审计 + `PluginEngine` 胶水暴露（另需同步 `ClassShutter` 白名单）
- **不做**"自动更新"（App 自己下载 APK 并 `pm install`）
