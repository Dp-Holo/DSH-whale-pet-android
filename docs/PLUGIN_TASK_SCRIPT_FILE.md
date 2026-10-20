# 任务：插件支持外部脚本文件（scriptFile）

> 提出方：App 侧维护方（用户指定方案） · 交给：插件宿主维护方
> 目标版本：`1.1.51-Extension`（按 AGENTS 硬规则 2，插件侧不递增数字）

---

## 一、背景与动机

现状：脚本型插件的 JS 代码内联在插件 JSON 的 `script` 字段里。JSON 字符串不能直接换行，
稍复杂的脚本只能写成 `\n` 转义的一长串，难写、难读、易出错。

用户选择方案 B：**支持把脚本放到外部 `.js` 文件**，插件 JSON 只引用文件。

---

## 二、需求

1. 插件 JSON 可声明：`"scriptFile": "my-plugin.js"`
   - 该字段是**元数据/展示用**（显示插件用哪个脚本文件），不是路径
2. 安装或首次加载时，通过**系统文件选择器（SAF）**让用户选择实际的 `.js` 文件
3. App 持久保存该文件的访问授权（`takePersistableUriPermission`），之后从该 URI 读取内容执行
4. 与现有方式**并存**，优先级：
   ```
   已选脚本文件（scriptUri 有效） > 内联 script > rules（规则型）
   ```
5. 插件管理里：
   - 显示当前绑定的脚本文件名（或"未选择脚本文件"）
   - 提供「重新选择脚本文件」操作
6. 文件被删除 / URI 失效 / 读取失败时：
   - 写审计（结果 `error`，说明原因）
   - 提示用户"脚本文件不可用，请重新选择"（而不是静默不工作）

---

## 三、技术约束（重要）

- **必须用 SAF**：Android 分区存储下，App 无法直接读取用户放在 `Download/` 的任意文件。
  用 `Intent.ACTION_OPEN_DOCUMENT`（MIME 可用 `text/javascript` 或 `*/*`）
  + `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
  + `contentResolver.takePersistableUriPermission(...)`
- 读取：UTF-8；**大小上限 256KB**，超过则拒绝并写审计
- 不要在**主线程**做大文件读取：脚本加载本身已在插件线程（`whale-plugin`）执行，读取放那里即可
- **沙箱不变**：脚本仍走现有 `ScriptSandbox`（ClassShutter + 指令上限 + 唯一 Java 入口），
  外部文件只是脚本来源不同，执行路径必须完全一致
- **不自动扫描目录**：只能读取用户通过系统选择器明确选择的文件

---

## 四、实现建议（插件目录内）

| 文件 | 改动 |
|---|---|
| `plugin/Plugin.kt` | 新增 `scriptFile: String?`（从 JSON 读）；解析时不要求文件存在 |
| `plugin/PluginManager.kt` | 新增脚本 URI 映射的存取：`scriptUriOf(ctx, pluginId)` / `setScriptUri(ctx, pluginId, uri)`（存 SharedPreferences，如 `plugin_script_uri`）；读取内容的方法（带大小限制与 UTF-8） |
| `plugin/PluginEngine.kt` | `loadInternal` 里确定脚本文本：`scriptUri` 内容 → 否则 `script` → 都没有则不加载（规则型另行处理） |
| `plugin/PluginUi.kt` | 安装含 `scriptFile` 的插件后，启动 SAF 选择；插件操作菜单加「重新选择脚本文件」；列表项显示脚本文件名 |
| `plugin/PluginManager.kt` | 选择/读取结果写审计（`manage | 绑定脚本文件 | ok/error`） |

URI 持久化注意：`takePersistableUriPermission` 必须在拿到 URI 的同一进程/同一时机调用，
否则重启后读不到。保存的是 URI 字符串（`uri.toString()`），不是文件路径。

---

## 五、兼容性

- 现有**内联脚本**插件、**规则型**插件完全不受影响
- 插件 `id` 校验规则不变
- 覆盖安装同一 `id` 时：脚本 URI 可保留（文件名可能没变），但**授权仍按现有逻辑清空**

---

## 六、测试要求（硬规则 7）

- 单测：
  - `scriptFile` 字段解析（有/无、空值）
  - 优先级：有 scriptUri 时用文件、无时回退内联脚本
  - 大小超限时拒绝
- 真机：装一个 `scriptFile` 插件 → 选文件 → 双击触发 → 确认效果与审计（`load | 脚本已加载`、`call | … | ok`）

---

## 七、交付

- 版本号 `1.1.51-Extension`
- 可安装的 `.apk` 文件（zip 格式，不要交解压目录）
- 变更说明：改了哪些文件、SAF 流程、URI 存储位置、审计新增条目
