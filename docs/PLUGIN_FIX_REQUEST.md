# 插件侧待修问题清单（来自第三方批判性审查）

> 本文件由 **App 侧维护方**整理，供**插件宿主 + 插件内容维护方**处理。
> 审查报告已由 App 侧逐条核对源码，以下条目**全部确认属实**（不是猜测）。
> App 侧已修的问题（余额乱序、openUrl 协议、崩溃循环、日志写入、死锁、Locale、注释）不在本清单内。

---

## 约束（照旧）

- 只改 `app/src/main/java/com/dsha/whalepet/plugin/**`、`plugins/**`、`docs/PLUGIN_SPEC.md`、`docs/probes/**`
- **不要**改 App 本体（`app/src/main/java/com/dsha/whalepet/*.kt`、`res/`、`.github/`）
- **不要**改 `common/`（共享基础设施，改动需双方确认）
- 改安全边界必须带单元测试（AGENTS.md 硬规则 7），CI 会先跑 `./gradlew test`
- 每次改动升 `versionName`；纯文档改动除外

---

## P0 — 授权旁路（安全）

### 1. 敏感确认的 TOCTOU：撤销授权后仍会执行

**位置**：`plugin/PluginManager.kt` 的 `handleCall()`，确认回调分支（约 331-338 行）

**现状**：
```kotlin
host.pluginConfirm(plugin.name, desc) { allowed, remember ->
    if (!allowed) {
        audit(..., "denied", "用户拒绝")
    } else {
        if (remember) setAlwaysAllowed(...)
        perform(...)          // ← 直接执行，不复查授权
    }
}
```

**问题**：确认条最长挂起 15 秒。期间用户可以在插件管理页**撤销授权**或**删除插件**，然后回头点「允许」——`perform()` 照样执行。这与 1.1.39 刚修的「覆盖安装继承旧授权」是同一类旁路，且更直接。

**修法**：`allowed == true` 分支里重新校验，任一不满足则记 `denied`（理由写"授权已变更"）并直接 return：
1. `cap in granted(ctx, plugin.id)`（授权仍存在）
2. 插件文件仍存在（`fileOf(ctx, plugin.id) != null`，未被删除）
3. 该插件仍处于启用状态

**验证**：真机操作——插件调用敏感能力 → 弹出确认条 → 切到管理页撤销授权 → 回到确认条点「允许」→ 应**不执行**，且审计写 `denied`。

---

## P1 — 审计失真（追责依据不成立）

### 2a. 确认条被顶掉，却记成"用户拒绝"

**位置**：`plugin/PluginConfirmOverlay.kt` 的 `show()`（约 40 行，`dismiss()` 旧确认）

**现状**：新的确认到来时，旧的按 `denied` 回调，`PluginManager` 审计写 `denied | 用户拒绝`。**用户根本没看到那条确认**，记录与事实不符。

**修法**：把"结束原因"区分开（如 `USER_DENIED` / `TIMEOUT` / `SUPERSEDED` / `SERVICE_STOPPED`），审计按真实原因写入。回调签名可改为 `(allowed, remember, reason)`。

### 2b. shizuku 被白名单拒绝，审计仍写 `ok`

**位置**：`plugin/PluginManager.kt` 的 `perform()`（约 378-382 行）

**现状**：
```kotlin
"shizuku" -> {
    val r = host.pluginShizuku(args)
    audit(ctx, plugin.id, "call", desc, "ok", r)   // ← 结果写死 ok
    r
}
```
`host.pluginShizuku` 在命令不在白名单时返回「拒绝执行：命令不在白名单内」，但审计结果仍是 `ok`。

**修法**：按返回值判结果。建议 `pluginShizuku` 返回结构化结果（如 `(success: Boolean, message: String)`），或至少：返回值以"拒绝执行"开头 → `denied`，以"执行异常/调用失败"开头 → `error`，其余 → `ok`。

### 2c. 删除 / 启停插件不写审计

**位置**：`plugin/PluginManager.kt` 的 `delete()` 与 `setEnabled()`

**现状**：两个方法都没有任何 `audit()` 调用。审计链在最关键的管理操作上断掉——"谁在什么时候删了/停了插件"没有任何记录。

**修法**：补记录，例如：
- `delete` → `audit(ctx, id, "manage", "删除插件", "ok")`
- `setEnabled` → `audit(ctx, id, "manage", if (enabled) "启用" else "停用", "ok")`

---

## P1 — 契约与行为不一致

### 3. `intervalMs` 是死字段（#14）

**位置**：`plugin/Plugin.kt`（约 80 行解析）、`PluginManager.dispatch()`（从未使用）

**现状**：解析了"定时"字段，`dispatch` 完全不读它；`docs/PLUGIN_SPEC.md` 也没记载 `cooldownMs`。接手者会以为定时事件已可用。

**修法**：二选一
- **删**：删除字段，`PLUGIN_SPEC` 明确写「暂不支持定时事件」；
- **实现**：由 App 侧在 Service 里新增定时触发点（跨边界，需要先与 App 侧约定事件名与 payload），插件侧再消费。

**建议**：先删/标注，实现另排期，避免文档误导。

---

## P2 — 工程质量

### 4. 脚本新鲜度用 32 位 `String.hashCode()`（#15）

**位置**：`plugin/PluginEngine.kt` 的 `isLoadedFresh()`

**问题**：32 位哈希碰撞时，脚本改动不会触发重新加载（概率极低，但逻辑上不严谨）。

**修法**：用「内容长度 + hash」组合判断，或 SHA-256 前缀。

### 5. `appStart` 延迟分发到已停止的服务（#16）

**位置**：`plugin/PluginEntry.kt` 的 `onServiceStart()`（`postDelayed 1500ms`）

**问题**：服务在 1.5 秒内停止时，延迟任务仍会向已销毁的 host 分发 `appStart`。

**修法**：延迟任务执行前判断 `host` 是否仍为启动时的同一实例（或加"服务已停止"标志）。

### 6. 规则型插件可发有声通知且无节流（#18）

**位置**：`plugin/PluginManager.kt` 的 `dispatch()`（`cooldownMs` 默认 0）

**问题**：已授权插件每次点击都能触发 `notify`；而 App 侧的插件通知渠道是 `IMPORTANCE_DEFAULT`（**有声**）→ 可被用来轰炸通知。

**修法**：
- 插件侧：给规则动作加最小触发间隔（如 1 秒），防连点轰炸；
- App 侧（需协调）：把插件通知渠道降为 `IMPORTANCE_LOW`（无声）——这一条归 App 侧改，不要在插件目录里改。

### 7. `lastFire` 不清理 + `dirStamp` 每次事件做磁盘 IO（#19）

**位置**：`plugin/PluginManager.kt`（`lastFire` 表、`dirStamp()`）

**问题**：
- `lastFire` 冷却表永不清理，插件/规则被删除后键仍残留（缓慢增长）；
- `dirStamp()` 在 `loadAll()` 里调用，而 `loadAll()` 在**每次事件**（含每一次点击）都在主线程执行 `listFiles()` 磁盘 IO。

**修法**：
- `lastFire`：定期清理或按需清理（如条目超过上限、或插件删除时移除对应键）；
- `dirStamp`：缓存结果并设短有效期（如 3~5 秒），或对高频事件路径跳过缓存校验。

---

## 跨边界（需与 App 侧一起做）

### 8. `shizuku` 在主线程同步执行 → 可能 ANR（#3）

**位置**：`plugin/PluginManager.kt` 的 `perform()`（规则型）与确认回调（`PluginConfirmOverlay` → `cb.invoke`）

**问题**：App 侧的 `ShizukuHelper.execWhitelistedSync()` 注释写明"**须在后台线程调用**"（内部最长等 5 秒）。但两条路径都在**主线程**调用它：
1. 规则型插件的 `shizuku` 动作：`onEvent`（主线程）→ `dispatch` → `perform` → 阻塞
2. 敏感确认回调：`PluginConfirmOverlay.finish()` 在主线程 `cb.invoke` → `perform` → 阻塞

**后果**：Shizuku 绑定慢时主线程阻塞最长 5 秒 → 系统判定无响应（ANR），可能弹窗或被杀。脚本型插件不受影响（脚本在后台线程执行）。

**修法（需配合 App 侧）**：
- 插件侧：把 `perform()` 中所有 `host.*` 调用统一切到后台线程（单线程执行器即可），确认回调里也 post 到该线程；
- App 侧：`PluginAppBridge` 的能力实现已按"可在后台线程调用"设计（内部 `handler.post` 切主线程），无需改动。

---

## 修复顺序建议

```
P0  2. TOCTOU
P1  2a/2b/2c 审计三项 + 3. intervalMs 标注
P1  8. shizuku 主线程（与 App 侧协同）
P2  4/5/6/7
```

每条修完请：升版本号 → 跑 `./gradlew test` → CI 绿 → 交付**可直接安装的 .apk 文件**（注意上次 1.1.39 交付成了解压目录，用户装不上；交付物必须是 zip 格式的 .apk）。

---

## 后续事项（App 侧已答复，2026-10-18）

### A. 空选择文案：App 侧已提供新字符串，请替换引用

- 新增 `plugin_none_selected` =「未选择任何能力」（App 侧 1.1.43）
- `plugin_caps_none`（「未声明能力」）保留原语义，**只用于"插件本身没有声明能力"**
- 请在 `PluginUi.authorizePlugin()` 的"空选择"分支改用 `R.string.plugin_none_selected`
  （这是一行改动；App 侧不代改插件目录）
- 另：`plugin_install_failed` 已改为同时覆盖「id 不合法」与「JSON 格式错误」两种原因

### B. `shizuku` 跨边界返回结构化：同意，但排到下一轮

App 侧同意把 `PluginAppBridge.shizuku(args): String` 改为结构化返回
（`(success: Boolean, message: String)` 或等价类型），以消除"按文案前缀归类"的脆弱点。

**本轮不改**的原因与安排：
- 当前退化后果只是"审计不够精确"，**不会误放行**（白名单仍在 App 侧拦截），风险可接受
- 改跨边界签名需要两边同时改，避免与插件侧正在进行的改动冲突
- 由 **App 侧下一轮发起**：先定义类型并保持向后兼容的适配，再通知插件侧适配宿主

### C. `common/MediaStoreLog.size()` 已加 `@Synchronized`（App 侧 1.1.43）

### D. 真机探针仍未跑

需要用户在设备上手动安装插件并操作；App 侧与插件侧的代理都无法代劳（`pm install` 被策略禁止）。
建议下一轮请用户跑一次并把 `Download/whale-plugins.log.txt` 贴回。
