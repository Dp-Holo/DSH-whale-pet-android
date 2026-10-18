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
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            p.outputStream.close()          // 命令不需要 stdin，先关闭
            // 必须先把输出读空再 waitFor：否则输出超过管道缓冲区时
            // 子进程会阻塞在写、父进程阻塞在 waitFor，形成死锁。
            p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    /** 执行并回传退出码 + 输出，便于诊断授权失败的真实原因。 */
    override fun run(args: Array<String>): String {
        if (!isAllowed(args)) return "拒绝执行：命令不在白名单内"
        return try {
            // 合并 stderr 到 stdout 后一次读完，避免「先读一个流、另一个流先满」造成的死锁
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = p.waitFor()
            buildString {
                append("exit=").append(code)
                if (out.isNotEmpty()) append(" | out: ").append(out.take(400))
            }
        } catch (t: Throwable) {
            "执行异常：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * 白名单校验：实现已抽到 [ShizukuCommandWhitelist]（纯逻辑，可单元测试）。
     * 这里只做委托，避免安全规则散落在依赖 Android 的类里无法回归验证。
     */
    private fun isAllowed(args: Array<String>): Boolean = ShizukuCommandWhitelist.isAllowed(args)

    override fun destroy() {
        // Shizuku 要求销毁时退出独立进程
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
