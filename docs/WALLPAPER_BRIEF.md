# 鲸鱼娘桌宠 → 动态壁纸：给壁纸方向接手的交底

> 目的：把「鲸鱼娘桌宠」的现状、边界、工程约束讲清楚，并列出我们需要壁纸方向专家回答的问题与交付物。
> 对方已交付过一份「人工桌面 · ROLE 能力」逆向资料包（WallpaperService / GL 渲染 / 事件投递），本文只谈**我们自己的项目**，不要求对方使用其逆向代码。

---

## 0. 一页速览

| 项 | 内容 |
|---|---|
| 项目 | 鲸鱼娘桌宠（Android 悬浮窗桌宠） |
| 仓库 | https://github.com/Dp-Holo/DSH-whale-pet-android （公开） |
| 当前版本 | `1.1.85` |
| 形态 | 单 Activity + 前台服务 + `TYPE_APPLICATION_OVERLAY` 悬浮窗 |
| 渲染方式 | 单张 2D 图（ImageView）+ `Choreographer` 帧循环 + `WindowManager.updateViewLayout` |
| 交互 | 单击说话 / 双击查余额 / 拖动 / 贴边反弹 / 自主游动 |
| 插件 | Rhino 沙箱 JS 插件，能力授权 + 敏感确认 + 审计 |
| 构建 | GitHub Actions（debug + 签名 release），本地无 Android SDK |
| 新目标 | 把桌宠扩展到**动态壁纸**（Live Wallpaper）形态 |

---

## 1. 项目是什么

一个常驻桌面的小宠物：

- 一只鲸鱼娘在屏幕上**自主游动**，可拖动、会贴边弹开；
- **单击**说一句台词（台词池可编辑，分普通 / 充值 / 消费三类）；
- **双击**查询 DeepSeek 账户余额，显示在头顶；
- 余额变化时冒台词 + 在脚下显示红色 `-0.05` / 绿色 `+0.10`；
- 有**插件系统**：插件可监听事件（启动、点击、余额变化、低电量、亮屏/息屏…），调用能力（说话、通知、震动、打开网页/应用、Shizuku 命令、联网）；
- 插件能力需逐项授权，敏感操作每次二次确认，全部调用写审计日志。

用户群是单人自用（作者本人），所以优先级是**观感与趣味**，不是商业化。

---

## 2. 技术盘点

### 2.1 工具链

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.0.20 |
| AGP / Gradle | 8.5.2 / 8.9 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 主要依赖 | Material 1.12.0、Rhino（rhino-android 1.6.0 + rhino-runtime 1.7.15.1） |
| 构建 | GitHub Actions；`./gradlew test` 先跑，再出 APK |

### 2.2 关键类

| 类 | 职责 |
|---|---|
| `MainActivity` | 四页界面（插件/主页/台词/设置）+ 右侧抽屉菜单 + 右下角 FAB |
| `WhalePetService` | 前台服务：悬浮窗、游动帧循环、点击/拖动、余额探测、事件广播、插件事件源 |
| `BalanceFetcher` | DeepSeek 余额查询（后台线程池、超时、金额格式化） |
| `ShizukuHelper` / `ShizukuCommandService` | 免 root 授权、白名单系统命令执行 |
| `WhaleLines` | 台词池（分三类、本机存储） |
| `PluginEntry` | 插件宿主入口（生命周期、事件） |
| `PluginManager` | 能力授权、敏感确认、审计、事件分发 |
| `PluginEngine` / `ScriptSandbox` | Rhino 沙箱执行（ClassShutter + 指令上限） |
| `PluginUi` / `PluginPage` | 插件管理界面 |

### 2.3 渲染与坐标（重要，壁纸化会复用这套逻辑）

- 窗口比本体大 25%（`windowPx = sizePx * 1.25`），本体靠 padding 居中，给点击缩放留余量；
- 帧循环用 `Choreographer` 对齐 vsync，高刷屏不会变快，掉帧有位移上限；
- **窗口坐标原点不在物理屏左上，而在状态栏下方**（实测偏移约 161px），必须用 `getLocationOnScreen` 实测校准；
- 触底软排斥 + 松手弹开，避免"贴底静止"。

### 2.4 插件系统（可作为壁纸的内容/交互扩展层）

| 事件 | 触发 |
|---|---|
| `appStart` | 桌宠服务启动 |
| `tap` / `doubleTap` | 单击 / 双击 |
| `balanceDecreased` | 余额减少（带 `value`/`prev`） |
| `batteryLow` | 进入低电量（带 `level`） |
| `screenOn` / `screenOff` | 亮屏 / 息屏 |

| 动作（能力） | 说明 |
|---|---|
| `say` / `notify` / `vibrate` | 非敏感 |
| `openUrl` / `openApp` / `shizuku` / `network` | 敏感：每次二次确认，可选"始终允许" |

沙箱：`ContextFactory` + `ClassShutter`（只放行 bridge 与 `java.lang.String`）、单次 500 万条指令上限、全部调用写审计日志（`Download/whale-plugins.log.txt`）。

### 2.5 工程约束（对方交付代码时必须遵守）

1. **只能走 GitHub Actions 构建**：本机没有 Android SDK，`./gradlew assembleDebug` 在本地跑不了；
2. 版本号规则：App 侧递增数字（`1.1.x`），插件侧用 `<当前版本>-Extension`；
3. 全部中文注释，说明"为什么"；新增 UI 文案进 `res/values/strings.xml`；
4. 改安全边界（白名单/能力判定/插件解析）必须带 JVM 单测；
5. 不提交密钥（签名 keystore 只在 GitHub Secrets）。

---

## 3. 壁纸化：本质差异与候选架构

### 3.1 差异

| 维度 | 现有悬浮窗 | 动态壁纸 |
|---|---|---|
| 层级 | 永远在最上层，覆盖所有 App | 只在桌面/锁屏**背景**，被图标覆盖 |
| 权限 | 需要悬浮窗权限（可用 Shizuku 自动授予） | 不需要悬浮窗权限 |
| 交互 | 全屏可点、可拖 | 仅桌面可见时可收触摸，且会被 launcher 手势抢 |
| 生命周期 | 前台服务 | `WallpaperService.Engine`，由系统绑定/解绑 |
| 渲染 | View + WindowManager 更新布局 | 需要画到 `Surface`（Canvas 或 GL） |
| 性能 | 单窗口 | 壁纸常驻，耗电与内存更敏感 |

**结论**：壁纸不是"把悬浮窗挪个层"，渲染与生命周期都要重写；但**宠物的状态模型（位置/朝向/缩放/动作）可以完全复用**。

### 3.2 三个候选架构

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 纯壁纸** | 只做 `WallpaperService`，宠物活在桌面背景 | 无需悬浮窗权限、观感原生、可与桌面图标融合 | 其他 App 上完全看不见；触摸受限；气泡/余额显示也要画在壁纸上 |
| **B. 壁纸 + 悬浮窗混合** | 壁纸负责环境动画，悬浮窗负责交互 | 交互能力保留 | 两套渲染、可能出现"两只鲸鱼娘"；仍需悬浮窗权限 |
| **C. 共享渲染核心 + 双后端**（推荐） | 抽出 `PetState`（位置/朝向/缩放）与 `PetRenderer` 接口；Overlay 后端保持现状，Wallpaper 后端新写 | 逻辑只写一份，两种形态都能跑 | 需要一次重构（把渲染与状态分离） |

**我们倾向 C**，但需要对方从壁纸工程角度判断这个拆分是否合理。

---

## 4. 需要对方回答的问题（请逐条回，编号对应）

**Q1 生命周期**：`WallpaperService.Engine` 的 `onSurfaceCreated/Destroyed`、`onVisibilityChanged`、`onOffsetsChanged` 在主流 ROM 上的实际行为与坑？切桌面、锁屏、分屏、旋转时分别会收到什么？

**Q2 渲染选型**：单张 2D 图 + 位移/缩放/水平翻转，目标 60fps、低耗电——用 `Canvas`（`lockCanvas`/`lockHardwareCanvas`）够吗？还是必须 GL？各自在低端机上的表现？

**Q3 触摸**：壁纸上如何可靠地拿到 tap / double tap？`onCommand("android.wallpaper.tap")` 与 `Engine.onTouchEvent` 哪个更可靠？launcher 的上滑/下滑手势会吃掉哪些事件？有没有"双击必丢"的已知问题？

**Q4 尺寸与形变**：多分辨率、挖孔、折叠屏、平板、横竖屏旋转时，Surface 尺寸如何变化？如何避免拉伸/裁切？

**Q5 锁屏**：Android 13+ 锁屏壁纸独立后，动态壁纸在锁屏上还渲染吗？`FLAG_SHOW_WALLPAPER` 相关的行为差异？

**Q6 省电**：不可见时应该完全停止帧循环吗？`onVisibilityChanged(false)` 后系统还会不会继续调度？充电/省电模式下有什么额外限制？

**Q7 素材格式**：静态图 vs 序列帧 vs APNG/WebP 动画 vs 视频（他们做过视频壁纸）。对"一只会游动的 2D 角色"，哪种最划算（体积/内存/流畅度）？

**Q8 系统事件**：在壁纸服务里监听电量/充电/耳机/锁屏广播是否可行？有没有"壁纸服务里注册广播被限制"的坑？

**Q9 共存**：如果将来同时保留悬浮窗和壁纸，怎么避免"两只鲸鱼娘"？有没有"只让其中一个可见"的系统级信号（例如当前是否在桌面）？

**Q10 兼容性**：请给出你们踩过的机型/launcher/厂商 ROM 坑清单（小米/华为/三星/Pixel 优先）。

**Q11 复用判断**：我们现有渲染是 `Choreographer` + `View` + `WindowManager.updateViewLayout`。在壁纸里能否复用这套"状态+动画"代码（只把输出改成画到 Surface）？还是必须重写成独立渲染循环？

**Q12 可靠性**：你们资料包里提到"V8 事件投递静默丢弃 / reload 窗口丢事件 / 可见性卡死"。映射到壁纸场景，我们最该防的是哪几类？（例如：不可见时仍投事件、Surface 重建期间丢帧、系统解绑后回调继续跑）

---

## 5. 我们能提供什么

- **插件系统**：壁纸上的交互/内容扩展可以直接复用（事件 + 能力 + 授权 + 审计），不必重造；
- **事件总线经验**：我们已知"静默丢弃"是坑，计划加投递计数与审计；
- **余额检测 / Shizuku 白名单 / CI 流水线 / 设计 token（颜色字号间距）**；
- **真实设备与用户反馈闭环**：小米 24122RKC7C（Android 16，1440×3200），可随时真机验证并回传日志。

---

## 6. 分工建议与接口草案

| 角色 | 负责 |
|---|---|
| **对方（壁纸方向）** | `WallpaperService.Engine` 骨架、渲染后端（Canvas/GL）、生命周期与可见性、触摸、兼容性清单 |
| **我方（App 本体）** | `PetState`/`PetRenderer` 抽象、状态机、插件桥、UI、发布与回滚 |

接口草案（Kotlin，可讨论）：

```kotlin
/** 渲染无关的宠物状态：位置单位 px，坐标系为屏幕坐标 */
data class PetFrame(
    val x: Float, val y: Float,
    val scale: Float, val rotation: Float,
    val facing: Int,   // 1 = 原图，-1 = 镜像
    val alpha: Float
)

/** 渲染后端：悬浮窗与壁纸各实现一份 */
interface PetRenderer {
    fun attach(width: Int, height: Int)
    fun render(frame: PetFrame, nowMs: Long)
    fun detach()
}
```

---

## 7. 交付物期望

| # | 交付 | 说明 |
|---|---|---|
| D1 | **架构评审意见** | 对第 3 节三个方案的选型结论与理由 |
| D2 | **最小可行壁纸实现** | `Engine` + 渲染 + 生命周期 + 触摸，能编译进我们仓库（Kotlin） |
| D3 | **兼容性/风险清单** | 第 4 节 Q10/Q12 的答案 |
| D4 | **分阶段计划** | MVP → 增强，每阶段可独立验收 |

### 里程碑建议

- **M1**：壁纸能显示静止的鲸鱼娘（Surface 渲染 + 尺寸适配）
- **M2**：动起来（游动 + 朝向 + 贴边）
- **M3**：触摸（单击/双击）+ 台词显示
- **M4**：余额/状态联动（复用现有探测与文案）
- **M5**：插件事件桥接（壁纸态也能触发插件）

---

## 8. 合规与边界

- 对方资料包中的反编译代码/资源**版权归 miHoYo**，只借鉴架构与思路，**不复制代码/素材**；
- 我们的插件脚本由用户自写，运行在沙箱内；壁纸化不改变这一点；
- 任何新增 Android 权限必须说明用途（壁纸本身不需要悬浮窗权限，这是收益之一）。

---

## 9. 阅读清单（给接手方）

1. `README.md` — 产品说明与功能
2. `docs/ARCHITECTURE.md` — 三层结构与关键链路（Mermaid 图）
3. `docs/PLUGIN_SPEC.md` — 插件契约（事件/动作/能力）
4. `app/src/main/java/com/dsha/whalepet/WhalePetService.kt` — 渲染、事件源、生命周期
5. `app/src/main/java/com/dsha/whalepet/plugin/PluginEntry.kt` / `PluginManager.kt` — 插件桥
