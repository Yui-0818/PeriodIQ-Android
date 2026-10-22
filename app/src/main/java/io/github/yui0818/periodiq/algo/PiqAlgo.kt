package io.github.yui0818.periodiq.algo

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * 算法同源入口（R3 F1）：Kotlin 侧调用 `app/src/main/python/app/` 里的纯算法函数。
 *
 * R13.1 起 `app/src/main/python/app/` 是**唯一 Python 源**（8 个文件入库）：
 * 不再从 `vendor/python` 复制，也不再核验 `docs/契约/python.sha256`；构建期只解析
 * `piq.python` / `PIQ_PYTHON` 指定的解释器。包路径保持 `app.algo.*`，源码内容不改。
 * 集合只含算法与 config，不含 db 层（aiosqlite 异步实现在 Chaquopy 里装不下）。
 *
 * **参数一律先拼 JSON 再 `json.loads`**（选型理由见报告 §3）：这样进 Python 的是原生
 * list/dict/float/str/bool，不依赖 JVM→Python 的自动映射（快照里有 `list[dict]` 形态的
 * 注解，形态一旦不符会在运行时炸）。返回值用 `json.dumps` 取回文本再交给 `org.json`。
 *
 * **None 的传法**：Chaquopy 把 Python `None` 映射成 Java `null`（真机实测：
 * `json.loads("null")` 返回 null，`requireNotNull` 因此抛 "Required value was null"）。
 * 反过来 Java null 传进 Python 就是 `None` ⇒ 可空参数一律直接传 Kotlin 的 null，
 * 不再试图"造一个 None 对象"。
 *
 * 需要 `datetime.date` 形参的函数用 `datetime.date.fromisoformat(字符串)` 造对象，
 * 与桌面 routes 层的入参形态一致（routes 也是把 ISO 串转 date 再传）。
 */
object PiqAlgo {

    private const val TAG = "PIQ"

    @Volatile
    private var ready = false

    private lateinit var jsonModule: PyObject
    private lateinit var dateClass: PyObject
    private lateinit var configModule: PyObject
    private lateinit var statsModule: PyObject
    private lateinit var bayesianModule: PyObject
    private lateinit var evalModule: PyObject
    private lateinit var fertilityModule: PyObject
    private lateinit var traceModule: PyObject
    private lateinit var sysModule: PyObject

    /** 幂等启动（首次桥调用时执行；`Python.start` 必须早于任何 `getModule`）。 */
    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (ready) return true
        return try {
            val t0 = System.nanoTime()
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            val py = Python.getInstance()
            jsonModule = py.requireModule("json")
            dateClass = requireNotNull(py.requireModule("datetime").get("date")) { "datetime.date 取不到" }
            configModule = py.requireModule("app.config")
            statsModule = py.requireModule("app.algo.stats")
            bayesianModule = py.requireModule("app.algo.bayesian")
            evalModule = py.requireModule("app.algo.eval")
            fertilityModule = py.requireModule("app.algo.fertility")
            traceModule = py.requireModule("app.algo.trace")
            sysModule = py.requireModule("sys")
            ready = true
            Log.i(
                TAG,
                "algo-ready|python=${pythonVersion()}|ms=${(System.nanoTime() - t0) / 1_000_000}",
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "algo-start-failed|type=${e.javaClass.name}|message=${e.message}")
            false
        }
    }

    /** `replay_ema(cycle_lengths, config, exclude_last)` → float。 */
    fun replayEma(cycleLengths: List<Double>, excludeLast: Boolean): Double {
        val args = loads(pythonList(cycleLengths))
        return requireNotNull(
            statsModule.callAttr("replay_ema", args, configModule, loads(if (excludeLast) "true" else "false")),
        ).toDouble()
    }

    /** `predict_core(cycle_lengths, prev_ema, config)` → dict。 */
    fun predictCore(cycleLengths: List<Double>, prevEma: Double?): JSONObject {
        val core = statsModule.callAttr(
            "predict_core", loads(pythonList(cycleLengths)), pyNumber(prevEma), configModule,
        )
        return dumpObject(requireNotNull(core) { "predict_core 返回 None" })
    }

    /**
     * `predict_next_period(last_period_start, cycle_lengths, prev_ema, config)` → dict。
     * `lastPeriodStartIso` 为 null 时传 Python `None`（0 条记录的空预测契约）。
     */
    fun predictNextPeriod(lastPeriodStartIso: String?, cycleLengths: List<Double>, prevEma: Double?): JSONObject {
        val result = statsModule.callAttr(
            "predict_next_period", isoDate(lastPeriodStartIso), loads(pythonList(cycleLengths)),
            pyNumber(prevEma), configModule,
        )
        return dumpObject(requireNotNull(result) { "predict_next_period 返回 None" })
    }

    /**
     * `on_new_cycle_added(new_period_start, prev_period_start, current_params, config)` → dict。
     * `currentParams` 必须已带 `cycle_lengths`（桌面 routes 也是这么注入的）。
     */
    fun onNewCycleAdded(newStartIso: String, prevStartIso: String?, currentParams: JSONObject): JSONObject {
        val result = bayesianModule.callAttr(
            "on_new_cycle_added",
            isoDate(newStartIso),
            isoDate(prevStartIso),
            loads(currentParams.toString()),
            configModule,
        )
        return dumpObject(requireNotNull(result) { "on_new_cycle_added 返回 None" })
    }

    /** `evaluate_predictions(predictions, n_days=None)` → dict（ISO 串日期由 Python 侧 `_to_date` 解析）。 */
    fun evaluatePredictions(predictions: JSONArray): JSONObject {
        val result = evalModule.callAttr("evaluate_predictions", loads(predictions.toString()))
        return dumpObject(requireNotNull(result) { "evaluate_predictions 返回 None" })
    }

    /** `compute_fertility_window(...)` → dict。 */
    fun computeFertilityWindow(
        predictedPeriodDateIso: String?,
        currentPeriodStartIso: String?,
        currentPeriodEndIso: String?,
        cycleStd: Double,
        predictedPeriodDays: Int?,
    ): JSONObject {
        val result = fertilityModule.callAttr(
            "compute_fertility_window",
            isoDate(predictedPeriodDateIso),
            isoDate(currentPeriodStartIso),
            isoDate(currentPeriodEndIso),
            cycleStd,
            configModule,
            predictedPeriodDays,
        )
        return dumpObject(requireNotNull(result) { "compute_fertility_window 返回 None" })
    }

    /** 设备侧 Python 版本（判据要用它留证）。 */
    fun pythonVersion(): String = runCatching {
        requireNotNull(sysModule.get("version")).toString().split(" ").firstOrNull() ?: "unknown"
    }.getOrDefault("unstarted")

    /**
     * `trace.prediction_trace(last_period_start, cycle_lengths, prev_ema, model_params, config)` → dict。
     *
     * 桌面 `routes/debug.py` 传的也是**同一入参**（`cycle_lengths` 与 `prev_ema` 取自同一处，
     * `last_period_start` 是 ISO 文本而非 date），因此本函数与 [predictCore] 的中间值逐值相同；
     * `model_params` 只用于签名兼容（trace 内部不读它，全部数值来自 `predict_core`）。
     */
    fun predictionTrace(
        lastPeriodStartIso: String,
        cycleLengths: List<Double>,
        prevEma: Double?,
        modelParams: JSONObject,
    ): JSONObject {
        val result = traceModule.callAttr(
            "prediction_trace", lastPeriodStartIso, loads(pythonList(cycleLengths)),
            pyNumber(prevEma), loads(modelParams.toString()), configModule,
        )
        return dumpObject(requireNotNull(result) { "prediction_trace 返回 None" })
    }

    /**
     * 设备侧 `app.config` 的数值常量（透视镜展示映射要用）。
     *
     * 值**不在 Kotlin 复制一份**：`PiqConfig` 的文档口径是"算法参数只在 Python 侧有一份"，
     * 这里取的就是每个算法函数收到的同一个模块对象。
     */
    fun configNumber(name: String): Double =
        requireNotNull(configModule.get(name)) { "config.$name 取不到" }.toDouble()

    /** `app.config` 的整数常量（`COLD_START_THRESHOLD`）。 */
    fun configInt(name: String): Int =
        requireNotNull(configModule.get(name)) { "config.$name 取不到" }.toInt()

    /** `app.config` 的字符串常量（`MODEL_VERSION_NONE`）。 */
    fun configText(name: String): String =
        requireNotNull(configModule.get(name)) { "config.$name 取不到" }.toString()

    /** 可空 ISO 日期 → Python `date`，null → Java null（= Python None）。 */
    private fun isoDate(iso: String?): PyObject? =
        if (iso == null) null else requireNotNull(dateClass.callAttr("fromisoformat", iso)) { "date.fromisoformat 返回 None: $iso" }

    /** 可空数 → 原生 Python number，null → None（走 Java null）。 */
    private fun pyNumber(value: Double?): PyObject? = if (value == null) null else loads(value.toString())

    private fun loads(json: String): PyObject = requireNotNull(jsonModule.callAttr("loads", json)) { "json.loads 返回 None: $json" }

    private fun dumpObject(value: PyObject): JSONObject =
        JSONObject(requireNotNull(jsonModule.callAttr("dumps", value)) { "json.dumps 返回 None" }.toString())

    private fun pythonList(values: List<Double>): String =
        JSONArray().also { a -> values.forEach { a.put(it) } }.toString()
}

/** `getModule` 的平台类型收成非空：模块取不到就是启动失败，调用方走可读错误分支。 */
private fun Python.requireModule(name: String): PyObject =
    requireNotNull(getModule(name)) { "python module 不可导入: $name" }
