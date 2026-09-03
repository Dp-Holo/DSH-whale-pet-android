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

    /** 允许的命令：appops set <whale-pkg> SYSTEM_ALERT_WINDOW allow / pm grant <whale-pkg> POST_NOTIFICATIONS */
    private fun isAllowed(args: Array<String>): Boolean {
        if (args.size != 5) return false
        val pkg = args[2]
        if (!pkg.startsWith("com.dsha.whalepet")) return false
        return when {
            args[0] == "appops" && args[1] == "set" &&
                args[3] == "SYSTEM_ALERT_WINDOW" && args[4] == "allow" -> true
            args[0] == "pm" && args[1] == "grant" &&
                args[3] == "android.permission.POST_NOTIFICATIONS" -> true
            else -> false
        }
    }

    override fun destroy() {
        // Shizuku 要求销毁时退出独立进程
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
