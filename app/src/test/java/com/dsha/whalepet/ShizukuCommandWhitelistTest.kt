package com.dsha.whalepet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shizuku 命令白名单的回归测试（这是项目的安全边界之一）。
 *
 * 重点不是"能跑"，而是**该拒绝的必须拒绝**：
 *  - 同前缀的冒名包名（com.dsha.whalepet.evil）
 *  - 非白名单权限、非白名单子命令
 *  - 参数数量不符的畸形命令
 */
class ShizukuCommandWhitelistTest {

    private fun allowed(vararg args: String): Boolean =
        ShizukuCommandWhitelist.isAllowed(arrayOf(*args))

    @Test
    fun allowsThreeWhitelistedCommandsForOwnPackage() {
        assertTrue(allowed("appops", "set", "com.dsha.whalepet", "SYSTEM_ALERT_WINDOW", "allow"))
        assertTrue(allowed("pm", "grant", "com.dsha.whalepet", "android.permission.POST_NOTIFICATIONS"))
        assertTrue(allowed("dumpsys", "deviceidle", "whitelist", "+com.dsha.whalepet"))
    }

    @Test
    fun allowsDebugPackageToo() {
        assertTrue(allowed("appops", "set", "com.dsha.whalepet.debug", "SYSTEM_ALERT_WINDOW", "allow"))
        assertTrue(allowed("pm", "grant", "com.dsha.whalepet.debug", "android.permission.POST_NOTIFICATIONS"))
        assertTrue(allowed("dumpsys", "deviceidle", "whitelist", "+com.dsha.whalepet.debug"))
    }

    @Test
    fun rejectsImpostorPackageWithSamePrefix() {
        // 1.1.30 修掉的攻击面：前缀匹配会让这两个包名被静默授权
        assertFalse(allowed("appops", "set", "com.dsha.whalepet.evil", "SYSTEM_ALERT_WINDOW", "allow"))
        assertFalse(allowed("pm", "grant", "com.dsha.whalepet.evil", "android.permission.POST_NOTIFICATIONS"))
        assertFalse(allowed("dumpsys", "deviceidle", "whitelist", "+com.dsha.whalepet.evil"))
        assertFalse(allowed("appops", "set", "com.dsha.whalepetx", "SYSTEM_ALERT_WINDOW", "allow"))
    }

    @Test
    fun rejectsArbitraryShellCommands() {
        assertFalse(allowed("rm", "-rf", "/sdcard"))
        assertFalse(allowed("sh", "-c", "id"))
        assertFalse(allowed("settings", "put", "global", "device_name", "x"))
        assertFalse(allowed("am", "start", "-n", "com.tencent.mm/.ui.LauncherUI"))
        assertFalse(allowed("pm", "install", "/sdcard/evil.apk"))
    }

    @Test
    fun rejectsNonWhitelistedPermission() {
        assertFalse(allowed("pm", "grant", "com.dsha.whalepet", "android.permission.CAMERA"))
        assertFalse(allowed("pm", "grant", "com.dsha.whalepet", "android.permission.READ_SMS"))
    }

    @Test
    fun rejectsNonWhitelistedSubCommandOrTarget() {
        assertFalse(allowed("appops", "set", "com.dsha.whalepet", "SYSTEM_ALERT_WINDOW", "deny"))
        assertFalse(allowed("appops", "get", "com.dsha.whalepet", "SYSTEM_ALERT_WINDOW", "allow"))
        assertFalse(allowed("dumpsys", "battery"))
        assertFalse(allowed("dumpsys", "deviceidle", "whitelist", "com.dsha.whalepet"))   // 缺 "+"
        assertFalse(allowed("dumpsys", "deviceidle", "whitelist", "-com.dsha.whalepet"))  // 减号不是加白
    }

    @Test
    fun rejectsMalformedArgumentCount() {
        assertFalse(allowed())
        assertFalse(allowed("appops"))
        assertFalse(allowed("appops", "set", "com.dsha.whalepet"))
        assertFalse(allowed("appops", "set", "com.dsha.whalepet", "SYSTEM_ALERT_WINDOW"))
        assertFalse(
            allowed("appops", "set", "com.dsha.whalepet", "SYSTEM_ALERT_WINDOW", "allow", "extra")
        )
    }
}
