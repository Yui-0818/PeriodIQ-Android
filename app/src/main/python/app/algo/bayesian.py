"""贝叶斯自学习模块（agent.md 第4.2章）。

纯函数实现，不依赖 db/routes。所有参数从 config 读取。
公式严格按 agent.md 第4.2章，参数值严格按第8章。

注意：on_new_cycle_added 不直接持久化数据，而是返回新 model_params 和 prediction，
由 routes 层（M2）负责持久化。这符合「algo/ 为纯函数」的架构原则。

先验策略（固定先验 + 全量数据）：
- 每次贝叶斯更新都从 config 固定先验（PRIOR_MEAN=28.0, PRIOR_STD=7.0）开始，
  用全部历史有效周期数据重算后验。
- 不使用"滚动先验"（把上次后验作为下次先验），否则历史数据会被重复计算。
- model_params.prior_mean/prior_std 字段存"最近一次后验"供审计，但不作为下次计算输入。
- 删除记录后全量重算时，结果与逐条增量更新一致（幂等性）。

版本标识口径（v0.14.0 修 AN-1，审计 H2；与 stats.predict_core / algo/trace 同源登记）：
- 本模块**不重复计算 model_version**：返回值中的 prediction 直接来自
  predict_next_period（→ predict_core），model_version/stage 是「用户可读档位口径」
  （按合法周期数 n 分档，与前端阶段徽章同族），落库时原样透传即可。
- 「贝叶斯是否真的生效」由 bayes_applied 表达（True ⟺ n >= COLD_START_THRESHOLD），
  已随 model_params 返回并落库；下游（routes/debug/前端/验证脚本）判断执行口径
  只能读它，**禁止**由 model_version 推断（n=2 时 model_version="bayesian_v1"
  而 bayes_applied=False，审计 H2 的根因正是这种反推）。
"""

import statistics
from datetime import date
from typing import Any

from app import config
from app.algo.stats import (
    normal_conjugate_posterior,
    predict_core,
    predict_next_period,
    replay_ema,
)


def bayesian_update(
    prior_mean: float,
    prior_std: float,
    observations: list[float],
    min_sigma: float = 2.0,
    weights: list[float] | None = None,
) -> tuple[float, float]:
    """正态-正态共轭后验更新（agent.md 4.2）。

    v0.8.0 起为 `stats.normal_conjugate_posterior` 的转发——共轭后验实现只有一份，
    预测核心（predict_core）与模型更新（on_new_cycle_added）不可能不同口径。
    保留本函数名与签名，以兼容 `from app.algo.bayesian import bayesian_update`
    与既有测试（weights=None 时与旧版逐元一致）。

    Args:
        prior_mean: 先验均值 mu_0。
        prior_std: 先验标准差 sigma_0。
        observations: 观测值列表（周期长度）。
        min_sigma: 标准差下限，默认 2.0（config.MIN_STD）。
        weights: v0.8.0 鲁棒降权权重；None 时不降权。

    Returns:
        (后验均值 mu_post, 后验标准差 sigma_post)。n=0 时返回先验不变。
    """
    return normal_conjugate_posterior(
        prior_mean, prior_std, observations, min_sigma, weights
    )


def prior_weight(n: float, k: float = 5.0) -> float:
    """先验权重（agent.md 4.1）。

    公式：return 1.0 / (1.0 + n / k)

    n 越大，先验权重越小（数据越多，越相信数据而非先验）。

    Args:
        n: 观测数（周期长度数量）。
        k: 衰减常数，默认 5.0（config.PRIOR_DECAY_K）。

    Returns:
        先验权重，范围 (0, 1]。
    """
    return 1.0 / (1.0 + n / k)


def on_new_cycle_added(
    new_period_start: date,
    prev_period_start: date | None,
    current_params: dict,
    config_obj: Any = config,
) -> dict:
    """新增月经记录后的模型更新流程（agent.md 4.2）。

    纯函数：不直接操作数据库，返回新 model_params 和 prediction 供 routes 持久化。

    流程（v0.8.1 修订：model_params 与预测同源，均由 predict_core 计算）：
    1. 算 cycle_length（首条=None）
    2. 硬无效判定：<7 或 >90 天 → 不参与计算（用户口径：过于离谱直接抛弃）
       偏离判定：已有≥2 个周期且偏离**中位数** > ANOMALY_DEVIATION_THRESHOLD →
       仅置 is_anomalous=True 供状态机告警与 UI 提示，**仍参与计算但被降权**（修 F5）
    3. 收集所有合法周期长度（仅硬无效者不入列；偏离周期入列，由鲁棒降权处理）
    4. predict_core(all_cycles) 一次性算出：动态贝叶斯收缩后的预测周期、加权 std、
       EMA、n_eff、先验权重（v0.8.1 统一口径：model_params 不再走独立的
       全量等权共轭路径，与 predict_next_period 严格同源，
       消除 PROGRESS 例外表第 7 项 / 方案文档 D11「三套平均并存」的根因）
    5. 返回新 model_params（routes 持久化）
    6. predict_next_period 生成新预测（内部同走 predict_core）
    7. 返回预测快照（routes 持久化，model_params_json 必填）

    版本标识（v0.14.0 修 AN-1 口径登记）：本函数不对 model_version 做二次加工
    （不按记录数重算、不改写 predict_core 的档位取值）；prediction["model_version"]
    是「用户可读档位」标签，是否真的跑了贝叶斯分支看 model_params["bayes_applied"]。

    Args:
        new_period_start: 新记录的月经开始日期。
        prev_period_start: 上一条记录的月经开始日期（首条为 None）。
        current_params: 当前模型参数，包含:
            - mean_cycle, std_cycle, n_records, ema_cycle, prior_mean, prior_std, prior_weight
            - cycle_lengths: 历史有效周期长度列表
        config_obj: 配置对象（默认 app.config 模块）。

    Returns:
        dict 包含:
        - model_params: 新模型参数（异常时保持原值）
        - prediction: 新预测结果
        - is_anomalous: 新周期是否异常（硬无效 **或** 偏离型；偏离型仍参与降权计算）
        - is_hard_invalid: 新周期是否硬无效（<7/>90，不参与任何计算，模型不更新）。
            v0.9.0 新增：routes 层持久化 model_params 的条件必须用本标志，
            不得用 is_anomalous（偏离型异常周期算法层已纳入降权计算，
            若按 is_anomalous 跳过落库会造成表与算法口径分叉——修复 P1）。
        - cycle_length: 计算出的周期长度（首条为 None）
    """
    # 步骤1: 算 cycle_length（首条=None）
    if prev_period_start is None:
        cycle_length = None
    else:
        cycle_length = (new_period_start - prev_period_start).days

    # 历史有效周期长度
    historical_cycles: list[float] = list(current_params.get("cycle_lengths", []))

    # 步骤2: 异常检查（v0.8.0：硬无效=不参计算；偏离=仅标记+降权，仍参计算）
    is_anomalous = False
    is_hard_invalid = False
    if cycle_length is not None:
        # 硬无效：<7 或 >90 天，一定不是有效周期，直接剔除
        if cycle_length < config_obj.MIN_CYCLE_DAYS or cycle_length > config_obj.MAX_CYCLE_DAYS:
            is_anomalous = True
            is_hard_invalid = True
        # 偏离判定：已有≥2 个周期时用**中位数**作基准（比均值抗污染），
        # 偏离超阈值仅置位 is_anomalous（供第7章状态机 ANOMALY_ALERT 与 UI 使用），
        # 数据本身保留，由 robust_weights 在计算中降权。
        elif (
            len(historical_cycles) >= 2
            and abs(cycle_length - statistics.median(historical_cycles))
            > config_obj.ANOMALY_DEVIATION_THRESHOLD
        ):
            is_anomalous = True

    # 步骤3: 收集有效周期长度（v0.8.0：仅硬无效不入列）
    if cycle_length is not None and not is_hard_invalid:
        all_cycles = historical_cycles + [float(cycle_length)]
    else:
        all_cycles = historical_cycles

    # 硬无效或首条：不更新模型参数，但仍生成预测
    # 注意锚点：预测恒从 new_period_start（最新一条真实记录）起算，
    # 即使本条周期因 <7/>90 被剔除也不回退到更早日期（修 F2）
    if cycle_length is None or is_hard_invalid:
        # 保持原模型参数
        new_model_params = {
            "mean_cycle": current_params["mean_cycle"],
            "std_cycle": current_params["std_cycle"],
            "n_records": current_params["n_records"],
            "ema_cycle": current_params["ema_cycle"],
            "prior_mean": current_params["prior_mean"],
            "prior_std": current_params["prior_std"],
            "prior_weight": current_params["prior_weight"],
        }
    else:
        # 步骤4（v0.8.1 统一口径）：model_params 直接取 predict_core 输出。
        # 旧实现在此走「全量等权共轭 + (1-pw) 线性混合」的独立路径，与预测的
        # window=6 加权+动态收缩口径漂移，导致 model_params 表与预测快照长期不一致。
        # 现在 model_params.mean_cycle/std_cycle/ema_cycle/prior_weight 与预测快照
        # 逐值相等（同一 core 输出），prior_mean/prior_std 存共轭后验供审计。
        # prev_ema 统一用链式重推（不含本条），保证与下方 prediction 的输入严格同源。
        prev_ema = replay_ema(all_cycles, config_obj, exclude_last=True)
        core = predict_core(all_cycles, prev_ema, config_obj)
        new_model_params = {
            "mean_cycle": core["predicted_cycle"],
            "std_cycle": core["std"],
            "n_records": core["n"],
            "ema_cycle": core["new_ema"],
            "prior_mean": core["mu_post"],
            "prior_std": core["sigma_post"],
            "prior_weight": core["prior_weight"],
            "n_eff": core["n_eff"],
            "dropped_illegal": core["dropped_illegal"],
            "bayes_applied": core["bayes_applied"],
            "shrink_irregularity": core["shrink_irregularity"],
        }

    # 步骤5-6: predict_next_period 生成新预测（内部同走 predict_core，口径必然一致）
    # last_period_start = 新记录的开始日期；prev_ema 统一用 replay_ema 从历史重推：
    # 正常分支时等于表内 ema_cycle（不含本条，predict_core 内部会应用本条）；
    # 硬无效分支时表值已含最新合法周期、直接传入会重复应用一次 EMA（review S2 修复）
    prediction = predict_next_period(
        new_period_start,
        all_cycles,
        replay_ema(all_cycles, config_obj, exclude_last=True),
        config_obj,
    )

    return {
        "model_params": new_model_params,
        "prediction": prediction,
        "is_anomalous": is_anomalous,
        "is_hard_invalid": is_hard_invalid,
        "cycle_length": cycle_length,
    }
