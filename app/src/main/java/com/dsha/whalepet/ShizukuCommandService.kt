package com.dsha.whalepet

/**
 * Shizuku UserService：运行在 Shizuku 启动的独立进程里，
 * 以 adb/shell 身份（uid=2000）执行系统命令（appops / pm）。
 *
 * 按官方要求：服务类直接实现 AIDL Stub（即 IBinder），
 * 无需继承 Service 或 UserService。
 */
class ShizukuCommandService : IShizukuCommand.Stub() {

    override fun exec(args: Array<String>): Int {
        return try {
            val p = Runtime.getRuntime().exec(args)
            p.waitFor()
        } catch (e: Exception) {
            -1
        }
    }
}
