# 架构说明（ARCHITECTURE）

> 面向接手者：**用图讲清"谁调用谁、数据怎么流"**，配合 [`HANDOVER.md`](HANDOVER.md)（文件与流程）与 [`PLUGIN_SPEC.md`](PLUGIN_SPEC.md)（插件契约）阅读。
> 本文所有图均为 Mermaid 文本，GitHub 页面会自动渲染为图形。

---

## 1. 三层结构总览

App 本体与插件宿主之间**只有三个接口**；插件宿主与插件内容之间只有**一份契约**。

```mermaid
flowchart TB
    subgraph APP["🏠 App 本体（维护方 A）"]
        direction LR
        A1["MainActivity<br/>三页界面"]
        A2["WhalePetService<br/>前台服务 · 悬浮窗 · 漫游"]
        A3["BalanceFetcher<br/>余额查询"]
        A4["ShizukuHelper / ShizukuCommandService<br/>免 root 授权 · 白名单命令"]
        A5["WhaleLines<br/>台词池"]
    end

    subgraph BRIDGE["🔌 兼容层（只有 3 个接口，双方约定不变）"]
        B1["PluginAppBridge<br/>① App 提供的基础能力"]
        B2["PluginEntry<br/>② 生命周期与事件<br/>③ 管理界面入口"]
    end

    subgraph HOST["🧩 插件宿主（维护方 B）"]
        C1["PluginManager<br/>能力授权 · 审计 · 事件分发"]
        C2["PluginEngine<br/>Rhino 沙箱"]
        C3["PluginUi<br/>插件管理界面"]
        C4["PluginConfirmOverlay<br/>敏感确认条"]
        C5["PluginLog<br/>审计日志"]
    end

    subgraph CONTENT["📦 插件内容（维护方 B）"]
        D1["plugins/*.json<br/>脚本型插件"]
    end

    A2 -->|实现| B1
    A1 -->|调用| B2
    A2 -->|调用| B2
    B2 -->|dispatch| C1
    B1 -->|提供能力| C1
    C1 --> C2
    C1 --> C4
    C1 --> C5
    C3 --> C1
    C2 -->|加载脚本| D1
```

**读法**：`A2`（服务）实现 `B1`（能力接口）并调用 `B2`（事件入口）；`B2` 把事件交给 `C1`；`C1` 负责校验与记账，`C2` 负责执行脚本，`C4` 负责敏感确认。

---

## 2. 关键链路①：双击 → 插件说话（普通能力）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant S as WhalePetService
    participant E as PluginEntry
    participant M as PluginManager
    participant G as PluginEngine
    participant J as 插件脚本

    U->>S: 双击鲸鱼娘
    S->>S: 判定为双击（300ms 内两击）
    S->>E: onEvent("doubleTap")
    E->>M: dispatch(event="doubleTap")
    M->>G: fireEvent(插件, "doubleTap")
    G->>J: 调用 on('doubleTap') 注册的处理函数
    J->>M: say("别点那么快嘛～")
    M->>M: 校验：能力已授权？（say 非敏感）
    M->>S: 经 PluginAppBridge.say(...)
    S->>U: 头顶气泡显示，2.2 秒后消失
    M->>M: 写审计：call | 说话：… | ok
```

---

## 3. 关键链路②：敏感操作 → 二次确认

`openUrl` / `openApp` / `shizuku` 属敏感能力：**即使已授权，每次也要用户点头**（可选"始终允许"）。

```mermaid
sequenceDiagram
    autonumber
    participant J as 插件脚本
    participant M as PluginManager
    participant O as PluginConfirmOverlay
    participant U as 用户
    participant S as WhalePetService

    J->>M: openApp("com.tencent.mm")
    M->>M: 能力已授权？→ 是
    M->>M: 属敏感且未"始终允许"？→ 是
    M->>O: 弹出确认条
    O->>U: 「允许 / 始终允许 / 拒绝」（15 秒未选＝拒绝）
    alt 用户点"允许"
        U-->>O: 允许
        O->>M: 回调 allowed=true
        M->>S: 执行 openApp
        M->>M: 审计 ok
    else 用户点"始终允许"
        U-->>O: 始终允许
        O->>M: 回调 allowed=true, remember=true
        M->>M: 记录该能力免询问
        M->>S: 执行 openApp
    else 用户拒绝或超时
        U-->>O: 拒绝 / 超时
        O->>M: 回调 allowed=false
        M->>M: 审计 denied（不执行）
    end
    Note over J,M: 脚本拿到的是 "pending: 等待用户确认"<br/>真正执行发生在用户点击之后（异步）
```

---

## 4. 插件生命周期（状态）

```mermaid
stateDiagram-v2
    [*] --> 已安装: 设置页粘贴 JSON 安装
    已安装 --> 已授权: 用户勾选能力
    已安装 --> 已停用: 用户停用
    已授权 --> 已加载: 服务启动预加载 / 事件到来
    已加载 --> 已加载: 事件触发并执行
    已加载 --> 已授权: 脚本内容变化（自动重载）
    已加载 --> 已停用: 用户停用
    已停用 --> 已加载: 用户启用（事件到来时加载）
    已授权 --> 已撤销: 撤销授权
    已撤销 --> 已安装: 重新授权
    已授权 --> [*]: 删除插件
    已停用 --> [*]: 删除插件
```

---

## 5. 余额检测的数据流（两条独立通道）

```mermaid
flowchart LR
    subgraph SHOW["展示通道（用户可见）"]
        T1["每 5 分钟"] --> Q1["查询余额 API"] --> P1["头顶浮字显示余额"]
    end
    subgraph WATCH["静默检测通道（3 秒）"]
        T2["每 3 秒"] --> Q2["查询余额 API"] --> C1{"比上次少？"}
        C1 -->|否| X1["忽略"]
        C1 -->|是| B1["气泡：正在偷吃用户token...<br/>（10 秒内最多一次）"]
        C1 -->|是| E1["通知插件：balanceDecreased<br/>携带 value / prev"]
    end
    subgraph LIFE["息屏联动"]
        SC["息屏"] --> STOP["两条通道都暂停"]
        SON["亮屏"] --> GO["立即恢复"]
    end
```

---

## 6. 目录归属（维护边界）

```mermaid
flowchart LR
    A["维护方 A<br/>App 本体"] --> A1["app/src/main/java/com/dsha/whalepet/*.kt"]
    A --> A2["res/ · .github/"]
    A --> A3["docs/ARCHITECTURE.md（本文）"]
    B["维护方 B<br/>插件宿主 + 内容"] --> B1["app/src/main/java/com/dsha/whalepet/plugin/**"]
    B --> B2["plugins/**"]
    B --> B3["docs/PLUGIN_SPEC.md · docs/probes/**"]
    A -.->|"接口不变即可独立改动"| B
```

---

## 7. 关键设计决策（为什么这样做）

| 决策 | 原因 | 代价 / 注意 |
|---|---|---|
| 悬浮窗边界用**实测偏移校准**（该机 162px） | 窗口坐标原点在状态栏下方，纯 `displayMetrics` 会算错导致触底静止 | 换设备需重新校准（代码自动做） |
| 窗口比本体大 **1.25 倍** | 点击放大时四周不被窗口裁剪 | 触摸热区比视觉略大 |
| 余额**检测与展示分离**（3 秒 / 5 分钟） | 想第一时间发现"被消耗"，又不想频繁打扰 | 每 3 秒一次网络请求（已做息屏暂停） |
| 插件脚本**只在后台单线程执行** | 既不卡 UI，又保证 Rhino 作用域线程安全 | 一个插件死循环会阻塞其它插件 |
| 沙箱用 **ClassShutter + 唯一 Java 入口** | 仅删全局变量挡不住 `__bridge.getClass()` 反射逃逸 | 新增暴露的 Java 类型必须同步进白名单 |
| 敏感能力**每次确认**（可记住） | 插件能力不小（打开应用、执行系统命令），需要用户把关 | 插件必须容忍"被拒绝" |
| 纯文档提交**不触发打包** | 否则同版本号会出现多个 APK，用户难以区分 | 文档改动不再产生构建记录 |
| 诊断/审计日志写入 **Download 且 1MB 自清除** | 真机排查第一手证据，又不会无限增长 | MediaStore 会自动补 `.txt` 后缀 |

---

## 8. 接手者最快路径（30 分钟）

1. 读 [`../AGENTS.md`](../AGENTS.md)（规矩与坑）→ 本文（结构与链路）→ [`HANDOVER.md`](HANDOVER.md)（流程与凭据）
2. 挑一条链路对照代码看：**双击→说话**（`WhalePetService` 的点击处理 → `PluginEntry.onEvent` → `PluginManager.dispatch` → `PluginEngine.fireEvent`）
3. 在真机上验证：装插件 → 双击 → 看 `Download/whale-plugins.log.txt` 是否出现 `call … ok`
4. 改一处小文案 → 提交 → 看 GitHub Actions 是否变绿 → 取 artifact 中的 APK
