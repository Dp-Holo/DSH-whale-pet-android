# 🐳 鲸鱼娘桌宠（Android）

DeepSeek 鲸鱼娘悬浮窗桌宠——独立的 Android 应用，浮在手机屏幕上自由游泳。

## 功能

- 🏊 **自主游动**：角度制平滑漫游，碰边反弹，无抖动
- 👆 **单击**：弹跳缩放 + 冒台词（14 句鲸鱼娘经典台词）
- 👆👆 **双击**：查询 DeepSeek 开放平台余额，头顶 #A2B4DD 上浮渐隐 0.8s
- 🖐 **拖动**：按住拖到任意位置，松手后继续游
- ⏱ **余额自动刷新**：每 5 分钟一次

## 使用

1. 安装 APK（GitHub Actions 构建产物：`whale-pet-apk` artifact）
2. 打开应用 → **授予悬浮窗权限**
3. 粘贴 **DeepSeek API Key**（[platform.deepseek.com](https://platform.deepseek.com) → API Keys）
4. 点**启动桌宠**，鲸鱼娘就浮到屏幕上啦

> API Key 只存本机 SharedPreferences，只用于查询余额，不会上传。

## 构建（GitHub Actions）

push 到 `main` 分支自动触发，或在 Actions 页手动 `Run workflow`。
产物在每次运行结果的 **Artifacts** 里下载（`whale-pet-apk`）。

## 本地构建

```bash
# 需要 JDK 17 + Android SDK
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/
```

## 结构

```
app/src/main/java/com/dsha/whalepet/
├── MainActivity.kt      # 设置页：权限 / API Key / 启停
├── WhalePetService.kt   # 悬浮窗桌宠：游动 / 交互 / 余额轮询
└── BalanceFetcher.kt    # DeepSeek 余额 API 查询
```

## 授权

- 代码：MIT
- 鲸鱼娘立绘（`res/drawable-nodpi/whale.png`）：社区二创，CC BY-NC-SA 4.0
  （来源：github.com/fornarwhal/deepseek-whale-girl-icon）
