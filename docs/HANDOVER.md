# 交接说明（HANDOVER）

> 目的：让**其他 AI 代理或开发者**可以独立接手本项目，无需原会话上下文。
> 阅读顺序：先读 [`AGENTS.md`](../AGENTS.md)（硬规则与已知坑）→ [`ARCHITECTURE.md`](ARCHITECTURE.md)（结构与链路图）→ 本文。

---

## 1. 项目概览

- **名称**：鲸鱼娘桌宠（Android 悬浮窗桌宠）
- **仓库**：https://github.com/Dp-Holo/DSH-whale-pet-android
- **包名**：release `com.dsha.whalepet` / debug `com.dsha.whalepet.debug`
- **技术栈**：Kotlin 2.0.20 · AGP 8.5.2 · minSdk 26 / targetSdk 35 · Material 3
- **脚本引擎**：Rhino（`com.faendir.rhino:rhino-android:1.6.0`，纯 Java）
- **构建**：GitHub Actions（`.github/workflows/build.yml`）→ debug + 签名 release APK
- **起点**：由 DSHA（DeepSeek Harness Android）驱动 AI 模型编写构建（最初为 deepseek-v4-flash；模型随迭代更新，不绑定具体代际）

### 功能范围

用户向的完整功能说明见 [README「功能」](../README.md)，这里只留**接手者需要知道的边界**：

- **插件系统有独立契约**：[PLUGIN_SPEC.md](PLUGIN_SPEC.md)，改动插件逻辑前必读；宿主代码在 `plugin/` 目录，与 App 本体分开维护
- **余额有两条独立通道**：5 分钟展示 + 3 秒静默检测（息屏时都暂停）；改动时要保证两者互不影响
- **诊断与审计日志**写在手机 `Download/` 下，是排查真机问题的第一手材料

---

## 2. 接管操作路径（二选一）

### 路径 A：在 DSHA / DSH 环境内接管（与原方式一致）

1. 打开 DSH，指定工作目录为仓库克隆路径
2. 读 `AGENTS.md` → 按「硬规则 + 交付固定动作」执行
3. 设备能力通过 DSHA 的 App 层接口（`/app/*`）。
   ⚠️ 容器虽然 bind-mount 了 `/sdcard`，但 DSHA 约束「**Android 设备文件写入必须走受保护的设备 shell**」，
   因此**不要直接 `cp` 写 `/sdcard`**。交付产物要用 `/app/export`——注意它只是给**文件名**追加 `.gz`，
   内容仍是原始字节（实测落地文件头是 `PK`、大小与源文件相同），所以导出后必须用设备 shell
   `cp` 成 `.apk` 结尾，否则安装器不认扩展名，用户会"装不上"。
   设备 shell 也无法接收容器里的文件，且 `pm install` 被策略禁止 → **安装 APK 只能由用户完成**。
4. 真机问题优先读 `Download/whale-debug.txt`

### 路径 B：在外部 AI 工具（Claude Code / Codex / Cursor 等）接管

1. `git clone https://github.com/Dp-Holo/DSH-whale-pet-android.git`
2. 读 `AGENTS.md` + 本文
3. 用个人 GitHub 凭据推送（建议 **fine-grained PAT**，仅本仓库 `Contents: RW` + `Actions: RW`）
4. 改代码 → push → 等 CI → 下载 artifact → 交付给用户（见第 5 节）

> ⚠️ 外部工具**无法直接访问用户的手机**（那是 DSHA 的能力）。设备安装、真机验证需用户手动完成，
> 或由 DSHA 侧代理协助。这一点必须在交接时向用户说明。

---

## 3. 凭据与密钥（关键）

| 凭据 | 位置 | 用途 / 注意 |
|---|---|---|
| 签名 keystore | GitHub Actions Secrets：`KEYSTORE_B64`（base64）、`KEYSTORE_PASSWORD`、`KEY_PASSWORD` | CI 用它签 release。**丢了就无法再发可覆盖升级的版本** |
| keystore 本地备份 | 由用户自行保存（原会话中导出到手机 `Download/`） | 建议离线保存（密码单独存放） |
| DeepSeek API Key | 用户手机内 SharedPreferences（`whale_pet_prefs`） | 仅用于查余额，不上传 |
| GitHub PAT | 用户持有 | **建议用 fine-grained 最小权限**；旧的全权限 token 应及时撤销 |

> 本项目**不包含**任何硬编码密钥；keystore 与密码只在用户侧与 Actions Secrets。
>
> ⚠️ **keystore 不应经共享存储传递**：历史上的备份曾被导出到手机 `Download/`（任何应用可读）。
> 发布私钥一旦泄露，签名身份即失效，只能换签名——老用户将无法覆盖升级。
> 正确做法：只在可信设备上用密码管理器/离线介质保存；若曾放到共享目录，应尽快删除并评估是否轮换。

---

## 4. 代码结构与维护归属

**两个维护方各改各的目录，不要交叉**（否则会互相覆盖）：

```
app/src/main/java/com/dsha/whalepet/          ← 归「App 本体」维护方
├── MainActivity.kt            # 主界面：底栏三页（主页 / 台词管理 / 设置）
├── WhalePetService.kt         # 前台服务：悬浮窗、漫游、交互、余额、状态联动、诊断
├── WhaleLines.kt              # 台词池（本地 JSON）
├── BalanceFetcher.kt          # 余额查询：展示用 fetch() + 数值用 fetchAmountAsync()
├── WhalePetApp.kt             # Application（Material3 动态取色）
├── ShizukuHelper.kt           # Shizuku：binder 监听、授权、白名单命令执行
└── ShizukuCommandService.kt   # Shizuku UserService（AIDL，命令白名单校验）

app/src/main/java/com/dsha/whalepet/plugin/    ← 归「插件宿主」维护方
├── PluginAppBridge.kt         # 【兼容层①】App 提供的基础能力接口（App 侧实现）
├── PluginEntry.kt             # 【兼容层②③】唯一入口 + PluginHost 适配
├── Plugin.kt                  # 插件模型与 JSON 解析
├── PluginEngine.kt            # Rhino 沙箱（唯一 Java 入口 __bridge、ClassShutter、指令上限）
├── PluginManager.kt           # 能力授权 / 敏感确认调度 / 审计 / 事件分发
├── PluginLog.kt               # 审计日志（MediaStore，1MB 自清除）
├── PluginUi.kt                # 插件管理界面
└── PluginConfirmOverlay.kt    # 敏感操作确认条（悬浮窗）

plugins/                       ← 归「插件内容」维护方（插件源文件）
docs/PLUGIN_SPEC.md            ← 宿主能力契约（App 侧定义，双方共同遵守）
docs/probes/                   ← 插件真机验证探针

app/src/main/res/layout/       # activity_main(外壳+底栏) / page_home / page_lines / page_settings
                               # overlay_whale / overlay_bubble / overlay_plugin_confirm / item_line
app/src/main/aidl/…/IShizukuCommand.aidl
.github/workflows/build.yml    # CI：解码 keystore → assembleDebug + assembleRelease → 上传 artifact
```

### 两方之间的接口（只有这三处）

| 接口 | 位置 | 谁调用 |
|---|---|---|
| 基础能力 | `PluginAppBridge`（say / notify / vibrate / openUrl / openApp / shizuku） | 插件宿主调用，App 侧实现 |
| 事件与生命周期 | `PluginEntry.onServiceStart / onServiceStop / onEvent` | App 侧调用，宿主实现 |
| 管理界面入口 | `PluginEntry.showManager(activity)` | App 侧调用 |

**只要这三个接口的签名不变**，两方即可独立改动各自的目录。

---

## 5. 标准工作流（每次迭代）

流程以 [`AGENTS.md`](../AGENTS.md) 的「交付给用户的固定动作」为准（改代码 → 升版本号 → 推送 → 等 CI → 取 artifact → 交付），
其中包含构建、取包与交付到手机的硬约束，这里不再重复。

唯一差异：若你在**外部工具**（非 DSHA 环境）接管，最后一步"交付到手机"只能由用户手动完成，详见 §2 路径 B。
发版规则同样见 `AGENTS.md` 硬规则 2。

---

## 6. 已知坑与设计要点

详见 `AGENTS.md` 的「已踩过的坑」表。补充两点：

- **悬浮窗坐标体系**：不要回退到 `displayMetrics` 直接计算边界；必须保留 `calibrateBounds()` 的实测偏移校准（否则底部静止、越界问题会复现）。
- **插件安全模型**：脚本作用域只暴露 `__bridge`；新增能力时必须同时：①在 `PluginCaps` 登记 ②在 `PluginManager.handleCall` 做授权/确认/审计 ③在 `PluginEngine` 胶水里暴露对应函数。**绕过 handleCall 的能力注入会被视为安全问题。**
  另：`ClassShutter` 白名单（`PluginEngine`）只放行 `PluginBridge`。**今后若有任何新的 Java 类型要暴露给脚本，必须同步加进白名单**，否则该类型在包装时会直接报错；白名单是"默认拒绝"，不要为了图方便改成放行整个包。

---

## 7. 待办 / 可扩展项（backlog）

| 项 | 说明 |
|---|---|
| 定时事件 | **暂不支持**。插件侧没有"每 N 分钟触发"的能力；要做需要 App 侧先新增事件源。 |
| `network` 能力 | 让插件可联网（天气、webhook），需新增能力 + 授权 + 审计 |
| 更多事件源 | 前台应用切换、充电开始/结束、整点 |
| 插件导入/导出 | 目前靠"粘贴 JSON"，可加系统文件选择器（SAF）导入 |
| ~~脚本执行时限~~ | **已完成（1.1.29）**。原判断"当前 Rhino 版本无该 API"是**错的**：`rhino-android:1.6.0` 只是适配层，真正依赖是传递引入的 `org.mozilla:rhino-runtime:1.7.13`，该版本有 `ContextFactory.observeInstructionCount` + `Context.setInstructionObserverThreshold`。现已启用（单次 500 万条指令，超时抛 `java.lang.Error`） |
| ~~真机探针（沙箱 / 死循环 / 未授权拦截）~~ | **已完成（2026-10-18，App 1.1.47）**：三项全部通过，实测记录见 `docs/probes/README.md` |
| i18n | 目前仅中文 |

---

## 8. 已确认的决策（用户已答复，勿重复问）

1. **继续**沿用"小迭代不发 Release"的发版规则，只交付 APK ✅
2. **允许**扩展 Shizuku 白名单命令（现 3 条，均限本应用包名）。新增的每条都必须在
   `ShizukuCommandService.isAllowed()` 里做参数校验，**不得放开任意 shell**——这是安全边界，不是待办。✅
3. **允许**给插件新增 `network` 能力（天气 / webhook 等）。实现时必须走完三处登记 + 授权 + 审计，
   并同步 `ClassShutter` 白名单。✅
4. **不做**"自动更新"（App 自己下载 APK 并 `pm install`）。❌

> 凭证提醒：用户手上出现过 **classic 全权限 PAT**（`ghp_` 开头，覆盖面是所有仓库）。
> §3 的"fine-grained 最小权限"要求仍需每次提醒；用过全权限 token 后应尽快撤销。
