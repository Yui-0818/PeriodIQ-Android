package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.algo.PiqAlgo
import io.github.yui0818.periodiq.api.contract.ApiError
import io.github.yui0818.periodiq.api.contract.Envelope
import io.github.yui0818.periodiq.api.contract.genericFailure
import io.github.yui0818.periodiq.api.contract.validationError
import io.github.yui0818.periodiq.api.projection.ApiProjection
import io.github.yui0818.periodiq.api.projection.nullableNumber
import io.github.yui0818.periodiq.api.projection.nullableString
import io.github.yui0818.periodiq.domain.import.ImportBodyGate
import io.github.yui0818.periodiq.domain.import.ImportGate
import io.github.yui0818.periodiq.domain.model.ModelRecomputeService
import io.github.yui0818.periodiq.data.CycleRow
import io.github.yui0818.periodiq.data.PiqCrud
import io.github.yui0818.periodiq.data.PiqDb
import io.github.yui0818.periodiq.data.PredictionRow

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 未实现分支的统一信封（REG-06）：501 + `implemented=false`，判据要求**绝不假 200**。
 *
 * 走 [Envelope.failure] 单源（R8 的 REG-06 另一半：同族两套实现合并成一套）。
 * 响应体**不带** `path`/方法名 —— 内部路由路径对用户零信息量，进页面 DOM 等于外泄路由表结构；
 * 技术细节只进 `api-error|method=…|path=…` 的本地日志。
 */
internal fun notImplementedEnvelope(reason: String): Envelope = Envelope.failure(
    501,
    reason,
    "NOT_IMPLEMENTED",
    JSONObject().put("implemented", false),
)

/**
 * `PUT /auth/profile` 的 `weight_kg` 取值（REG-04：逐字段对照桌面接受集的产物）。
 *
 * 契约：**缺键或 JSON null ⇒ 返回 null**（表示"这一列不改"）；**键存在但不是数值、或数值越界 ⇒ 抛 422**
 * （名字里的 `OrReject` 说的就是这件事，与同文件的 [isoOrReject] 同一命名口径）。
 *
 * 桌面声明是 `Optional[float] = Field(None, ge=25, le=300)`（`schemas/api.py:479`），pydantic 2
 * **lax 模式**下**接受数字字符串**（实测 `"70.5"`→70.5、`" 70.5 "`→70.5、`"1e2"`→100.0、`"+28"`→28.0），
 * 安卓同样用 [doubleOrNull] ⇒ 这些形态两端同判。
 *
 * ⚠ **非有限值是真实分叉，不是假想**（`NumericFieldAcceptanceTest` 三条实测）：
 * Java/Kotlin 的浮点字面量语法**大小写敏感** —— `"nan"` 解析失败（返回 null，两端都拒），
 * 但 `"NaN"` 得到 `Double.NaN`、`"Infinity"` 得到 `POSITIVE_INFINITY`；
 * 而 `NaN < 25` 与 `NaN > 300` **都是 false**（IEEE 754），所以"只比区间"的旧写法会把 NaN 放行入库，
 * 桌面则在 `le=300` 上拒它。`[PiqConfig.WEIGHT_MIN_KG]`/`[PiqConfig.WEIGHT_MAX_KG]` 因此
 * 必须配一条有限性判断才等价 —— 这条判断不是防御性代码，是**补齐桌面的 ge/le 语义**。
 */
internal fun profileWeightOrReject(body: JSONObject): Double? {
    if (!body.has("weight_kg") || body.isNull("weight_kg")) return null
    val value = body.doubleOrNull("weight_kg")
        ?: throw validationError("weight_kg", "值应为数值")
    if (!value.isFinite() || value < PiqConfig.WEIGHT_MIN_KG || value > PiqConfig.WEIGHT_MAX_KG) {
        throw validationError("weight_kg", "数值应在 25 到 300 之间")
    }
    return value
}

/** 桌面 `datetime.now(timezone.utc).isoformat()` 给的是 `+00:00` 而不是 `Z`，这里逐字对齐。 */
private val UTC_SECONDS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
private const val UTC_OFFSET_SUFFIX = "+00:00"

/**
 * 陈旧预测修正的审计时间戳（`model_params.stale_correction.corrected_at`）。
 *
 * ⚠ 基线（`a65d82e` 的 `PiqRouter.kt:1038-1039`）写的是 `LocalDateTime.now(UTC).format(ISO_OFFSET_DATE_TIME)`，
 * 而 `LocalDateTime` **不带偏移字段** ⇒ 该表达式**每次必抛** `UnsupportedTemporalTypeException`
 * （`StaleCorrectionTimestampTest` 前两条把它钉成实测读数）；调用点落在 `store.write { … }` 里、
 * `createPrediction` 之前 ⇒ 整个修正事务回滚，`handle()` 再把它收敛成 `500 INTERNAL_ERROR`。
 *
 * 产出形态 `2026-10-14T05:59:43+00:00`：显式偏移 + **秒级截断**（不用 `ISO_OFFSET_DATE_TIME`
 * 直出，因为零偏移会被它印成 `Z`，与桌面形态不一致；这个字段的值会进 `model_params_json` 跨端往返）。
 * 桌面同一字段带微秒 ⇒ 精度差一档作为 P3 登记在 `R9.1报告1.md` §6，不在此默默放宽。
 */
internal fun utcSecondsIso(): String =
    OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(UTC_SECONDS_FORMAT) + UTC_OFFSET_SUFFIX

/**
 * 路由壳（R3 F2 + R7 F1）：R3 的 30 条端点再加 R7 的三条算法透视镜端点，
 * **路径与响应信封逐字对齐桌面 REST**。
 *
 * - 前缀 [PiqConfig.API_PREFIX]（`twa` 那条按桌面一样挂在根路径上）；
 * - 成功 = `{status, json}`（`json` 就是桌面那个 HTTP 响应体，数组端点保持数组），
 *   失败 = 同信封里放桌面扁平错误契约 `{detail, code}`（+ 业务附加字段如 `anomalies`）；
 * - **未实现的分支一律 501 + `implemented=false`**，绝不假 200（`S-AND-R3-BRIDGE-30` 的 Then）；
 * - 算法一律转 [PiqAlgo]（Python 快照），本文件不重算任何模型指标（第9章禁令）。
 */
/** 算法不可用的统一错误（R13.1 F10）：503 + 现有扁平错误信封，文案可读。 */
internal fun algorithmUnavailable(): ApiError =
    ApiError(503, "本机算法暂时不可用，请稍后重试。", "ALGO_UNAVAILABLE")

/**
 * 算法端点的统一闸门（R13.1 F10）：JVM 用假 [AlgoGate] 就能验证
 * “失败 ⇒ 503 + 业务体一行都不执行”，不必绕过 Android SQLite 桩类去造 router。
 */
internal fun <T> runAlgorithmEndpoint(gate: AlgoGate, block: () -> T): T {
    if (!gate.ensure()) throw algorithmUnavailable()
    return block()
}

/**
 * 删除周期的算法闸门：删完还剩周期时 [recomputeAfterDeleteTx] 才会走到
 * `regenerateCurrentPrediction` → [PiqAlgo]；删最后一条只 reset 模型参数，不查闸门。
 * 计数必须在删除之前完成（503 时行保持不变）。
 */
internal fun <T> runDeleteWithAlgorithmGate(remainingAfterDelete: Int, gate: AlgoGate, delete: () -> T): T {
    if (remainingAfterDelete > 0) runAlgorithmEndpoint(gate) { }
    return delete()
}

/**
 * 可清空字段（备注 / 昵称）专用：缺键或显式 null 才算“未提供”，`""` 必须原样下传。
 * 只给这两个字段用，不替换全局 `optionalString`（R13.1 F2 / R12-P2-01）。
 */
internal fun optionalStringAllowingEmpty(body: JSONObject, key: String): String? {
    if (!body.has(key) || body.isNull(key)) return null
    return body.optString(key)
}

class PiqRouter(
    private val store: PiqDb,
    private val crud: PiqCrud,
    private val sessions: SessionHolder,
    private val limiter: RateLimiter,
    private val appVersion: String,
    private val algoGate: AlgoGate,
) {

    private val recompute = ModelRecomputeService(crud)

    /** 算法端点入口：先验会话（401），再验算法可用性（503），顺序不得颠倒。 */
    private fun requireAlgorithmReady() {
        runAlgorithmEndpoint(algoGate) { }
    }

    /** 入口：任何异常都收敛成可读信封（不得把堆栈抛给页面）。 */
    fun handle(methodIn: String, pathIn: String, bodyIn: String?): Envelope {
        val method = methodIn.uppercase()
        val path = pathIn.substringBefore('?')
        return try {
            dispatch(method, path, bodyIn)
        } catch (e: ApiError) {
            Log.i(TAG, "api-error|method=$method|path=$path|status=${e.status}|code=${e.code}")
            Envelope.error(e)
        } catch (e: Throwable) {
            Log.w(TAG, "api-exception|method=$method|path=$path|type=${e.javaClass.name}|message=${e.message}")
            genericFailure(500, "INTERNAL_ERROR")
        }
    }

    private fun dispatch(method: String, path: String, raw: String?): Envelope = when {
        path == PATH_ASSETLINKS -> notImplementedEnvelope("TWA 域名所有权文件只在 PWA/TWA 分发形态有意义；本机 APK 不适用")

        // REG-06：404 不再把方法名与路径回显给用户（内部路径对用户零信息量，
        // 且等于把路由表结构甩进页面 DOM）。技术细节仍进 `api-error|method=…|path=…` 日志。
        !path.startsWith(PiqConfig.API_PREFIX) -> throw ApiError(404, MSG_NOT_FOUND, "NOT_FOUND")

        else -> {
            val rest = path.removePrefix(PiqConfig.API_PREFIX)
            // 只有 JSON 备份导入走四档可读错误 + 体积闸门（REG-02/03/05）；其余端点保持原宽松形态
            val body = if (method == "POST" && rest == REST_IMPORT) ImportBodyGate.parse(raw) else parseBody(rest, raw)
            route(method, rest, body)
        }
    }

    private fun route(method: String, rest: String, body: JSONObject): Envelope = when {
        method == "GET" && rest == "/health" ->
            Envelope(200, JSONObject().put("status", "ok").put("version", appVersion))

        method == "POST" && rest == "/auth/register" -> register(body)
        method == "POST" && rest == "/auth/login" -> login(body)
        method == "GET" && rest == "/auth/me" -> Envelope(200, ApiProjection.user(requireUser()))
        method == "POST" && rest == "/auth/logout" -> Envelope(200, logout())
        method == "POST" && rest == "/auth/change-password" -> changePassword(body)
        method == "POST" && rest == "/auth/delete-account" -> deleteAccount(body)
        method == "PUT" && rest == "/auth/profile" -> updateProfile(body)

        method == "GET" && rest == "/cycles" ->
            Envelope(200, ApiProjection.cycles(crud.getAllCycles(requireUser().userId()), ApiProjection::cycle))
        method == "POST" && rest == "/cycles" -> createCycle(body)
        method == "PUT" && CYCLE_ID.matches(rest) -> updateCycle(cycleIdOf(rest), body)
        method == "DELETE" && CYCLE_ID.matches(rest) -> deleteCycle(cycleIdOf(rest))

        method == "GET" && rest == "/predictions/current" -> currentPrediction()
        method == "GET" && rest == "/predictions/history" -> predictionHistory()

        method == "GET" && rest == "/analysis/accuracy" -> accuracy()
        method == "GET" && rest == "/analysis/cycle-stats" -> cycleStats()
        method == "GET" && rest == "/analysis/fertile-window" -> fertileWindow()

        method == "GET" && rest == "/settings" -> Envelope(200, JSONObject())
        method == "PUT" && rest == "/settings" -> Envelope(200, JSONObject().also { crud.updateSettings(requireUser().userId()) })
        method == "GET" && rest == "/export" -> exportData()
        method == "GET" && rest == "/export/preview" -> exportPreview()
        method == "POST" && rest == "/import" -> importData(body)
        // R8 的 P-03：前端导入中枢无条件渲染 4 个通道按钮，其中 PDF 会打这条路径。
        // 本机不引 PDF 解析库（禁新依赖）也不联网，所以必须给**设计过的 501 + 可执行建议**，
        // 而不是落到最后的 404 分支、把方法名与路径拼成一句用户读不懂的话。
        method == "POST" && rest == "/import/pdf" ->
            notImplementedEnvelope("PDF 历史导入在安卓端没有解析器（本机不引入 PDF 库，也不联网）。" +
                "可以先在桌面端用「导出备份」得到 JSON 文件，再走这里的「JSON 备份」通道导入。")

        method == "GET" && rest == "/backups" ->
            notImplementedEnvelope("桌面列的是启动器写在备份目录里的 app-*.db；本机 APK 没有该产物形态")
        method == "PUT" && rest == "/backups/dir" ->
            notImplementedEnvelope("同上：本机没有可选目录的 .db 备份写入方，设置目录没有作用对象")
        method == "GET" && rest == "/debug/model" -> {
            val userId = requireUser().userId()
            requireAlgorithmReady()
            Envelope(200, PiqDebug.model(crud, userId))
        }
        method == "GET" && rest == "/debug/model_history" -> {
            val userId = requireUser().userId()
            requireAlgorithmReady()
            Envelope(200, PiqDebug.modelHistory(crud, userId))
        }
        method == "GET" && rest == "/debug/prediction_trace" -> {
            val userId = requireUser().userId()
            requireAlgorithmReady()
            Envelope(200, PiqDebug.predictionTrace(crud, userId))
        }
        method == "GET" && rest.startsWith("/debug/") ->
            notImplementedEnvelope("桌面 debug 路由族的其余路径没有端上消费方（前端透视镜只用 model / model_history / prediction_trace 三条）")
        method == "GET" && rest == "/keepalive" ->
            notImplementedEnvelope("SSE 心跳用于让常驻服务自我关停；APK 内无常驻服务，且桥是单次请求-响应形态")
        method == "GET" && rest == "/keepalive/status" ->
            notImplementedEnvelope("同上：没有可被轮询的服务进程")

        else -> throw ApiError(404, MSG_NOT_FOUND, "NOT_FOUND")
    }

    // ============================================================
    // auth（7）
    // ============================================================

    private fun register(body: JSONObject): Envelope {
        limiter.checkRegister()
        val username = requireString(body, "username")
        PiqSecurity.usernameRejection(username)?.let { throw validationError("username", it) }
        val password = requireString(body, "password")
        PiqSecurity.newPasswordRejection(password)?.let { throw validationError("password", it) }
        val nickname = optionalString(body, "nickname")
        if (nickname != null && nickname.length > PiqConfig.MAX_NICKNAME_CHARS) throw validationError("nickname", "字符串太长")

        if (crud.getUserByUsername(username) != null) {
            limiter.recordRegisterFailure()
            throw ApiError(409, "用户名已被占用", "USERNAME_TAKEN")
        }
        val userId = try {
            crud.createUser(username, PiqSecurity.hashPassword(password), nickname ?: username)
        } catch (e: SQLiteConstraintException) {
            limiter.recordRegisterFailure()
            throw ApiError(409, "用户名已被占用", "USERNAME_TAKEN")
        }
        limiter.clearRegister()
        crud.ensureUserDefaults(userId)
        val user = crud.getUserById(userId) ?: throw ApiError(500, "注册后读取用户失败", "INTERNAL_ERROR")
        return Envelope(201, issueSession(user))
    }

    private fun login(body: JSONObject): Envelope {
        val username = requireString(body, "username")
        if (username.length < PiqConfig.LOGIN_USERNAME_MIN_CHARS || username.length > PiqConfig.USERNAME_MAX_CHARS) {
            throw validationError("username",
                "用户名长度须为 ${PiqConfig.LOGIN_USERNAME_MIN_CHARS}-${PiqConfig.USERNAME_MAX_CHARS} 位")
        }
        val password = requireString(body, "password")
        limiter.checkLogin(username)

        val user = crud.getUserByUsername(username)
        val stored = user?.textOrNull("password_hash") ?: PiqSecurity.dummyStoredHash()
        val ok = PiqSecurity.verifyPassword(password, stored)
        if (user != null && ok) {
            limiter.clearLogin(username)
            val payload = issueSession(user)
            crud.pruneUserSessions(user.userId())
            return Envelope(200, payload)
        }
        limiter.recordLoginFailure(username)
        throw ApiError(401, "用户名或密码错误", "AUTH_FAILED")
    }

    private fun issueSession(user: JSONObject): JSONObject {
        val token = PiqSecurity.generateToken()
        crud.createSession(token, user.userId())
        sessions.issue(token)
        return JSONObject().put("token", token).put("user", ApiProjection.user(user))
    }

    private fun logout(): JSONObject {
        val user = requireUser()
        sessions.current()?.let { crud.deleteSession(it) }
        sessions.clear()
        return JSONObject().put("message", "已登出").put("username", user.optString("username"))
    }

    private fun changePassword(body: JSONObject): Envelope {
        val user = requireUser()
        val oldPassword = requireString(body, "old_password")
        val newPassword = requireString(body, "new_password")
        if (!PiqSecurity.verifyPassword(oldPassword, user.textOrNull("password_hash") ?: "")) {
            throw ApiError(400, "原密码不正确", "WRONG_PASSWORD")
        }
        PiqSecurity.newPasswordRejection(newPassword)?.let { throw validationError("new_password", it) }
        crud.updateUserPassword(user.userId(), PiqSecurity.hashPassword(newPassword))
        sessions.current()?.let { crud.deleteOtherSessions(user.userId(), it) }
        return Envelope(200, JSONObject().put("message", "密码已更新，其他设备已强制登出"))
    }

    private fun deleteAccount(body: JSONObject): Envelope {
        val user = requireUser()
        val password = requireString(body, "password")
        if (!PiqSecurity.verifyPassword(password, user.textOrNull("password_hash") ?: "")) {
            throw ApiError(400, "密码不正确", "WRONG_PASSWORD")
        }
        crud.deleteUserAccount(user.userId())
        sessions.clear()
        return Envelope(200, JSONObject().put("message", "账号及全部数据已删除"))
    }

    private fun updateProfile(body: JSONObject): Envelope {
        val user = requireUser()
        val avatar = optionalString(body, "avatar")
        if (avatar != null &&
            (avatar.length > PiqConfig.AVATAR_MAX_CHARS || !PiqConfig.AVATAR_PREFIX_PATTERN.matches(avatar))
        ) {
            throw ApiError(400, "头像格式或大小不合法", "INVALID_AVATAR")
        }
        val nickname = optionalStringAllowingEmpty(body, "nickname")
        if (nickname != null && nickname.length > PiqConfig.MAX_NICKNAME_CHARS) throw validationError("nickname", "字符串太长")
        val birthday = optionalString(body, "birthday")?.let { isoOrReject(it, "birthday") }
        val weight = profileWeightOrReject(body)
        crud.updateProfile(
            user.userId(),
            nickname = nickname,
            avatar = avatar,
            birthday = birthday?.toIso(),
            weightKg = weight,
            clearBirthday = body.boolAt("clear_birthday"),
            clearWeight = body.boolAt("clear_weight"),
        )
        val updated = crud.getUserById(user.userId()) ?: throw ApiError(500, "资料更新后读取失败", "INTERNAL_ERROR")
        return Envelope(200, ApiProjection.user(updated))
    }

    // ============================================================
    // cycles（4）
    // ============================================================

    private fun createCycle(body: JSONObject): Envelope {
        val user = requireUser()
        val userId = user.userId()
        val start = isoOrReject(requireString(body, "period_start"), "period_start")
        val end = optionalString(body, "period_end")?.let { isoOrReject(it, "period_end") }
        val note = optionalString(body, "note") ?: ""
        if (note.length > PiqConfig.MAX_NOTE_CHARS) throw validationError("note", "字符串太长")
        rejectFutureLimit(start, "月经开始日期")
        end?.let { rejectFutureLimit(it, "月经结束日期") }

        val created = store.write { db ->
            if (end != null && end < start) {
                throw ApiError(400, "结束日期不能早于开始日期", "INVALID_PERIOD_END")
            }
            checkDateOverlap(userId, start, end, db, excludingId = null)
            requireAlgorithmReady()
            val newId = crud.createCycle(CycleRow(userId, start.toIso(), end?.toIso(), null, null, note, false), db)
            newId to runPostCycleChain(userId, newId, start, end, note, db)
        }
        val saved = crud.getCycleById(userId, created.first)
            ?: throw ApiError(500, "写入后读取记录失败", "INTERNAL_ERROR")
        return Envelope(201, ApiProjection.cycle(saved).put("prediction", predictionOrNothing(created.second)))
    }

    private fun updateCycle(cycleId: Long, body: JSONObject): Envelope {
        val user = requireUser()
        val userId = user.userId()
        store.write { db ->
            val existing = crud.getCycleById(userId, cycleId, db) ?: throw ApiError(404, "记录不存在", "NOT_FOUND")
            val bodyStart = optionalString(body, "period_start")?.let {
                isoOrReject(it, "period_start").also { d -> rejectFutureLimit(d, "月经开始日期") }
            }
            val bodyEnd = optionalString(body, "period_end")?.let {
                isoOrReject(it, "period_end").also { d -> rejectFutureLimit(d, "月经结束日期") }
            }
            val clearEnd = body.boolAt("clear_period_end")
            if (clearEnd && bodyEnd != null) {
                throw validationError("clear_period_end", "clear_period_end 与 period_end 不能同时提供")
            }
            val note = optionalStringAllowingEmpty(body, "note")
            if (note != null && note.length > PiqConfig.MAX_NOTE_CHARS) throw validationError("note", "字符串太长")

            val finalStart = bodyStart ?: (PyMath.parseIsoDate(existing.textOrNull("period_start"))
                ?: throw ApiError(500, "记录的开始日期不可解析", "INTERNAL_ERROR"))
            val finalEnd = when {
                clearEnd -> null
                bodyEnd != null -> bodyEnd
                else -> PyMath.parseIsoDate(existing.textOrNull("period_end"))
            }
            if (finalEnd != null && finalEnd < finalStart) {
                throw ApiError(400, "结束日期不能早于开始日期", "INVALID_PERIOD_END")
            }
            val startChanged = bodyStart != null && bodyStart.toIso() != existing.optString("period_start")
            if (startChanged) requireAlgorithmReady()
            checkDateOverlap(userId, finalStart, finalEnd, db, excludingId = cycleId)
            val periodDays = if (finalEnd != null) PyMath.daysBetween(finalStart, finalEnd) + 1 else null

            crud.updateCycle(
                userId = userId,
                cycleId = cycleId,
                periodStart = if (startChanged) finalStart.toIso() else null,
                periodEnd = finalEnd?.toIso(),
                periodDays = periodDays,
                note = note,
                clearPeriodEnd = clearEnd,
                handle = db,
            )
            if (startChanged) recomputeAllRecords(userId, db)
        }
        val updated = crud.getCycleById(userId, cycleId) ?: throw ApiError(404, "记录不存在", "NOT_FOUND")
        return Envelope(200, ApiProjection.cycle(updated))
    }

    private fun deleteCycle(cycleId: Long): Envelope {
        val userId = requireUser().userId()
        crud.getCycleById(userId, cycleId) ?: throw ApiError(404, "记录不存在", "NOT_FOUND")
        store.write { db ->
            val remaining = crud.getAllCyclesAsc(userId, db).count { it.optLong("id") != cycleId }
            runDeleteWithAlgorithmGate(remaining, algoGate) {
                crud.deleteCycle(userId, cycleId, db)
                recomputeAllRecords(userId, db)
            }
        }
        return Envelope(
            200,
            JSONObject().put("message", "记录已删除").put("id", cycleId).put("recomputed", true),
        )
    }

    /**
     * POST /cycles 副作用链（契约 5.3）：算 cycle_length → 异常检查 → 贝叶斯更新 →
     * 生成预测 → 落库 → 回填全部未验证预测。补录历史分支走全量重算管线（v0.14.0 修 AN-4）。
     */
    private fun runPostCycleChain(
        userId: Long,
        newCycleId: Long,
        start: LocalDate,
        end: LocalDate?,
        note: String,
        db: SQLiteDatabase,
    ): JSONObject {
        val prevStart = crud.getPreviousCycleBefore(userId, start.toIso())?.textOrNull("period_start")
        val params = crud.getModelParams(userId, db)
        val lengths = JSONArray(crud.getAllValidCycleLengths(userId, db).jsonText())
        params.put("cycle_lengths", lengths)

        val algo = PiqAlgo.onNewCycleAdded(start.toIso(), prevStart, params)
        val cycleLength = algo.longOrNull("cycle_length")
        val isAnomalous = algo.boolAt("is_anomalous")
        val isHardInvalid = algo.boolAt("is_hard_invalid")
        val newParams = algo.optJSONObject("model_params") ?: JSONObject()
        val prediction = algo.optJSONObject("prediction") ?: JSONObject()
        val periodDays = if (end != null) PyMath.daysBetween(start, end) + 1 else null

        crud.finalizeCycleDerived(
            newCycleId, PiqCrud.sanitizeCycleLength(cycleLength), periodDays, isAnomalous, note, db,
        )

        val latestStart = crud.getAllCyclesAsc(userId, db).lastOrNull()?.textOrNull("period_start") ?: start.toIso()
        if (latestStart != start.toIso()) {
            recomputeAllRecords(userId, db)
            return currentPredictionPayload(userId, db)
        }

        if (cycleLength != null && !isHardInvalid) {
            crud.updateModelParams(
                userId,
                meanCycle = newParams.optDouble("mean_cycle"),
                stdCycle = newParams.optDouble("std_cycle"),
                nRecords = newParams.optLong("n_records"),
                emaCycle = newParams.optDouble("ema_cycle"),
                priorMean = newParams.optDouble("prior_mean"),
                priorStd = newParams.optDouble("prior_std"),
                priorWeight = newParams.optDouble("prior_weight"),
                handle = db,
            )
        }

        crud.getAllPredictions(userId, db).forEach { pred ->
            if (pred.textOrNull("actual_date") == null) {
                val predicted = PyMath.parseIsoDate(pred.textOrNull("predicted_date")) ?: return@forEach
                crud.backfillPredictionActual(
                    pred.optLong("id"), start.toIso(), kotlin.math.abs(PyMath.daysBetween(predicted, start)), db,
                )
            }
        }

        if (prediction.textOrNull("predicted_date") != null) {
            crud.createPrediction(predictionRow(userId, prediction), db)
        }
        return prediction
    }

    /** cycles._current_prediction_payload：与 regenerate 同源同参，只读不写。 */
    private fun currentPredictionPayload(userId: Long, db: SQLiteDatabase): JSONObject {
        val lengths = crud.getAllValidCycleLengths(userId, db)
        val latest = crud.getLatestCycle(userId, db) ?: return PiqAlgo.predictNextPeriod(null, lengths, null)
        return PiqAlgo.predictNextPeriod(
            latest.textOrNull("period_start"), lengths, PiqAlgo.replayEma(lengths, excludeLast = true),
        )
    }

    /** cycles._recompute_all_records：重推派生字段 → 重算管线 → 重推 actual（AN-3）。 */
    private fun recomputeAllRecords(userId: Long, db: SQLiteDatabase) {
        recompute.recomputeDerivedFields(userId, db)
        recomputeAfterDeleteTx(db, userId)
        rebackfillPredictionActuals(userId, db)
    }

    private fun recomputeAfterDeleteTx(db: SQLiteDatabase, userId: Long) {
        val lengths = crud.getAllValidCycleLengths(userId, db)
        recompute.fullRecomputeModelParams(userId, lengths, db)
        crud.deleteUnverifiedPredictions(userId, db)
        recompute.regenerateCurrentPrediction(userId, db)
    }

    /**
     * cycles._rebackfill_prediction_actuals：锚点 = `predicted_date` 之前最后一条记录，
     * 来潮日 = 锚点之后最早一条；推不出锚点或锚点后无记录则保持原状（不做"清空"语义）。
     */
    private fun rebackfillPredictionActuals(userId: Long, db: SQLiteDatabase): Int {
        val starts = crud.getAllCyclesAsc(userId, db).mapNotNull { PyMath.parseIsoDate(it.textOrNull("period_start")) }
        if (starts.isEmpty()) return 0
        var updated = 0
        for (pred in crud.getAllPredictions(userId, db)) {
            val predicted = PyMath.parseIsoDate(pred.textOrNull("predicted_date")) ?: continue
            val idx = starts.indexOfLast { it < predicted } + 1
            if (idx <= 0 || idx >= starts.size) continue
            val actual = starts[idx]
            val errorDays = kotlin.math.abs(PyMath.daysBetween(predicted, actual))
            if (pred.textOrNull("actual_date") == actual.toIso() && pred.longOrNull("error_days") == errorDays) continue
            crud.backfillPredictionActual(pred.optLong("id"), actual.toIso(), errorDays, db)
            updated += 1
        }
        return updated
    }

    /** cycles._check_date_overlap：前后邻居全量规则（AN-4）；未填结束日占用 [start, start]。 */
    private fun checkDateOverlap(
        userId: Long,
        newStart: LocalDate,
        newEnd: LocalDate?,
        db: SQLiteDatabase,
        excludingId: Long?,
    ) {
        val records = crud.getAllCyclesAsc(userId, db).filter { it.optLong("id") != excludingId }
        var prev: JSONObject? = null
        var next: JSONObject? = null
        for (r in records) {
            val s = PyMath.parseIsoDate(r.textOrNull("period_start")) ?: continue
            if (s < newStart) {
                val prevS = prev?.let { PyMath.parseIsoDate(it.textOrNull("period_start")) }
                if (prevS == null || prevS < s) prev = r
            } else {
                val nextS = next?.let { PyMath.parseIsoDate(it.textOrNull("period_start")) }
                if (nextS == null || s < nextS) next = r
            }
        }
        prev?.let { p ->
            val prevEnd = PyMath.parseIsoDate(p.textOrNull("period_end"))
                ?: PyMath.parseIsoDate(p.textOrNull("period_start"))
            if (prevEnd != null && !newStart.isAfter(prevEnd)) {
                throw ApiError(
                    400,
                    "开始日期与前一条记录（${p.optString("period_start")}~${prevEnd.toIso()}）重叠：" +
                        "period_start 不能早于或等于上一条记录的 period_end",
                    "DATE_OVERLAP",
                )
            }
        }
        next?.let { n ->
            val occupiedUntil = (newEnd ?: newStart).toIso()
            val nextStart = n.optString("period_start")
            if (occupiedUntil >= nextStart) {
                throw ApiError(
                    400,
                    "记录区间与下一条记录（$nextStart）重叠：period_end 不能晚于或等于下一条记录的 period_start",
                    "DATE_OVERLAP",
                )
            }
        }
    }

    // ============================================================
    // predictions（2）/ analysis（3）
    // ============================================================

    private fun currentPrediction(): Envelope {
        val userId = requireUser().userId()
        requireAlgorithmReady()
        val resolved = freshPrediction(userId)
        val latestCycle = resolved.optJSONObject("latest_cycle")
        if (latestCycle == null) {
            return Envelope(200, JSONObject()
                .put("predicted_date", JSONObject.NULL)
                .put("confidence", 0.0)
                .put("model_version", "none")
                .put("message", "尚无月经记录，请先记录至少一次月经开始日期"))
        }
        val latestPred = resolved.optJSONObject("latest_pred")
        if (latestPred == null) {
            return Envelope(200, JSONObject()
                .put("predicted_date", JSONObject.NULL)
                .put("confidence", 0.0)
                .put("model_version", "none")
                .put("message", "暂无预测，请稍后重试"))
        }
        val predicted = PyMath.parseIsoDate(latestPred.textOrNull("predicted_date"))
        return Envelope(200, JSONObject()
            .put("predicted_date", latestPred.nullableString("predicted_date"))
            .put("predicted_start_lower", latestPred.nullableString("predicted_start_lower"))
            .put("predicted_start_upper", latestPred.nullableString("predicted_start_upper"))
            .put("confidence", latestPred.opt("confidence"))
            .put("model_version", latestPred.opt("model_version"))
            .put("days_until_predicted", if (predicted == null) JSONObject.NULL else PyMath.daysBetween(LocalDate.now(), predicted))
            .put("model_params", resolved.optJSONObject("model_params") ?: JSONObject())
            .put("is_corrected", resolved.optBoolean("is_corrected"))
            .put("corrected_from", resolved.nullableString("corrected_from")))
    }

    private fun predictionHistory(): Envelope {
        val rows = crud.getPredictionHistory(requireUser().userId(), PiqConfig.PREDICTION_HISTORY_LIMIT)
        return Envelope(200, ApiProjection.cycles(rows, ApiProjection::predictionHistoryItem))
    }

    private fun accuracy(): Envelope {
        val userId = requireUser().userId()
        requireAlgorithmReady()
        val rows = crud.getAllPredictions(userId)
        return Envelope(200, PiqAlgo.evaluatePredictions(rows.jsonArray()))
    }

    private fun cycleStats(): Envelope {
        val userId = requireUser().userId()
        requireAlgorithmReady()
        val cycles = crud.getAllCycles(userId)
        if (cycles.isEmpty()) {
            return Envelope(200, JSONObject()
                .put("total_records", 0)
                .put("total_cycles", 0)
                .put("avg_cycle_length", JSONObject.NULL)
                .put("avg_period_days", JSONObject.NULL)
                .put("shortest_cycle", JSONObject.NULL)
                .put("longest_cycle", JSONObject.NULL)
                .put("anomalous_count", 0)
                .put("status", "EMPTY")
                .put("status_message", "暂无数据")
                .put("clinical_notes", JSONArray()))
        }
        val valid = cycles.mapNotNull { it.longOrNull("cycle_length") }
        val days = cycles.mapNotNull { it.longOrNull("period_days") }
        val anomalous = cycles.count { it.boolAt("is_anomalous") }
        val avgCycle = if (valid.isEmpty()) null else valid.sumOf { it.toDouble() } / valid.size
        val avgPeriod = if (days.isEmpty()) null else days.sumOf { it.toDouble() } / days.size
        val recentAnomalous = crud.getRecentAnomalousCount(userId, PiqConfig.ANOMALY_CONSECUTIVE_THRESHOLD)
        val (status, message) = determineStatus(cycles.size, recentAnomalous)

        return Envelope(200, JSONObject()
            .put("total_records", cycles.size)
            .put("total_cycles", valid.size)
            .put("avg_cycle_length", if (avgCycle == null) JSONObject.NULL else PyMath.roundTo(avgCycle, 2))
            .put("avg_period_days", if (avgPeriod == null) JSONObject.NULL else PyMath.roundTo(avgPeriod, 2))
            .put("shortest_cycle", if (valid.isEmpty()) JSONObject.NULL else valid.min())
            .put("longest_cycle", if (valid.isEmpty()) JSONObject.NULL else valid.max())
            .put("anomalous_count", anomalous)
            .put("status", status)
            .put("status_message", message)
            .put("clinical_notes", clinicalNotes(cycles, crud.getLatestPrediction(userId))))
    }

    /** analysis._determine_status（第7章状态机，P12 的近期窗口语义）。 */
    private fun determineStatus(totalRecords: Int, recentAnomalous: Long): Pair<String, String> = when {
        recentAnomalous >= PiqConfig.ANOMALY_CONSECUTIVE_THRESHOLD ->
            "ANOMALY_ALERT" to "近期周期波动较大，建议检查记录或咨询医生"
        totalRecords <= RECORDS_COLD_MAX -> "COLD" to "记录更多周期可提升精度"
        totalRecords <= RECORDS_WARMING_MAX -> "WARMING" to "模型正在学习你的周期"
        totalRecords <= RECORDS_STABLE_MAX -> "STABLE" to "正常展示"
        else -> "MATURE" to "正常展示"
    }

    /** analysis._build_clinical_notes：纯派生提示，措辞「建议关注」不构成诊断。 */
    private fun clinicalNotes(cycles: List<JSONObject>, latestPred: JSONObject?): JSONArray {
        val notes = JSONArray()
        val recent = cycles.sortedByDescending { it.optString("period_start") }.take(PiqConfig.CLINICAL_WINDOW)
        val lengths = recent.mapNotNull { it.longOrNull("cycle_length") }
        val short = lengths.filter { it < PiqConfig.CLINICAL_CYCLE_MIN_DAYS }
        val long = lengths.filter { it > PiqConfig.CLINICAL_CYCLE_MAX_DAYS }
        if (short.isNotEmpty()) notes.put(JSONObject()
            .put("code", "short_cycle").put("severity", "info")
            .put("message", "近期有周期短于 ${PiqConfig.CLINICAL_CYCLE_MIN_DAYS} 天（最短 ${short.min()} 天）。若持续出现，建议咨询医生。"))
        if (long.isNotEmpty()) notes.put(JSONObject()
            .put("code", "long_cycle").put("severity", "info")
            .put("message", "近期有周期长于 ${PiqConfig.CLINICAL_CYCLE_MAX_DAYS} 天（最长 ${long.max()} 天）。若持续出现，建议咨询医生。"))
        val longPeriod = recent.mapNotNull { it.longOrNull("period_days") }.filter { it > PiqConfig.CLINICAL_PERIOD_MAX_DAYS }
        if (longPeriod.isNotEmpty()) notes.put(JSONObject()
            .put("code", "long_period").put("severity", "info")
            .put("message", "近期经期超过 ${PiqConfig.CLINICAL_PERIOD_MAX_DAYS} 天（最长 ${longPeriod.max()} 天）。若持续出现，建议咨询医生。"))

        if (latestPred != null && latestPred.textOrNull("actual_date") == null) {
            val predicted = PyMath.parseIsoDate(latestPred.textOrNull("predicted_date"))
            if (predicted != null) {
                val overdue = PyMath.daysBetween(predicted, LocalDate.now())
                if (overdue >= PiqConfig.OVERDUE_NOTICE_DAYS) {
                    notes.put(JSONObject()
                        .put("code", "overdue").put("severity", "attention")
                        .put("message", "预测日已过 $overdue 天且未记录新周期。月经推迟可能与多种因素有关；" +
                            "若已来潮请到日历记录，若持续未来潮，建议验孕或咨询医生。"))
                }
            }
        }
        return notes
    }

    private fun fertileWindow(): Envelope {
        val userId = requireUser().userId()
        requireAlgorithmReady()
        val resolved = freshPrediction(userId)
        val latestCycle = resolved.optJSONObject("latest_cycle")
        val latestPred = resolved.optJSONObject("latest_pred")
        if (latestCycle == null || latestPred == null) {
            return Envelope(200, PiqAlgo.computeFertilityWindow(null, null, null, PiqConfig.PRIOR_STD, null))
        }
        val params = resolved.optJSONObject("model_params") ?: JSONObject()
        val cycleStd = params.doubleOrNull("std_cycle") ?: PiqConfig.PRIOR_STD
        val history = crud.getAllCycles(userId)
            .mapNotNull { it.longOrNull("period_days") }
            .filter { it >= 1 }
        val predictedPeriodDays =
            if (history.isEmpty()) null else PyMath.intRound(PyMath.median(history.map { it.toDouble() }))
        return Envelope(200, PiqAlgo.computeFertilityWindow(
            latestPred.textOrNull("predicted_date"),
            latestCycle.textOrNull("period_start"),
            latestCycle.textOrNull("period_end"),
            cycleStd,
            predictedPeriodDays,
        ))
    }

    // ============================================================
    // settings 与导入导出（端点 6 个：`/settings` GET、`/settings` PUT、`/export`、`/export/preview`、`/import`、`/import/pdf`；括号里原来的 5 是**函数数**，与别节按端点计不同口径）
    // ============================================================

    private fun exportData(): Envelope {
        val userId = requireUser().userId()
        val total = crud.getAllPredictions(userId).size
        return Envelope(200, JSONObject()
            .put("cycles", crud.getAllCycles(userId).jsonArray())
            .put("predictions", crud.getPredictionsChronological(userId, PiqConfig.MAX_EXPORT_PREDICTIONS).jsonArray())
            .put("model_params", crud.getModelParams(userId))
            .put("app_settings", JSONObject())
            .put("exported_at", LocalDate.now().toIso())
            .put("predictions_total", total)
            .put("predictions_truncated", total > PiqConfig.MAX_EXPORT_PREDICTIONS))
    }

    private fun exportPreview(): Envelope {
        val userId = requireUser().userId()
        requireAlgorithmReady()
        val modelParams = crud.getModelParams(userId)
        val validLengths = crud.getAllValidCycleLengths(userId)
        val fresh = freshPrediction(userId)
        return Envelope(200, JSONObject()
            .put("cycles", crud.getAllCycles(userId).jsonArray())
            .put("prediction", previewPrediction(fresh.optJSONObject("latest_pred"), fresh.optJSONObject("model_params")))
            .put("model", exportModel(modelParams, validLengths)))
    }

    private fun previewPrediction(latestPred: JSONObject?, modelParams: JSONObject?): Any {
        if (latestPred == null) return JSONObject.NULL
        val predicted = PyMath.parseIsoDate(latestPred.textOrNull("predicted_date"))
        return JSONObject()
            .put("predicted_date", latestPred.nullableString("predicted_date"))
            .put("predicted_start_lower", latestPred.nullableString("predicted_start_lower"))
            .put("predicted_start_upper", latestPred.nullableString("predicted_start_upper"))
            .put("confidence", latestPred.nullableNumber("confidence"))
            .put("model_version", latestPred.nullableString("model_version"))
            .put("days_until_predicted", if (predicted == null) JSONObject.NULL else PyMath.daysBetween(LocalDate.now(), predicted))
            .put("model_params", modelParams ?: JSONObject())
            .put("actual_date", latestPred.nullableString("actual_date"))
            .put("created_at", latestPred.nullableString("created_at"))
            .put("id", latestPred.nullableNumber("id"))
    }

    /** settings._build_export_model：字段全部取自 predict_core / model_params，不重写算法。 */
    private fun exportModel(modelParams: JSONObject, validLengths: List<Double>): JSONObject {
        val prevEma = if (validLengths.isEmpty()) null else PiqAlgo.replayEma(validLengths, excludeLast = true)
        val core = PiqAlgo.predictCore(validLengths, prevEma)
        val priorWeight = PyMath.roundTo(core.optDouble("prior_weight"), 4)
        val weights = JSONArray()
            .put(JSONObject().put("label", "先验权重").put("value", priorWeight))
            .put(JSONObject().put("label", "数据权重").put("value", PyMath.roundTo(1.0 - priorWeight, 4)))
        return JSONObject()
            .put("n_records", modelParams.longOrNull("n_records") ?: core.optLong("n"))
            .put("mean_cycle", PyMath.roundTo(modelParams.doubleOrNull("mean_cycle") ?: PiqConfig.PRIOR_MEAN, 4))
            .put("std_cycle", PyMath.roundTo(modelParams.doubleOrNull("std_cycle") ?: PiqConfig.PRIOR_STD, 4))
            .put("ema_cycle", PyMath.roundTo(modelParams.doubleOrNull("ema_cycle") ?: PiqConfig.PRIOR_MEAN, 4))
            .put("prior_mean", PyMath.roundTo(modelParams.doubleOrNull("prior_mean") ?: PiqConfig.PRIOR_MEAN, 4))
            .put("prior_std", PyMath.roundTo(modelParams.doubleOrNull("prior_std") ?: PiqConfig.PRIOR_STD, 4))
            .put("prior_weight", priorWeight)
            .put("posterior_n_eff", PyMath.roundTo(core.optDouble("posterior_n_eff"), 4))
            .put("shrink_irregularity", PyMath.roundTo(core.optDouble("shrink_irregularity"), 4))
            .put("weights", weights)
            .put("model_version", if (validLengths.isEmpty()) "none" else core.optString("model_version"))
    }

    /**
     * POST /import（settings.import_data 的移植）：校验成行 + 单事务落库两段。
     *
     * 拆成 [validateImportPayload] 与 [applyImport] 是因为原来这条线性流水有 125 行
     * （R9.4 审计 §3 的"单个函数 > 120 行要么有理由要么拆"）；**顺序与语句一字未动**，
     * 行为不变的证明 = 四条导入族哨兵（`ImportGateTest` / `ImportBodyGateTest` /
     * `ImportDateFieldsTest` / `ImportParityTest`）在同一次回归里全绿。
     */
    private fun importData(body: JSONObject): Envelope {
        val userId = requireUser().userId()
        val payload = validateImportPayload(userId, body)
        val counts = applyImport(userId, payload)
        return Envelope(200, JSONObject()
            .put("imported_cycles", counts.first)
            .put("imported_predictions", counts.second)
            .put("message", "成功导入 ${counts.first} 条记录、${counts.second} 条历史预测"))
    }

    /**
     * 只读校验 + 成行，**一个字节都不写库**：
     * 形状闸门 → 替换字符 → 空载荷 → 条数上限 → 异常评估 → 逐条严格校验 → `app_settings` 形态。
     * 闸门顺序就是原来的顺序：任何一步不过都抛与改前同码同文案的异常（此时库还没被碰过）。
     */
    private fun validateImportPayload(userId: Long, body: JSONObject): ImportPayload {
        ImportGate.requirePayloadShapes(body)
        if (ImportGate.hasReplacementCharacter(body)) {
            throw ApiError(
                400,
                "这份文件里的中文已经损坏（多半是用 GBK/ANSI 编码保存的）。" +
                    "请在桌面端把它另存为 UTF-8 编码后再导入。",
                "INVALID_IMPORT",
            )
        }
        val cycles = body.optJSONArray("cycles") ?: JSONArray()
        val predictions = body.optJSONArray("predictions") ?: JSONArray()
        if (!ImportGate.hasImportablePayload(body)) {
            throw ApiError(400, "导入数据不能为空", "EMPTY_IMPORT")
        }
        if (cycles.length() > PiqConfig.MAX_IMPORT_CYCLES) {
            throw validationError("cycles", "cycles 超出导入上限（${PiqConfig.MAX_IMPORT_CYCLES} 条）")
        }
        if (predictions.length() > PiqConfig.MAX_IMPORT_PREDICTIONS) {
            throw validationError("predictions", "predictions 超出导入上限（${PiqConfig.MAX_IMPORT_PREDICTIONS} 条）")
        }

        val today = LocalDate.now()
        val historyLengths = crud.getAllValidCycleLengths(userId)
        val anomalies = ImportGate.evaluate(cycles.objects(), historyLengths, today)
        if (anomalies.length() > 0 && body.opt("allow_anomalies") != true) {
            throw ApiError(400, "数据异常，需确认", "DATA_ANOMALY", JSONObject().put("anomalies", anomalies))
        }

        val cycleRows = importCycleRows(userId, cycles)
        val predictionRows = importPredictionRows(userId, predictions)

        if (body.has("app_settings") && body.opt("app_settings") != null && body.opt("app_settings") !is JSONObject) {
            throw ApiError(400, "app_settings 必须是对象或 null", "INVALID_IMPORT")
        }
        return ImportPayload(cycleRows, predictionRows)
    }

    /**
     * 逐条严格校验周期行（G1-G5 之外的字段级规则）。
     * 从 [validateImportPayload] 里切出来只为"一个函数一段事"，**语句顺序与文案一字未动**：
     * 日期形态、未来日期、端点倒挂、备注长度、`is_anomalous` 解析各自抛的码与改前一致。
     */
    private fun importCycleRows(userId: Long, cycles: JSONArray): List<CycleRow> =
        (0 until cycles.length()).map { i ->
            val c = cycles.optJSONObject(i) ?: throw validationError("cycles.$i", "应为对象")
            val start = ImportGate.CYCLE_START.require(c)
            val end = ImportGate.CYCLE_END.optional(c)
            rejectImportFuture(start, "开始日期", start)
            if (end != null) rejectImportFuture(end, "结束日期", start)
            if (end != null && end < start) {
                throw ApiError(400, "记录 ${start.toIso()} 的结束日期早于开始日期", "INVALID_IMPORT")
            }
            val note = c.textOrNull("note") ?: ""
            if (note.length > PiqConfig.MAX_NOTE_CHARS) {
                throw ApiError(400, importNoteTooLongDetail(start.toIso()), "INVALID_IMPORT")
            }
            CycleRow(
                userId = userId,
                periodStart = start.toIso(),
                periodEnd = end?.toIso(),
                cycleLength = PiqCrud.sanitizeCycleLength(c.longOrNull("cycle_length")),
                periodDays = strictInt(c, "period_days"),
                note = note,
                isAnomalous = parseIsAnomalous(c.opt("is_anomalous"), start),
            )
        }

    /**
     * 逐条严格校验历史预测行，并按 `(predicted_date, created_at)` 排序
     * （排序在改前也是**校验之后**做的，落库行序因此不变；`createdAtOrNow` 保留导入件自带的时间戳）。
     */
    private fun importPredictionRows(userId: Long, predictions: JSONArray): List<Pair<PredictionRow, String>> =
        (0 until predictions.length()).map { i ->
            val p = predictions.optJSONObject(i) ?: throw validationError("predictions.$i", "应为对象")
            val predicted = ImportGate.PRED_DATE.require(p)
            val upper = ImportGate.PRED_UPPER.require(p)
            val lower = ImportGate.PRED_LOWER.require(p)
            if (!(lower <= predicted && predicted <= upper)) {
                throw ApiError(400, "预测 ${predicted.toIso()} 的区间端点倒挂", "INVALID_IMPORT")
            }
            val confidence = ImportGate.confidenceValue(p)
            if (confidence == null || confidence < 0.0 || confidence > 1.0) {
                throw ApiError(400, "预测 ${predicted.toIso()} 的 confidence 非法（须为 0.0-1.0 的数值）", "INVALID_IMPORT")
            }
            val modelVersion = ImportGate.normalizeModelVersion(p)
            if (modelVersion.length > PiqConfig.MAX_MODEL_VERSION_LEN ||
                !PiqConfig.ALLOWED_MODEL_VERSIONS.contains(modelVersion)
            ) {
                throw ApiError(
                    400,
                    "预测 ${predicted.toIso()} 的 model_version 非法（合法取值：" +
                        PiqConfig.ALLOWED_MODEL_VERSIONS.sorted().joinToString("/") + "）",
                    "INVALID_IMPORT",
                )
            }
            val actual = ImportGate.PRED_ACTUAL.optional(p)
            val mpJson = when (val mp = p.opt("model_params_json")) {
                is JSONObject -> mp.toString()
                is String -> if (mp.isNotEmpty()) mp else "{}"
                else -> "{}"
            }
            PredictionRow(
                userId = userId,
                predictedDate = predicted.toIso(),
                predictedStartUpper = upper.toIso(),
                predictedStartLower = lower.toIso(),
                confidence = confidence,
                modelVersion = modelVersion,
                modelParamsJson = mpJson,
                actualDate = actual?.toIso(),
                errorDays = strictInt(p, "error_days"),
            ) to createdAtOrNow(p, predicted)
        }.sortedWith(compareBy({ it.first.predictedDate }, { it.second }))

    /**
     * 单事务落库（桌面 v0.12.0 修 E7 同口径）：清空重建 → 重推派生字段 → 全量重算 →
     * 缺未验证预测时补生成。返回 `(导入周期数, 导入预测数)`。
     */
    private fun applyImport(userId: Long, payload: ImportPayload): Pair<Int, Int> = store.write { db ->
        if (payload.cycles.isNotEmpty()) requireAlgorithmReady()
        crud.deleteUserCycles(userId, db)
        crud.deleteUserPredictions(userId, db)
        payload.cycles.forEach { crud.createCycle(it, db) }
        payload.predictions.forEach { (row, createdAt) -> crud.createImportPrediction(row, createdAt, db) }
        if (payload.cycles.isNotEmpty()) {
            recompute.recomputeDerivedFields(userId, db)
            val lengths = crud.getAllValidCycleLengths(userId, db)
            recompute.fullRecomputeModelParams(userId, lengths, db)
            val hasUnverified = crud.getAllPredictions(userId, db).any { it.textOrNull("actual_date") == null }
            if (!hasUnverified) recompute.regenerateCurrentPrediction(userId, db)
        } else {
            crud.resetModelParams(userId, db)
        }
        payload.cycles.size to payload.predictions.size
    }

    // ============================================================
    // prediction_service（current 与 fertile-window 共用的"确保新鲜"）
    // ============================================================

    /** 返回 {latest_cycle, latest_pred, is_corrected, corrected_from, model_params}。 */
    private fun freshPrediction(userId: Long): JSONObject {
        val latestCycle = crud.getLatestCycle(userId)
        if (latestCycle == null) {
            return JSONObject()
                .put("latest_cycle", JSONObject.NULL)
                .put("latest_pred", JSONObject.NULL)
                .put("is_corrected", false)
                .put("corrected_from", JSONObject.NULL)
                .put("model_params", JSONObject())
        }
        var latestPred = crud.getLatestPrediction(userId)
        var isCorrected = false
        var correctedFrom: String? = null
        var modelParams = JSONObject()

        if (latestPred != null) {
            modelParams = jsonObjectOrEmpty(latestPred.textOrNull("model_params_json"))
            val predicted = PyMath.parseIsoDate(latestPred.textOrNull("predicted_date"))
            val today = LocalDate.now()
            val stale = predicted != null &&
                predicted.plusDays(PiqConfig.PREDICTION_STALE_GRACE_DAYS).isBefore(today) &&
                latestPred.textOrNull("actual_date") == null
            if (stale) {
                val outcome = store.write { db ->
                    val rePred = crud.getLatestPrediction(userId, db)
                    val rePredDate = rePred?.let { PyMath.parseIsoDate(it.textOrNull("predicted_date")) }
                    val reStale = rePred != null && rePred.textOrNull("actual_date") == null &&
                        rePredDate != null && rePredDate.isBefore(today)
                    val reCycle = if (reStale) crud.getLatestCycle(userId, db) else null
                    if (rePredDate == null || reCycle == null) return@write null
                    val lengths = crud.getAllValidCycleLengths(userId, db)
                    val fresh = PiqAlgo.predictNextPeriod(
                        reCycle.textOrNull("period_start"), lengths, PiqAlgo.replayEma(lengths, excludeLast = true),
                    )
                    val freshDate = fresh.textOrNull("predicted_date") ?: return@write null
                    if (PyMath.parseIsoDate(freshDate) == rePredDate) return@write null
                    val audit = fresh.optJSONObject("model_params") ?: JSONObject()
                    audit.put("stale_correction", JSONObject()
                        .put("from_predicted_date", rePred.optString("predicted_date"))
                        .put("corrected_at", utcSecondsIso())
                        .put("anchor_period_start", reCycle.optString("period_start"))
                        .put("n_valid_cycles", lengths.size))
                    crud.createPrediction(
                        PredictionRow(
                            userId, freshDate,
                            fresh.optString("predicted_start_upper"), fresh.optString("predicted_start_lower"),
                            fresh.optDouble("confidence"), fresh.optString("model_version"), audit.toString(),
                        ), db,
                    )
                    rePred to (fresh to audit)
                }
                if (outcome != null) {
                    val rePred = outcome.first
                    val fresh = outcome.second.first
                    val audit = outcome.second.second
                    correctedFrom = rePred.textOrNull("predicted_date")
                    isCorrected = true
                    latestPred = JSONObject(rePred.toString())
                        .put("predicted_date", fresh.optString("predicted_date"))
                        .put("predicted_start_lower", fresh.optString("predicted_start_lower"))
                        .put("predicted_start_upper", fresh.optString("predicted_start_upper"))
                        .put("confidence", fresh.opt("confidence"))
                        .put("model_version", fresh.optString("model_version"))
                    modelParams = audit
                }
            }
        }
        healModelParamsCache(userId)
        return JSONObject()
            .put("latest_cycle", latestCycle)
            .put("latest_pred", latestPred ?: JSONObject.NULL)
            .put("is_corrected", isCorrected)
            .put("corrected_from", correctedFrom ?: JSONObject.NULL)
            .put("model_params", modelParams)
    }

    /** prediction_service.heal_model_params_cache：全字段漂移检测（v0.13.0 修 F13）。 */
    private fun healModelParamsCache(userId: Long) {
        val mp = crud.getModelParams(userId)
        val cycles = crud.getAllValidCycleLengths(userId)
        if (cycles.isEmpty()) return
        val core = PiqAlgo.predictCore(cycles, PiqAlgo.replayEma(cycles, excludeLast = true))
        val eps = PiqConfig.MODEL_PARAMS_DRIFT_EPS
        val epsPw = PiqConfig.MODEL_PARAMS_DRIFT_EPS_PRIOR_WEIGHT
        val drifted = kotlin.math.abs(mp.optDouble("mean_cycle") - core.optDouble("predicted_cycle")) > eps ||
            kotlin.math.abs(mp.optDouble("std_cycle") - core.optDouble("std")) > eps ||
            kotlin.math.abs(mp.optDouble("ema_cycle") - core.optDouble("new_ema")) > eps ||
            kotlin.math.abs(mp.optDouble("prior_mean") - core.optDouble("mu_post")) > eps ||
            kotlin.math.abs(mp.optDouble("prior_std") - core.optDouble("sigma_post")) > eps ||
            kotlin.math.abs(mp.optDouble("prior_weight") - core.optDouble("prior_weight")) > epsPw ||
            mp.optLong("n_records") != core.optLong("n")
        if (drifted) crud.updateModelParamsFromCore(userId, core)
    }

    // ============================================================
    // 入参 / 出参小工具
    // ============================================================

    private fun requireUser(): JSONObject {
        val token = sessions.current() ?: throw unauthorized()
        return crud.getSessionUser(token) ?: run {
            sessions.clear()
            throw unauthorized()
        }
    }

    private fun unauthorized() = ApiError(401, "未登录或登录已过期", "UNAUTHORIZED")

    private fun JSONObject.userId(): Long = optLong("id")

    private fun parseBody(path: String, raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "body-parse-failed|path=$path|len=${raw.length}|type=${it.javaClass.name}")
            throw validationError("body", "请求体不是合法的 JSON 对象")
        }
    }

    private fun requireString(body: JSONObject, key: String): String {
        val raw = body.textOrNull(key) ?: throw validationError(key, "Field required")
        if (raw.isEmpty()) throw validationError(key, "should have at least 1 character")
        return raw
    }

    private fun optionalString(body: JSONObject, key: String): String? = body.textOrNull(key)

    private fun isoOrReject(raw: String, field: String): LocalDate =
        PyMath.parseDateStrictIso(raw) ?: throw validationError(field, "日期格式应为 YYYY-MM-DD")

    private fun rejectFutureLimit(value: LocalDate, label: String) {
        val limit = LocalDate.now().plusDays(PiqConfig.MAX_FUTURE_CYCLE_DAYS)
        if (value.isAfter(limit)) throw validationError(label, "${label}不能晚于今天（$limit）")
    }

    /** settings._reject_import_future_date：与 POST/PUT 同一 config 常量、同一错误族。 */
    private fun rejectImportFuture(value: LocalDate, label: String, recordStart: LocalDate) {
        val limit = LocalDate.now().plusDays(PiqConfig.MAX_FUTURE_CYCLE_DAYS)
        if (value.isAfter(limit)) {
            throw ApiError(
                400,
                "记录 ${recordStart.toIso()} 的$label ${value.toIso()} 晚于允许上限（最晚 $limit）",
                "INVALID_IMPORT",
            )
        }
    }

    /** 桌面口径：`isinstance(x, int) and not isinstance(x, bool)` —— 浮点/字符串一律视为"无值"。 */
    private fun strictInt(row: JSONObject, key: String): Long? {
        if (!row.has(key) || row.isNull(key)) return null
        return when (val raw = row.opt(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }
    }

    /** settings._parse_is_anomalous：只接受 bool 或 0/1 整数；键缺失/null → false。 */
    private fun parseIsAnomalous(value: Any?, recordStart: LocalDate): Boolean {
        val invalid = ApiError(
            400, "记录 ${recordStart.toIso()} 的 is_anomalous 非法（须为 bool 或 0/1 整数）", "INVALID_IMPORT",
        )
        return when (value) {
            null, JSONObject.NULL -> false
            is Boolean -> value
            is Int -> if (value == 0 || value == 1) value == 1 else throw invalid
            is Long -> if (value == 0L || value == 1L) value == 1L else throw invalid
            else -> throw invalid
        }
    }

    /** settings._validate_created_at：缺失/空串兜底为导入时刻；过长或含控制字符 → 400。 */
    private fun createdAtOrNow(row: JSONObject, predicted: LocalDate): String {
        val raw = row.textOrNull("created_at")
        if (raw.isNullOrEmpty()) return PyMath.nowSqliteUtc()
        if (raw.length > PiqConfig.MAX_CREATED_AT_LEN || raw.any { it < ' ' || it == '' }) {
            throw ApiError(
                400,
                "预测 ${predicted.toIso()} 的 created_at 非法（须为不超过 ${PiqConfig.MAX_CREATED_AT_LEN} 字符、" +
                    "不含控制字符的时间戳文本）",
                "INVALID_IMPORT",
            )
        }
        return raw
    }

    private fun predictionRow(userId: Long, prediction: JSONObject) = PredictionRow(
        userId = userId,
        predictedDate = prediction.optString("predicted_date"),
        predictedStartUpper = prediction.optString("predicted_start_upper"),
        predictedStartLower = prediction.optString("predicted_start_lower"),
        confidence = prediction.optDouble("confidence"),
        modelVersion = prediction.optString("model_version"),
        modelParamsJson = prediction.optJSONObject("model_params")?.toString() ?: "{}",
    )

    /** `prediction` 在无预测日时为 null（契约 5.3：CycleCreateResponse.prediction 可空）。 */
    private fun predictionOrNothing(prediction: JSONObject): Any =
        if (prediction.textOrNull("predicted_date") == null) JSONObject.NULL else prediction

    private fun jsonObjectOrEmpty(raw: String?): JSONObject =
        if (raw.isNullOrEmpty()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

    companion object {
        private const val TAG = "PIQ"
        private const val PATH_ASSETLINKS = "/.well-known/assetlinks.json"
        private val CYCLE_ID = Regex("^/cycles/\\d+$")

        /**
         * `analysis._determine_status` 的记录数分档上界（桌面在同一函数里写死的三个档位）：
         * 少于等于 COLD 上界 = 冷启动样本不足；超过 STABLE 上界即进入 MATURE。
         */
        private const val RECORDS_COLD_MAX = 2
        private const val RECORDS_WARMING_MAX = 5
        private const val RECORDS_STABLE_MAX = 10

        /** `POST /api/v1/import` 的后缀（只有它走四档可读错误 + 体积闸门）。 */
        private const val REST_IMPORT = "/import"

        /** 404 的用户可见文案（REG-06：不回显方法名与路径；细节进 `api-error` 日志）。 */
        private const val MSG_NOT_FOUND = "本机没有对应的功能。请从应用内的页面重新进入；" +
            "如果一直出现这句话，请把看到的页面和操作步骤记下来反馈。"

        private fun cycleIdOf(rest: String): Long = rest.removePrefix("/cycles/").toLong()

    }
}

private fun List<JSONObject>.jsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

private fun List<Double>.jsonText(): String = JSONArray().also { a -> forEach { a.put(it) } }.toString()

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * 导入备注超长时给用户看的那句 400 文案（`importCycleRows` 的拒因）。
 *
 * 单独成函数只为一件事：让这句话能被 JVM 哨兵直接调用并逐字比对 —— `importCycleRows` 要 `PiqDb`
 * 才能走到，单测够不到（本文件顶上的 [profileWeightOrReject] 与 [notImplementedEnvelope] 是同一手法）。
 * 文案是**用户可见**的，所以它写的上限必须与真正的闸门 [PiqConfig.MAX_NOTE_CHARS] 是同一个数。
 */
internal fun importNoteTooLongDetail(startIso: String): String =
    "记录 $startIso 的备注超长（>${PiqConfig.MAX_NOTE_CHARS} 字符）"

/**
 * [PiqRouter.validateImportPayload] 的产物：已经过全部闸门、但**还没有一行落库**的导入载荷。
 * 预测按 `(行, created_at)` 成对带着 —— `created_at` 不是 `PredictionRow` 的列，
 * 由 `createImportPrediction` 单独绑定（桌面把导入行的时间戳原样带走）。
 */
private data class ImportPayload(
    val cycles: List<CycleRow>,
    val predictions: List<Pair<PredictionRow, String>>,
)
