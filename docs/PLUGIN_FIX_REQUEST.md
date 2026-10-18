# 插件侧待改清单（当前有效）

> 整理方：**App 侧维护方** · 更新：2026-10-18 · 对应 App 版本 `1.1.47`
>
> 说明：1.1.42 那批修复（TOCTOU、审计失真、intervalMs、主线程 ANR、hash/节流/缓存）**已验收通过**，
> 不再列在本清单里。下面是**目前仍待处理**的事项，按优先级排列。
> 每条都已核对源码位置，可直接照做。

---

## 约束（与 AGENTS.md 一致）

- 只改：`app/src/main/java/com/dsha/whalepet/plugin/**`、`plugins/**`、`docs/PLUGIN_SPEC.md`、`docs/probes/**`
- **不要改** App 本体（`com/dsha/whalepet/*.kt`、`res/`、`.github/`）与 `common/**`（共享基础设施）
- **版本号规则（重要）**：插件侧迭代**不递增数字**，用 `<App 当前版本>-Extension`
  （当前应为 `1.1.47-Extension`）——否则同一数字下会出现两个内容不同的包，用户无法区分
- 改安全边界必须带单测（AGENTS.md 硬规则 7）；CI 先跑 `./gradlew test`，不通过不出包
- 交付物必须是**可直接安装的 `.apk` 文件**（zip 格式），不要交解压目录

---

## P0 · 授权语义

### 1. 授权对话框"取消勾选"不生效（用户以为收回了权限，实际没有）

**位置**：
- `plugin/PluginUi.kt` 的 `authorizePlugin()`（约 103-114 行：把已授权能力**预先勾选**）
- `plugin/PluginManager.kt` 的 `grant()`（约 194-197 行：只 `addAll`，**从不 remove**）

**问题**：用户取消勾选某项再点保存 → `grant()` 只把新勾选项加进去，被取消的**依然保持授权**。
唯一有效的撤销入口是"撤销全部"。这是授权 UI 与安全语义的直接背离，比 TOCTOU 更容易被普通用户踩中。

**修法**：`grant()` 改为**全量替换**（先清空该插件的授权集合，再写入本次勾选项），
或新增 `revoke(pluginId, caps)` 由 UI 在保存时调用。注意保留 `caps.isEmpty()` 时"不写入"的现有兜底。

**验证**：给插件授权 `say` + `openApp` → 重新打开授权框，取消 `openApp` → 保存 →
双击触发插件调用 `openApp` → 应被拒（审计 `denied`），而不是弹确认条。

---

## P1 · 契约与文案

### 2. `docs/PLUGIN_SPEC.md` 三处与实现不符

| # | 问题 | 修法 |
|---|---|---|
| a | §1 字段表把 `script` 标为**必填**，但规则型插件（`rules`）没有 `script` | 改为"`script` 与 `rules` 二选一" |
| b | 规则型的 `rules` / `cooldownMs` 语法**完全没写进契约** | 补一节规则型格式说明（含 1 秒硬下限的说明） |
| c | `apiVersion = 1（对应 App 1.1.30）` 停在旧版；1.1.31~1.1.42 改了插件行为却没按 §9 流程升 | 按 §9 升 `apiVersion`，并在变更记录里列出 1.1.31 起的插件行为变化 |

### 3. 空选择文案替换（一行）

- App 侧已新增 `plugin_none_selected` =「未选择任何能力」（1.1.43）
- 请在 `PluginUi.authorizePlugin()` 的"空选择"分支改用 `R.string.plugin_none_selected`
- `plugin_caps_none`（「未声明能力」）保留原语义，只用于"插件本身没有声明能力"

---

## P1 · 审计完整性（探针实测发现）

### 4. 死循环被中止时没有审计记录

**位置**：`plugin/PluginEngine.kt` 的 `fireInternal()`（事件执行处）

**实测（2026-10-18，App 1.1.47）**：探针 2（`while(true){}`）被指令上限中止了——证据是
日志里只有「开始」、**没有「结束」**，且随后其它插件仍能正常执行（线程未卡死）。
但审计日志里**没有**一条"事件处理失败/超时中止"的记录。

**问题**：安全性没问题，但审计不完整——事后无法从日志判断"这次双击为什么没反应"。

**修法**：`fireInternal` 捕获到中止（`java.lang.Error` 或超时）时，写一条审计，例如：
`audit(ctx, pluginId, event, "事件处理", "error", "执行被中止：超出指令上限")`

**验证**：重跑探针 2，审计应出现一条 `error` 且不再只有"开始"。

---

## P2 · 工程质量

### 5. 沙箱与指令上限缺自动化测试（AGENTS 硬规则 7 的缺口）

**现状**：`ClassShutter`（脚本只能访问 `__bridge` 一个 Java 类）与指令上限（500 万条）
目前只有 `docs/probes/` 的人工探针 + 源码分析，**CI 里没有任何自动化回归**。
哪天重构 `PluginEngine`，CI 全绿也可能沙箱洞开。

**修法**：把沙箱的"建作用域 + ClassShutter + 执行脚本"抽成不依赖 Android `Context` 的部分，
用 Rhino（纯 Java 依赖）在 JVM 单测里跑：
- 断言 `typeof Packages === 'undefined'`、`typeof java === 'undefined'`
- 断言 `__bridge.getClass()` 抛异常（被 ClassShutter 拦）
- 断言死循环在 N 条指令内被中止（抛 Error）

> 参考 App 侧做法：`ShizukuCommandWhitelist` 就是为了可测而从 Service 里抽出来的纯逻辑。

### 6. `shizuku` 迁移到结构化返回（可选，App 侧已就绪）

App 侧 1.1.47 已在 `common/ShizukuOutcome` 提供结构化类型，并在 `PluginAppBridge` 加了
默认方法 `shizukuStructured(args)`（内部按文案归类，归类规则由 App 侧维护）。

- 现状：`PluginHostImpl` 仍自己按文案前缀归类（脆弱点）
- 建议：`PluginHostImpl.pluginShizuku()` 改用 `bridge.shizukuStructured(args)`，
  把 `Outcome.OK/DENIED/ERROR` 映射到已有的 `ShizukuResult`
- 迁移是**可选的**，不做也不影响功能（最坏只是审计不精确，不会误放行）

---

## 已验收（无需再处理）

| 项 | 版本 |
|---|---|
| 敏感确认 TOCTOU（撤销授权后仍执行） | 1.1.42 ✅ |
| 审计失真三处（顶替/超时/服务停止、白名单拒绝记 ok、删停用不记录） | 1.1.42 ✅ |
| `intervalMs` 死字段移除 + 契约标注 | 1.1.42 ✅ |
| 主线程执行 shizuku（ANR） | 1.1.42 ✅ |
| 脚本指纹哈希、appStart 延迟、规则节流、冷却表与目录缓存 | 1.1.42 ✅ |
| 真机探针：沙箱逃逸 / 死循环 / 未授权拦截 | 1.1.47 实测 ✅（见 `docs/probes/README.md`） |

---

## 修复顺序建议

```
P0  1. 授权取消勾选
P1  2. PLUGIN_SPEC 三处 + 3. 空选择文案
P1  4. 中止审计
P2  5. 沙箱自动测试 → 6. shizuku 结构化迁移
```

每条修完：升版本号（`1.1.47-Extension` 这类）→ 跑 `./gradlew test` → CI 绿 → 交付可安装的 `.apk`。
