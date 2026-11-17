# 壁纸方向评审与交付（D1–D4）

> 对应 `docs/WALLPAPER_BRIEF.md` 第 4/7 节。所有结论基于：
> ① 本仓库现有实现（悬浮窗 + Choreographer + WindowManager）；
> ② 我们在同机型（小米 24122RKC7C / Android 16 / HyperOS 3 / 1440×3200）上对另一款商业动态壁纸
> （人工桌面，CRI Mana + GL + V8）做的逆向分析与真机观测；
> ③ Android 官方 `WallpaperService` 语义。
> 本文件不含任何 miHoYo 反编译代码/素材，只引用其可观察行为与架构结论。

---

## D1 架构评审：三个方案的选型结论

**结论：选 C（共享渲染核心 + 双后端），但分两步落地，不要一次性重构。**

| 方案 | 判断 | 理由 |
|---|---|---|
| A 纯壁纸 | 不选（作为最终形态） | 交互与"随时可见"是桌宠的核心卖点；壁纸在别的 App 上完全不可见，等于砍掉一半功能 |
| B 壁纸 + 悬浮窗并行 | 不选（作为默认） | 两套渲染、两只鲸鱼娘、两套生命周期；用户会困惑，维护成本最高 |
| **C 共享核心 + 双后端** | **选** | 状态/动作/台词只写一份；壁纸负责"环境感"，悬浮窗负责"可交互"，按开关二选一，必要时可同时开（用可见性互斥） |

C 的落地细节（比交底里的草案多三点约束）：

1. **`PetFrame` / `PetRenderer` 按草案保留**，只给 `PetFrame` 增加一个 `bubble: String?` 字段——
   气泡是"宠物状态"的一部分（有生命周期、会过期），放进渲染参数比让渲染端反查状态更干净；
   如果坚持草案签名，也可以由渲染后端实现 `BubbleAware` 接口，二选一，我按前者实现了。
2. **状态层必须纯 Kotlin**：不 import 任何 `android.view.*`。这样 `./gradlew test` 能覆盖运动/边界/气泡，
   也方便以后做桌面端/Web 预览。
3. **迁移分两步**：先让壁纸端独立实现一份运动逻辑（本次 D2 的做法），悬浮窗代码**一行不动**；
   等壁纸形态验收通过，再把两边共用的部分抽成 `PetMotion`（见 D4 的 M2.5），避免"边改边坏"。

---

## 第 4 节 12 个问题的逐条回答

### Q1 生命周期：Engine 各回调的实际行为与坑

- `onCreate(holder)`：Engine 建立时调用一次；**此时 Surface 还不可用**，只能做初始化（开触摸、建状态）。
- `onSurfaceCreated` → `onSurfaceChanged(w,h)`：Surface 创建后紧随一次 changed；旋转/分屏/折叠/分辨率变化时，
  常见序列是 `onSurfaceDestroyed → onSurfaceCreated → onSurfaceChanged`，**Engine 本身通常不重建**。
- `onVisibilityChanged(boolean)`：桌面可见/被全屏应用覆盖/息屏/锁屏时切换。它是"是否该跑帧"的唯一可靠信号，
  但**不能当唯一真相**：部分 ROM 在通知栏、对话框、分屏下判定不一致，且可能与 `onSurfaceChanged` 乱序。
- `onOffsetsChanged`：launcher 翻页时回调；只有设了 `setOffsetNotificationsEnabled(true)` 才稳定收到
  （API 24+ 默认关闭），且各家 launcher 行为不同——**不要依赖它做核心逻辑**。
- `onSurfaceRedrawNeeded`（API 24+）：要求重绘一帧；实现里应能"无状态地再画一次"。
- 多 Engine：系统可能同时存在"预览用"和"真实壁纸"两个 Engine，**任何静态/单例状态都会互相污染**；
  本实现所有状态都放在 Engine 实例里。
- 实测坑（同机型）：MIUI/HyperOS 的 launcher 在"上滑回桌面/通知栏下拉/最近任务"时会播放壁纸缩放与模糊动画，
  短时间内可能连续触发可见性变化；**帧循环必须幂等**（重复 start 不能叠加回调）。
- 还有一个系统级坑：壁纸进程被系统 force-stop 后，**不会自动重新绑定**（我们在人工桌面上实测：6 分钟不回来）。
  所以壁纸端不要依赖"进程常驻"做状态累积；每次 `onSurfaceCreated` 都应当能从一个干净状态恢复。

### Q2 渲染选型：Canvas 够不够？要不要 GL？

- **单张 2D 图 + 位移/缩放/镜像：`lockHardwareCanvas()` 足够**，并且是首选。
  - `lockHardwareCanvas`（API 23+）走 GPU 渲染线程，画一张 bitmap + 一个圆角气泡的负载极低；
  - 60fps 的关键不是 Canvas 还是 GL，而是**每帧不要分配对象**（Paint/Matrix/RectF 复用）、不要每帧 decode/缩放位图；
  - 低端机上 Canvas 的优势是驱动兼容性好、崩溃面小；GL 需要自己处理 EGL 上下文丢失、Surface 重建、纹理重建，
    对"一张图"是过度设计。
- **什么时候才该上 GL**：多图层粒子、实时滤镜（模糊/扭曲）、需要 shader 的形变、同屏大量精灵。
  真到那一步，`PetRenderer` 接口不变，加一个 `GlPetRenderer` 即可。
- 实测建议：先用 Canvas 出 M1–M2，如果 60fps 掉帧（用 `Choreographer` 的帧间隔统计），再评估 GL。
- 另一个省电点：**不可见就完全停帧**（见 Q6），不要用"低帧率空转"。

### Q3 触摸：tap / double tap 怎么拿才可靠？

- **用 `Engine.onTouchEvent` + `GestureDetector`**，不要依赖 `onCommand("android.wallpaper.tap")`：
  后者只有少数 launcher 会发，且不带坐标，不能作为主路径。
- `setTouchEventsEnabled(true)` 必须显式打开（默认关闭）。
- **会被 launcher 吃掉的手势**：
  - 纵向滑动（上滑进应用抽屉/最近任务、下滑拉通知栏）——基本收不到；
  - 横向翻页——通常被 launcher 的 ViewPager 抢走；
  - 长按——多数 launcher 用于"壁纸/小部件"菜单，**不要做长按交互**；
  - 双击——部分 ROM 把"双击桌面"绑定为锁屏/息屏，会被系统抢走；这是"双击有时没反应"的主因，不是你的代码问题。
- 可靠做法：
  - 单击用 `onSingleTapConfirmed`（等双击窗口过去再触发，避免"单击误触发"）；
  - 双击用 `onDoubleTap`，但要接受"可能被 ROM 抢走"，**双击只做锦上添花**（本实现里是"跳一下"），
    核心交互（说话）放在单击；
  - `onDown` 返回 `true`，否则后续事件收不到；
  - 不要在 `onTouchEvent` 里做耗时操作（读写 SharedPreferences 可以在 tap 回调里做，量很小）。
- 已知问题：在壁纸可见但 launcher 正在做转场动画时，触摸事件可能延迟或丢失——这是系统行为，无法完全规避；
  交互反馈不要依赖"必须收到 UP"。

### Q4 尺寸与形变：多分辨率/挖孔/折叠/旋转怎么处理？

- `onSurfaceChanged(w,h)` 给的是**当前 Surface 的像素尺寸**，旋转/分屏/折叠会重新回调；不要缓存一次就完事。
- 避免拉伸：**按短边等比缩放**，而不是分别缩放到 w/h（本实现用 `petSizePx / bitmap.width` 的等比缩放）。
- 挖孔/圆角：`WallpaperService` 不是窗口，拿不到 `WindowInsets`；用**保守安全边距**（例如四周留 6–8% 或至少一个状态栏高度）
  比试图精确避让更稳；本实现把运动边界按"本体半径"内缩，天然避开大部分遮挡。
- 旋转：`onSurfaceChanged` 后用 `clampTo(w,h)` 把位置夹回新边界（本实现已做），否则横屏时鲸鱼会跑到屏幕外。
- 折叠屏：展开/折叠会连续触发尺寸变化；帧循环不要在这期间重建渲染器（本实现只在 Surface 销毁时释放）。

### Q5 锁屏：Android 13+ 锁屏壁纸独立后，动态壁纸还渲染吗？

- Android 13 起系统支持"锁屏壁纸与桌面壁纸分开"，但**是否把动态壁纸用到锁屏由 ROM 决定**：
  小米/华为通常沿用同一动态壁纸（或缓存一帧静态图），Pixel 更倾向锁屏用静态图。
- 对开发者的含义：**不要假设锁屏时会渲染**，也不要依赖锁屏做状态（例如"锁屏时切睡眠态"要能容忍不回调）。
- `FLAG_SHOW_WALLPAPER` 是 Activity 的窗口标志（让 Activity 透明显示壁纸），与壁纸服务无关，别混用。
- 建议：把"锁屏/息屏"当作 `visible=false` 处理即可；恢复时按当前 `visible` 重新起循环。

### Q6 省电：不可见时要不要停？系统还会调度吗？

- **必须停**。`onVisibilityChanged(false)` 后立即 `removeFrameCallback`；不可见期间没有任何理由画帧。
- 系统不会"替你省"：只要你还挂着 Choreographer 回调，它就会按 vsync 调度（哪怕 Surface 不可见），
  这是纯耗电；部分 ROM 还会因为频繁唤醒把进程当成"高耗电"限制掉。
- 额外限制：
  - 省电模式下帧率可能被压、vsync 回调变慢——用"按真实帧间隔缩放位移"（本实现 `dtScale`）保证速度不变；
  - 充电状态不影响调度，但可以作为"允许更活跃动画"的信号（可选）；
  - 息屏时 `onVisibilityChanged(false)` 通常一定会来；如果某些 ROM 不发，可用 `ACTION_SCREEN_OFF` 兜底（Q8）。
- 建议指标：静止/不可见时 CPU ≈ 0；可见游动时单核 < 3%（本机型人工桌面实测 1.5–3%，可作为上限参考）。

### Q7 素材格式：静态图 / 序列帧 / APNG / WebP 动画 / 视频？

对"一只会游动的 2D 角色"，按性价比排序：

1. **单张静态图 + 运行时变换（本实现）**：体积最小、内存最省、最稳；游动感来自位移/朝向/缩放，不需要逐帧素材。
2. **序列帧（sprite sheet）**：只有在"角色本身要做动作（摆尾/眨眼）"时才值得；代价是内存（一帧 512×512×4B≈1MB，
   30 帧就是 30MB）和加载时间。建议用**图集 + 按需解码**，不要一次 decode 全部。
3. **APNG / 动画 WebP**：解码器逐帧解，CPU 不低、控制粒度差；Android 上兼容性与内存都不如 sprite sheet。
4. **视频（他们做过）**：观感最好，但耗电、内存、解码器占用都最高；桌宠这种"小面积、低帧率需求"不划算。

结论：**静态图先行**；要做摆尾再上 sprite sheet；不建议为了桌宠上视频。

### Q8 系统事件：壁纸服务里监听电量/充电/耳机/锁屏广播可行吗？

- 可行。`WallpaperService` 是**被系统绑定的 Service**，跑在应用进程里，运行时注册的广播接收器正常收到。
- 注意 Android 14+ 的限制主要针对**清单里静态注册的隐式广播**；运行时 `registerReceiver` 不受影响。
- 常用且可靠：
  - `ACTION_BATTERY_CHANGED`（sticky，可直接 `registerReceiver(null, filter)` 读当前值）；
  - `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`（运行时注册）；
  - `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`；
  - 耳机：`AudioManager.ACTION_AUDIO_BECOMING_NOISY` 或 `AudioManager` 的设备回调（后者更现代）。
- 注册/反注册放在 `Engine.onCreate/onDestroy`，**不要放 `Service.onCreate`**（多 Engine 会重复注册）。
- 不要在广播里做重活；只改状态，渲染层下一帧自然生效。

### Q9 共存：如何避免"两只鲸鱼娘"？

- 系统**没有**公开的"当前是否在桌面"信号（UsageStats/Accessibility 都不适合作为默认方案）。
- 最可靠的代理信号就是 **`onVisibilityChanged`**：壁纸可见 ≈ 桌面在前台（通知栏/对话框等半透明场景可能误判）。
- 推荐两种策略，二选一：
  1. **模式开关（默认，简单可靠）**：设置里选"悬浮窗 / 壁纸 / 两者"，选其一时另一个后端不启动。
  2. **可见性互斥（进阶）**：两个后端共享一个进程内 `PetPresence`（壁纸可见 → 悬浮窗 `removeView`；
     壁纸不可见 → 恢复悬浮窗）。要接受"通知栏展开时壁纸仍可见 → 悬浮窗被误隐藏"的边界情况。
- 本仓库的 App 与壁纸服务默认同进程，**用单例 + `@Volatile` 即可**，不需要 AIDL/Binder。

### Q10 兼容性：机型 / launcher / ROM 坑清单

见 D3 的清单（单独成节）。

### Q11 复用判断：现有"Choreographer + View + WindowManager"能直接复用吗？

- **状态与动画逻辑可以复用，渲染输出必须重写**：
  - 可复用：位置/速度/角度/朝向/缩放的推进、边界排斥、帧率无关的 `dtScale`、台词池、余额数据；
  - 必须重写：`WindowManager.updateViewLayout`、窗口 padding/尺寸（壁纸没有窗口）、`View.animate()`（壁纸没有 View）、
    状态栏偏移校准（壁纸坐标原点就是 Surface 左上角，不需要那 162px 校准）。
- 建议路径：先把漫游逻辑从 `WhalePetService` 抽成 **`PetMotion`（纯 Kotlin）**，悬浮窗后端只是"把 PetMotion 的结果
  写进 WindowManager.LayoutParams"，壁纸后端"把结果画到 Canvas"。这就是 C 方案的终态。
- 本次交付为了不碰已验证的悬浮窗代码，壁纸端先独立实现了一份同参数的运动逻辑；M2.5 再合并。

### Q12 可靠性：映射到壁纸场景，最该防哪几类？

见 D3 的"可靠性风险清单"（Q12 专项）。

---

## D3 兼容性 / 风险清单（Q10 + Q12）

### 机型 / ROM / launcher

| 对象 | 已知行为/坑 | 应对 |
|---|---|---|
| 小米 / HyperOS（本机实测） | 壁纸进程可能被杀且**不自动重绑**；launcher 转场会触发壁纸缩放/模糊，可见性回调抖动；双击可能被系统"锁屏"占用 | 每次 Surface 创建都从干净状态恢复；帧循环幂等；双击只做非核心交互 |
| 华为 / EMUI | 后台管理激进，壁纸服务可能被冻结；锁屏常沿用同一壁纸 | 不可见即停帧；不要依赖常驻计时器 |
| 三星 One UI | Good Lock 可单独设置锁屏壁纸；部分机型壁纸服务会被省电策略限制 | 同上；锁屏行为按"可能不渲染"设计 |
| Pixel / AOSP | Android 13+ 锁屏壁纸独立，动态壁纸通常只用于桌面；双击桌面可被系统占用 | 不假设锁屏渲染；双击降级 |
| 折叠屏/平板 | 尺寸频繁变化、宽高比差异大 | `onSurfaceChanged` 重新适配 + `clampTo`；按短边等比缩放 |
| 分屏/自由窗口 | 壁纸只在桌面可见时出现，尺寸可能变化 | 以可见性驱动循环 |
| 手势导航 | 上滑/下滑手势优先于壁纸触摸 | 核心交互放单击；不要依赖滑动 |

### 可靠性风险（Q12 专项，按优先级）

1. **不可见仍在跑帧**：最伤电，也最容易被系统限制。→ 可见性门控 + 停循环（已实现）。
2. **Surface 销毁/重建期间丢帧或崩溃**：`lockCanvas` 抛异常、Bitmap 已回收。→ `try/catch` 丢帧、
   先停循环再 `detach`（已实现）；3 次连续失败自动停循环。
3. **Engine 多实例/预览态**：静态状态互相污染。→ 所有状态放 Engine 实例（已实现）。
4. **系统解绑后回调继续跑**：`onDestroy` 后仍有帧回调。→ `running` 标志 + `removeFrameCallback`（已实现）。
5. **可见性乱序/抖动**：连续 true/false 导致循环叠加或提前停。→ `startLoop` 幂等（`running` 判断，已实现）。
6. **旋转/分屏后坐标越界**：→ `clampTo`（已实现）。
7. **长按/双击被 launcher 抢**：→ 只做单击核心交互（已实现）。
8. **壁纸进程被杀不重绑**：→ 不依赖进程内持久状态；每次 Surface 创建重新初始化（已实现）。
9. **气泡/文字资源泄漏**：每帧 new Paint/StaticLayout。→ 画笔复用；气泡只在有文字时构建（已实现，StaticLayout 每帧构建可再优化为缓存）。

---

## D4 分阶段计划

> 本次 D2 已实现 M1–M3 的骨架（静止 → 游动 → 单击/双击），下面标注每阶段的验收点与后续工作。

| 阶段 | 内容 | 验收标准 | 状态 |
|---|---|---|---|
| **M1 静止显示** | Engine + Canvas 渲染 + 尺寸适配 | 设为壁纸后能看到鲸鱼娘，不拉伸、不越界；旋转后仍在屏内 | ✅ 已实现 |
| **M2 动起来** | 角度漫游 + 朝向 + 边界反弹 + 帧率无关 | 60fps（掉帧率 < 5%）；不可见时 CPU≈0；120Hz 不加速 | ✅ 已实现 |
| **M2.5 共享运动核心** | 把壁纸/悬浮窗的漫游逻辑抽成 `PetMotion`（纯 Kotlin），两端各接一个后端 | 悬浮窗行为不变；单测覆盖边界/朝向；两端参数一致 | ⏳ 建议下一迭代 |
| **M3 触摸 + 台词** | 单击冒台词（复用 `WhaleLines`）、双击跳一下 | 单击 100% 触发（非双击场景）；气泡 2.6s 消失；双击被 ROM 抢走时不崩 | ✅ 已实现 |
| **M4 状态联动** | 余额/低电量/充电显示在壁纸气泡 | 复用 `BalanceFetcher`（抽公共层，壁纸只读缓存）；断网/超时有兜底文案 | ⏳ 待做 |
| **M5 插件事件桥** | 壁纸态触发 `tap`/`screenOn`/`screenOff` 给 `PluginEntry` | 插件能收到事件；事件投递有计数与审计（避免静默丢弃） | ⏳ 待做 |

### 里程碑验收建议（真机）

- 装包后从设置页「设为动态壁纸（实验）」拉起系统选择器 → 应用；
- 回桌面观察 30 秒：游动流畅、贴边反弹、不掉帧；
- 切到别的 App 再回来：不黑屏、不卡死、能继续游动；
- 锁屏/解锁、旋转、分屏各试一次；
- 连续单击 10 次、双击 5 次：台词正常、无崩溃；
- 观察 5 分钟：`dumpsys batterystats` / 开发者选项里的壁纸进程 CPU 应接近 0（不可见时）。

---

## 附：本次代码改动（D2）

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/dsha/whalepet/wallpaper/PetFrame.kt` | 渲染无关状态快照（草案 + `bubble` 字段） |
| `.../wallpaper/PetRenderer.kt` | 渲染后端接口（按草案） |
| `.../wallpaper/WallpaperPetState.kt` | 纯 Kotlin 运动/气泡状态（可 JVM 单测） |
| `.../wallpaper/CanvasPetRenderer.kt` | Canvas（hardware）渲染后端：背景渐变 + 鲸鱼 + 气泡 |
| `.../wallpaper/WhaleWallpaperService.kt` | `WallpaperService.Engine`：生命周期、可见性、Choreographer、触摸 |
| `app/src/main/res/xml/whale_wallpaper.xml` | 壁纸元数据 |
| `app/src/main/AndroidManifest.xml` | 注册壁纸服务（`BIND_WALLPAPER`） |
| `app/src/main/res/layout/page_settings.xml` | 设置页新增「设为动态壁纸（实验）」入口 |
| `app/src/main/java/com/dsha/whalepet/MainActivity.kt` | 拉起系统 live wallpaper 选择器（含降级） |
| `app/src/main/res/values/strings.xml` | 新增文案（不硬编码） |
| `app/src/test/java/com/dsha/whalepet/wallpaper/WallpaperPetStateTest.kt` | 边界/旋转夹取/气泡过期/朝向的 JVM 单测 |
| `app/build.gradle.kts` | 版本号 `1.1.86` |

**未新增任何 Android 权限**（壁纸本身不需要悬浮窗权限，这也是相对悬浮窗形态的收益之一）。

---

## 附：与「人工桌面」资料的边界

- 只借鉴其可观察行为与架构结论（视频/角色壁纸的可见性卡死、事件静默丢弃、反篡改路径等），
- **未复制**其代码、资源或加密素材；本实现全部为本仓库原有素材（`home_whale.webp`）与自写逻辑。
