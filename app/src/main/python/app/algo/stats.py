"""统计预测模块（agent.md 第4.1章）。

纯函数实现，不依赖 db/routes。所有参数从 config 读取。
公式严格按 agent.md 第4.1章，参数值严格按第8章。
"""

import math
import statistics
from datetime import date, timedelta
from typing import Any

from app import config


def _legal_cycles(cycle_lengths: list[float], config_obj: Any = config) -> list[float]:
    """硬剔除：仅保留 [MIN_CYCLE_DAYS, MAX_CYCLE_DAYS] 区间的周期长度。

    v0.8.0 用户明确口径：过于离谱的数据（如 1 天、200 天）一定是录入错误或
    非周期出血，不参与任何计算；而 [7,90] 内的真实波动一律保留，只降权不删除。
    """
    lo, hi = config_obj.MIN_CYCLE_DAYS, config_obj.MAX_CYCLE_DAYS
    return [float(c) for c in cycle_lengths if lo <= float(c) <= hi]


def robust_weights(cycle_lengths: list[float], config_obj: Any = config) -> list[float]:
    """鲁棒降权权重（agent.md 4.1 v0.8.0，修 F5）。

    不再“偏离均值>14天就丢弃整条”，而是按偏离程度连续降权：
    - med   = 中位数（比均值抗污染）
    - scale = max(1.4826 * MAD, ROBUST_MIN_SCALE)   1.4826 使 MAD 与 标准差一致；
      下限防止周期高度一致时 MAD≈0 导致把正常波动误判为异常
    - z     = |x - med| / scale
    - w     = 1 / (1 + max(0, z - ROBUST_SOFT_Z) ** 2)，再取 max(w, ROBUST_MIN_WEIGHT)

    性质：越异常降权越大（用户要求），但权重永不归零，信息不被丢弃；
    正常波动（z<=1.5）权重为 1.0，不受影响。

    Args:
        cycle_lengths: 周期长度列表（调用前应已经过 _legal_cycles 硬剔除）。

    Returns:
        与入参等长的权重列表；n<=1 时全为 1.0（无离散度可估）。
    """
    n = len(cycle_lengths)
    if n <= 1:
        return [1.0] * n
    med = statistics.median(cycle_lengths)
    mad = statistics.median([abs(float(c) - med) for c in cycle_lengths])
    scale = max(config_obj.ROBUST_MAD_SCALE * mad, config_obj.ROBUST_MIN_SCALE)
    weights: list[float] = []
    for c in cycle_lengths:
        z = abs(float(c) - med) / scale
        excess = max(0.0, z - config_obj.ROBUST_SOFT_Z)
        w = 1.0 / (1.0 + excess * excess)
        weights.append(max(w, config_obj.ROBUST_MIN_WEIGHT))
    return weights


def effective_sample_size(weights: list[float]) -> float:
    """Kish 有效样本量 n_eff = (Σw)^2 / Σw^2。

    降权后的样本对统计量的贡献变小，n_eff 相应下降，用于置信度与先验权重计算。
    全部等权时 n_eff == 样本数。
    """
    sw = sum(weights)
    sw2 = sum(w * w for w in weights)
    if sw2 <= 0:
        return 0.0
    return sw * sw / sw2


def weighted_mean(
    cycle_lengths: list[float],
    N: int,
    decay: float = 0.85,
    extra_weights: list[float] | None = None,
) -> float:
    """加权平均（agent.md 4.1）。

    权重按衰减率分配，越近的记录权重越大；v0.8.0 可选叠加鲁棒降权权重：
    - recent = cycle_lengths[-N:]
    - weights = [decay ** i * extra_weights[i] for i ...]（未传 extra_weights 时退化为原公式）
    - 返回 sum(c*w) / sum(w)

    Args:
        cycle_lengths: 历史周期长度列表（按时间升序，最晚在末尾）。
        N: 取最近 N 次（由调用方显式传入窗口，如 config.STATS_WINDOW_N_V080=6；
           不得依赖默认值）。
        decay: 衰减率，默认 0.85（config.WEIGHT_DECAY）。
        extra_weights: 鲁棒降权权重（与 recent 对齐），None 表示不降权。

    Returns:
        加权平均值。空列表返回先验均值 28.0。
    """
    recent = cycle_lengths[-N:]
    if len(recent) == 0:
        return config.PRIOR_MEAN
    weights = [decay ** i for i in range(len(recent) - 1, -1, -1)]
    if extra_weights is not None:
        tail = extra_weights[-len(recent):]
        weights = [w * rw for w, rw in zip(weights, tail)]
    total_w = sum(weights)
    if total_w <= 0:
        return config.PRIOR_MEAN
    return sum(c * w for c, w in zip(recent, weights)) / total_w


def ema_update(prev_ema: float | None, new_cycle: float, alpha: float = 0.3) -> float:
    """EMA 指数移动平均更新（agent.md 4.1）。

    - prev_ema 为 None 时返回 new_cycle（首次初始化）
    - 否则 alpha * new_cycle + (1 - alpha) * prev_ema

    Args:
        prev_ema: 前一个 EMA 值，None 表示首次。
        new_cycle: 新观测的周期长度。
        alpha: 学习率，默认 0.3（config.EMA_ALPHA）。

    Returns:
        更新后的 EMA 值。
    """
    if prev_ema is None:
        return new_cycle
    return alpha * new_cycle + (1 - alpha) * prev_ema


def cycle_std(
    cycle_lengths: list[float],
    N: int,
    extra_weights: list[float] | None = None,
) -> float:
    """周期标准差（agent.md 4.1）。

    - recent = cycle_lengths[-N:]
    - 不足 2 个样本返回先验标准差 7.0（v0.8.0 修 F5：不再在单样本上开根号崩溃）
    - 否则返回 max(sample_std, MIN_STD)，防止 std=0 导致区间为 0 天（第9章致命Bug禁令）
    - 传入 extra_weights 时用加权方差 + Bessel 修正（n_eff/(n_eff-1)）

    Args:
        cycle_lengths: 历史周期长度列表。
        N: 取最近 N 次（由调用方显式传入窗口，如 config.STATS_WINDOW_N_V080=6；
           不得依赖默认值。v0.14.2：config.STATS_WINDOW_N=12 已全库无读取，
           保留仅为历史审计留痕）。
        extra_weights: 鲁棒降权权重（与 recent 对齐）。

    Returns:
        样本标准差。不足 2 个样本时返回 7.0。结果不低于 MIN_STD=2.0。
    """
    recent = cycle_lengths[-N:]
    if len(recent) < 2:
        return config.PRIOR_STD
    if extra_weights is None:
        # 第9章致命Bug禁令：std 下限 MIN_STD=2.0，防止区间为 0 天
        return max(statistics.stdev(recent), config.MIN_STD)

    tail = extra_weights[-len(recent):]
    sw = sum(tail)
    if sw <= 0:
        return config.PRIOR_STD
    mean = sum(c * w for c, w in zip(recent, tail)) / sw
    var = sum(w * (c - mean) ** 2 for c, w in zip(recent, tail)) / sw
    n_eff = effective_sample_size(tail)
    if n_eff > 1:
        var *= n_eff / (n_eff - 1.0)  # 频率权重 Bessel 修正，避免系统性低估方差
    return max(var ** 0.5, config.MIN_STD)


def replay_ema(
    cycle_lengths: list[float],
    config_obj: Any = config,
    exclude_last: bool = True,
) -> float:
    """按全量历史重放 EMA 链（v0.8.1 新增）。

    种子取 PRIOR_MEAN，与 on_new_cycle_added 的链式更新严格一致
    （首次更新 ema_update(PRIOR_MEAN, c2)，后续逐条递推），
    使得从历史重推的 EMA 与 model_params 表中的 ema_cycle 逐值相等。
    用于过期刷新/全量重算/缓存自愈路径：这些路径只能拿到全量历史，
    不能直接用表中的 ema_cycle 作为 predict_core 的 prev_ema
    （表值已含最新一条，直接传入会重复应用一次 EMA）。

    Args:
        cycle_lengths: 按时间升序的合法周期长度列表。
        config_obj: 配置对象。
        exclude_last: True 返回「不含最新一条」的 prev_ema（predict_core 入参语义）；
            False 返回含全部记录的 EMA（即 model_params 表应存的值）。

    Returns:
        重放得到的 EMA；序列为空时返回 PRIOR_MEAN。
    """
    seq = cycle_lengths[:-1] if exclude_last else list(cycle_lengths)
    ema = config_obj.PRIOR_MEAN
    for c in seq:
        ema = ema_update(ema, c, config_obj.EMA_ALPHA)
    return ema


def _clamp(value: float, low: float, high: float) -> float:
    """将 value 限制在 [low, high] 区间。"""
    return max(low, min(high, value))


def decay_weights(weights: list[float], decay: float) -> list[float]:
    """把时间衰减乘到一组权重上（v0.14.2 修 AN-4）。

    约定与 `weighted_mean` 的时间衰减完全一致：**序列最后一个元素（最新一条）
    保持原权重不变**，往前每退一格多乘一次 `decay`。因此 decay=1.0 是恒等变换，
    可直接用于"取消时间衰减"的对照实验。

    Args:
        weights: 按时间升序的权重（如鲁棒降权权重）。
        decay: 衰减率，本项目统一取 `config.WEIGHT_DECAY`。

    Returns:
        与入参等长的新列表；空列表原样返回。
    """
    n = len(weights)
    return [w * (decay ** (n - 1 - i)) for i, w in enumerate(weights)]


def normal_conjugate_posterior(
    prior_mean: float,
    prior_std: float,
    observations: list[float],
    min_sigma: float = 2.0,
    weights: list[float] | None = None,
) -> tuple[float, float]:
    """正态-正态共轭后验（agent.md 4.2 bayesian_update 的唯一实现）。

    公式：
    - mu_post = (mu_0/sigma_0^2 + n*x_bar/sigma^2) / (1/sigma_0^2 + n/sigma^2)
    - sigma_post = sqrt(1 / (1/sigma_0^2 + n/sigma^2))
    - sigma = max(sample_std, min_sigma)

    收缩强度由**数据方差自然决定**（精度加权），不需额外的衰减常数：
    - 周期很规律（σ→min_sigma）→ 先验相对不精确 → 几乎不收缩
    - 周期很乱（σ 大）→ 先验相对精确 → 明显拉回 28
    这是 v0.8.0 将 Step 4 真实参与时选共轭后验而非固定系数线性混合的原因
    （后者会与共轭后验的职责重复，使连续 4 个 30 天的用户被误预测为 28.9 天）。

    Args:
        prior_mean: 先验均值 mu_0。
        prior_std: 先验标准差 sigma_0。
        observations: 观测值列表（周期长度）。
        min_sigma: 标准差下限，默认 2.0（config.MIN_STD）。
        weights: v0.8.0 鲁棒降权权重。传入时 x_bar 用加权均值、公式中的 n 用
            Kish 有效样本量 n_eff（降权样本不涨证据）；None 时与旧版逐元一致。

    Returns:
        (后验均值 mu_post, 后验标准差 sigma_post)。n=0 时返回先验不变。
    """
    n = len(observations)
    if n == 0:
        return prior_mean, prior_std

    x_bar = sum(observations) / n
    if n < 2:
        sigma = min_sigma
    else:
        sigma = max(statistics.stdev(observations), min_sigma)

    n_eff = float(n)
    if weights is not None and len(weights) == n:
        sw = sum(weights)
        if sw > 0:
            x_bar = sum(w * x for w, x in zip(weights, observations)) / sw
            n_eff = effective_sample_size(weights)
            if n >= 2 and n_eff > 1:
                var = sum(w * (x - x_bar) ** 2 for w, x in zip(weights, observations)) / sw
                var *= n_eff / (n_eff - 1.0)
                sigma = max(var ** 0.5, min_sigma)
            else:
                sigma = min_sigma

    prior_var = prior_std ** 2
    obs_var = sigma ** 2
    numerator = prior_mean / prior_var + n_eff * x_bar / obs_var
    denominator = 1.0 / prior_var + n_eff / obs_var
    return numerator / denominator, (1.0 / denominator) ** 0.5


def predict_core(
    cycle_lengths: list[float],
    prev_ema: float | None,
    config_obj: Any = config,
) -> dict:
    """预测计算核心（v0.8.0 新增，agent.md 4.1/4.4）。

    **单一实现原则**：`predict_next_period` 与 `algo/trace.py` 均调用本函数，
    从根上消除 v0.7.x「trace 自己重写一套导致与预测对不上」（文档 F7）的口径漂移。

    **口径登记（v0.14.0 修 AN-1，审计 H2「model_version 语义错位」）——两把尺子，不得互相冒充**：
    - **用户可读档位口径**：`stage` / `model_version` 按 `n`（合法周期数）分四档，
      与前端阶段徽章、`/debug` 面板的 empty/cold_1/cold_2/bayesian 同名对齐
      （取值见函数末尾映射表：n=0→cold_start_1、n=1→cold_start_2、
      n≥2→bayesian_v1）。n=2 时的 "bayesian_v1" 只是**版本标识**。
    - **算法真实执行口径**：`bayes_applied`（True ⟺ `n >= COLD_START_THRESHOLD`）、
      `n`、`n_eff`。判断「贝叶斯是否真的生效」**只能读 bayes_applied**，
      禁止用 model_version 反推——审计 H2 的根因正是下游把 model_version
      当作「贝叶斯已生效」的凭据（n=2 时 model_version="bayesian_v1" 而
      bayes_applied=False，贝叶斯根本没参与计算）。
    - 两口径在「有记录但无合法周期」时必然不同（例：3 条记录、其中一条周期
      硬无效被剔除 → 档位已到 cold_start_2/bayesian_v1，而算法侧
      bayes_applied=False、n=0），这是设计而非 bug：档位描述「用户累积了
      多少记录」，bayes_applied 描述「本次计算是否真的走了贝叶斯分支」。
    - trace/debug 的展示文案同样不得用档位推断执行口径（见 algo/trace.py 顶部登记）。

    口径（v0.8.0 用户授权，仅 P1）：
    1. 硬剔除：<7 或 >90 天的周期不参与（_legal_cycles）
    2. 鲁棒降权：[7,90] 内按稳健 z 分数连续降权，越异常权重越低，不删除（robust_weights）
    3. 窗口：取最近 STATS_WINDOW_N_V080=6 个周期（修 F1，与方案基线一致）。
       **v0.14.2 口径澄清（修 AN-4 附带）**：这个"窗口 6"只作用于 Step 1 的
       加权均值（已不进入点预测）与 Step 5/6 的加权 std（irr、区间、置信度），
       **不作用于 Step 4 的共轭后验**——后验吃全部合法周期，靠时间衰减
       （decay_weights）而非硬截断来体现"近期更重要"。因此本函数内
       "点预测用全量历史、离散度用近窗 6"是**有意的不对称**：
       均值对近因敏感、离散度要够长的基线才稳。旧文档把"窗口 6"写成
       整条链路的口径，与 Step 4 的实际入参不符。
    4. 锚点同步：latest_cycle 恒取**合法周期中的最后一条**，且调用方传入的
       last_period_start 始终是最新真实记录日期——即使最新那条周期被硬剔除，
       预测仍从该日期起算（修 F2：不因数据异常回退到更早的日期）
    5. Step 4 贝叶斯收缩**真实参与**最终值（不再是 trace 里的展示装饰）：
       基础值取正态-正态共轭后验均值 mu_post；v0.8.1 起（R6 动态化）附加
       先验收缩强度由周期离散度连续决定：
       predicted = mu_post + irr * pw * (PRIOR_MEAN - mu_post)
       irr = clamp((std - REGULAR_STD) / (IRREGULAR_STD - REGULAR_STD), 0, 1)
       规律用户 irr=0 仅单次收缩；高度不规律用户 irr=1 等效旧口径线性混合。
       n < COLD_START_THRESHOLD 时跳过（冷启动不收缩）
    5a. **v0.14.2 修 AN-4（组合修复）**：后验权重不再是"只有鲁棒降权"，
       而是 `decay_weights(robust_weights, WEIGHT_DECAY)` —— 时间衰减（越近权重越大）
       第一次真正作用在决定输出的那一步上。此前 Step 1/2/3 的近窗加权与 EMA 融合
       在 n>=3 时全被丢弃（扰动取证：改这三个常数，点预测变化恒为 0）。
       该修复使非平稳用户（周期缓慢漂移/基线阶跃）的系统性滞后显著缩小，
       详见下方 Step 4 处注释与 AGENTS.md 4.3h。

    Args:
        cycle_lengths: 原始周期长度列表（升序，可含非法值，本函数会过滤）。
        prev_ema: 上一个 EMA 值，None 表示未初始化。
        config_obj: 配置对象。

    Returns:
        包含各中间量的 dict，供 predict_next_period 组日期、供 trace 组步骤展示。
        v0.14.0 修 AN-2 新增两个"真实分支"审计键（trace 的展示条件必须读它们，
        不得另立近似条件）：
        - interval_n_eff_applied: bool，Step 5 是否真的走了小样本不确定性展宽分支
          （条件 n > 1 且 n_eff >= 1.0；冷启动一律 False）
        - interval_mult_applied: float，Step 5 实际生效的区间乘数
          （展宽分支为 INTERVAL_STD_MULT + INTERVAL_IRREGULAR_EXTRA·irr，
          冷启动/极端降权分支恒为 INTERVAL_STD_MULT）
    """
    legal = _legal_cycles(cycle_lengths, config_obj)
    n = len(legal)
    rw = robust_weights(legal, config_obj)
    n_eff = effective_sample_size(rw) if n else 0.0
    window = config_obj.STATS_WINDOW_N_V080

    latest_cycle = legal[-1] if legal else None
    base_ema = prev_ema if prev_ema is not None else config_obj.PRIOR_MEAN

    # Step 1 加权平均（含鲁棒降权；空列表返回先验均值 28.0）
    w_mean = weighted_mean(legal, window, config_obj.WEIGHT_DECAY, rw)

    # Step 2 EMA 更新（与冷启动各分支口径一致）
    if n == 0:
        ema_prev, new_ema = base_ema, base_ema
    elif n == 1:
        ema_prev = config_obj.PRIOR_MEAN
        new_ema = ema_update(ema_prev, latest_cycle, config_obj.EMA_ALPHA)
    else:
        ema_prev = base_ema
        new_ema = ema_update(ema_prev, latest_cycle, config_obj.EMA_ALPHA)

    # Step 3 融合预测
    if n == 0:
        fused = config_obj.PRIOR_MEAN
    elif n == 1:
        fused = float(latest_cycle)
    else:
        fused = (
            (1 - config_obj.EMA_FUSION_WEIGHT) * w_mean
            + config_obj.EMA_FUSION_WEIGHT * new_ema
        )

    # Step 4 前置：区间标准差（加权 std，n<=1 退回先验，修 F5）。
    # v0.8.1 起提前计算：Step 4 的规律度判定与 Step 5 区间共用同一个 std，
    # 避免两处口径漂移。
    if n <= 1:
        std = config_obj.PRIOR_STD
    else:
        std = cycle_std(legal, window, rw)

    # Step 4 贝叶斯收缩（v0.8.0 真实参与最终值；v0.8.1 R6 动态化）
    # 基础值采正态-正态共轭后验：收缩强度由数据方差自然决定，
    # 规律用户不再被拉向先验 28 天（v0.8.0 修复的重复收缩问题）。
    # v0.8.1 起附加先验收缩按离散度连续启用（用户方案：按规律与否动态调整，
    # 而非二值开关）：std <= ADAPTIVE_SHRINK_REGULAR_STD 完全不附加；
    # std >= ADAPTIVE_SHRINK_IRREGULAR_STD 附加至满强度 pw，等效旧口径
    # (1-pw)*mu_post + pw*PRIOR_MEAN；两者之间线性插值，避免阈值悬崖。
    pw = 1.0 / (1.0 + n_eff / config_obj.PRIOR_DECAY_K) if n_eff > 0 else 1.0
    # v0.14.2 修 AN-4（组合修复）：把既有的 WEIGHT_DECAY 时间衰减接进共轭后验权重。
    #
    # 缺陷（实测取证，主 agent 与独立复核子 agent 各自复现）：n >= COLD_START_THRESHOLD
    # 时点预测完全由本步后验决定，而 Step 1 的加权平均（近窗 6 + 衰减 0.85）与
    # Step 2/3 的 EMA 融合结果**没有任何一条路径进入输出**——扰动取证：
    # n>=3 的全部 194 个滚动步上，分别改动 WEIGHT_DECAY / EMA_ALPHA /
    # EMA_FUSION_WEIGHT，点预测变化量恒为**精确的 0.00000000**（连区间与置信度
    # 也一律 Δ=0）；这三个常数只在 n<3 的 20 个步上有效（最大 0.34 / 0.96 / 1.47 天）。
    # 也就是说"越近的记录权重越大"这条 v0.4.2 起的既定口径，
    # 在真正决定结果的那一步上从未生效：后验只吃全量历史，只有鲁棒降权、没有时间权重。
    #
    # 后果（tools/backtest.py 滚动起源实测，改前）：非平稳用户系统性滞后——
    #   drift_28_34  MAE 1.77，而"直接重复上次周期"基线只有 0.27
    #   shift_28_35 MAE 3.55，同基线 0.32；区间覆盖率 0.50（项目不得不为该场景
    #   单独豁免覆盖率闸门，见 backtest.run_gate）
    #
    # 修法与取舍：**只把时间衰减乘进后验权重**，不新增常数、不新增机制、
    # 不引入硬窗口（v0.8.1 R6 已明确"连续插值优于二值开关，避免阈值悬崖"，
    # 硬截断后验会在窗口边界制造同样的悬崖）。
    # 实测（同一滚动起源，10 场景；两个 agent 独立复现，数字一致）：
    #   聚合 MAE 2.208 → 2.109；±2 命中 0.663 → 0.693
    #   逐场景覆盖均值 0.879 → 0.893；drift 1.77 → 1.14；shift 3.55 → 2.86（覆盖 0.50 → 0.59）
    #   **区间半宽与置信度 214/214 步逐位不变**（区间用本函数另行计算的 std 与 n_eff，
    #   不受本行影响）——改善全部来自点预测变准，不是把区间放宽换来的
    #   既有全部闸门仍通过（backtest --gate 26/26）
    # **诚实登记：这不是"零代价"改动**。两个场景有可测劣化：
    #   normal_28_4 +0.14 天、irregular_25_40 +0.14 天（另 normal_32_5 +0.09、
    #   outlier_single +0.06）——把平稳噪声场景的少量信息换成了非平稳场景的大幅改善，
    #   这正是"近因加权"的固有偏差-方差取舍。
    # 闸门口径说明：按 4.3d 字面的"逐场景同时优于基线 B/C"读法，本改动应记
    # `拒绝合并`（4 个平稳场景未达标）；按池化读法（聚合 MAE 与命中率同时提升）达标。
    # 本项目实际采用的是池化口径 + 既有闸门，两个劣化场景已在此显式登记为已知取舍。
    # 对照候选"后验只取最近 8 个周期"池化 MAE 更低（1.995），但需要新增一个窗口常数，
    # 且是硬截断，未采纳；"硬窗口 6"更会令 normal_32_5 的 ±7 命中掉到 0.82，
    # 直接跌破项目闸门 hit7 >= 0.85（数值见 PROGRESS v0.14.2 登记）。
    #
    # 注意：`pw` 与区间仍用未衰减的 Kish `n_eff`（本函数上方计算的那一个）。
    # 这不是疏漏——`n_eff` 在此处的语义是"有多少独立观测"，而时间衰减刻画的是
    # "观测的有效新旧"，两者不可混用；且 pw 只作用于附加收缩项
    # irr·pw·(PRIOR_MEAN − mu_post)，实测其整体影响在噪声量级（PRIOR_DECAY_K
    # 从 2 扫到 1e9，聚合 MAE 跨度仅 0.025 天）。
    posterior_weights = decay_weights(rw, config_obj.WEIGHT_DECAY)
    mu_post, sigma_post = normal_conjugate_posterior(
        config_obj.PRIOR_MEAN, config_obj.PRIOR_STD, legal, config_obj.MIN_STD,
        posterior_weights,
    )
    bayes_applied = n >= config_obj.COLD_START_THRESHOLD
    shrink_span = (
        config_obj.ADAPTIVE_SHRINK_IRREGULAR_STD - config_obj.ADAPTIVE_SHRINK_REGULAR_STD
    )
    shrink_irregularity = (
        _clamp(
            (std - config_obj.ADAPTIVE_SHRINK_REGULAR_STD) / shrink_span, 0.0, 1.0
        )
        if shrink_span > 0
        else 0.0
    )
    if bayes_applied:
        predicted_cycle = mu_post + shrink_irregularity * pw * (
            config_obj.PRIOR_MEAN - mu_post
        )
    else:
        predicted_cycle = fused

    # Step 5 预测区间（std 已在 Step 4 前置计算）。
    # v0.10.0 F4 区间校准（agent.md 4.3d，回测实证）：
    #   mult_eff = INTERVAL_STD_MULT + INTERVAL_IRREGULAR_EXTRA · shrink_irregularity
    #   interval_days = std · mult_eff · sqrt(1 + 1/n_eff)   （n_eff < 1 时不放大）
    # 两项修正只影响区间半宽、不影响点预测：
    # - sqrt(1+1/n_eff)：「预测新观测」的误差方差 = σ² + σ²/n_eff（均值估计不确定度），
    #   小样本时区间偏窄是系统性低估（normal_28_4 覆盖 0.77 vs 名义 0.87）
    # - irr 附加展宽：高度不规律时近窗 σ 低估长期波动。
    #   irregular 覆盖实测双值（v0.13.0 残留清理：数值漂移注释标注，保留历史可溯源）：
    #      v0.10.0 F4 校准时代实测 0.77 / v0.13.0 当前回测 0.73
    #   （两值均为各自阶段实测，差异源于场景参数演化，非回归信号）
    #   实测：normal_28_4 → 0.82、irregular → 0.77（v0.10.0 时代）、聚合 0.92→0.93；
    #   规律用户（irr=0）仅受 n_eff 项影响（区间宽 7.0→7.5 天，覆盖保持 1.00）
    # v0.13.0 修 F11：n<=1（有效周期不足 2 个，std 取先验兜底=PRIOR_STD）属冷启动，
    # 不再叠加 sqrt(1+1/n_eff) 不确定性放大——std 已是先验值，再乘均值估计不确定度
    # 是双重计入，违背 config.py 登记契约「冷启动 ±PRIOR_STD·MULT=±10.5」。
    # 兑现方式：n>1 才进入 n_eff 修正分支，冷启动一律 std·INTERVAL_STD_MULT。
    # v0.14.0 修 AN-2：分支条件落成显式布尔键 interval_n_eff_applied 随 core 返回，
    # trace 的 Step 5 展示条件与 interval_mult_applied 一律读它（口径单一来源）——
    # 旧 trace 用 core["n"] > 0 近似判定，n=1 冷启动时谎称"含小样本展宽"。
    interval_mult_eff = config_obj.INTERVAL_STD_MULT + getattr(
        config_obj, "INTERVAL_IRREGULAR_EXTRA", 0.0
    ) * shrink_irregularity
    interval_n_eff_applied = n > 1 and n_eff >= 1.0
    if interval_n_eff_applied:
        interval_days = std * interval_mult_eff * math.sqrt(1.0 + 1.0 / n_eff)
        interval_mult_applied = interval_mult_eff
    else:
        # 冷启动（n<=1，std=PRIOR_STD 先验兜底）：兑现契约 ±PRIOR_STD·MULT，
        # 不因 n_eff 放大（F11）；n_eff<1 的极端降权场景同样不放大（F4 原语义）
        interval_days = std * config_obj.INTERVAL_STD_MULT
        interval_mult_applied = config_obj.INTERVAL_STD_MULT

    # Step 6 置信度（按有效样本量而非原始条数，降权越多置信度越低）
    if n == 0:
        confidence = config_obj.CONFIDENCE_COLD_1
    elif n == 1:
        confidence = config_obj.CONFIDENCE_COLD_2
    else:
        confidence = _clamp(1.0 - std / config_obj.CONFIDENCE_STD_SCALE, config_obj.CONFIDENCE_MIN, config_obj.CONFIDENCE_MAX)

    # stage / model_version 映射（v0.14.0 修 AN-1：**取值不变**，只做口径登记）。
    # 四档按 n（合法周期数）划分，与 debug._STAGE_META 的 empty/cold_1/cold_2/bayesian
    # 对齐，属「用户可读档位」；n=2 的 "bayesian_v1" 是版本标识，贝叶斯分支是否
    # 真的执行由 bayes_applied（n >= COLD_START_THRESHOLD）决定——两者不得互相推断
    # （审计 H2：下游曾把 model_version 当作"贝叶斯已生效"的凭据）。
    if n == 0:
        stage, model_version = "empty", config_obj.MODEL_VERSION_COLD_1
    elif n == 1:
        stage, model_version = "cold_1", config_obj.MODEL_VERSION_COLD_2
    elif n == 2:
        stage, model_version = "cold_2", config_obj.MODEL_VERSION_BAYESIAN
    else:
        stage, model_version = "bayesian", config_obj.MODEL_VERSION_BAYESIAN

    return {
        "legal_cycles": legal,
        "robust_weights": rw,
        "n": n,
        "n_eff": n_eff,
        "window": window,
        "latest_cycle": latest_cycle,
        "dropped_illegal": len(cycle_lengths) - n,
        "ema_prev": ema_prev,
        "new_ema": new_ema,
        "w_mean": w_mean,
        "fused": fused,
        "mu_post": mu_post,
        "sigma_post": sigma_post,
        "prior_weight": pw,
        # v0.14.2 修 AN-4：后验实际吃进去的"时间衰减后"有效样本量。
        # 与上面的 n_eff（未衰减，用于区间与 pw）区分开——trace 展示后验公式时
        # 必须用这一个，否则又是"展示口径 ≠ 执行口径"。
        "posterior_n_eff": effective_sample_size(posterior_weights),
        "shrink_irregularity": shrink_irregularity,
        "bayes_applied": bayes_applied,
        "predicted_cycle": predicted_cycle,
        "std": std,
        "interval_days": interval_days,
        # v0.14.0 修 AN-2：Step 5 真实分支与生效乘数（trace 展示条件的唯一来源）
        "interval_n_eff_applied": interval_n_eff_applied,
        "interval_mult_applied": interval_mult_applied,
        "confidence": confidence,
        "stage": stage,
        "model_version": model_version,
    }


def predict_next_period(
    last_period_start: date | None,
    cycle_lengths: list[float],
    prev_ema: float | None,
    config_obj: Any = config,
) -> dict:
    """预测下次月经开始时间（agent.md 4.1，v0.8.0 调用 predict_core）。

    冷启动规则（数值与旧版一致，新增鲁棒降权与贝叶斯收缩）：
    - last_period_start 为 None（0 条记录）: 返回空预测
    - 0 个合法周期（1 条记录）: 预测=last+28天, std=7.0, confidence=0.4
    - 1 个合法周期（2 条记录）: 用该周期长度, std=7.0, confidence=0.5
    - 2+ 个合法周期（3+ 条记录）: 加权平均 + EMA 融合 + 贝叶斯收缩 + 加权 std

    锚点（v0.8.0 修 F2）：predicted_date **恒从 last_period_start（最新一条真实记录）起算**，
    即使该条记录的周期长度因非法（<7/>90）被剔除也不回退到更早日期。

    版本标识（v0.14.0 修 AN-1 口径登记）：model_version 透传 predict_core 的
    「用户可读档位」值（n=0→cold_start_1、n=1→cold_start_2、n≥2→bayesian_v1；
    0 条记录时本函数直接返回 MODEL_VERSION_NONE），**不代表贝叶斯已生效**；
    「贝叶斯是否真的参与计算」见 model_params.bayes_applied（True ⟺ n >= 3）。

    Args:
        last_period_start: 最近一条月经开始日期，None 表示无记录。
        cycle_lengths: 历史周期长度列表（升序）。
        prev_ema: 前一个 EMA 值，None 表示尚未初始化。
        config_obj: 配置对象（默认 app.config 模块）。

    Returns:
        预测结果 dict，包含:
        - predicted_date / predicted_start_lower / predicted_start_upper: ISO 日期字符串
        - confidence: 置信度 [0.3, 0.95]
        - model_version: 模型版本标识
        - model_params: 模型参数快照（含 v0.8.0 新增 n_eff / dropped_illegal 审计字段）
    """
    # 0 条记录：无预测（API 契约 5.3 返回 null）
    if last_period_start is None:
        return {
            "predicted_date": None,
            "predicted_start_lower": None,
            "predicted_start_upper": None,
            "confidence": 0.0,
            "model_version": config_obj.MODEL_VERSION_NONE,
            "model_params": {
                "mean_cycle": config_obj.PRIOR_MEAN,
                "std_cycle": config_obj.PRIOR_STD,
                "n_records": 0,
                "ema_cycle": config_obj.PRIOR_MEAN,
                "prior_weight": 1.0,
            },
        }

    core = predict_core(cycle_lengths, prev_ema, config_obj)
    predicted_cycle = core["predicted_cycle"]
    std = core["std"]
    # v0.10.0 F4：区间端点必须取 core["interval_days"]（单一来源，R7 原则）——
    # 旧写法在此用 std·MULT 独立重算，与 trace/core 的校准后区间分叉
    interval_half_days = round(core["interval_days"])

    # 计算预测日期与区间（锚点 = 最新记录日期，恒不变）
    predicted_date = last_period_start + timedelta(days=round(predicted_cycle))
    predicted_start_lower = predicted_date - timedelta(days=interval_half_days)
    predicted_start_upper = predicted_date + timedelta(days=interval_half_days)

    return {
        "predicted_date": predicted_date.isoformat(),
        "predicted_start_lower": predicted_start_lower.isoformat(),
        "predicted_start_upper": predicted_start_upper.isoformat(),
        "confidence": round(core["confidence"], 4),
        "model_version": core["model_version"],
        "model_params": {
            "mean_cycle": round(predicted_cycle, 4),
            "std_cycle": round(std, 4),
            "n_records": core["n"],
            "ema_cycle": round(core["new_ema"], 4),
            "prior_weight": round(core["prior_weight"], 4),
            "shrink_irregularity": round(core["shrink_irregularity"], 4),
            # v0.8.0 审计字段：降权与硬剔除留痕，保证可回溯
            "n_eff": round(core["n_eff"], 4),
            "dropped_illegal": core["dropped_illegal"],
            "bayes_applied": core["bayes_applied"],
        },
    }
