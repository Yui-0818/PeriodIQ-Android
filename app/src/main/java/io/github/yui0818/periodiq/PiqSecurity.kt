package io.github.yui0818.periodiq

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 口令哈希与会话令牌（R3 F2「认证」）。
 *
 * **与桌面 `backend/app/security.py` 同源**：算法参数与存储串格式逐字一致
 * （`pbkdf2_sha256$<iterations>$<salt_hex>$<hash_hex>`，16 字节盐、60 万次迭代、
 * PBKDF2-HMAC-SHA256、32 字节摘要）。桌面走 `hashlib.pbkdf2_hmac`，
 * 安卓侧走本文件的 RFC 8018 实现（显式 UTF-8 口令字节，见 [pbkdf2] 的理由）——
 * **同输入必同输出**这条不是假设，由 `PiqSecuritySourceTest` 用桌面 `app.security` 本体
 * 导出的存储串与摘要向量逐字节核验（含中文与 emoji 口令）。
 *
 * 为什么不直接调桌面的 `security.py`：它 `import fastapi`（HTTPException / Request），
 * 进不了 Chaquopy，且与 `app.db` 成环 —— 与 db 层同一类边界（提示词 §1）。
 */
object PiqSecurity {

    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    private const val ALGO = "pbkdf2_sha256"

    /** PBKDF2 的块序号编码（RFC 8018 §5.2 的 INT(i)，大端 4 字节）；本实现只用 i=1。 */
    private val BLOCK_INDEX_1 = byteArrayOf(0, 0, 0, 1)

    private val random = SecureRandom()

    /** 与桌面 `hash_password` 同式（盐随机）。 */
    fun hashPassword(
        password: String,
        salt: ByteArray = newSalt(),
        iterations: Int = PBKDF2_ITERATIONS,
    ): String = listOf(ALGO, iterations.toString(), salt.toHexString(), pbkdf2(password, salt, iterations).toHexString())
        .joinToString(separator = "$")

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /**
     * 与桌面 `verify_password` 同语义：存储串格式非法 / 算法不匹配 / 盐长度不符一律 false，
     * **不抛异常**（旧哈希按串内自描述的迭代数验证，低迭代仍可用）。
     */
    fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4) return false
        val (algo, itersStr, saltHex, hashHex) = parts
        if (algo != ALGO) return false
        val iterations = itersStr.toIntOrNull() ?: return false
        if (iterations < 1 || saltHex.length != SALT_BYTES * 2) return false
        val salt = saltHex.hexToBytesOrNull() ?: return false
        val expected = hashHex.hexToBytesOrNull() ?: return false
        val actual = runCatching { pbkdf2(password, salt, iterations) }.getOrNull() ?: return false
        return MessageDigest.isEqual(actual, expected)
    }

    /** 与桌面 `dummy_stored_hash` 同式：用户不存在时也执行一次等成本校验（拉平响应时间，防用户名枚举）。 */
    fun dummyStoredHash(): String = listOf(
        ALGO, PBKDF2_ITERATIONS.toString(), "00".repeat(SALT_BYTES), "00".repeat(KEY_BYTES),
    ).joinToString(separator = "$")

    /** 与桌面 `generate_token`（`secrets.token_urlsafe(32)`）同形：32 字节 → base64url 去填充。 */
    fun generateToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** PBKDF2-HMAC-SHA256 单块派生：口令取显式 UTF-8 字节、盐为原始字节、迭代数由调用方传入（存储串自描述）；返回 [KEY_BYTES] 字节派生密钥。 */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        // RFC 8018 §5.2 的 PBKDF2-HMAC-SHA256，**显式按 UTF-8 字节**取口令。
        //
        // 为什么不用 JCE 的 `PBEKeySpec(char[])`：它接的是 char[]（UTF-16 码元），
        // "码元怎么变成密钥字节"不在任何规范里，由 provider 自己决定；安卓运行时的 provider 是
        // Conscrypt 而不是桌面 JDK 的 SunJCE，不能拿本机实测外推它。
        // （原假设"SunJCE 在代理对上与 hashlib 分叉"已被同源向量哨兵证伪：Temurin 21 上两者
        // 在 ASCII 与 emoji 两组上都逐字节一致 —— 见 PiqSecuritySourceTest 的同一条读数。）
        // 显式 UTF-8 + 桌面导出的 5 条摘要向量逐字节核验，把这一不确定性消掉。
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val length = mac.macLength
        // 摘要 256 位 ⇒ 派生长度恰为 1 个块（DK = T_1），块序号恒为 1
        check(length == KEY_BYTES) { "PBKDF2 块假设被破坏：macLength=$length KEY_BYTES=$KEY_BYTES" }

        val accumulator = ByteArray(length)
        val previous = ByteArray(length)
        mac.update(salt)
        mac.update(BLOCK_INDEX_1)
        mac.doFinal(previous, 0) // U_1
        previous.copyInto(accumulator)
        repeat(iterations - 1) {
            mac.update(previous)
            mac.doFinal(previous, 0) // U_i = HMAC(K, U_{i-1})（doFinal 自带状态复位）
            for (j in accumulator.indices) {
                accumulator[j] = (accumulator[j].toInt() xor previous[j].toInt()).toByte()
            }
        }
        return accumulator
    }

    // ---- 与桌面 schemas/api.py 同源的入站校验（v0.13.1 修 G12 / G5）----

    private val USERNAME_PATTERN = Regex("^[A-Za-z0-9_-]+$")

    private val WEAK_PASSWORDS = setOf(
        "12345678", "123456789", "1234567890", "password", "password1",
        "qwerty123", "qwertyuiop", "11111111", "00000000", "abc12345",
        "iloveyou", "admin123", "welcome1", "letmein1", "monkey123",
        "dragon123", "sunshine", "princess", "football", "abcd1234",
    )

    /** 用户名非法原因（null = 合法）；规则同 `RegisterRequest`。 */
    fun usernameRejection(username: String): String? = when {
        username.length < PiqConfig.USERNAME_MIN_CHARS || username.length > PiqConfig.USERNAME_MAX_CHARS ->
            "用户名长度须为 ${PiqConfig.USERNAME_MIN_CHARS}-${PiqConfig.USERNAME_MAX_CHARS} 位"
        !USERNAME_PATTERN.matches(username) -> "用户名仅允许字母、数字、下划线与连字符"
        else -> null
    }

    /** 新口令非法原因（null = 合法）；规则同 `validate_new_password`，长度上下限单源在 [PiqConfig]。 */
    fun newPasswordRejection(password: String): String? = when {
        password.length < PiqConfig.PASSWORD_MIN_CHARS || password.length > PiqConfig.PASSWORD_MAX_CHARS ->
            "口令长度须为 ${PiqConfig.PASSWORD_MIN_CHARS}-${PiqConfig.PASSWORD_MAX_CHARS} 位"
        WEAK_PASSWORDS.contains(password.lowercase()) -> "口令过于常见，请更换更复杂的口令"
        password.toSet().size == 1 -> "口令不能为同一字符的重复"
        else -> null
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
