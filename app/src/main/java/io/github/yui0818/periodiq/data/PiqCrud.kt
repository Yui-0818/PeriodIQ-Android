package io.github.yui0818.periodiq.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import io.github.yui0818.periodiq.PiqConfig
import io.github.yui0818.periodiq.PyMath
import io.github.yui0818.periodiq.boolAt
import io.github.yui0818.periodiq.doubleOrNull
import io.github.yui0818.periodiq.longOrNull
import io.github.yui0818.periodiq.textOrNull
import org.json.JSONObject

/**
 * 数据访问层（R3 F1）：桌面 `backend/app/db/crud.py` 的 Kotlin 忠实移植。
 *
 * **口径来源**：`crud.py` + `docs/契约/跨端契约.md` §2 是唯一规格来源，SQL 文本、函数边界与
 * 返回值语义逐条对照（`insert_row` 返 lastrowid、`write_query` 返 rowcount；
 * "None = 不更新"与"clear_\* = 显式清空"两套语义不得合并）。全部语句参数化（`?` 占位符）。
 *
 * **事务约定**照 crud.py 文件头的 E7：参与"删除 / 修正 / 导入重算"链的函数都接
 * `handle: SQLiteDatabase?` —— 传外层写事务连接时并入同一事务，传 null 时自开一次事务
 * （桌面用不可重入的 `asyncio.Lock` 快速失败；安卓用 [PiqDb.write] 的 `ReentrantLock`
 * 串行化，链内必须透传同一连接，否则中途异常会留下半提交状态）。
 */
class PiqCrud(private val store: PiqDb) {

    private val db: SQLiteDatabase get() = store.handle

    // ============================================================
    // users / sessions / profile
    // ============================================================

    /** crud.create_user：返回新 id；用户名 UNIQUE 冲突抛异常（由路由转 409）。 */
    fun createUser(username: String, passwordHash: String, nickname: String?, handle: SQLiteDatabase? = null): Long =
        insert(handle, "INSERT INTO users (username, password_hash, nickname) VALUES (?, ?, ?)",
            argsOf(username, passwordHash, nickname))

    /** crud.get_user_by_username：按用户名取整行；不存在返回 null。 */
    fun getUserByUsername(username: String): JSONObject? =
        one("SELECT * FROM users WHERE username = ?", argsOf(username), PiqDb.USER_COLUMNS)

    /** crud.get_user_by_id：按 user_id 取整行；不存在返回 null。 */
    fun getUserById(userId: Long): JSONObject? =
        one("SELECT * FROM users WHERE id = ?", argsOf(userId), PiqDb.USER_COLUMNS)

    /** crud.update_profile：仅更新传入字段；clear_* 表示显式清空可空字段。 */
    fun updateProfile(
        userId: Long,
        nickname: String? = null,
        avatar: String? = null,
        birthday: String? = null,
        weightKg: Double? = null,
        clearBirthday: Boolean = false,
        clearWeight: Boolean = false,
        handle: SQLiteDatabase? = null,
    ): Int {
        val fields = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (nickname != null) { fields.add("nickname = ?"); params.add(nickname) }
        if (avatar != null) { fields.add("avatar = ?"); params.add(avatar) }
        if (birthday != null) { fields.add("birthday = ?"); params.add(birthday) }
        else if (clearBirthday) fields.add("birthday = NULL")
        if (weightKg != null) { fields.add("weight_kg = ?"); params.add(weightKg) }
        else if (clearWeight) fields.add("weight_kg = NULL")
        if (fields.isEmpty()) return 0
        params.add(userId)
        return update(handle, "UPDATE users SET ${fields.joinToString(", ")} WHERE id = ?", params.toTypedArray())
    }

    /** crud.create_session（v0.13.1 修 G4）：`expires_at` = UTC 此刻 + SESSION_TTL_DAYS。 */
    fun createSession(token: String, userId: Long, handle: SQLiteDatabase? = null): Long =
        insert(handle, "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)",
            argsOf(token, userId, PyMath.utcPlusDays(PiqConfig.SESSION_TTL_DAYS)))

    /**
     * crud.get_session_user：过期判定在 SQL 内完成
     * （`expires_at IS NULL OR expires_at > datetime('now')`；NULL 按不过期 fail-open）。
     */
    fun getSessionUser(token: String): JSONObject? = one(
        "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id " +
            "WHERE s.token = ? AND (s.expires_at IS NULL OR s.expires_at > datetime('now'))",
        argsOf(token), PiqDb.USER_COLUMNS,
    )

    /** crud.delete_session：按令牌删会话，返回删除行数。当前唯一调用点是登出（`/auth/logout`）；删号不经过本函数，走 `deleteUserAccount` 里按 `user_id` 整片删。 */
    fun deleteSession(token: String, handle: SQLiteDatabase? = null): Int =
        update(handle, "DELETE FROM sessions WHERE token = ?", argsOf(token))

    /** crud.prune_user_sessions：先清过期，再只保留最新 N 个（顺序不可交换，同一事务）。 */
    fun pruneUserSessions(userId: Long, handle: SQLiteDatabase? = null) {
        val run: (SQLiteDatabase) -> Unit = { h ->
            bind(h, SQL_PRUNE_EXPIRED, argsOf(userId)).use { it.executeUpdateDelete() }
            bind(h, SQL_PRUNE_KEEP_NEWEST, argsOf(userId, userId, PiqConfig.SESSION_MAX_PER_USER))
                .use { it.executeUpdateDelete() }
        }
        if (handle != null) run(handle) else store.write { run(it) }
    }

    /** crud.update_user_password：改写口令哈希列，返回受影响行数。 */
    fun updateUserPassword(userId: Long, passwordHash: String, handle: SQLiteDatabase? = null): Int =
        update(handle, "UPDATE users SET password_hash = ? WHERE id = ?", argsOf(passwordHash, userId))

    /** crud.delete_other_sessions：删该用户除 keep_token 外的全部会话（改密后踢掉其它设备）。 */
    fun deleteOtherSessions(userId: Long, keepToken: String, handle: SQLiteDatabase? = null): Int =
        update(handle, "DELETE FROM sessions WHERE user_id = ? AND token != ?", argsOf(userId, keepToken))

    /** crud.delete_user_account：子表先删、users 行最后删（单事务）。 */
    fun deleteUserAccount(userId: Long, handle: SQLiteDatabase? = null) {
        val run: (SQLiteDatabase) -> Unit = { h ->
            SQL_DELETE_USER_ACCOUNT.forEach { sql -> bind(h, sql, argsOf(userId)).use { it.executeUpdateDelete() } }
        }
        if (handle != null) run(handle) else store.write { run(it) }
    }

    /** crud.ensure_user_defaults：model_params / app_settings 默认行（幂等 INSERT OR IGNORE）。 */
    fun ensureUserDefaults(userId: Long, handle: SQLiteDatabase? = null) {
        update(handle, "INSERT OR IGNORE INTO model_params (user_id) VALUES (?)", argsOf(userId))
        update(handle, "INSERT OR IGNORE INTO app_settings (user_id) VALUES (?)", argsOf(userId))
    }

    // ============================================================
    // cycles
    // ============================================================

    /** crud.get_all_cycles：该用户全部周期，`period_start` 倒序、同日期再按 `id` 倒序。 */
    fun getAllCycles(userId: Long): List<JSONObject> =
        all("SELECT * FROM cycles WHERE user_id = ? ORDER BY period_start DESC, id DESC",
            argsOf(userId), PiqDb.CYCLE_COLUMNS)

    /** crud.get_cycle_by_id：取该用户名下指定 id 的一条周期；不存在或不属于该用户返回 null。 */
    fun getCycleById(userId: Long, cycleId: Long, handle: SQLiteDatabase? = null): JSONObject? =
        oneOn(handle, "SELECT * FROM cycles WHERE id = ? AND user_id = ?",
            argsOf(cycleId, userId), PiqDb.CYCLE_COLUMNS)

    /** crud.get_latest_cycle：period_start 最新的一条周期（预测与重算的锚点）。 */
    fun getLatestCycle(userId: Long, handle: SQLiteDatabase? = null): JSONObject? =
        oneOn(handle, "SELECT * FROM cycles WHERE user_id = ? ORDER BY period_start DESC, id DESC LIMIT 1",
            argsOf(userId), PiqDb.CYCLE_COLUMNS)

    /** crud.get_previous_cycle_before：早于给定日期的最近一条（无 id 次键，与桌面同形）。 */
    fun getPreviousCycleBefore(userId: Long, periodStart: String): JSONObject? =
        one("SELECT * FROM cycles WHERE user_id = ? AND period_start < ? ORDER BY period_start DESC LIMIT 1",
            argsOf(userId, periodStart), PiqDb.CYCLE_COLUMNS)

    /** crud.get_all_cycles_asc：日历**升序**的全部周期；导出给页面的是倒序，派生长度与门禁必须用这份。 */
    fun getAllCyclesAsc(userId: Long, handle: SQLiteDatabase? = null): List<JSONObject> =
        allOn(handle, "SELECT * FROM cycles WHERE user_id = ? ORDER BY period_start ASC, id ASC",
            argsOf(userId), PiqDb.CYCLE_COLUMNS)

    /** crud.create_cycle：`cycle_length` 入库前经 sanitize（超 [7,90] → NULL，M4 Bug1）。 */
    fun createCycle(row: CycleRow, handle: SQLiteDatabase? = null): Long = insert(
        handle,
        "INSERT INTO cycles (user_id, period_start, period_end, cycle_length, period_days, note, is_anomalous) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
        argsOf(row.userId, row.periodStart, row.periodEnd, sanitizeCycleLength(row.cycleLength),
            row.periodDays, row.note, if (row.isAnomalous) 1L else 0L),
    )

    /**
     * crud.update_cycle：`null` 表示"不更新该字段"；[clearPeriodEnd] 为 true 时
     * `period_end` 与 `period_days` 一并显式置 NULL（v0.11.5 修 B1）。
     * 传入超范围的 `cycle_length` 会经 sanitize 转 NULL 写入（M4 Bug1）。
     */
    fun updateCycle(
        userId: Long,
        cycleId: Long,
        periodStart: String? = null,
        periodEnd: String? = null,
        cycleLength: Long? = null,
        periodDays: Long? = null,
        note: String? = null,
        isAnomalous: Boolean? = null,
        clearPeriodEnd: Boolean = false,
        handle: SQLiteDatabase? = null,
    ): Int {
        val hasCycleLength = cycleLength != null
        val sanitized = if (hasCycleLength) sanitizeCycleLength(cycleLength) else null
        val fields = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (periodStart != null) { fields.add("period_start = ?"); params.add(periodStart) }
        if (clearPeriodEnd) {
            fields.add("period_end = NULL")
            fields.add("period_days = NULL")
        } else if (periodEnd != null) {
            fields.add("period_end = ?"); params.add(periodEnd)
        }
        if (hasCycleLength) { fields.add("cycle_length = ?"); params.add(sanitized) }
        if (periodDays != null) { fields.add("period_days = ?"); params.add(periodDays) }
        if (note != null) { fields.add("note = ?"); params.add(note) }
        if (isAnomalous != null) { fields.add("is_anomalous = ?"); params.add(if (isAnomalous) 1L else 0L) }
        if (fields.isEmpty()) return 0
        params.add(userId)
        params.add(cycleId)
        return update(handle, "UPDATE cycles SET ${fields.joinToString(", ")} WHERE user_id = ? AND id = ?",
            params.toTypedArray())
    }

    /** crud.delete_cycle：删该用户的一条周期，返回删除行数。 */
    fun deleteCycle(userId: Long, cycleId: Long, handle: SQLiteDatabase? = null): Int =
        update(handle, "DELETE FROM cycles WHERE user_id = ? AND id = ?", argsOf(userId, cycleId))

    /**
     * crud.get_recent_anomalous_count：最近 [limit] 条里的异常数（第7章 ANOMALY_ALERT）。
     * `limit` 是 Kotlin `Int`（非用户输入），直接内联进 SQL —— SQLite 的 `LIMIT` 表达式
     * 不接受 TEXT 绑定，走字符串占位符会报 datatype mismatch。
     */
    fun getRecentAnomalousCount(userId: Long, limit: Int): Long = scalarLong(
        "SELECT COUNT(*) FROM (SELECT is_anomalous FROM cycles WHERE user_id = ? " +
            "ORDER BY period_start DESC LIMIT $limit) WHERE is_anomalous = 1",
        argsOf(userId),
    )

    /** crud.finalize_cycle_derived：总是显式写入（与 update_cycle 的"None=不更新"不同）。 */
    fun finalizeCycleDerived(
        cycleId: Long,
        cycleLength: Long?,
        periodDays: Long?,
        isAnomalous: Boolean,
        note: String,
        handle: SQLiteDatabase? = null,
    ): Int = update(
        handle,
        "UPDATE cycles SET cycle_length = ?, period_days = ?, is_anomalous = ?, note = ? WHERE id = ?",
        argsOf(cycleLength, periodDays, if (isAnomalous) 1L else 0L, note, cycleId),
    )

    // ============================================================
    // predictions
    // ============================================================

    /** crud.create_prediction（model_params_json 必填，第9章致命Bug禁令）。 */
    fun createPrediction(row: PredictionRow, handle: SQLiteDatabase? = null): Long = insert(
        handle,
        "INSERT INTO predictions (user_id, predicted_date, predicted_start_upper, predicted_start_lower, " +
            "confidence, model_version, model_params_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
        argsOf(row.userId, row.predictedDate, row.predictedStartUpper, row.predictedStartLower,
            row.confidence, row.modelVersion, row.modelParamsJson),
    )

    /** crud.create_import_prediction：十列 INSERT（还原精度历史）。 */
    fun createImportPrediction(row: PredictionRow, createdAt: String, handle: SQLiteDatabase? = null): Long = insert(
        handle,
        "INSERT INTO predictions (user_id, predicted_date, predicted_start_upper, predicted_start_lower, " +
            "confidence, model_version, model_params_json, actual_date, error_days, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        argsOf(row.userId, row.predictedDate, row.predictedStartUpper, row.predictedStartLower,
            row.confidence, row.modelVersion, row.modelParamsJson, row.actualDate, row.errorDays, createdAt),
    )

    /** crud.get_latest_prediction：`created_at` 最新的一条预测（同秒再按 `id` 倒序）；无预测返回 null。 */
    fun getLatestPrediction(userId: Long, handle: SQLiteDatabase? = null): JSONObject? =
        oneOn(handle, "SELECT * FROM predictions WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            argsOf(userId), PiqDb.PREDICTION_COLUMNS)

    /** 用户可见的"预测历史倒序"；语义不得与规范序取数混用（crud.py 禁令）。 */
    fun getPredictionHistory(userId: Long, limit: Int): List<JSONObject> =
        all("SELECT * FROM predictions WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT $limit",
            argsOf(userId), PiqDb.PREDICTION_COLUMNS)

    /** crud.get_all_predictions：确定性次序号（精度评估 / E8 回填用）。 */
    fun getAllPredictions(userId: Long, handle: SQLiteDatabase? = null): List<JSONObject> =
        allOn(handle, "SELECT * FROM predictions WHERE user_id = ? " +
            "ORDER BY created_at ASC, predicted_date ASC, id ASC",
            argsOf(userId), PiqDb.PREDICTION_COLUMNS)

    /** crud.get_predictions_chronological：规范序（导出 / 调试用）；limit=null 不截断。 */
    fun getPredictionsChronological(userId: Long, limit: Int?, handle: SQLiteDatabase? = null): List<JSONObject> =
        allOn(handle, predictionsChronologicalSql(limit), argsOf(userId), PiqDb.PREDICTION_COLUMNS)

    /** crud.delete_unverified_predictions：删所有还没回填 actual_date 的预测，返回行数。 */
    fun deleteUnverifiedPredictions(userId: Long, handle: SQLiteDatabase? = null): Int =
        update(handle, "DELETE FROM predictions WHERE user_id = ? AND actual_date IS NULL", argsOf(userId))

    /** crud.backfill_prediction_actual：把实际开始日回填进对应预测并算 error_days。 */
    fun backfillPredictionActual(
        predictionId: Long,
        actualDate: String,
        errorDays: Long,
        handle: SQLiteDatabase? = null,
    ): Int = update(handle, "UPDATE predictions SET actual_date = ?, error_days = ? WHERE id = ?",
        argsOf(actualDate, errorDays, predictionId))

    /** crud.delete_user_cycles：清空该用户全部周期（导入=清空重建的前半）。 */
    fun deleteUserCycles(userId: Long, handle: SQLiteDatabase? = null): Int =
        update(handle, "DELETE FROM cycles WHERE user_id = ?", argsOf(userId))

    /** crud.delete_user_predictions：清空该用户全部历史预测（导入=清空重建的后半）。 */
    fun deleteUserPredictions(userId: Long, handle: SQLiteDatabase? = null): Int =
        update(handle, "DELETE FROM predictions WHERE user_id = ?", argsOf(userId))

    /** crud.get_all_valid_cycle_lengths：cycle_length 非空即合法，按 period_start 升序。 */
    fun getAllValidCycleLengths(userId: Long, handle: SQLiteDatabase? = null): List<Double> {
        val rows = allOn(handle, "SELECT cycle_length FROM cycles WHERE user_id = ? " +
            "AND cycle_length IS NOT NULL ORDER BY period_start ASC", argsOf(userId), listOf("cycle_length"))
        return rows.map { it.optLong("cycle_length").toDouble() }
    }

    // ============================================================
    // model_params / app_settings
    // ============================================================

    /** crud.get_model_params：行缺失时返回默认值（ensure_user_defaults 的兜底）。 */
    fun getModelParams(userId: Long, handle: SQLiteDatabase? = null): JSONObject {
        val row = oneOn(handle, "SELECT * FROM model_params WHERE user_id = ?",
            argsOf(userId), PiqDb.MODEL_PARAMS_COLUMNS)
        return row ?: JSONObject().apply {
            put("user_id", userId)
            put("mean_cycle", PiqConfig.PRIOR_MEAN)
            put("std_cycle", PiqConfig.PRIOR_STD)
            put("n_records", 0L)
            put("ema_cycle", PiqConfig.PRIOR_MEAN)
            put("prior_mean", PiqConfig.PRIOR_MEAN)
            put("prior_std", PiqConfig.PRIOR_STD)
            put("prior_weight", PiqConfig.PRIOR_WEIGHT)
            put("updated_at", JSONObject.NULL)
        }
    }

    /** crud.update_model_params 的 predict_core 版本（字段名逐字取自 core 的输出键）。 */
    fun updateModelParamsFromCore(userId: Long, core: JSONObject, handle: SQLiteDatabase? = null): Int =
        updateModelParams(
            userId,
            meanCycle = core.optDouble("predicted_cycle"),
            stdCycle = core.optDouble("std"),
            nRecords = core.optLong("n"),
            emaCycle = core.optDouble("new_ema"),
            priorMean = core.optDouble("mu_post"),
            priorStd = core.optDouble("sigma_post"),
            priorWeight = core.optDouble("prior_weight"),
            handle = handle,
        )

    /** crud.update_model_params（UPSERT：行不存在则插入）。 */
    fun updateModelParams(
        userId: Long,
        meanCycle: Double,
        stdCycle: Double,
        nRecords: Long,
        emaCycle: Double,
        priorMean: Double,
        priorStd: Double,
        priorWeight: Double,
        handle: SQLiteDatabase? = null,
    ): Int = update(
        handle,
        "INSERT INTO model_params (user_id, mean_cycle, std_cycle, n_records, ema_cycle, " +
            "prior_mean, prior_std, prior_weight, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
            "ON CONFLICT(user_id) DO UPDATE SET " +
            "mean_cycle = excluded.mean_cycle, std_cycle = excluded.std_cycle, " +
            "n_records = excluded.n_records, ema_cycle = excluded.ema_cycle, " +
            "prior_mean = excluded.prior_mean, prior_std = excluded.prior_std, " +
            "prior_weight = excluded.prior_weight, updated_at = CURRENT_TIMESTAMP",
        argsOf(userId, meanCycle, stdCycle, nRecords, emaCycle, priorMean, priorStd, priorWeight),
    )

    /** crud.reset_model_params：把该用户的 model_params 行打回默认值，返回行数。 */
    fun resetModelParams(userId: Long, handle: SQLiteDatabase? = null): Int =
        update(handle, SQL_RESET_MODEL_PARAMS, argsOf(userId))

    /** crud.update_settings（空壳语义：只 UPSERT updated_at）。 */
    fun updateSettings(userId: Long, handle: SQLiteDatabase? = null): Int = update(
        handle,
        "INSERT INTO app_settings (user_id, updated_at) VALUES (?, CURRENT_TIMESTAMP) " +
            "ON CONFLICT(user_id) DO UPDATE SET updated_at = CURRENT_TIMESTAMP",
        argsOf(userId),
    )

    /** ModelRecomputeService 的 SQL 落点：按 id 更新三列派生字段（SQL 保留在数据层）。 */
    fun updateCycleDerivedFields(
        id: Long,
        cycleLength: Long?,
        periodDays: Long?,
        isAnomalous: Boolean,
        handle: SQLiteDatabase? = null,
    ): Int = update(
        handle,
        "UPDATE cycles SET cycle_length = ?, period_days = ?, is_anomalous = ? WHERE id = ?",
        argsOf(cycleLength, periodDays, if (isAnomalous) 1L else 0L, id),
    )

    // ============================================================
    // 执行层 helpers（读走 rawQuery，写走 SQLiteStatement 的类型化绑定）
    // ============================================================

    private fun scalarLong(sql: String, args: Array<Any?>? = null): Long =
        db.rawQuery(sql, args?.stringArgs()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun one(sql: String, args: Array<Any?>, columns: List<String>): JSONObject? =
        oneOn(null, sql, args, columns)

    private fun oneOn(handle: SQLiteDatabase?, sql: String, args: Array<Any?>, columns: List<String>): JSONObject? =
        (handle ?: db).rawQuery(sql, args.stringArgs()).use { c ->
            if (c.moveToFirst()) c.toRowJson(columns) else null
        }

    private fun all(sql: String, args: Array<Any?>, columns: List<String>): List<JSONObject> =
        allOn(null, sql, args, columns)

    private fun allOn(handle: SQLiteDatabase?, sql: String, args: Array<Any?>, columns: List<String>): List<JSONObject> =
        (handle ?: db).rawQuery(sql, args.stringArgs()).use { c ->
            val out = mutableListOf<JSONObject>()
            while (c.moveToNext()) out.add(c.toRowJson(columns))
            out
        }

    /** insert_row：handle=null 时自开一次事务（桌面 WriteLock 的同语义）。 */
    private fun insert(handle: SQLiteDatabase?, sql: String, args: Array<Any?>): Long =
        if (handle != null) bind(handle, sql, args).use { it.executeInsert() }
        else store.write { bind(it, sql, args).use { s -> s.executeInsert() } }

    /** write_query：返回受影响行数（cursor.rowcount 的等价物）。 */
    private fun update(handle: SQLiteDatabase?, sql: String, args: Array<Any?>): Int =
        if (handle != null) bind(handle, sql, args).use { it.executeUpdateDelete() }
        else store.write { bind(it, sql, args).use { s -> s.executeUpdateDelete() } }

    private fun bind(handle: SQLiteDatabase, sql: String, args: Array<Any?>): SQLiteStatement {
        val stmt = handle.compileStatement(sql)
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null, JSONObject.NULL -> stmt.bindNull(index)
                is Long -> stmt.bindLong(index, value)
                is Int -> stmt.bindLong(index, value.toLong())
                is Double -> stmt.bindDouble(index, value)
                is Boolean -> stmt.bindLong(index, if (value) 1L else 0L)
                else -> stmt.bindString(index, value.toString())
            }
        }
        return stmt
    }

    companion object {

        /** crud._sanitize_cycle_length：超 [7,90] 返回 null（避免触发 DDL chk_length）。 */
        fun sanitizeCycleLength(value: Long?): Long? =
            if (value == null) null
            else if (value < PiqConfig.MIN_CYCLE_DAYS || value > PiqConfig.MAX_CYCLE_DAYS) null
            else value

        /**
         * crud.get_predictions_chronological 的取数 SQL：规范序 `predicted_date → created_at → id`。
         *
         * 单独成函数只为了这条排序规则**可被断言** —— 生产取数与哨兵共用这一处拼接，
         * 改排序键两边同时变（R9.3 的 L3-d P1：规则只在测试里自建时，生产 SQL 改坏没人报警）。
         * `limit` 是 Kotlin `Int`（非用户输入）⇒ 与 [getPredictionHistory] 同理直接内联，
         * SQLite 的 `LIMIT` 不接受 TEXT 绑定。
         */
        internal fun predictionsChronologicalSql(limit: Int?): String {
            val base = "SELECT * FROM predictions WHERE user_id = ? " +
                "ORDER BY predicted_date ASC, created_at ASC, id ASC"
            return if (limit == null) base else "$base LIMIT $limit"
        }

        /**
         * crud.reset_model_params 的 SQL：默认值全部接 [PiqConfig]，与 DDL 的 `DEFAULT` 共用同一份真值
         * （接线前这里把 `28.0 / 7.0 / 0.5` 又抄了一遍，见台账 L3R93-REMAIN5 的 09）。
         * `internal` 只为了让 `SchemaDefaultTextTest` 能逐字节钉住拼接结果 —— 渲染漂移是这类接线唯一的
         * 隐性后果（`331/331` 条目 CRC 也看不出来，CRC 证书面字节不证语义）。
         */
        internal const val SQL_RESET_MODEL_PARAMS =
            "UPDATE model_params SET mean_cycle = ${PiqConfig.PRIOR_MEAN}, std_cycle = ${PiqConfig.PRIOR_STD}, " +
                "n_records = 0, ema_cycle = ${PiqConfig.PRIOR_MEAN}, " +
                "prior_mean = ${PiqConfig.PRIOR_MEAN}, prior_std = ${PiqConfig.PRIOR_STD}, " +
                "prior_weight = ${PiqConfig.PRIOR_WEIGHT}, " +
                "updated_at = CURRENT_TIMESTAMP WHERE user_id = ?"

        private const val SQL_PRUNE_EXPIRED =
            "DELETE FROM sessions WHERE user_id = ? AND expires_at <= datetime('now')"

        private const val SQL_PRUNE_KEEP_NEWEST =
            "DELETE FROM sessions WHERE user_id = ? AND token NOT IN (" +
                "SELECT token FROM sessions WHERE user_id = ? " +
                "ORDER BY created_at DESC, rowid DESC LIMIT ?)"

        private val SQL_DELETE_USER_ACCOUNT = listOf(
            "DELETE FROM cycles WHERE user_id = ?",
            "DELETE FROM predictions WHERE user_id = ?",
            "DELETE FROM model_params WHERE user_id = ?",
            "DELETE FROM app_settings WHERE user_id = ?",
            "DELETE FROM sessions WHERE user_id = ?",
            "DELETE FROM users WHERE id = ?",
        )

    }
}

/** crud.create_cycle 的入参行（字段名同 DDL）。 */
data class CycleRow(
    val userId: Long,
    val periodStart: String,
    val periodEnd: String?,
    val cycleLength: Long?,
    val periodDays: Long?,
    val note: String,
    val isAnomalous: Boolean,
)

/** crud.create_prediction / create_import_prediction 的入参行。 */
data class PredictionRow(
    val userId: Long,
    val predictedDate: String,
    val predictedStartUpper: String,
    val predictedStartLower: String,
    val confidence: Double,
    val modelVersion: String,
    val modelParamsJson: String,
    val actualDate: String? = null,
    val errorDays: Long? = null,
)

/** 把变参拼成 `rawQuery` 的占位符数组；只在本文件内使用（R9.2 小项 #6 由 public 收为文件私有）。 */
private fun argsOf(vararg values: Any?): Array<Any?> = arrayOf(*values)

/** rawQuery 只接受字符串占位符数组；数值参数靠列 affinity 完成同形转换（口径说明见类文档）。 */
private fun Array<Any?>.stringArgs(): Array<String?> = Array(size) { i ->
    when (val value = this[i]) {
        null, JSONObject.NULL -> null
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}
