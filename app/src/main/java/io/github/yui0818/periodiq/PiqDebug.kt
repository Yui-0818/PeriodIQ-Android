package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.algo.PiqAlgo
import io.github.yui0818.periodiq.api.projection.nullableString
import io.github.yui0818.periodiq.data.PiqCrud

import org.json.JSONArray
import org.json.JSONObject

/**
 * 算法透视镜的三个只读端点（R7 F1）= 桌面 `backend/app/routes/debug.py` 的 Kotlin 移植。
 *
 * **两把尺子不得混用**（桌面 v0.14.0 修 BN-1 的口径登记表，跨端契约 §2）：
 *
 * 1. **记录数口径** `n_records_total` = `cycles` 表条数（含硬无效记录与"只有开始日"的锚点记录）
 *    ⇒ 决定 `algorithm_stage` 徽章与阶段文案，三个端点都用它，并带 `algorithm_stage_caliber="n_records"` 自证；
 * 2. **参与集口径** `model_input_n` = `cycle_length IS NOT NULL` 的条数 = `predict_core` 的真实输入规模
 *    ⇒ 决定权重分布、置信度与 `model_params.n_records`；
 *    `model_history` 逐快照**只有**这一口径（`algorithm_stage_by_model_input`），
 *    其记录数口径一律 `null`：`predictions` 表还有"过期刷新新增行 / 删除重算先删后增 / 导入一次多行"
 *    三条非 POST 写入路径，快照序号与记录条数无一一对应关系 ⇒ **绝不伪造**；
 * 3. "贝叶斯是否真的生效"只看 `bayes_applied`（取自 `predict_core` 的真实输出）；
 *    `model_version` 只是版本标签，3 条记录（2 个有效周期）时它已是 `bayesian_v1` 而贝叶斯尚未参与。
 *
 * **只读**：本对象不写库、不触发生成预测（与桌面模块顶部的只读约束同一条）。
 * 展示文案与数字形态逐字照抄桌面（`%g` / `:.1f` / `:.4f` 由 [PyMath.generalFormat] /
 * [PyMath.fixedFormat] 复刻），配置常量一律取设备侧 `app.config`（见 [PiqAlgo.configNumber]）。
 */
object PiqDebug {

    private const val STAGE_EMPTY = "empty"
    private const val STAGE_COLD_1 = "cold_1"
    private const val STAGE_COLD_2 = "cold_2"
    private const val STAGE_BAYESIAN = "bayesian"

    /** `algorithm_stage_caliber` 的取值：三个端点的徽章都是记录数口径。 */
    private const val CALIBER_N_RECORDS = "n_records"

    /** `model_params.n_records` 的口径自证文案（桌面逐字）。 */
    private const val CALIBER_MODEL_INPUT = "model_input（cycles.cycle_length IS NOT NULL）"

    private val STAGE_LABEL = mapOf(
        STAGE_EMPTY to "空白",
        STAGE_COLD_1 to "冷启动（1条）",
        STAGE_COLD_2 to "冷启动（2条）",
        STAGE_BAYESIAN to "贝叶斯自学习",
    )

    private val STAGE_DESCRIPTION = mapOf(
        STAGE_EMPTY to "尚无记录，请先记录至少一次月经开始日期",
        STAGE_COLD_1 to "仅1条记录，使用先验默认值28天",
        STAGE_COLD_2 to "2条记录，使用最近一次周期长度",
        STAGE_BAYESIAN to "已有3+条记录，贝叶斯后验生效，先验权重随数据增加而衰减",
    )

    /** 展示映射要用的 `app.config` 常量，一次取好（值不在 Kotlin 复制）。 */
    private data class Caliber(
        val priorMean: Double,
        val priorStd: Double,
        val confidenceCold0: Double,
        val coldStartThreshold: Int,
        val priorDecayK: Double,
        val modelVersionNone: String,
    )

    private fun readCaliber(): Caliber = Caliber(
        priorMean = PiqAlgo.configNumber("PRIOR_MEAN"),
        priorStd = PiqAlgo.configNumber("PRIOR_STD"),
        confidenceCold0 = PiqAlgo.configNumber("CONFIDENCE_COLD_0"),
        coldStartThreshold = PiqAlgo.configInt("COLD_START_THRESHOLD"),
        priorDecayK = PiqAlgo.configNumber("PRIOR_DECAY_K"),
        modelVersionNone = PiqAlgo.configText("MODEL_VERSION_NONE"),
    )

    // ============================================================
    // 展示映射（桌面 `backend/app/routes/debug.py` 的 8 个下划线前缀纯函数，安卓侧同名逐条对照：
    // _stage_by_record_count / _stage_by_model_input / _stage_description / _confidence_label /
    // _weight_label / _display_prior_weight / _weight_description / _display_note）
    // 现查命令（桌面仓库）：grep -nE '^def _' backend/app/routes/debug.py
    // ============================================================

    /** `_stage_by_record_count`：入参必须是**记录数口径**（喂模型输入规模会贴出偏乐观的徽章）。 */
    private fun stageByRecordCount(nRecords: Int): String = when {
        nRecords == 0 -> STAGE_EMPTY
        nRecords == 1 -> STAGE_COLD_1
        nRecords == 2 -> STAGE_COLD_2
        else -> STAGE_BAYESIAN
    }

    /** `_stage_by_model_input`：**参与集**口径，仅供 `model_history` 逐快照，映射与 `predict_core.stage` 一致。 */
    private fun stageByModelInput(nValid: Int): String = when {
        nValid <= 0 -> STAGE_EMPTY
        nValid == 1 -> STAGE_COLD_1
        nValid == 2 -> STAGE_COLD_2
        else -> STAGE_BAYESIAN
    }

    /**
     * `_stage_description`：bayesian 档在 `bayes_applied=False` 时必须如实说明贝叶尚未参与
     * （直接复用「后验生效」文案会造出虚假确定感 —— 桌面审计 H2）。
     */
    private fun stageDescription(stage: String, nValid: Int, bayesApplied: Boolean, c: Caliber): String =
        if (stage == STAGE_BAYESIAN && !bayesApplied) {
            "已有3+条记录，进入自学习阶段；当前有效周期数 $nValid 个（< ${c.coldStartThreshold}），" +
                "贝叶斯后验尚未参与预测"
        } else {
            STAGE_DESCRIPTION.getValue(stage)
        }

    /** 置信度分档阈值（逐字来自桌面 `_confidence_label`；断点归属桌面 BN-2）：低于各值依次落入 低/中低/中/中高，其余为高。 */
    private const val CONF_BAND_LOW = 0.4
    private const val CONF_BAND_MID_LOW = 0.6
    private const val CONF_BAND_MID = 0.75
    private const val CONF_BAND_MID_HIGH = 0.85

    /** `_confidence_label`。 */
    private fun confidenceLabel(confidence: Double): String = when {
        confidence < CONF_BAND_LOW -> "低"
        confidence < CONF_BAND_MID_LOW -> "中低"
        confidence < CONF_BAND_MID -> "中"
        confidence < CONF_BAND_MID_HIGH -> "中高"
        else -> "高"
    }

    /** 先验权重分档阈值（逐字来自桌面 `_weight_label`；断点归属桌面 BN-2）：高于各值依次落入 纯先验/先验主导/过渡期/数据主导，其余为强数据。 */
    private const val WEIGHT_BAND_PURE_PRIOR = 0.7
    private const val WEIGHT_BAND_PRIOR_DOMINANT = 0.5
    private const val WEIGHT_BAND_TRANSITION = 0.3
    private const val WEIGHT_BAND_DATA_DOMINANT = 0.15

    /** `_weight_label`：区间左开右闭（四个断点的归属见桌面 BN-2）。 */
    private fun weightLabel(priorWeight: Double): String = when {
        priorWeight > WEIGHT_BAND_PURE_PRIOR -> "纯先验"
        priorWeight > WEIGHT_BAND_PRIOR_DOMINANT -> "先验主导"
        priorWeight > WEIGHT_BAND_TRANSITION -> "过渡期"
        priorWeight > WEIGHT_BAND_DATA_DOMINANT -> "数据主导"
        else -> "强数据"
    }

    /**
     * `_display_prior_weight`：v0.5.0 的**展示档位**（cold_2 用记录数 2 算出 ≈0.7143）。
     * 自桌面 BN-3 起它不再覆盖 `weight_distribution.prior_weight`，只作为 `display_note` 的对照值。
     */
    private fun displayPriorWeight(stage: String, tablePriorWeight: Double, c: Caliber): Double = when (stage) {
        STAGE_EMPTY, STAGE_COLD_1 -> 1.0
        STAGE_COLD_2 -> PyMath.roundTo(1.0 / (1.0 + 2 / c.priorDecayK), 4)
        else -> tablePriorWeight
    }

    /** `_weight_description`：如实标注口径；`n_eff=0`（纯先验）要说明原因。 */
    private fun weightDescription(priorWeight: Double, nEff: Double, c: Caliber): String {
        val dataWeight = 1.0 - priorWeight
        val head = "数据权重 ${PyMath.fixedFormat(dataWeight * 100, 1)}%，" +
            "先验权重 ${PyMath.fixedFormat(priorWeight * 100, 1)}%"
        return if (nEff <= 0.0) {
            head + "（算法真实值：有效周期数 0，n_eff=0，完全依赖先验）"
        } else {
            head + "（算法真实值：n_eff=${PyMath.generalFormat(nEff)}，" +
                "先验衰减常数 k=${PyMath.generalFormat(c.priorDecayK)}，公式 1/(1+n_eff/k)）"
        }
    }

    /** `_display_note`：展示档位与算法真实值的差异说明；两者相等时明说一致。 */
    private fun displayNote(
        displayWeight: Double, priorWeight: Double, nRecordsTotal: Int, nValid: Int, nEff: Double,
    ): String {
        if (kotlin.math.abs(displayWeight - priorWeight) < DISPLAY_NOTE_TIE_EPS) {
            return "展示值与算法真实值一致（v0.14.0 起展示档位不再覆盖真实值）"
        }
        return "展示档位 ${PyMath.fixedFormat(displayWeight, 4)} 来自 v0.5.0 展示决策" +
            "（按记录数 $nRecordsTotal 计算），算法真实先验权重为 ${PyMath.fixedFormat(priorWeight, 4)}" +
            "（按有效周期数 $nValid 计算，n_eff=${PyMath.generalFormat(nEff)}）；" +
            "v0.14.0 修 BN-3 起本响应与面板一律以算法真实值为准"
    }

    // ============================================================
    // 端点
    // ============================================================

    /**
     * `GET /debug/model`：当前模型快照。
     *
     * 权重/`n_eff`/`bayes_applied`/`model_version` 取当前 `predict_core` 的真实输出
     * （入参与 POST 链、trace、过期刷新同源：`prev_ema` **不含**最新一条）；
     * `mean_cycle` 等四个数取 `model_params` 派生缓存（与 core 逐值相等）。
     */
    fun model(crud: PiqCrud, userId: Long): JSONObject {
        val c = readCaliber()
        val modelParams = crud.getModelParams(userId)
        val cycles = crud.getAllCycles(userId)
        val validLengths = crud.getAllValidCycleLengths(userId)
        val latestPrediction = crud.getLatestPrediction(userId)

        val nRecordsTotal = cycles.size
        val stage = stageByRecordCount(nRecordsTotal)
        val nValid = validLengths.size
        val nAnomalous = cycles.count { it.boolAt("is_anomalous") }
        val nHardInvalid = cycles.count { it.boolAt("is_anomalous") && it.isNull("cycle_length") }
        val nAnchorRecords = cycles.count { it.isNull("cycle_length") && !it.boolAt("is_anomalous") }

        val core = PiqAlgo.predictCore(validLengths, PiqAlgo.replayEma(validLengths, excludeLast = true))
        val priorWeight = PyMath.roundTo(core.optDouble("prior_weight"), 4)
        val dataWeight = PyMath.roundTo(1.0 - priorWeight, 4)
        val nEff = PyMath.roundTo(core.optDouble("n_eff"), 2)
        val bayesApplied = core.optBoolean("bayes_applied")
        val displayWeight = PyMath.roundTo(displayPriorWeight(stage, priorWeight, c), 4)

        val confidence = if (stage == STAGE_EMPTY) {
            c.confidenceCold0
        } else {
            latestPrediction?.doubleOrNull("confidence") ?: c.confidenceCold0
        }
        val modelVersion = if (nRecordsTotal == 0) c.modelVersionNone else core.optString("model_version")

        return JSONObject()
            .put("algorithm_stage", stage)
            .put("algorithm_stage_caliber", CALIBER_N_RECORDS)
            .put("stage_label", STAGE_LABEL.getValue(stage))
            .put("stage_description", stageDescription(stage, nValid, bayesApplied, c))
            .put(
                "model_params",
                JSONObject()
                    .put("mean_cycle", PyMath.roundTo(modelParams.doubleOrNull("mean_cycle") ?: c.priorMean, 2))
                    .put("std_cycle", PyMath.roundTo(modelParams.doubleOrNull("std_cycle") ?: c.priorStd, 2))
                    .put("n_records", nValid)
                    .put("n_records_caliber", CALIBER_MODEL_INPUT)
                    .put("ema_cycle", PyMath.roundTo(modelParams.doubleOrNull("ema_cycle") ?: c.priorMean, 2))
                    .put("prior_mean", PyMath.roundTo(modelParams.doubleOrNull("prior_mean") ?: c.priorMean, 2))
                    .put("prior_std", PyMath.roundTo(modelParams.doubleOrNull("prior_std") ?: c.priorStd, 2))
                    .put("prior_weight", priorWeight),
            )
            .put(
                "weight_distribution",
                JSONObject()
                    .put("prior_weight", priorWeight)
                    .put("data_weight", dataWeight)
                    .put("label", weightLabel(priorWeight))
                    .put("description", weightDescription(priorWeight, nEff, c))
                    .put("display_prior_weight", displayWeight)
                    .put("display_note", displayNote(displayWeight, priorWeight, nRecordsTotal, nValid, nEff)),
            )
            .put("confidence", PyMath.roundTo(confidence, 3))
            .put("confidence_label", confidenceLabel(confidence))
            .put("n_records_total", nRecordsTotal)
            .put("n_total_cycles", nRecordsTotal)
            .put("model_input_n", nValid)
            .put("n_valid_cycles", nValid)
            .put("n_anomalous", nAnomalous)
            .put("n_hard_invalid", nHardInvalid)
            .put("n_anchor_records", nAnchorRecords)
            .put("bayes_applied", bayesApplied)
            .put("model_version", modelVersion)
            .put("n_eff", nEff)
            .put("last_updated", if (nRecordsTotal == 0) JSONObject.NULL else modelParams.nullableString("updated_at"))
    }

    /**
     * `GET /debug/model_history`：模型参数演化轨迹（逐条取 `predictions.model_params_json` 快照）。
     *
     * 排序 = `created_at`（主键：本消费方的语义锚是"快照何时生成"）→ `predicted_date`（并列兜底，
     * 不依赖插入顺序）→ `id`（最后兜底）；快照 JSON 解析失败的条目**跳过**而不报错（桌面风险 7.1 缓解）。
     */
    fun modelHistory(crud: PiqCrud, userId: Long): JSONObject {
        val c = readCaliber()
        val history = JSONArray()
        crud.getAllPredictions(userId)
            .sortedWith(
                compareBy({ it.textOrNull("created_at") ?: "" },
                    { it.textOrNull("predicted_date") ?: "" },
                    { it.optLong("id") }),
            )
            .forEach { prediction ->
                val snapshot = parseSnapshot(prediction.textOrNull("model_params_json")) ?: return@forEach
                val modelInputN = snapshot.longOrNull("n_records") ?: 0L
                history.put(
                    JSONObject()
                        .put("prediction_id", prediction.optLong("id"))
                        .put("created_at", prediction.nullableString("created_at"))
                        .put("n_records_at_time", modelInputN)
                        .put("model_input_n", modelInputN)
                        .put("n_records_recorded", JSONObject.NULL)
                        .put("algorithm_stage", JSONObject.NULL)
                        .put("algorithm_stage_caliber", CALIBER_N_RECORDS)
                        .put("algorithm_stage_by_model_input", stageByModelInput(modelInputN.toInt()))
                        .put("model_version", prediction.nullableString("model_version"))
                        .put("bayes_applied", snapshotBayesApplied(snapshot))
                        .put(
                            "model_params",
                            JSONObject()
                                .put("mean_cycle", PyMath.roundTo(snapshot.doubleOrNull("mean_cycle") ?: c.priorMean, 2))
                                .put("std_cycle", PyMath.roundTo(snapshot.doubleOrNull("std_cycle") ?: c.priorStd, 2))
                                .put("n_records", modelInputN)
                                .put("ema_cycle", PyMath.roundTo(snapshot.doubleOrNull("ema_cycle") ?: c.priorMean, 2))
                                .put("prior_weight", PyMath.roundTo(snapshot.doubleOrNull("prior_weight") ?: 1.0, 4)),
                        ),
                )
            }
        return JSONObject()
            .put("total_snapshots", history.length())
            .put("n_records_recorded_available", false)
            .put("caliber_note", CALIBER_NOTE)
            .put("history", history)
    }

    /**
     * `GET /debug/prediction_trace`：当前预测的逐步算踪（Python 侧 `algo.trace.prediction_trace`）。
     *
     * 无记录 ⇒ 直接给空态（`steps=[]`、`final_prediction=null`、`message`）；
     * 有记录 ⇒ 步骤集合完全由 trace 的算法规则决定（本层**不增删任何步骤**），
     * 只在顶层按记录数口径覆盖 `algorithm_stage` 并补 `bayes_applied` / `model_version` 自证。
     */
    fun predictionTrace(crud: PiqCrud, userId: Long): JSONObject {
        val c = readCaliber()
        val latestCycle = crud.getLatestCycle(userId) ?: return emptyTrace(c)
        val cycleLengths = crud.getAllValidCycleLengths(userId)
        val prevEma = PiqAlgo.replayEma(cycleLengths, excludeLast = true)
        val result = PiqAlgo.predictionTrace(
            latestCycle.textOrNull("period_start") ?: "", cycleLengths, prevEma, crud.getModelParams(userId),
        )
        val core = PiqAlgo.predictCore(cycleLengths, prevEma)
        val nValid = core.optLong("n")
        val bayesApplied = core.optBoolean("bayes_applied")
        val nRecords = crud.getAllCycles(userId).size

        result.put("algorithm_stage", stageByRecordCount(nRecords))
            .put("algorithm_stage_caliber", CALIBER_N_RECORDS)
            .put("n_records_total", nRecords)
            .put("model_input_n", nValid)
            .put("bayes_applied", bayesApplied)
            .put("model_version", core.optString("model_version"))
        if (!bayesApplied) {
            result.put(
                "note",
                "冷启动阶段：贝叶斯后验未参与预测——有效周期数 $nValid 个（需 ≥${c.coldStartThreshold} 个），" +
                    "当前月经记录 $nRecords 条",
            )
        }
        return result
    }

    /** 0 条记录时的算踪空态（与桌面 `get_debug_prediction_trace` 的空分支逐键一致）。 */
    private fun emptyTrace(c: Caliber): JSONObject = JSONObject()
        .put("steps", JSONArray())
        .put("final_prediction", JSONObject.NULL)
        .put("algorithm_stage", STAGE_EMPTY)
        .put("algorithm_stage_caliber", CALIBER_N_RECORDS)
        .put("n_records_total", 0)
        .put("model_input_n", 0)
        .put("bayes_applied", false)
        .put("model_version", c.modelVersionNone)
        .put("message", "尚无记录，无法计算预测")

    /** 快照文本 → 对象；缺失/空串按桌面的 `or "{}"` 取空对象，非法 JSON 返回 null（该条跳过）。 */
    private fun parseSnapshot(raw: String?): JSONObject? = when {
        raw.isNullOrEmpty() -> JSONObject()
        else -> runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * 快照里的 `bayes_applied`：桌面判据是 `isinstance(x, (bool, int))`，
     * 旧快照/导入快照缺这个键或给的是浮点 ⇒ 返回 null（**不猜**）。
     */
    private fun snapshotBayesApplied(snapshot: JSONObject): Any = when (val raw = snapshot.opt("bayes_applied")) {
        is Boolean -> raw
        is Int -> raw != 0
        is Long -> raw != 0L
        else -> JSONObject.NULL
    }

    /** `caliber_note` 逐字取自桌面（分段写法和 Python 源码的隐式拼接一一对应）。 */
    private const val CALIBER_NOTE =
        "n_records_at_time / model_input_n 为模型输入口径（该快照 model_params_json." +
        "n_records，即当时 predict_core 的真实输入规模）；n_records_recorded 为记录数" +
        "口径，因 predictions 表存在非 POST /cycles 写入路径（过期刷新新增行、删除/" +
        "修正重算先删后增、导入一次多行），快照序号与记录条数无一一对应关系，故一律" +
        "返回 null，algorithm_stage（记录数口径）随之不可确定；逐快照的模型状态请看" +
        "algorithm_stage_by_model_input / bayes_applied（参与集口径）；当前记录数见 " +
        "GET /debug/model 的 n_records_total。"

    /** `display_note` 判"展示值与真实值一致"的容差（桌面写死 1e-9，这里命名以免落进文案拼读）。 */
    private const val DISPLAY_NOTE_TIE_EPS = 1e-9
}
