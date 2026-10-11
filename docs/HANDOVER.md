# 交接说明（HANDOVER）

> 目的：让**其他 AI 代理或开发者**可以独立接手本项目，无需原会话上下文。
> 先读 [`AGENTS.md`](../AGENTS.md)（硬规则与已知坑），再读本文。

---

## 1. 项目概览

- **名称**：鲸鱼娘桌宠（Android 悬浮窗桌宠）
- **仓库**：https://github.com/Dp-Holo/DSH-whale-pet-android
- **包名**：release `com.dsha.whalepet` / debug `com.dsha.whalepet.debug`
- **技术栈**：Kotlin 2.0.20 · AGP 8.5.2 · minSdk 26 / targetSdk 35 · Material 3
- **脚本引擎**：Rhino（`com.faendir.rhino:rhino-android:1.6.0`，纯 Java）
- **构建**：GitHub Actions（`.github/workflows/build.yml`）→ debug + 签名 release APK
- **起点**：由 DSHA（DeepSeek Harness Android）驱动 deepseek-v4-flash 编写构建

### 功能清单（当前）

| 模块 | 内容 |
|---|---|
| 悬浮窗桌宠 | 自主游动（角度制漫游 + 镜面反射 + 近边界软排斥）、单击台词（含"至少隔两句"去重）、双击查余额、拖动 |
| 余额 | 5 分钟轮询展示；**3 秒静默检测**，余额**减少**时自动冒「正在偷吃用户token...」（10 秒节流）；**熄屏暂停**两类查询，亮屏立即恢复（1.1.29 起） |
| 状态联动 | 低电量提示、息屏暂停游动 |
| 台词管理 | 条目化增删改（本地 JSON 持久化，即时生效） |
| 权限 | 悬浮窗、通知（Shizuku 可自动授予）、电池优化白名单 |
| 插件系统 | 脚本型（JS 沙箱）：7 事件 × 7 动作、能力授权、敏感操作二次确认、审计日志；沙箱为 Rhino `ClassShutter`（脚本仅 `__bridge` 一个 Java 类可见）+ 单次执行指令上限（1.1.29 起） |
| 诊断 | `Download/whale-debug.txt`（运行时状态）、`Download/whale-plugins.log.txt`（插件审计），均 1MB 自清除 |

---

## 2. 接管操作路径（二选一）

### 路径 A：在 DSHA / DSH 环境内接管（与原方式一致）

1. 打开 DSH，指定工作目录为仓库克隆路径
2. 读 `AGENTS.md` → 按「硬规则 + 交付固定动作」执行
3. 设备能力通过 DSHA 的 App 层接口（`/app/*`）。
   ⚠️ 容器虽然 bind-mount 了 `/sdcard`，但 DSHA 约束「**Android 设备文件写入必须走受保护的设备 shell**」，
   因此**不要直接 `cp` 写 `/sdcard`**。交付产物要用 `/app/export`（该通道会自动 gzip，落地名带 `.gz`）。
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

---

## 4. 代码结构

```
app/src/main/java/com/dsha/whalepet/
├── MainActivity.kt            # 主界面：底栏三页（主页 / 台词管理 / 设置）+ 插件管理对话框 + 关于
├── WhalePetService.kt         # 前台服务：悬浮窗、漫游、交互、余额、状态联动、插件宿主、诊断
├── WhaleLines.kt              # 台词池（本地 JSON）
├── BalanceFetcher.kt          # 余额查询：展示用 fetch() + 数值用 fetchAmountAsync()
├── WhalePetApp.kt             # Application（Material3 动态取色）
├── ShizukuHelper.kt           # Shizuku：binder 监听、授权、白名单命令执行
├── ShizukuCommandService.kt   # Shizuku UserService（AIDL，命令白名单校验）
├── Plugin.kt                  # 插件模型与 JSON 解析（能力/事件/动作）
├── PluginEngine.kt            # Rhino 沙箱：__bridge 唯一 Java 入口、胶水 API、后台单线程执行
│                              #   + ContextFactory / ClassShutter 白名单 + 指令上限（1.1.29 起）
├── PluginManager.kt           # 插件：文件、能力授权、敏感确认调度、审计、事件分发
└── PluginLog.kt               # 插件审计日志（MediaStore，1MB 自清除）

app/src/main/res/layout/       # activity_main(外壳+底栏) / page_home / page_lines / page_settings
                               # overlay_whale / overlay_bubble / overlay_plugin_confirm / item_line
app/src/main/aidl/…/IShizukuCommand.aidl
.github/workflows/build.yml    # CI：解码 keystore → assembleDebug + assembleRelease → 上传 artifact
```

---

## 5. 标准工作流（每次迭代）

```bash
# 1) 改代码 + 升 versionName（app/build.gradle.kts）
# 2) 提交推送
git add -A && git commit -m "<type>: <中文说明>" && git push
# 3) 等 CI 成功（GitHub Actions；失败看日志里的 e: 行）
# 4) 下载 release artifact → 解压取 app-release.apk
# 5) 交付：/app/export?path=<apk>（落 Download/DSHA；通道会自动 gzip，用户自行解压安装）
#    容器直写 /sdcard 被约束禁止、pm install 被策略禁止 → 安装由用户完成
#    并告知用户：文件名 / 字节数 / SHA256 / 本版改动 / 验证方法
```

**发版规则**（用户指定，务必遵守）：小迭代 `1.1.x` 不发 Release；大迭代 `1.2/1.3` 仅按用户指令发布；Release 页只留最新（旧版转 draft）。

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
| 定时事件 `interval` | 解析已支持，触发未接入（`PluginRule.intervalMs` 尚未使用） |
| `network` 能力 | 让插件可联网（天气、webhook），需新增能力 + 授权 + 审计 |
| 更多事件源 | 前台应用切换、充电开始/结束、整点 |
| 插件导入/导出 | 目前靠"粘贴 JSON"，可加系统文件选择器（SAF）导入 |
| ~~脚本执行时限~~ | **已完成（1.1.29）**。原判断"当前 Rhino 版本无该 API"是**错的**：`rhino-android:1.6.0` 只是适配层，真正依赖是传递引入的 `org.mozilla:rhino-runtime:1.7.13`，该版本有 `ContextFactory.observeInstructionCount` + `Context.setInstructionObserverThreshold`。现已启用（单次 500 万条指令，超时抛 `java.lang.Error`） |
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
