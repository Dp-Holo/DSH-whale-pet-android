package com.dsha.whalepet.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网能力策略的测试（硬规则 7：改安全边界必须带用例）。
 *
 * 这里覆盖三件"一旦放松就出事"的事：
 *  1. 只允许 http/https，且只允许 GET/POST
 *  2. 审计里**不能**出现 query（插件常把 API key 放在里面）
 *  3. 给脚本的信封是合法 JSON（响应体里的引号/换行必须被正确转义）
 */
class NetworkPolicyTest {

    private fun ok(method: String, url: String) =
        NetworkPolicy.check(method, url) is NetworkPolicy.Check.Ok

    private fun reject(method: String, url: String): String {
        val c = NetworkPolicy.check(method, url)
        assertTrue("应当被拒：$method $url", c is NetworkPolicy.Check.Reject)
        return (c as NetworkPolicy.Check.Reject).reason
    }

    @Test
    fun allowsHttpGetAndHttpPost() {
        assertTrue(ok("GET", "https://api.example.com/v1/balance"))
        assertTrue(ok("POST", "http://192.168.1.10:8080/hook"))
        // 大小写不敏感
        assertTrue(ok("get", "https://example.com/a"))
        assertTrue(ok("post", "https://example.com/a"))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        // file:// 能读本地文件，必须挡死
        assertTrue(reject("GET", "file:///sdcard/Download/x.txt").contains("http"))
        assertTrue(reject("GET", "javascript:alert(1)").contains("http"))
        assertTrue(reject("GET", "content://media/external/1").contains("http"))
    }

    @Test
    fun rejectsMissingSchemeOrHost() {
        assertTrue(reject("GET", "example.com/a").contains("http"))
        assertTrue(reject("GET", "https:x").contains("主机名"))
    }

    @Test
    fun rejectsBlankAndOverlongUrl() {
        assertTrue(reject("GET", "").contains("为空"))
        val longUrl = "https://example.com/" + "a".repeat(NetworkPolicy.MAX_URL_LENGTH)
        assertTrue(reject("GET", longUrl).contains("过长"))
    }

    @Test
    fun rejectsNonGetPostMethods() {
        listOf("PUT", "DELETE", "PATCH", "HEAD").forEach { m ->
            assertTrue("应拒绝 $m", reject(m, "https://example.com/a").contains("方法"))
        }
    }

    // ── 审计用的 URL ──────────────────────────────────────────

    @Test
    fun auditUrlDropsQuerySoApiKeysStayOutOfTheLog() {
        val audited = NetworkPolicy.auditUrl("https://api.example.com/v1/data?api_key=SECRET&x=1")
        assertEquals("https://api.example.com/v1/data", audited)
        assertFalse("审计里不能出现密钥", audited.contains("SECRET"))
    }

    @Test
    fun auditUrlDropsEmbeddedCredentials() {
        val audited = NetworkPolicy.auditUrl("https://user:pass@example.com/a")
        assertEquals("https://example.com/a", audited)
        assertFalse(audited.contains("pass"))
    }

    @Test
    fun auditUrlKeepsPortAndPath() {
        assertEquals(
            "http://192.168.1.10:8080/hook",
            NetworkPolicy.auditUrl("http://192.168.1.10:8080/hook?token=abc")
        )
    }

    @Test
    fun auditUrlDegradesGracefully() {
        assertEquals("(无法解析的 URL)", NetworkPolicy.auditUrl("不是 URL"))
    }

    // ── 给脚本的信封 ──────────────────────────────────────────

    @Test
    fun envelopeMarks2xxAsOk() {
        val o = JSONObject(NetworkPolicy.envelope(200, "hello", false))
        assertTrue(o.getBoolean("ok"))
        assertEquals(200, o.getInt("status"))
        assertEquals("hello", o.getString("body"))
        assertFalse(o.getBoolean("truncated"))
    }

    @Test
    fun envelopeMarks4xxAnd5xxAsNotOk() {
        listOf(400, 403, 404, 500).forEach { code ->
            val o = JSONObject(NetworkPolicy.envelope(code, "err", false))
            assertFalse("HTTP $code 不应算成功", o.getBoolean("ok"))
            assertEquals(code, o.getInt("status"))
        }
    }

    @Test
    fun envelopeEscapesBodySoScriptsCanParseIt() {
        // 响应体里的引号/换行/反斜杠不能把 JSON 弄坏——手拼字符串就会坏在这
        val nasty = "line1\nline2 \"quoted\" \\ and {json:true}"
        val o = JSONObject(NetworkPolicy.envelope(200, nasty, false))
        assertEquals(nasty, o.getString("body"))
    }

    @Test
    fun envelopeCarriesTruncatedFlag() {
        val o = JSONObject(NetworkPolicy.envelope(200, "x", true))
        assertTrue(o.getBoolean("truncated"))
    }

    @Test
    fun errorEnvelopeHasNoStatusButAReason() {
        val o = JSONObject(NetworkPolicy.errorEnvelope("timeout"))
        assertFalse(o.getBoolean("ok"))
        assertEquals("timeout", o.getString("error"))
        assertFalse(o.has("status"))
    }

    @Test
    fun limitsAreSaneValues() {
        assertEquals(64 * 1024, NetworkPolicy.MAX_RESPONSE_BYTES)
        assertTrue("必须有超时", NetworkPolicy.CONNECT_TIMEOUT_MS in 1..30_000)
        assertTrue("必须有最小间隔", NetworkPolicy.MIN_INTERVAL_MS >= 1_000L)
        assertEquals(setOf("GET", "POST"), NetworkPolicy.ALLOWED_METHODS)
    }
}
