# 视频壁纸：架构、素材格式与验证

> 对应需求：壁纸呈现视频（3D 观感）、**音频律动**、**摇一摇**。
> 本文说明实现方式、素材格式、如何替换正式素材、已知限制与验收方法。

## 1. 为什么这样实现

| 决策 | 原因 |
|---|---|
| 视频走 `MediaPlayer` + `GL` 合成，而不是直接 `setSurface(holder.surface)` | 直接输出无法做缩放/旋转；GL 只需在顶点着色器乘一个矩阵，开销可忽略，却能实现音频脉动、摇一摇晃动 |
| 音频律动用**预计算包络**，而不是 `Visualizer`/`AudioRecord` | 免 `RECORD_AUDIO` 权限；无额外解码线程；用 `MediaPlayer.currentPosition` 对齐，天然同步 |
| 摇一摇用加速度计"合加速度偏差" | 不同握持角度都稳定；免权限；只在可见时注册，省电 |
| 保留 Canvas 静态渲染作为兜底 | 素材缺失/EGL 初始化失败时退化为静态图，避免黑屏或反复崩溃 |

## 2. 架构

```
WhaleWallpaperService.Engine
  ├─ 可见性/尺寸/Surface 生命周期（唯一状态源）
  ├─ Choreographer（可见时 60fps，不可见立即停）
  ├─ VideoPetState（纯 Kotlin：pulse / shake / pop → scale、rotation）
  ├─ AudioEnvelope（按播放位置采样 RMS，0..1）
  ├─ ShakeDetector（加速度计 → onShake 回调）
  └─ VideoPetRenderer
        MediaPlayer ──(SurfaceTexture)──> OES 纹理 ──GL 着色器──> 壁纸 Surface
```

每帧：
1. `level = envelope.sample(player.currentPositionMs())`
2. `state.setAudioLevel(level); state.step(dtScale)`
3. `renderer.render(state.frame(w,h), now)`（顶点着色器应用 scale/rotation）

## 3. 素材格式

放在 `app/src/main/assets/wallpaper/`：

| 文件 | 说明 |
|---|---|
| `test_idle.mp4` | 视频本体（H.264 + AAC，竖屏或横屏均可；运行时按 cover 等比铺满） |
| `test_idle_env.json` | 音频包络：`{"stepMs":50,"rms":[0.0,0.12,...]}`，`rms` 为 0..1 的均方根 |

- 视频建议：H.264 baseline/main、720p 或 1080p、24–30fps、2–6 Mbps；带音轨（音轨只用于包络，播放时静音）。
- 包络建议：50ms 一个点（20Hz），与视频时长一致即可。
- 替换正式素材：改 `WhaleWallpaperService` 里的 `videoAsset` / `envelopeAsset` 两个常量，或直接覆盖同名文件。

## 4. 包络生成（可复现）

```bash
# 1) 从视频提取 16k 单声道 PCM
ffmpeg -y -i test_idle.mp4 -ac 1 -ar 16000 -f s16le pcm.raw

# 2) 每 50ms 算 RMS，归一化到 0..1
python3 - <<'PY'
import json, struct, math
raw = open('pcm.raw','rb').read()
samples = struct.unpack('<%dh' % (len(raw)//2), raw)
step = 800  # 16000Hz * 0.05s
rms = []
for i in range(0, len(samples), step):
    chunk = samples[i:i+step]
    if not chunk: break
    rms.append(math.sqrt(sum(s*s for s in chunk)/len(chunk)))
peak = max(rms) or 1
json.dump({"stepMs":50,"rms":[round(v/peak,3) for v in rms]}, open('test_idle_env.json','w'))
PY
```

## 5. 摇一摇参数

- 判定：`|合加速度 - 9.8| > 7 m/s²`（明显甩动；轻碰不触发）
- 冷却：700ms（避免一次摇动连续触发）
- 表现：`VideoPetState.onShake()` → 3Hz 左右摆动，幅度指数衰减，上限约 2.5°
- 只在 `onVisibilityChanged(true)` 时注册传感器，`false` 时注销

## 6. 音频律动参数

- 缩放上限 2.5%（`scale = 1 + pulse*0.025`），太大像"呼吸机"，太小看不出来
- 包络平滑：每帧向目标值靠近 28%，避免包络抖动导致画面抽搐
- 视频静音播放（`setVolume(0,0)`），包络来自音轨、不打扰用户

## 7. 已知限制（后续迭代）

1. **单片段**：目前只循环一段视频；"摇一摇/点击切片段"需要 `ClipStateMachine` + 多段素材（内容侧准备）。
2. **无透明通道**：整屏视频铺满，不支持"角色浮在图标之间"；需要时再加 alpha 双流/matting。
3. **台词气泡**：视频模式下单击只做弹跳 + 日志；气泡需要 GL 文字纹理（M3.5）。
4. **无实时 3D**：这是刻意的取舍——预渲染视频 + 轻量合成，开销远低于实时 3D 引擎。

## 8. 验证清单（真机）

| 项 | 方法 | 通过标准 |
|---|---|---|
| 视频播放 | 设为壁纸后看桌面 | 画面动、循环无黑帧 |
| 音频律动 | 播放带节拍的测试视频 | 画面随节拍轻微缩放，无抽搐 |
| 摇一摇 | 摇动手机 | 画面左右晃一下后恢复 |
| 可见性 | 切到别的 App 再回来 | 不可见时停帧；回来继续，无卡死 |
| 旋转/分屏 | 转屏 | 按 cover 适配，不变形 |
| 兜底 | 临时移除 mp4（仅调试） | 退化为静态 Canvas，不黑屏不崩 |

## 9. 与 ROLE 的对应

| ROLE 能力 | 本实现 |
|---|---|
| 音频有无/幅度 | 预计算包络 → 缩放脉动（比人工桌面只传布尔更进一步） |
| 摇一摇 | 加速度计 → 晃动表现 |
| 点击/双击 | 弹跳反馈（切片段待内容） |
| 锁屏/解锁/可见性 | 停帧/恢复 |
| 充电/低电量/耳机 | 待接事件（复用现有监听即可） |

## 10. 实测结果（小米 24122RKC7C / Android 16 / HyperOS 3，1.1.93）

用合成素材（720×1280、带节拍音轨）在真机上验证，运行时数据来自
`Android/data/com.dsha.whalepet/files/wallpaper_runtime.txt`：

| 项 | 实测 |
|---|---|
| 解码器 | `player prepared w=720 h=1280`，硬解 `c2.qti.avc.decoder`，约 28fps |
| 播放推进 | `pos` 5306 → 7309 → 1116（循环）持续增长 |
| 音频脉动 | `pulse` 0.04 → 0.21 → 0.29 → 0.33 → 0.37 → 0.40，`scale` 1.001 → 1.010 |
| 摇一摇 | 连续 3 次 `shake`，`rot` 出现非零（随后指数衰减回 0） |
| 可见性 | `visible=false` → `player pause`；`visible=true` → `player resume` |
| 不可见功耗 | 暂停后 10 秒进程 CPU = 0 tick（约 0%） |
| Surface 重建 | 应用升级后 `surfaceDestroyed` → 重新 prepare，壁纸未丢失 |

**结论**：视频播放、音频律动、摇一摇、可见性暂停/恢复四条链路均已打通。
当前视频是合成彩条 + 节拍音（占位），替换正式素材后即为成品观感。
