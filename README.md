# 鲸鱼娘桌宠（Android）

DeepSeek 鲸鱼娘悬浮窗桌宠——独立的 Android 应用，浮在手机屏幕上自由游泳。

## 功能

### 桌宠交互（悬浮窗）
- **自主游动**：平滑漫游、碰边镜面反弹、靠近边缘自动转向（不贴边滑行）、朝向翻转带滞回与过渡
- **单击**：弹跳缩放 + 随机说一句台词；同一条台词说完后**至少隔两句**才会再次出现
- **双击**：查询 DeepSeek 账户余额，在头顶上浮渐隐显示（主文字 #A2B4DD + 右下浅灰文字副本）
- **拖动**：按住拖到任意位置，松手后继续游；贴边松手会立即朝内游开
- **余额自动刷新**：每 5 分钟自动查询一次，结果显示在头顶
- **余额消耗检测**：每 3 秒静默查一次，余额减少时冒一句「正在偷吃用户token...」（10 秒节流）
- **熄屏暂停查询**：屏幕关闭时上面两种查询都停发网络请求，亮屏立即恢复（间隔不变）

### 设置页（底部导航「设置」）
- **悬浮窗权限**：跳转系统设置授予；已装 Shizuku 时自动授予
- **DeepSeek API Key**：填写后保存于本机，仅用于查询余额
- **立即查余额**：即时验证 Key 是否可用
- **启动 / 停止桌宠**：控制前台服务与悬浮窗

### 台词管理页（底部导航「台词管理」）
- **条目列表**：展示全部台词
- **➕ 添加**：新增台词（空内容会被拦下）
- **🖊 编辑**：修改该条台词
- **❌ 删除**：二次确认后删除
- 编辑**即时生效**，无需重启桌宠

### 插件（设置页 →「插件」）
- **规则型**：JSON 声明「事件 → 动作」，事件有 `appStart` / `balanceDecreased` / `batteryLow` / `screenOff` / `screenOn` / `tap` / `doubleTap`
- **脚本型**：JS（Rhino）编写，可用 `say / notify / vibrate / openUrl / openApp / shizuku / log` 与 `on(event, handler)`
- **能力授权**：插件须声明 capabilities，用户逐项授权后才能调用
- **敏感能力二次确认**：`openUrl` / `openApp` / `shizuku` 执行前弹确认条，可选「始终允许」；15 秒未选择视为拒绝
- **沙箱**：脚本只能接触 `__bridge` 一个 Java 类（Rhino `ClassShutter`），单次执行有指令上限，死循环会被中止
- **审计**：所有调用（含被拒绝、执行失败）写入 `Download/whale-plugins.log`

### 系统与后台
- **前台服务常驻**：保证悬浮窗不被系统回收（附常驻通知）
- **Shizuku 免 root 自动授权**：点一次允许后自动授予悬浮窗 + 通知权限
- **Material 3 动态取色**：Android 12+ 主界面跟随壁纸主题色
- **运行时诊断**：状态写入 `Download/whale-debug.txt`，达 **1MB 自动清除历史**，便于问题追溯

### 权限用途
| 权限 | 用途 |
|---|---|
| SYSTEM_ALERT_WINDOW | 让鲸鱼娘显示在其他应用之上 |
| FOREGROUND_SERVICE / _SPECIAL_USE | 悬浮窗后台常驻 |
| POST_NOTIFICATIONS | 前台服务常驻通知 |
| INTERNET | 查询 DeepSeek 余额 |

## 权限自动授予（Shizuku）

若手机已运行 [Shizuku](https://shizuku.rikka.app/)（ADB 或 root 方式启动），
打开应用并**点一次允许**后，应用会自动通过 Shizuku 授予：

- **悬浮窗权限**：`appops set <pkg> SYSTEM_ALERT_WINDOW allow`
- **通知权限**：`pm grant <pkg> android.permission.POST_NOTIFICATIONS`

无需手动跳系统设置页。Shizuku 不可用时回退手动授权。

## 下载

- **正式版（推荐）**：前往 [Releases](https://github.com/Dp-Holo/DSH-whale-pet-android/releases) 下载最新 `whale-pet-release.apk`（正式签名，包名 `com.dsha.whalepet`）
- **开发版**：GitHub Actions 每次构建产物（`whale-pet-apk` artifact，包名 `com.dsha.whalepet.debug`）

## 使用

1. 安装 APK（见上方下载；或 GitHub Actions 构建产物：`whale-pet-apk` artifact）
2. 打开应用 → 授予悬浮窗权限（Shizuku 可用则自动）
3. 粘贴 **DeepSeek API Key**（[platform.deepseek.com](https://platform.deepseek.com) → API Keys）
4. 点**启动桌宠**，鲸鱼娘就浮到屏幕上啦

> API Key 只存本机 SharedPreferences，只用于查询余额，不会上传。

## 构建（GitHub Actions）

push 到 `main` 分支自动触发，或在 Actions 页手动 `Run workflow`。
产物在每次运行结果的 **Artifacts** 里下载（`whale-pet-apk`）。

## 本地构建

```bash
# 需要 JDK 17 + Android SDK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/
```

## 结构

```
app/src/main/java/com/dsha/whalepet/
├── MainActivity.kt           # 主界面：底栏两页（设置 / 台词管理）+ 插件页
├── WhalePetService.kt        # 悬浮窗桌宠：游动 / 交互 / 余额轮询 / 运行时诊断 / 插件宿主
├── WhaleLines.kt             # 台词池：默认台词 + 本机增删改（JSON）
├── BalanceFetcher.kt         # DeepSeek 余额 API 查询
├── WhalePetApp.kt            # Application：Material3 动态取色
├── ShizukuHelper.kt          # Shizuku 免 root 自动授权（异步 + 监听）
├── ShizukuCommandService.kt  # Shizuku UserService：shell 身份执行白名单命令
├── Plugin.kt                 # 插件模型：能力声明 / 规则 / 脚本解析
├── PluginEngine.kt           # 脚本引擎：Rhino 沙箱（ClassShutter + 指令上限）
├── PluginManager.kt          # 能力授权 / 敏感二次确认 / 事件分发 / 审计
└── PluginLog.kt              # 插件审计日志（MediaStore 写入 Download）
```

## 授权

- 代码：MIT
- 鲸鱼娘立绘（`res/drawable-nodpi/whale.png`）：社区二创，CC BY-NC-SA 4.0
  （来源：github.com/fornarwhal/deepseek-whale-girl-icon）
