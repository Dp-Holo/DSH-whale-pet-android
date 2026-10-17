# 宿主能力契约（插件开发规范）

> **维护方：App 侧**（软件本体由代理 A 负责；插件内容由代理 B 负责）
> 本文件是插件与 App 之间的**唯一接口契约**。插件开发只依据本文件，不需要读 Kotlin 源码。
> 契约变更流程见文末 §9。

- **契约版本**：`apiVersion = 1`（对应当前 App `1.1.30`）
- 插件文件：`{"id","name","version","enabled","capabilities","script"}`
- 脚本语言：JavaScript（Rhino 引擎，ES5 语法为主；`JSON`、`Array`、`String`、`Math` 可用）

---

## 1. 插件文件格式

```json
{
  "id": "my-plugin",
  "name": "我的插件",
  "version": 1,
  "enabled": true,
  "capabilities": ["say", "notify"],
  "script": "on('doubleTap', function (ctx) { say('你好'); });"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 唯一标识，**强制校验**：字母或数字开头，其后只允许字母、数字、`.`、`_`、`-`，最长 64；**不允许 `/` 与中文**（id 会被拼成文件名落盘，不合规直接拒绝安装）。**授权按 id 记录**，改名等于新插件 |
| `name` | ✅ | 界面显示名 |
| `version` | 建议 | 插件自身版本号（整数），仅作标记 |
| `enabled` | 建议 | 默认 true |
| `capabilities` | ✅ | 需要的能力（见 §3）。**规则里用到的能力会自动补齐**，但仍建议显式声明 |
| `script` | ✅ | JS 源码（单行字符串，内部用单引号避免转义） |

> 兼容形态：用 `rules`（事件→动作数组）的「规则型」插件同受支持，但**新插件请用 `script`**。

---

## 2. 事件（用 `on(事件名, 处理函数)` 订阅）

处理函数收到一个对象 `ctx`，字段如下：

| 事件名 | 触发时机 | `ctx` 字段 |
|---|---|---|
| `appStart` | 桌宠服务启动后约 1.5 秒 | 无 |
| `balanceDecreased` | 余额**减少**时（App 每 3 秒静默查询） | `value`(Number 新余额)、`prev`(Number 旧余额) |
| `batteryLow` | **刚**进入低电量（≤20% 且未充电） | `level`(Number 百分比) |
| `screenOff` / `screenOn` | 息屏 / 亮屏 | 无 |
| `tap` | 单击鲸鱼娘 | 无 |
| `doubleTap` | 双击鲸鱼娘 | 无 |

**注意**
- 事件是"通知式"的：错过不补发，不做队列。
- `ctx` 只有上表字段，**不要依赖其它字段**。
- 同一个事件可以注册多个处理函数；异常会被隔离（写审计日志，不影响其它插件）。

示例：
```js
on('balanceDecreased', function (ctx) {
  var used = ctx.prev - ctx.value;
  say('又消耗了 ' + used.toFixed(2) + ' 元');
});
```

---

## 3. 动作 API（能力）

| API | 能力名 | 参数 | 敏感 | 返回 |
|---|---|---|---|---|
| `say(text)` | `say` | 文本 | 否 | `ok` |
| `notify(title, text)` | `notify` | 标题、正文 | 否 | `ok` |
| `vibrate(ms)` | `vibrate` | 毫秒（1~5000，默认 300） | 否 | `ok` |
| `openUrl(url)` | `openUrl` | 完整 URL（需带 scheme） | ✅ | `ok` / `error: 无法打开` |
| `openApp(pkg)` | `openApp` | 应用包名 | ✅ | `ok` / `error: 应用不存在` |
| `shizuku(...args)` | `shizuku` | 白名单命令参数 | ✅ | 命令输出诊断串（见 §5） |
| `log(text)` | —（无需能力） | 文本 | 否 | `ok` |

**返回值语义**（脚本可据此分支）：
| 返回 | 含义 |
|---|---|
| `ok` | 已执行 |
| `denied: 未授权 …` | 该能力未被用户授权 |
| `pending: 等待用户确认` | 敏感操作已弹出确认条，用户尚未选择；**确认通过后才会真正执行** |
| `error: …` | 执行失败（参数错误、系统拒绝等） |

**重要**：敏感能力是**异步确认**的 —— `openApp()` 返回 `pending` 不代表已打开，真正打开发生在用户点「允许」之后。

---

## 4. 用户侧的授权与确认（插件作者必须理解）

1. **安装时**：App 弹出能力授权框，用户逐项勾选；未勾选的能力调用**一律被拒**。
   **覆盖安装（同一个 `id` 重装）会清空该插件此前的全部授权**（含"始终允许"），必须重新勾选——
   否则一份同 id 的新脚本就能直接继承旧脚本的能力，等于绕过确认。
2. **敏感能力**（`openUrl`/`openApp`/`shizuku`）：即使已授权，**每次执行**也会弹悬浮确认条：
   - 「允许」→ 本次执行
   - 「始终允许」→ 该插件的该能力以后不再询问
   - 「拒绝」或 **15 秒未操作** → 视为拒绝
3. **全程审计**：每次调用（含被拒、失败）写入 `Download/whale-plugins.log.txt`。

> 因此插件**不要设计成"依赖静默执行"**：敏感动作可能被拒绝，代码要考虑返回值并优雅降级。

---

## 5. `shizuku` 的可用范围（受限）

插件只能执行 **App 白名单内的命令**，且目标包名必须是本应用：

| 命令 | 用途 |
|---|---|
| `shizuku('appops','set','<pkg>','SYSTEM_ALERT_WINDOW','allow')` | 悬浮窗授权 |
| `shizuku('pm','grant','<pkg>','android.permission.POST_NOTIFICATIONS')` | 通知授权 |
| `shizuku('dumpsys','deviceidle','whitelist','+<pkg>')` | 电池优化白名单 |

- `<pkg>` 只能是 `com.dsha.whalepet` 或 `com.dsha.whalepet.debug`（**精确匹配**）
- 其它命令一律被拒（返回"拒绝执行：命令不在白名单内"）
- 白名单扩展需 App 侧改代码（见 §9），**插件方无法自行扩展**

---

## 6. 沙箱边界（能做什么 / 不能做什么）

**可用**：JS 标准对象（`Array`/`String`/`Math`/`JSON`）、上表 API、`on`。

**不可用（会抛错或被拒绝）**：
- ❌ 任何 Java 类：`Packages`、`java`、`javax`、`org`、`com`… 全局已删除
- ❌ `__bridge.getClass()` / `getClassLoader()` 等反射逃逸（ClassShutter 只放行 `__bridge` 本身）
- ❌ 文件读写、网络请求（未提供对应 API）
- ❌ 其它插件的作用域（每个插件独立作用域，互不可见）

**执行限制**：单次执行最多 **500 万条指令**，超时会被强制中止（抛错、写审计），脚本的 `try/catch` 拦不住。
- 结论：**不要写死循环或重负载循环**；长任务应拆成多次事件触发。
- 插件运行在独立后台线程：插件卡住**不会**影响桌宠动画与 UI，但会阻塞其它插件的执行。

---

## 7. 审计日志格式

文件：`Download/whale-plugins.log.txt`（1MB 自动清除历史，同名变体自动收敛）

```
MM-dd HH:mm:ss | 插件id | 事件或动作类别 | 动作描述 | 结果 | 详情
```

结果取值：`ok` / `denied` / `error`；另有 `grant`（授权）、`revoke`（撤销）、`load`（加载）。

> 插件可控的字段（`id`、动作描述、`log()` 正文）里的换行会被转义成字面量 `\n`，
> 保证**一次调用只占一行**。别试图用它伪造日志——转义后只会留下痕迹。

示例：
```
10-11 23:16:50 | sample-starter | grant | 授权能力：say | ok
10-11 23:22:02 | sample-starter | load | 脚本已加载 | ok
10-11 23:25:10 | probe-ungranted | call | 打开应用：com.tencent.mm | denied | 未授权能力：打开其他应用
```

调试建议：把关键分支用 `log()` 打点，用户跑一次后你就能从日志看到实际路径。

---

## 8. 开发与验证建议

1. 先写最小插件（`on('doubleTap', …)` + `say`），确认链路通
2. 用 `docs/probes/` 里的三个探针确认沙箱/超时/未授权拦截
3. 复杂逻辑用 `log()` 打点，别靠猜
4. 插件正文建议保持在**几十行以内**；重逻辑说明事件选错了

---

## 9. 契约变更流程

新增/修改事件或能力，必须由 **App 侧**执行：

1. `PluginCaps` 登记能力（含是否敏感）
2. `PluginManager.handleCall` 实现授权 + 确认 + 审计
3. `PluginEngine` 胶水暴露对应函数，**并同步 ClassShutter 白名单**（若涉及新的 Java 类型）
4. 更新本文件与 `apiVersion`，在 App 版本说明里注明
5. 现有插件**向后兼容**：不删除已有事件/能力名，只增不改

> 插件侧有需求 → 提给 App 侧 → App 侧实现后更新本文档 → 插件侧再使用。**不要假设未列入本文件的能力可用。**
