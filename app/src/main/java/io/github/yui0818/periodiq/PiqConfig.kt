package io.github.yui0818.periodiq

/**
 * 安卓侧需要、且**必须与桌面 `backend/app/config.py` 同值**的常量（R3）。
 *
 * 算法参数的**计算实现**不在这里重复：它们由 `app.config` 模块在设备侧 Python 里读取
 * （`PiqAlgo` 把同一个模块对象传给每个算法函数）。这里只放**路由壳与数据层**在
 * Kotlin 侧判定要用到的那些（周期合法性区间、会话治理、导入上限、导出口径），
 * 每个值都注明桌面出处，改动必须两端同轮（`AGENTS.md` §3.9 第 3 条）。
 */
object PiqConfig {

    /** API 前缀（config.py `API_PREFIX`）。 */
    const val API_PREFIX = "/api/v1"

    /** 周期长度合法区间的下界（config.MIN_CYCLE_DAYS）。⚠ 本类**没有**版本常量：健康检查的版本是 `MainActivity.kt:50` 传入的 `BuildConfig.VERSION_NAME`。 */
    const val MIN_CYCLE_DAYS = 7L           // config.MIN_CYCLE_DAYS
    const val MAX_CYCLE_DAYS = 90L          // config.MAX_CYCLE_DAYS
    const val ANOMALY_DEVIATION_THRESHOLD = 14  // config.ANOMALY_DEVIATION_THRESHOLD（偏离中位数）
    const val ANOMALY_CONSECUTIVE_THRESHOLD = 3 // config.ANOMALY_CONSECUTIVE_THRESHOLD

    const val SESSION_TTL_DAYS = 30L        // config.SESSION_TTL_DAYS
    const val SESSION_MAX_PER_USER = 10     // config.SESSION_MAX_PER_USER

    const val MAX_IMPORT_CYCLES = 1000      // config.MAX_IMPORT_CYCLES
    const val MAX_IMPORT_PREDICTIONS = 2000 // config.MAX_IMPORT_PREDICTIONS
    const val MAX_EXPORT_PREDICTIONS = 1000 // config.MAX_EXPORT_PREDICTIONS
    const val MAX_FUTURE_CYCLE_DAYS = 0L    // config.MAX_FUTURE_CYCLE_DAYS（0 = 不晚于今天）

    /** config.GATE_*（R2-A 导入异常门禁 G1-G5，唯一实现 `services/gate.py`）。 */
    const val GATE_CYCLE_MIN_DAYS = 7L
    const val GATE_CYCLE_MAX_DAYS = 90L
    const val GATE_PERIOD_MIN_DAYS = 2L
    const val GATE_PERIOD_MAX_DAYS = 10L
    const val GATE_HISTORY_DEVIATION_DAYS = 14.0
    const val GATE_GAP_MAX_DAYS = 90L

    /** 导入 note 上限（settings.py 与 schemas F5/G3 同限）。 */
    const val MAX_NOTE_CHARS = 2000

    /** `_MAX_CREATED_AT_LEN`（settings.py：created_at 宽松长度上限）。 */
    const val MAX_CREATED_AT_LEN = 32

    /** settings.py 的 `len(model_version) > 32` 上限（v0.14.0 修 CN-2 白名单同族）。 */
    const val MAX_MODEL_VERSION_LEN = 32

    /** 头像校验（auth.py `_AVATAR_MAX_CHARS` / `_AVATAR_PREFIX_RE`）。 */
    const val AVATAR_MAX_CHARS = 400_000
    val AVATAR_PREFIX_PATTERN = Regex("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$")

    /** 预测历史 / 精度评估取数上限（predictions.py `limit=50`）。 */
    const val PREDICTION_HISTORY_LIMIT = 50

    /** `_ALLOWED_MODEL_VERSIONS`（settings.py，v0.14.0 修 CN-2 白名单）。 */
    val ALLOWED_MODEL_VERSIONS = setOf("none", "cold_start_1", "cold_start_2", "bayesian_v1", "unknown")

    /** 登录限流（auth.py `_LOGIN_*`）。 */
    const val LOGIN_MAX_FAILURES = 5
    const val LOGIN_WINDOW_SECONDS = 15.0 * 60.0
    const val LOGIN_LOCK_SECONDS = 5.0 * 60.0

    /** 注册限流（config.REGISTER_*）。 */
    const val REGISTER_MAX_FAILURES = 20
    const val REGISTER_WINDOW_SECONDS = 600.0
    const val REGISTER_LOCK_SECONDS = 300.0

    /** 状态机与临床提示（routes/analysis.py + config.py 同名参数）。 */
    const val CLINICAL_WINDOW = 6
    const val CLINICAL_CYCLE_MIN_DAYS = 21L
    const val CLINICAL_CYCLE_MAX_DAYS = 35L
    const val CLINICAL_PERIOD_MAX_DAYS = 8L
    const val OVERDUE_NOTICE_DAYS = 7L
    const val PRIOR_STD = 7.0

    /** 预测过期宽限（config.PREDICTION_STALE_GRACE_DAYS）。 */
    const val PREDICTION_STALE_GRACE_DAYS = 0L

    /**
     * 导入请求体的**JSON 文本字节**上限（R9 的 REG-02；**安卓专属**，桌面 `import_data` 没有体积闸门）。
     *
     * 定值依据（实测，读数见 R9 报告 §3 的体积表）：两端共用的上限
     * （`MAX_IMPORT_CYCLES=1000` × `MAX_NOTE_CHARS=2000` 中文字符 + `MAX_IMPORT_PREDICTIONS=2000`
     * 各带 200 字 `model_params_json`）合出来的**最大合法备份 = 6,834,114 B = 6.52 MiB**
     * ⇒ 8 MiB 有约 23% 余量，不会挡住任何合法备份；超上限在**解析之前**按长度拒绝，
     * 因为页内解析是同步的（R8 实测 20 MB 阻塞 694 ms，且 60 s 前端超时在桥路径上不可能触发）。
     */
    const val MAX_IMPORT_BYTES = 8L * 1024 * 1024

    /** `RegisterRequest.nickname` / `ProfileUpdate.nickname` 的 `max_length=24`（R8 的 Q-41：原先两处裸数字）。 */
    const val MAX_NICKNAME_CHARS = 24

    /**
     * `ProfileUpdate.weight_kg` 的 `ge=25 / le=300`（桌面 `schemas/api.py:479`，
     * 全后端**唯一**带 `ge/le` 的数值字段 —— REG-04 逐字段实测的产物）。
     *
     * ⚠ 这两个界在桌面**同时承担"拒绝 NaN/Infinity"的职责**（pydantic 对非有限值报
     * `less_than_equal`/`greater_than_equal`）；安卓侧 `it < 25.0 || it > 300.0` 对 NaN **恒假**
     * （IEEE 754 比较），所以界本身不够 —— 必须显式判有限性，见 `profileWeightOrReject`。
     */
    const val WEIGHT_MIN_KG = 25.0
    const val WEIGHT_MAX_KG = 300.0

    /**
     * `RegisterRequest.username`（min 3 / max 32）与 `LoginRequest.username`（**min 1** / max 32）。
     *
     * ⚠ 桌面这两条**本来就不一样**（`schemas/api.py:432` vs `:452`），所以安卓的注册与登录
     * 也必须走不同的下限；R8 的 L3 建议"登录复用 3-32"不成立（实测桌面登录对 1-2 字符用户名是放行的），
     * 登记在 R9 报告 §6。
     */
    const val USERNAME_MIN_CHARS = 3
    const val USERNAME_MAX_CHARS = 32
    const val LOGIN_USERNAME_MIN_CHARS = 1

    /**
     * 口令长度上下限（桌面 `schemas/api.py` 的 `RegisterRequest.password` 与
     * `validate_new_password` 都是 min 8 / max 128）。
     *
     * ⚠ 桌面的 `LoginRequest.password` 是 **min 1** / max 128，所以这一对只管**注册与改密**；
     * 安卓的登录腿不做长度校验（口令错本来就会被拒），别把这里当成登录的门槛引用。
     */
    const val PASSWORD_MIN_CHARS = 8
    const val PASSWORD_MAX_CHARS = 128

    /**
     * 模型参数的先验默认值（`config.PRIOR_*`）。
     *
     * R9.4 的接线之后，这几个常量**真的传导**了：DDL 的 `chk_length` 上下界、`model_params` 的
     * 七个 `DEFAULT`、`PiqCrud.resetModelParams` 的 SQL、`PiqCrud.getModelParams` 的行缺失兜底、
     * `PiqRouter.exportModel` 的兜底都由这里拼接（接线前各写一遍裸字面量 ⇒ 台账 L3R93-REMAIN5）。
     * ⚠ 因此**改这里的值等于改建库文本**：`SchemaDefaultTextTest` 逐字节钉住渲染结果
     * （`MIN/MAX_CYCLE_DAYS` 是 `Long`，写成 `Double` 会渲染成 `7.0`/`90.0` ⇒ 那条哨兵立刻报红）。
     */
    const val PRIOR_MEAN = 28.0
    const val PRIOR_WEIGHT = 0.5

    /** 派生缓存自愈容差（config.MODEL_PARAMS_DRIFT_EPS / _PRIOR_WEIGHT）。 */
    const val MODEL_PARAMS_DRIFT_EPS = 0.01
    const val MODEL_PARAMS_DRIFT_EPS_PRIOR_WEIGHT = 0.001
}
