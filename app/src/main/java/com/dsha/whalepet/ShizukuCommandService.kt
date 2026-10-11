package com.dsha.whalepet

/**
 * Shizuku UserService：运行在 Shizuku 启动的独立进程里，
 * 以 adb/shell 身份（uid=2000）执行系统命令（appops / pm）。
 *
 * 按官方要求：服务类直接实现 AIDL Stub（即 IBinder），
 * 并实现 destroy() 供 Shizuku 终止进程。
 */
class ShizukuCommandService : IShizukuCommand.Stub() {

    override fun exec(args: Array<String>): Int {
        // 参数白名单：仅允许本应用所需的两种授权命令，且目标包名必须是本应用家族，
        // 防止未来任何调用方借该通道执行任意 shell 命令。
        if (!isAllowed(args)) return -1
        return try {
            val p = Runtime.getRuntime().exec(args)
            p.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    /** 执行并回传退出码 + stdout/stderr，便于诊断授权失败的真实原因。 */
    override fun run(args: Array<String>): String {
        if (!isAllowed(args)) return "拒绝执行：命令不在白名单内"
        return try {
            val p = Runtime.getRuntime().exec(args)
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val err = p.errorStream.bufferedReader().use { it.readText() }.trim()
            val code = p.waitFor()
            buildString {
                append("exit=").append(code)
                if (out.isNotEmpty()) append(" | out: ").append(out.take(200))
                if (err.isNotEmpty()) append(" | err: ").append(err.take(200))
            }
        } catch (t: Throwable) {
            "执行异常：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * 允许的命令（白名单，目标包名必须是本应用）：
     *  - appops set <pkg> SYSTEM_ALERT_WINDOW allow
     *  - pm grant <pkg> android.permission.POST_NOTIFICATIONS
     *  - dumpsys deviceidle whitelist +<pkg>   （加入电池优化白名单）
     *
     * 包名采用**精确匹配**（release / debug 两个确定值）：
     * 前缀匹配会让 com.dsha.whalepet.evil 这类同前缀包名也被静默授权。
     */
    private fun isAllowed(args: Array<String>): Boolean {
        if (args.size == 5) {
            val pkg = args[2]
            if (pkg !in ALLOWED_PACKAGES) return false
            return when {
                args[0] == "appops" && args[1] == "set" &&
                    args[3] == "SYSTEM_ALERT_WINDOW" && args[4] == "allow" -> true
                args[0] == "pm" && args[1] == "grant" &&
                    args[3] == "android.permission.POST_NOTIFICATIONS" -> true
                else -> false
            }
        }
        if (args.size == 4) {
            if (args[0] != "dumpsys" || args[1] != "deviceidle" || args[2] != "whitelist") return false
            val target = args[3]
            return target.startsWith("+") && target.removePrefix("+") in ALLOWED_PACKAGES
        }
        return false
    }

    private companion object {
        /** 仅本应用的确定包名（release / debug），精确匹配。 */
        val ALLOWED_PACKAGES = setOf("com.dsha.whalepet", "com.dsha.whalepet.debug")
    }

    override fun destroy() {
        // Shizuku 要求销毁时退出独立进程
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
