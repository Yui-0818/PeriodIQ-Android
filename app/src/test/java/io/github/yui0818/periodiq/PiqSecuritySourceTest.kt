package io.github.yui0818.periodiq

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 口令哈希与令牌的**同源**哨兵（R3 F2）。
 *
 * 期望值不是手写的：由仓库外脚本 `export_source_vectors.py` **调用桌面 `backend/app/security.py` 本体**
 * （`hash_password` / `verify_password` / `dummy_stored_hash` / `generate_token`）导出，
 * 落在 `.dsh/r3_probe/source_vectors.json`；下面每条常量都是那份文件里的原文。
 * 复现命令见 R3 报告 §3.7。
 */
class PiqSecuritySourceTest {

    /** 桌面 `hash_password` 产出的存储串，安卓侧必须原样验证通过。 */
    @Test
    fun `desktop stored hashes verify on android implementation`() {
        DESKTOP_STORED.forEach { (password, stored) ->
            assertTrue("桌面存储串应通过：$password", PiqSecurity.verifyPassword(password, stored))
            assertFalse("改一个字符必须失败：$password", PiqSecurity.verifyPassword(password + "x", stored))
        }
    }

    /** 存储串形态与迭代数必须与桌面一致（600k / 16 字节盐 / 32 字节摘要）。 */
    @Test
    fun `android produced stored string has desktop shape`() {
        val stored = PiqSecurity.hashPassword("Passw0rd-Ascii")
        val parts = stored.split('$')
        assertEquals(4, parts.size)
        assertEquals("pbkdf2_sha256", parts[0])
        assertEquals("600000", parts[1])
        assertEquals(32, parts[2].length)
        assertEquals(64, parts[3].length)
        assertTrue(PiqSecurity.verifyPassword("Passw0rd-Ascii", stored))
    }

    /** 派生函数逐字节对齐 `hashlib.pbkdf2_hmac`（含中文与 emoji 口令）。 */
    @Test
    fun `pbkdf2 digest matches hashlib byte for byte`() {
        PBKDF2_VECTORS.forEach { (password, saltHex, iterations, expectHex) ->
            val actual = PiqSecurity.pbkdf2(password, hexToBytes(saltHex), iterations).toHexString()
            assertEquals("PBKDF2 摘要不一致（$password @ $iterations）", expectHex, actual)
        }
    }

    /**
     * 为什么自带 PBKDF2 而不用 JCE `PBEKeySpec(char[])` —— **本条是实测，不是推测**。
     *
     * 最初的理由（"SunJCE 逐 UTF-16 码元编码，代理对会分叉"）被这条哨兵**证伪**：
     * 在 Temurin 21 上，JCE 与 hashlib 在 ASCII 与 emoji 两组向量上都逐字节一致。
     * 保留自实现的真实理由是：**口令→字节的编码不在 JCE 规范里**，而安卓运行时的
     * provider 是 Conscrypt（不是 SunJCE），不能拿桌面 JDK 的实测结果外推它一致；
     * 显式 `password.toByteArray(UTF_8)` 把这一不确定性彻底消掉。
     *
     * 因此本条同时是**行程记录仪**：两端（JCE 与本实现）在任何一侧改编码时这里会 FAIL 并留下读数。
     */
    @Test
    fun `own implementation agrees with jce on the host jdk and is provider independent`() {
        val salt = hexToBytes("ffffffffffffffffffffffffffffffff")
        listOf("Passw0rd-Ascii" to 1000, "月圈周期-2026#测试" to 4096, "🌙emoji-密码-0226" to 1000)
            .forEach { (password, iterations) ->
                assertEquals(
                    "JDK21 上 JCE 与显式 UTF-8 实现应一致（$password @ $iterations）",
                    PiqSecurity.pbkdf2(password, salt, iterations).toHexString(),
                    jcePbkdf2(password, salt, iterations),
                )
            }
    }

    /** 垃圾存储串：格式合法但永远验证不过（桌面 `verify_password(anything, dummy) == False`）。 */
    @Test
    fun `dummy stored hash never verifies`() {
        val parts = PiqSecurity.dummyStoredHash().split('$')
        assertEquals(4, parts.size)
        assertEquals("pbkdf2_sha256", parts[0])
        assertEquals("600000", parts[1])
        assertEquals("00".repeat(16), parts[2])
        assertEquals("00".repeat(32), parts[3])
        assertFalse(PiqSecurity.verifyPassword("anything", PiqSecurity.dummyStoredHash()))
    }

    /** 非法存储串一律 false 且不抛异常（与桌面 try/except 同语义）。 */
    @Test
    fun `malformed stored hashes are rejected without throwing`() {
        fun stored(algo: String, iters: String, salt: String, hash: String) =
            listOf(algo, iters, salt, hash).joinToString(separator = "$")

        listOf(
            "",
            stored("pbkdf2_sha256", "600000", "abcd", "ef"),
            stored("sha1", "1", "00".repeat(16), "00".repeat(32)),
            stored("pbkdf2_sha256", "0", "00".repeat(16), "00".repeat(32)),
            stored("pbkdf2_sha256", "abc", "00".repeat(16), "00".repeat(32)),
            stored("pbkdf2_sha256", "1", "00".repeat(8), "00".repeat(32)),
            stored("pbkdf2_sha256", "1", "zz".repeat(16), "00".repeat(32)),
            "pbkdf2_sha256\$600000\$" + "00".repeat(16),
        ).forEach { storedString ->
            assertFalse("必须判 false：$storedString", PiqSecurity.verifyPassword("x", storedString))
        }
    }

    /** `secrets.token_urlsafe(32)`：43 字符、base64url 字母表、两次生成不同。 */
    @Test
    fun `session token matches token_urlsafe shape`() {
        val token = PiqSecurity.generateToken()
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(token.contains('='))
        assertTrue(token != PiqSecurity.generateToken())
    }

    /** 用户名与新口令的入站规则（`RegisterRequest` / `validate_new_password`）。 */
    @Test
    fun `username and password policy match desktop schemas`() {
        assertNull(PiqSecurity.usernameRejection("abc"))
        assertNull(PiqSecurity.usernameRejection("A_b-9"))
        assertNotNull(PiqSecurity.usernameRejection("ab"))
        assertNotNull(PiqSecurity.usernameRejection("a".repeat(33)))
        assertNotNull(PiqSecurity.usernameRejection("中文名"))
        assertNotNull(PiqSecurity.usernameRejection("has space"))
        assertNull(PiqSecurity.newPasswordRejection("Valid-Pass-1"))
        assertNotNull(PiqSecurity.newPasswordRejection("1234567"))
        assertNotNull(PiqSecurity.newPasswordRejection("password"))
        assertNotNull(PiqSecurity.newPasswordRejection("aaaaaaaa"))
        assertNotNull(PiqSecurity.newPasswordRejection("a".repeat(129)))
    }

    /**
     * 口令长度的上下限是**闭区间**，且拒因文案里的数字随 [PiqConfig] 插值
     * （R9.2 小项 #5：常量从本文件上提到 `PiqConfig` 之后，用户可见文案必须逐字不变）。
     *
     * 文案写成字面量而不是再插值一次：那正是这条哨兵要钉的东西 —— 谁改了常量而忘了
     * 让文案跟着走，这里就红。
     */
    @Test
    fun `password length bounds are closed and the message still reads eight dash one twenty eight`() {
        val atMin = "Ab1!".repeat(PiqConfig.PASSWORD_MIN_CHARS / 4)
        val atMax = "Ab1!".repeat(PiqConfig.PASSWORD_MAX_CHARS / 4)
        // 这里**不**再断言 "atMin.length == PASSWORD_MIN_CHARS"：那是 `4*(N/4)==N` 的算术恒等式，
        // 产品代码怎么改都红不了（R9.3 的 L3-c 复核计的正是这类占位断言）。下面四条才是判据。
        assertNull(PiqSecurity.newPasswordRejection(atMin))
        assertNull(PiqSecurity.newPasswordRejection(atMax))
        assertEquals("口令长度须为 8-128 位", PiqSecurity.newPasswordRejection(atMin.dropLast(1)))
        assertEquals("口令长度须为 8-128 位", PiqSecurity.newPasswordRejection(atMax + "!bA"))
    }

    private fun jcePbkdf2(password: String, salt: ByteArray, iterations: Int): String =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
            .encoded
            .toHexString()

    private fun hexToBytes(hex: String): ByteArray = hex.hexToBytesOrNull() ?: ByteArray(0)

    companion object {
        /** 原文取自 `.dsh/r3_probe/source_vectors.json` 的 `security.items`。 */
        private val DESKTOP_STORED = listOf(
            "Passw0rd-Ascii" to
                "pbkdf2_sha256\$600000\$bc385dbe07f5f1ad1845bf798eed143c\$7fd8ce970b3de2c7699186fb82e6050c59c05a91f9e7d95b8f04cc77962888e6",
            "月圈周期-2026#测试" to
                "pbkdf2_sha256\$600000\$64997eb399519017233310be8eb884d4\$c472c312ea23a0f6349ac74e4e76c9aa0e243de10128b9a24a2fef546b09a67e",
            "aaaaaaaa" to
                "pbkdf2_sha256\$600000\$620e1bb2fe5828298c1e7cb5de4042bb\$aa78f1ed814361a783b664afab791f32e801a0900ee584b6d5ddf43d57bfefd6",
            "🌙emoji-密码-0226" to
                "pbkdf2_sha256\$600000\$8fa0a12a512ac168db9ec1b2b77f2d30\$8d25ad513a851389993e4c55e297e48a9167ea10d0c5c9a34c4e74a35fd1cc0e",
        )

        /** 原文取自同一文件的 `pbkdf2` 数组。 */
        private val PBKDF2_VECTORS = listOf(
            Quad("Passw0rd-Ascii", "00112233445566778899aabbccddeeff", 1,
                "a078c17adda545ca91513ea78b8fd121193386a04e4b15c4b07ead383bf0ea0f"),
            Quad("Passw0rd-Ascii", "00112233445566778899aabbccddeeff", 1000,
                "114038207c1e902266b06ab97fba445e9b1280d62106c35f62779b53cab9b1b9"),
            Quad("月圈周期-2026#测试", "deadbeefcafebabe0123456789abcdef", 4096,
                "a2aa16c5cca2fd6debfce1e23db9cfaed82307d99186fe5c7850b833f88651a5"),
            Quad("aaaaaaaa", "102030405060708090a0b0c0d0e0f0ff", 600000,
                "70ed8d6588b8abed3b5389fc20859b6987f0067aea9e4e3da1a342df5f970c48"),
            Quad("🌙emoji-密码-0226", "ffffffffffffffffffffffffffffffff", 1000,
                "90419c7ec97ada95ab747d3d372f0eab6e20a781f6e45c83d4b4de953b5f3695"),
        )
    }

    private data class Quad(val password: String, val saltHex: String, val iterations: Int, val digestHex: String)
}
