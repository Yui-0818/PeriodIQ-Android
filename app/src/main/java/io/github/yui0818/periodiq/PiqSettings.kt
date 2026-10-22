package io.github.yui0818.periodiq

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * 宿主机设置与自动登录凭证（R3 F5；语义归属见契约 §7.2.2）。
 * R11 起同一个存储里还放着**三类提醒的五条设备级键**（[notifyValue] / [setNotifyValue]）。
 *
 * **键名与归属**：`app_settings.auto_login` 是**设备级偏好**——只存本机，
 * **不参与 JSON 备份、不参与导入导出、不进日志**。键名里的 `app_settings.` 只是**命名域**
 * （"这是一条设置"），**不代表它落在 `app_settings` 表里**：那张表在 R1-A 之后是
 * "只有 user_id + updated_at 的空壳"（桌面 `database.py` 注释），没有任何业务列能承载这个键。
 * 它也与契约 §7.2 的 `custom_mode`（主题/免登录）**不是一回事**，两者不共用存储。
 *
 * **为什么不进备份**（顾问 2026-10-10 裁定的两条理由）：① 契约 §1 的备份是**数据**桥梁，
 * 不是设备偏好桥梁；② 免登录凭证属于**本机安全边界**，进了备份就等于把"跳过登录"的能力
 * 交给一个可拷贝、可传播的文件，与契约 §7.2.1 末句"不得写入日志、备份或导出文件"冲突。
 * 已被接受的后果：**换机恢复后开关必然丢失**，用户需在新设备上重新打开一次（不是缺陷）。
 *
 * ⇒ 落点选宿主机 SharedPreferences（与 `ui_variant` 同一族，契约 §7.1/§7.2 的宿主机侧设置）。
 *
 * **令牌落盘形态**：AES/GCM/NoPadding，密钥在 **Android Keystore**（`AndroidKeyStore`，
 * 不可导出）；密文 base64 存进 SharedPreferences。解密失败（密钥被撤销 / 设备恢复出厂语义外）
 * ⇒ 一律清掉凭证，由调用方静默回登录页。日志里只出现长度与状态，**绝不出现令牌明文**。
 */
class PiqSettings(context: Context) : CredentialStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoLoginEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_LOGIN, false)

    /** 开关写入（不含凭证）；关闭时顺带清掉已落盘的凭证。 */
    fun setAutoLoginEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, enabled).apply()
        if (!enabled) clear()
        Log.i(TAG, "setting-changed|key=$KEY_AUTO_LOGIN|value=$enabled")
    }

    override fun enabled(): Boolean = isAutoLoginEnabled()

    /**
     * 读一条提醒设置（R11）。**没存过就回默认值**——"设备从没设过"不等于"用户关掉了"，
     * 两者对用户的含义不同（提前提醒默认开、其余默认关，见 `R11 修复提示词2` §3）。
     * 存了但读不出合法形态（历史脏值）同样回默认，并且**不改写**存储。
     */
    fun notifyValue(key: String): String {
        val fallback = NOTIFY_DEFAULTS[key] ?: return ""
        val stored = prefs.getString(key, null) ?: return fallback
        return when (key) {
            KEY_NOTIFY_DAYS -> PiqReminderPlan.normalizeDays(stored).toString()
            else -> stored.toBooleanStrictOrNull()?.toString() ?: fallback
        }
    }

    /**
     * 写一条提醒设置，返回**实际落盘的值**（`notify_days` 的非法值在这里就回落成 `"3"`；
     * 布尔键收到非 `true`/`false` 的串返回 null，由调用方回 `INVALID_VALUE`）。
     *
     * 五条键都是设备级偏好：不进 JSON 备份、不进数据库、不参与 [clear]
     * （[clear] 只管自动登录凭证，关掉提醒开关不该把用户的周期数据或凭证带走）。
     */
    fun setNotifyValue(key: String, value: String): String? {
        if (!NOTIFY_DEFAULTS.containsKey(key)) return null
        val normalized = when (key) {
            KEY_NOTIFY_DAYS -> PiqReminderPlan.normalizeDays(value).toString()
            else -> value.toBooleanStrictOrNull()?.toString() ?: return null
        }
        prefs.edit().putString(key, normalized).apply()
        Log.i(TAG, "notify-setting|key=$key|value=$normalized")
        return normalized
    }

    /** 排程用的五条设置 + 调用点给定的登录态（登录态不属于设置，故不入参持久化）。 */
    fun notifyOptions(loggedIn: Boolean): PiqReminderPlan.Options = PiqReminderPlan.Options(
        masterEnabled = notifyValue(KEY_NOTIFY_ENABLED).toBooleanStrictOrNull() ?: false,
        advanceEnabled = notifyValue(KEY_NOTIFY_ADVANCE).toBooleanStrictOrNull() ?: true,
        onDayEnabled = notifyValue(KEY_NOTIFY_ON_DAY).toBooleanStrictOrNull() ?: false,
        overdueEnabled = notifyValue(KEY_NOTIFY_OVERDUE).toBooleanStrictOrNull() ?: false,
        days = PiqReminderPlan.normalizeDays(notifyValue(KEY_NOTIFY_DAYS)),
        loggedIn = loggedIn,
    )

    /** 落盘凭证；**返回是否真的存下**（失败原因只落日志，令牌文本绝不落日志）。 */
    override fun save(token: String): Boolean {
        val key = secretKey()
        if (key == null) {
            logFailure("save", "keystore_key_unavailable")
            return false
        }
        val result = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }
        val encoded = result.getOrNull()
        if (encoded == null) {
            logFailure("save", result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown")
            return false
        }
        prefs.edit().putString(KEY_CREDENTIAL, encoded).apply()
        Log.i(TAG, "credential-saved|bytes=${encoded.length}|mode=aes-gcm-keystore")
        return true
    }

    override fun load(): String? {
        val encoded = prefs.getString(KEY_CREDENTIAL, null) ?: return null
        val decoded: ByteArray? = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        if (decoded == null || decoded.size <= IV_BYTES) {
            clear()
            return null
        }
        val key = secretKey() ?: run { logFailure("load", "keystore_key_unavailable"); clear(); return null }
        val plain = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_BITS, decoded.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(decoded, IV_BYTES, decoded.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
        if (plain == null) {
            logFailure("load", "decrypt_failed")
            clear()
            return null
        }
        Log.i(TAG, "credential-loaded|bytes=${plain.length}|mode=aes-gcm-keystore")
        return plain
    }

    /**
     * 撤销落盘凭证。**同时把自动登录开关回弹成 false**（R9.2 的 L2E-02）：
     * 凭证没了还留着 `auto_login=true`，就是本文件 `setSetting` 明令禁止的
     * "显示已开启但没有凭证"状态（下一次启动只会安静地停在登录页，用户以为开关还在）。
     */
    override fun clear() {
        val had = prefs.contains(KEY_CREDENTIAL)
        val autoWas = isAutoLoginEnabled()
        prefs.edit().remove(KEY_CREDENTIAL).apply()
        if (autoWas) setAutoLoginEnabled(false)
        Log.i(TAG, "credential-cleared|had=$had|auto-login-was=$autoWas|auto-login-reset=$autoWas")
    }

    /** Keystore 里的 256 位 AES 密钥（不存在就按 GCM 用途生成；不可导出）。 */
    private fun secretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return@runCatching it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        generator.generateKey()
    }.onFailure { Log.w(TAG, "keystore-error|type=${it.javaClass.simpleName}|message=${it.message}") }.getOrNull()

    private fun logFailure(op: String, reason: String) =
        Log.w(TAG, "credential-$op|ok=false|reason=$reason")

    companion object {
        private const val TAG = "PIQ"
        private const val PREFS = "piq_settings"

        /** 键名（契约 §7.2.2 的**命名域**，不是表归属）：设备级偏好，永不进备份 / 导入导出 / 日志。 */
        const val KEY_AUTO_LOGIN = "app_settings.auto_login"
        private const val KEY_CREDENTIAL = "piq_credential_v1"

        /**
         * R11 三类提醒的五条设备级键（与 [KEY_AUTO_LOGIN] 同族同存储：SharedPreferences `piq_settings`）。
         * 键名逐字取自 `R11 修复提示词2` §3；**默认值就是"没存过时的答案"**，所以这里也是默认值清单。
         * 不进备份、不进数据库、不动表结构（契约 §13）。
         */
        const val KEY_NOTIFY_ENABLED = "app_settings.notify_enabled"
        const val KEY_NOTIFY_ADVANCE = "app_settings.notify_advance"
        const val KEY_NOTIFY_DAYS = "app_settings.notify_days"
        const val KEY_NOTIFY_ON_DAY = "app_settings.notify_on_day"
        const val KEY_NOTIFY_OVERDUE = "app_settings.notify_overdue"

        /** 五条提醒键（桥的白名单就是这张表的键集）。 */
        val NOTIFY_KEYS: List<String> = listOf(
            KEY_NOTIFY_ENABLED,
            KEY_NOTIFY_ADVANCE,
            KEY_NOTIFY_DAYS,
            KEY_NOTIFY_ON_DAY,
            KEY_NOTIFY_OVERDUE,
        )

        /** 默认态：总开关关 · 提前开 · N=3 · 当日关 · 逾期关。 */
        val NOTIFY_DEFAULTS: Map<String, String> = mapOf(
            KEY_NOTIFY_ENABLED to "false",
            KEY_NOTIFY_ADVANCE to "true",
            KEY_NOTIFY_DAYS to "3",
            KEY_NOTIFY_ON_DAY to "false",
            KEY_NOTIFY_OVERDUE to "false",
        )

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "periodiq_autologin_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}

/**
 * 桥暴露给门控内 UI 的宿主机设置读写（R3 F5；R11 起同一通道还承载五条提醒设置）。
 *
 * 只两件事：取值 / 赋值。**开启自动登录时立刻把当前会话令牌落盘**（用户是在已登录的设置页里
 * 点开的，不立刻存就等不到"下一次登录"）；关闭时连同凭证一起抹掉。提醒键写完紧接着让
 * [PiqReminderHooks] 重排（[setNotifySetting]）——"任何改动立即写设置并立即重排"就是这一句。
 *
 * ⚠ 这两个方法**不是** REST 端点的替身：其余全部端点（分发处 `method ==` 守卫行 31 条，三种口径的拆分见 `MainActivity` 的桥 KDoc）仍由 [PiqRouter] 承担，
 * 自动登录开关属于宿主机配置（与 `ui_variant` 同族），桌面 UI 里没有这一行。
 *
 * ⚠ 为什么失败时还要**宿主自己发一条提示**（R8 的 V-1，L3 实测并由 L1 复核）：
 * 快照里 `js/app.js:2075` 的 `write()` 写的是
 * `try { bridge.setSetting(...); return true } catch { return false }` ——
 * 桥返回的是字符串、不抛异常，所以 `ok:false` 永远不会被前端读到，开关照样点亮。
 * ⇒ 只改返回值等于没修"用户看到的那件事"。这里在失败分支补一条可见回执
 *   （零权限、零依赖、不动快照；前端将来若开始读返回值，这条回执可以撤）。
 */
class HostSettingsBridge(
    private val settings: PiqSettings,
    private val sessions: SessionHolder,
    private val context: Context,
    private val reminders: PiqReminderHooks,
) {

    private companion object {
        const val TAG = "PIQ"
    }

    /**
     * 取值：自动登录 + R11 的五条提醒键，其余一律空串。
     * 提醒键**没存过时回默认值**（"没存过" ≠ "关闭"，提示词 §3），所以前端第一次读
     * `app_settings.notify_advance` 就会拿到 `"true"`，而不是空串被读成关。
     */
    fun getSetting(key: String): String = when (key) {
        PiqSettings.KEY_AUTO_LOGIN -> settings.isAutoLoginEnabled().toString()
        in PiqSettings.NOTIFY_KEYS -> settings.notifyValue(key)
        else -> ""
    }

    /**
     * 开启自动登录时把当前会话落盘；**落盘失败必须同时做三件事**（R8 的 P-08 / V-1）：
     * ① 回 `ok:false` + 原因码；② 把开关回弹成 `false`（不留"显示已开启但没有凭证"的状态）；
     * ③ 给一条用户看得见的提示——因为快照里的 `write()` 不读返回值（见类注释）。
     *
     * R11 的五条提醒键走同一条纪律：**先落值，再立刻重排**，而总开关打开时多一步权限
     * （提示词 §2②"任何改动立即写设置并立即重排"+ §2④权限三态）。
     */
    fun setSetting(key: String, value: String): String {
        if (key in PiqSettings.NOTIFY_KEYS) return setNotifySetting(key, value)
        if (key != PiqSettings.KEY_AUTO_LOGIN) {
            return JSONObject().put("ok", false).put("code", "UNKNOWN_KEY").toString()
        }
        val parsed = value.toBooleanStrictOrNull()
            ?: return JSONObject().put("ok", false).put("code", "INVALID_VALUE").toString()
        settings.setAutoLoginEnabled(parsed)
        if (parsed) {
            val token = sessions.current()
            if (token == null) {
                Log.w(TAG, "host-setting|key=${PiqSettings.KEY_AUTO_LOGIN}|value=true|saved=false|reason=no-active-session")
                // 开关回弹：不留"显示已开启但其实没有凭证"的状态（P-08 的另一半）
                settings.setAutoLoginEnabled(false)
                hint("现在没有登录中的账号，自动登录打不开。请先登录，再回到这一页打开开关。")
                return JSONObject().put("ok", false).put("code", "NO_ACTIVE_SESSION")
                    .put("value", false).toString()
            }
            if (!settings.save(token)) {
                Log.w(TAG, "host-setting|key=${PiqSettings.KEY_AUTO_LOGIN}|value=true|saved=false|reason=storage-unavailable")
                settings.setAutoLoginEnabled(false)
                hint("这台设备的加密存储暂时用不了，自动登录没能保存。下次打开应用仍需要输入口令。")
                return JSONObject().put("ok", false).put("code", "STORAGE_UNAVAILABLE")
                    .put("value", false).toString()
            }
        }
        return JSONObject().put("ok", true).put("value", parsed).toString()
    }

    /**
     * 提醒设置的一条写入。返回体里的 `value` 是**实际落盘的值**（`notify_days` 收到 `4` 会回 `3`），
     * 前端以此为准点亮控件，而不是相信自己刚写进去的那串。
     *
     * 总开关打开 ⇒ 先落 `true` 再请求权限（权限框只在用户亲手打开这一条路径上出现一次，
     * 不许循环弹框）：
     *  - 授予 ⇒ 重排（闹钟已经挂上了，这里只是把落盘与时刻对齐一次）；
     *  - 拒绝 ⇒ 落回 `false` + 全取消 + 一条看得见的提示。
     * 落 `true` 在前是有意的：API 33 以下没有这个运行时权限，同一条代码路径要能一路走通。
     */
    private fun setNotifySetting(key: String, value: String): String {
        val normalized = settings.setNotifyValue(key, value)
            ?: return JSONObject().put("ok", false).put("code", "INVALID_VALUE").toString()
        if (key == PiqSettings.KEY_NOTIFY_ENABLED && normalized == "true" && !reminders.notificationPermissionGranted()) {
            reminders.requestNotificationPermission { granted ->
                if (granted) {
                    reminders.rescheduleAsync("permission-granted")
                } else {
                    settings.setNotifyValue(key, "false")
                    reminders.cancelAllAsync("permission-denied")
                    hint("没有通知权限，提醒已自动关闭。请到系统设置里允许通知。")
                    Log.i(TAG, "notify-permission|granted=false|key=$key|rolled-back=true")
                }
            }
        }
        reminders.rescheduleAsync("setting-$key")
        return JSONObject().put("ok", true).put("key", key).put("value", normalized).toString()
    }

    /** 桥跑在 WebView 的 JS 桥线程上 ⇒ `Toast` 的线程纪律统一走 [showUserHint]。 */
    private fun hint(text: String) = showUserHint(context, text)
}

/**
 * 给用户看得见的一句提示（宿主机侧所有非 REST 回执的共用出口）。
 *
 * 调用点跑在哪条线程都有可能（JS 桥线程、资源拦截线程、主线程），而 `Toast` 必须在主线程 ⇒
 * 这里统一切回去。为什么宿主还要自己发提示而不是只回 `ok:false`：见 [HostSettingsBridge] 的类注释
 * ——快照里页内不读桥的返回值，只改返回值等于用户什么也看不见。
 */
internal fun showUserHint(context: Context, text: String) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    } else {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
