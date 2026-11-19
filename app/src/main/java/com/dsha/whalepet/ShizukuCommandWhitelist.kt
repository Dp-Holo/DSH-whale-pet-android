package com.dsha.whalepet

/**
 * Shizuku 命令白名单（**纯逻辑，无 Android 依赖**，因此可被 JVM 单元测试覆盖）。
 *
 * 允许的命令只有三条，且目标包名必须是本应用：
 *  - `appops set <pkg> SYSTEM_ALERT_WINDOW allow`        （5 个参数）
 *  - `pm grant <pkg> android.permission.POST_NOTIFICATIONS`（4 个参数）
 *  - `dumpsys deviceidle whitelist +<pkg>`               （4 个参数）
 *
 * 两条安全约定（改动务必保持，测试里有对应回归用例）：
 *  1. **包名精确匹配**：不用前缀匹配，否则 `com.dsha.whalepet.evil` 会被静默授权。
 *  2. **参数级校验**：逐项核对子命令、权限名与目标，不放开任意 shell。
 *
 * 历史：`pm grant` 曾与 `appops set` 同放在「5 个参数」分支，而真实调用只有 4 个参数，
 * 导致通知权限的自动授予**从未生效**（1.1.32 由单元测试发现并修正）。
 */
object ShizukuCommandWhitelist {

    /** 仅本应用的确定包名（release / debug）。 */
    val ALLOWED_PACKAGES: Set<String> = setOf("com.dsha.whalepet", "com.dsha.whalepet.debug")

    fun isAllowed(args: Array<String>): Boolean {
        // appops set <pkg> SYSTEM_ALERT_WINDOW allow
        if (args.size == 5) {
            return args[0] == "appops" && args[1] == "set" &&
                args[2] in ALLOWED_PACKAGES &&
                args[3] == "SYSTEM_ALERT_WINDOW" && args[4] == "allow"
        }
        if (args.size == 4) {
            // pm grant <pkg> android.permission.POST_NOTIFICATIONS
            if (args[0] == "pm" && args[1] == "grant" &&
                args[2] in ALLOWED_PACKAGES &&
                args[3] == "android.permission.POST_NOTIFICATIONS"
            ) {
                return true
            }
            // dumpsys deviceidle whitelist +<pkg>
            if (args[0] != "dumpsys" || args[1] != "deviceidle" || args[2] != "whitelist") {
                return false
            }
            val target = args[3]
            return target.startsWith("+") && target.removePrefix("+") in ALLOWED_PACKAGES
        }
        return false
    }
}
