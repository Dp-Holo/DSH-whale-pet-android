# 动态壁纸入口：直达预览与 MIUI 授权

> 记录「点设为动态壁纸 → 系统预览 → 应用」这条链路的最终实现，以及踩过的坑。
> 结论来自真机（小米 24122RKC7C / Android 16 / HyperOS 3）与系统 `LiveWallpapersPicker` 反编译。

## 1. 最终流程

```
设置页「设为动态壁纸（实验）」
  └─ startActivityForResult(
        ACTION_CHANGE_LIVE_WALLPAPER,
        EXTRA_LIVE_WALLPAPER_COMPONENT = com.dsha.whalepet/.wallpaper.WhaleWallpaperService)
       ├─ 正常：进入系统 LiveWallpaperChange 预览页 → 用户点「设置壁纸」
       └─ 被拦截秒退（<1.5s、RESULT_CANCELED）
            └─ 弹窗：去授权（MIUI 应用权限页）/ 去列表 / 取消
                 ├─ 去授权：miui.intent.action.APP_PERM_EDITOR（extra_pkgname=本应用）
                 │    用户在「动态壁纸服务/修改壁纸」处允许后，再点入口即直达
                 └─ 去列表：ACTION_LIVE_WALLPAPER_CHOOSER（LiveWallpaperActivity，一定可用）
```

## 2. 踩过的坑（按发现顺序）

| 现象 | 真实原因 | 处理 |
|---|---|---|
| `resolveActivity` 全为 null，点了只有 toast | Android 11+ 包可见性：系统壁纸选择器默认不可见 | manifest 增加 `<queries>`（三个壁纸 Intent + livepicker 包名） |
| 能解析但仍无页面 | 旧逻辑"解析不到就跳过"；改为直接 `startActivity` 并记录结果 | 不再用 resolved 做门禁 |
| 直达 131ms/41ms 秒退 | MIUI `LiveWallpaperChange` 的前置检查失败 | 见下 |
| 怀疑 AppOps `change_wallpaper` | 未找到同名 op；`android:write_wallpaper` 授权后 mode=0 仍被拦 | 排除 |
| 怀疑 `WallpaperInfo` 解析失败 | 反射诊断少了 `GET_META_DATA`，是假阳性；补齐后 `WallpaperInfo ok` | 排除 |
| **最终根因** | **MIUI 的「动态壁纸服务」特殊授权缺失**（人工桌面已获授权，故能直达） | 弹窗引导用户去 MIUI 权限页开启 |

## 3. 为什么不能由应用自己授权

该授权是 MIUI 的特殊访问权限，`signature|privileged` 级别，普通应用无法通过 `pm grant` / `appops set` 获得；**
只能由用户在系统界面确认**。Shizuku 能执行 shell，但没有对应可授予的公开 op（实测 `write_wallpaper` 不是它）。

## 4. 验收

- 未授权：点入口 → 秒退 → 弹窗出现，两个按钮都能用；
- 授权页能找到「动态壁纸服务/修改壁纸」，开启后返回；
- 再点入口 → **直接进入鲸鱼娘预览页**（真机已验证）；
- 从列表入口（弹窗「去列表」）始终可以设置壁纸，作为兜底。
