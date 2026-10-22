package io.github.yui0818.periodiq.domain.model

import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import io.github.yui0818.periodiq.PiqConfig
import io.github.yui0818.periodiq.PyMath
import io.github.yui0818.periodiq.algo.PiqAlgo
import io.github.yui0818.periodiq.data.PiqCrud
import io.github.yui0818.periodiq.data.PredictionRow
import io.github.yui0818.periodiq.textOrNull
import org.json.JSONObject

/**
 * 模型重算服务（B2a）：从 `PiqCrud` 上移的三个重算函数，断开数据层对 `PiqAlgo` 的依赖。
 *
 * **不新开事务**：`handle` 由调用方事务传入；传 null 时各 `PiqCrud` 方法沿用原有的自开事务语义。
 */
class ModelRecomputeService(private val crud: PiqCrud) {

    /** crud.full_recompute_model_params：无合法周期 → reset；否则 predict_core 一次性重算。 */
    fun fullRecomputeModelParams(userId: Long, cycleLengths: List<Double>, handle: SQLiteDatabase? = null): JSONObject {
        if (cycleLengths.isEmpty()) {
            crud.resetModelParams(userId, handle)
            return crud.getModelParams(userId, handle)
        }
        val prevEma = PiqAlgo.replayEma(cycleLengths, excludeLast = true)
        val core = PiqAlgo.predictCore(cycleLengths, prevEma)
        crud.updateModelParamsFromCore(userId, core, handle)
        return crud.getModelParams(userId, handle)
    }

    /** crud.regenerate_current_prediction：删除重算与导入补偿的单一来源。 */
    fun regenerateCurrentPrediction(userId: Long, handle: SQLiteDatabase? = null): Long? {
        val lengths = crud.getAllValidCycleLengths(userId, handle)
        val latest = crud.getLatestCycle(userId, handle) ?: return null
        val anchor = latest.textOrNull("period_start") ?: return null
        val prediction = PiqAlgo.predictNextPeriod(anchor, lengths, PiqAlgo.replayEma(lengths, excludeLast = true))
        val predictedDate = prediction.textOrNull("predicted_date") ?: return null
        return crud.createPrediction(
            PredictionRow(
                userId = userId,
                predictedDate = predictedDate,
                predictedStartUpper = prediction.textOrNull("predicted_start_upper") ?: predictedDate,
                predictedStartLower = prediction.textOrNull("predicted_start_lower") ?: predictedDate,
                confidence = prediction.optDouble("confidence"),
                modelVersion = prediction.textOrNull("model_version") ?: "none",
                modelParamsJson = prediction.optJSONObject("model_params")?.toString() ?: "{}",
            ),
            handle,
        )
    }

    /**
     * crud.recompute_derived_fields：按当前全部记录重推 cycle_length / period_days / is_anomalous。
     * 判定口径与 `on_new_cycle_added` 逐条一致（硬无效 → 长度 NULL + 标记；
     * 偏离中位数 > 阈值 → 仅标记、长度保留；`prev_start` 无条件前移）。
     */
    fun recomputeDerivedFields(userId: Long, handle: SQLiteDatabase? = null) {
        val records = crud.getAllCyclesAsc(userId, handle)
        var prevStart: LocalDate? = null
        val prevLegalLengths = mutableListOf<Double>()
        for (r in records) {
            val start = PyMath.parseIsoDate(r.textOrNull("period_start")) ?: continue
            val end = PyMath.parseIsoDate(r.textOrNull("period_end"))
            val periodDays = if (end != null) PyMath.daysBetween(start, end) + 1 else null

            var cycleLength: Long? = null
            var isAnomalous = false
            val prev = prevStart
            if (prev != null) {
                val raw = PyMath.daysBetween(prev, start)
                if (raw < PiqConfig.MIN_CYCLE_DAYS || raw > PiqConfig.MAX_CYCLE_DAYS) {
                    isAnomalous = true
                } else {
                    cycleLength = raw
                    if (prevLegalLengths.size >= 2 &&
                        kotlin.math.abs(raw - PyMath.median(prevLegalLengths)) > PiqConfig.ANOMALY_DEVIATION_THRESHOLD
                    ) {
                        isAnomalous = true
                    }
                    prevLegalLengths.add(raw.toDouble())
                }
            }
            prevStart = start
            crud.updateCycleDerivedFields(r.optLong("id"), cycleLength, periodDays, isAnomalous, handle)
        }
    }
}
