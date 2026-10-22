"""预测算踪模块（agent.md 第4.4章）。

v0.8.0 关键变更（用户 2026-09-06 授权，范围仅 P1）：
- **不再自己重写一套计算**：本模块直接调用 `stats.predict_core`，与实际预测
  `predict_next_period` 共用同一实现。这从根上消除了 v0.7.x 记录的 F7 缺陷
  （trace 内部重复实现 → 与真实预测漂移，仅靠测试 abs<0.01 兜住）。
- **Step 4「贝叶斯调整」由纯展示改为真实参与**：core 中基础值取共轭后验均值，
  v0.8.1 起附加先验收缩强度随周期离散度连续变化（规律用户不被拉向 28，
  不规律用户按比例加强先验收缩），description 如实说明当前档位。
- v0.8.1 R6 动态收缩：单次收缩是规律档位的特例（irr=0），
  不规律档位等效旧口径线性混合，不再存在「双重收缩固定开启」的旧路径。
- 鲁棒降权（F5）与硬剔除边界（<7 / >90）在 Step 1/5 的 formula 中显式可见，
  debug 面板能看到每个周期被降权的程度与被丢弃的非法值个数。

不修改任何算法行为（只读取 core 的中间值），不持久化数据，是纯函数（不 import db/routes）。

设计约定：
- 步骤序号规则沿用 v0.5.0：冷启动（0 < n < COLD_START_THRESHOLD）跳过 Step 4，序号 3→5；
  n == 0（仅 1 条月经记录）与 n >= COLD_START_THRESHOLD 时展示 Step 4，共 6 步。
- trace.final_prediction 与 predict_next_period 必然完全一致（同一 core 输出），
  不再依赖"两边各自实现再比对"。
- Step 4 的收缩采正态-正态共轭后验 + 离散度驱动的附加先验收缩（v0.8.1 动态化），
  `prior_weight` 仅作为审计字段展示，固定混合旧路径已删除。

口径登记（v0.14.0 修 AN-1，审计 H2；与 stats.predict_core docstring 同源表述）：
- `algorithm_stage` = **用户可读档位口径**（empty/cold_1/cold_2/bayesian，按 n 划分，
  与前端阶段徽章、/debug/model 同族），`model_version` 是同一档位的版本标识。
- `bayes_applied` / `n` = **算法真实执行口径**，随本函数返回值一并暴露（顶层键
  `bayes_applied` / `n`），供 /debug/prediction_trace 与验证脚本如实展示。
- 两者不得互相冒充：`algorithm_stage="bayesian"/model_version="bayesian_v1"`
  **不等于**贝叶斯已生效（n=2 时 bayes_applied=False）；判断执行口径一律读
  bayes_applied，禁止由档位反推。
- 展示口径同样如此（v0.14.0 修 AN-2）：Step 5 的展宽措辞只能读 core 的
  `interval_n_eff_applied` / `interval_mult_applied`，Step 6 的刻度只能读
  config_obj.CONFIDENCE_STD_SCALE——不得在 trace 内重新近似或硬编码参数。
"""

import math
from typing import Any

from app import config
from app.algo import stats


def prediction_trace(
    last_period_start: str,
    cycle_lengths: list[float],
    prev_ema: float | None,
    model_params: dict,
    config_obj: Any = config,
) -> dict:
    """逐步重放预测计算，收集每步中间值。

    参数语义与 predict_next_period 一致：
    - cycle_lengths: 历史周期长度列表（升序），空列表表示仅1条月经记录
    - prev_ema: 前一个 EMA 值（来自 model_params 表 ema_cycle，None 表示未初始化）
    - model_params: 兼容签名保留；实际计算全部来自 stats.predict_core，
      不读取 model_params.prior_mean（该字段存上次后验，作先验会重复计算历史数据）
    - config_obj: 配置对象（默认 app.config 模块）

    Returns:
        {
            "steps": [{step, name, value, formula, description}, ...],
            "final_prediction": {
                "predicted_cycle_length", "confidence", "std", "interval"
            },
            "algorithm_stage": "empty" | "cold_1" | "cold_2" | "bayesian",
            # v0.14.0 修 AN-1：算法真实执行口径（不得由 algorithm_stage 反推）
            "bayes_applied": bool,   # 贝叶斯分支是否真的参与最终值（n >= 3）
            "n": int,                # 参与计算的合法周期数（= 记录数 - 1 再剔除非法）
            "model_version": str,    # 与 algorithm_stage 同档位的版本标识
        }
    """
    # 单一实现：与实际预测完全同源
    core = stats.predict_core(cycle_lengths, prev_ema, config_obj)
    n = core["n"]
    steps: list[dict] = []
    # v0.14.0 修 AN-2：Step 5 的展示条件与公式必须取 core 的真实分支判定，
    # 不得用 n > 0 / n >= 1 之类的近似（冷启动 n=1 时区间并未展宽）。
    interval_n_eff_applied = core["interval_n_eff_applied"]
    irr = core["shrink_irregularity"]
    irr_extra_applied = interval_n_eff_applied and irr > 0

    # Step 1: 加权平均（含鲁棒降权；空列表返回先验均值 28.0）
    w_parts = ", ".join(
        f"{c:g}×{w:.2f}" for c, w in zip(core["legal_cycles"][-core["window"]:],
                                         core["robust_weights"][-core["window"]:])
    )
    steps.append({
        "step": 1,
        "name": "加权平均",
        "value": round(core["w_mean"], 2),
        "formula": (
            f"weighted_mean(cycles, N={core['window']}, decay={config_obj.WEIGHT_DECAY}, "
            f"robust_weights)"
        ),
        "description": (
            f"最近{core['window']}次周期加权平均，异常值按偏离程度降权而非丢弃"
            + (f"（{w_parts}）" if w_parts else "")
        ),
    })

    # Step 2: EMA更新（与 predict_core 各分支口径一致）
    # v0.14.0 修 AN-2：formula 的 prev/new 直接取 core["ema_prev"]/core["latest_cycle"]，
    # 与 predict_core Step 2 真实用值逐字一致；冷启动（n==1）时 ema_prev 被强制为
    # PRIOR_MEAN（不采用调用方传入的 prev_ema），必须在 description 里如实说明种子来源。
    if n == 0:
        desc2 = f"EMA学习率{config_obj.EMA_ALPHA}；无合法周期（n=0），EMA 保持 {round(core['new_ema'], 2)} 不更新"
    elif n == 1:
        desc2 = (
            f"EMA学习率{config_obj.EMA_ALPHA}；冷启动种子 prev 取先验均值 "
            f"{config_obj.PRIOR_MEAN}（n=1 时忽略传入的 prev_ema）"
        )
    else:
        desc2 = f"EMA学习率{config_obj.EMA_ALPHA}"
    steps.append({
        "step": 2,
        "name": "EMA更新",
        "value": round(core["new_ema"], 2),
        "formula": (
            f"ema_update(prev={round(core['ema_prev'], 2)}, "
            f"new={core['latest_cycle'] if core['latest_cycle'] is not None else 'None'}, "
            f"alpha={config_obj.EMA_ALPHA})"
        ),
        "description": desc2,
    })

    # Step 3: 融合预测（与 predict_core 冷启动/正常分支数值一致）
    # v0.14.2 修 AN-5（展示诚实性）：本步产物 `fused` 只在冷启动 n==2 时成为最终预测；
    # n>=3 时最终值由 Step 4 **直接对全部合法周期求共轭后验**得出，并不消费 `fused`
    # （实测取证：n>=3 时扰动 EMA_FUSION_WEIGHT / EMA_ALPHA，点预测变化量恒为 0）。
    # 旧文案只写"加权平均与EMA的加权融合"，配上后面的步骤编号会被读成
    # 「逐步链式推导」——用户会以为最终数字经过了这一步。必须写明真实角色。
    if n == 0:
        desc3, formula3 = "尚无有效周期（只有锚点记录），使用先验默认值28天", f"先验均值 {config_obj.PRIOR_MEAN}（冷启动，无周期数据）"
    elif n == 1:
        # v0.14.2 修 AN-5：旧文案写"仅2条记录"，是用**合法周期数**去断言**记录数**——
        # 两者只在"每条记录都产生一个合法周期"时才相等，而首条记录天然没有周期长度，
        # 存在硬无效记录时更会发散。同一类口径错误 v0.14.0 BN-1 已在 debug 层修过，
        # 这里按"周期数"如实表述（trace 只拿得到 cycle_lengths，本就无法知道记录数）。
        desc3 = "仅有1个有效周期，直接使用该周期长度"
        formula3 = f"最近周期长度 {core['latest_cycle']:g}（冷启动，1个有效周期）"
    elif n == 2:
        desc3 = "加权平均与EMA的加权融合（冷启动 n=2，本值即为最终预测）"
        formula3 = (
            f"(1-{config_obj.EMA_FUSION_WEIGHT})*{round(core['w_mean'], 2)} + "
            f"{config_obj.EMA_FUSION_WEIGHT}*{round(core['new_ema'], 2)}"
        )
    else:
        desc3 = (
            "加权平均与EMA的加权融合 —— **仅供审计**：n≥"
            f"{config_obj.COLD_START_THRESHOLD} 时最终预测由下一步"
            "直接对历史周期求共轭后验，不使用本值"
        )
        formula3 = (
            f"(1-{config_obj.EMA_FUSION_WEIGHT})*{round(core['w_mean'], 2)} + "
            f"{config_obj.EMA_FUSION_WEIGHT}*{round(core['new_ema'], 2)}"
            "（未进入最终预测）"
        )
    steps.append({
        "step": 3,
        "name": "融合预测",
        "value": round(core["fused"], 2),
        "formula": formula3,
        "description": desc3,
    })

    # Step 4: 贝叶斯调整（v0.8.0 起真实参与最终值；v0.8.1 R6 动态化：
    # 附加先验收缩强度随周期离散度连续变化；n==0 或 n>=3 展示，冷启动 1-2 个周期跳过）
    if n == 0 or n >= config_obj.COLD_START_THRESHOLD:
        # v0.14.2 修 AN-5：旧文案写作「融合值 X → 后验 Y」，其中 "→" 谎称了两个数
        # 之间的数据流——后验并不接收融合值（它直接吃 core["legal_cycles"]）。
        # 改为如实说明"本步不消费上一步"，并把后验自己的权重口径与有效样本量摊开。
        audit_tail = (
            f"（本步直接对全部 {n} 个合法周期求后验，不消费上一步的融合值 "
            f"{round(core['fused'], 2)}；"
            f"后验权重＝鲁棒降权×时间衰减 {config_obj.WEIGHT_DECAY}，"
            f"后验有效样本量 {round(core['posterior_n_eff'], 2)}；"
            f"审计用先验权重 {round(core['prior_weight'] * 100, 1)}%"
            f"——本档离散度未达附加收缩阈值，该权重只作审计、不进入点预测）"
        )
        if not core["bayes_applied"]:
            desc4 = "无周期数据，后验退化为先验均值（与实际预测一致）"
            # v0.14.2 修 AN-5：n=0 时 irr 会被 clamp 拉满到 1.0（std=PRIOR_STD=7），
            # 旧写法只看 irr>0 就拼出「+ 1.0·pw·(28 − mu_post)」——展示了一条
            # **根本没执行**的式子（该附加项只在 bayes_applied 为真时才跑）。
            # 数值上恰为 0 所以结果不歪，但语义虚假，且与本卡 description
            # 「后验退化为先验均值」自相矛盾。分支不存在就不展示。
            formula4 = (
                f"无合法周期 → 不执行共轭后验与附加收缩，"
                f"直接取先验均值 {config_obj.PRIOR_MEAN}"
            )
        elif irr <= 0:
            desc4 = (
                "共轭后验均值即为最终预测周期：窗口离散度低于规律阈值 "
                f"{config_obj.ADAPTIVE_SHRINK_REGULAR_STD} 天，不附加先验收缩"
                + audit_tail
            )
            formula4 = (
                f"normal_conjugate_posterior(mu_0={config_obj.PRIOR_MEAN}, "
                f"sigma_0={config_obj.PRIOR_STD}, obs=全部合法周期({n}个), "
                f"w=robust_weights×decay^Δ({config_obj.WEIGHT_DECAY}), "
                f"n_eff={round(core['posterior_n_eff'], 2)})"
            )
        else:
            desc4 = (
                f"周期离散度较高（加权 std={round(core['std'], 2)} 天），"
                "按离散度比例附加先验收缩 "
                f"{round(irr * core['prior_weight'] * 100, 1)}%："
                f"后验 {round(core['mu_post'], 2)} → 最终 {round(core['predicted_cycle'], 2)}"
            )
            formula4 = (
                f"normal_conjugate_posterior(mu_0={config_obj.PRIOR_MEAN}, "
                f"sigma_0={config_obj.PRIOR_STD}, obs=全部合法周期({n}个), "
                f"w=robust_weights×decay^Δ({config_obj.WEIGHT_DECAY}), "
                f"n_eff={round(core['posterior_n_eff'], 2)})"
                f" + {round(irr, 2)}·pw·({config_obj.PRIOR_MEAN} − mu_post)"
            )
        steps.append({
            "step": 4,
            "name": "贝叶斯调整",
            "value": round(core["predicted_cycle"], 2),
            # v0.14.2：formula 只列**真实输入**。旧写法把 sigma 写成 core["std"]
            # （窗口 6 的加权 std），而后验内部用的是"对全部合法周期的加权标准差"，
            # 历史长于 6 个周期时两者并不相等 —— 又是一处"展示口径 ≠ 执行口径"。
            "formula": formula4,
            "description": desc4,
        })

    # Step 5: 预测区间（与 predict_next_period 的 std 口径一致；v0.10.0 F4 校准同步）
    # v0.14.0 修 AN-2：公式与措辞全部读 core 的**真实生效值**
    # （interval_n_eff_applied / interval_mult_applied）。旧实现有三处不一致：
    # (a) 展宽措辞条件用 core["n"] > 0，而真实分支是 n > 1 且 n_eff >= 1.0
    #     → n=1 冷启动时谎称"含小样本与不规律展宽修正"；
    # (b) formula 里的 mult 无条件按 MULT+EXTRA·irr 展示（冷启动实际只用 std·MULT）；
    # (c) n_eff 修正项只看 n_eff >= 1.0，漏掉 n <= 1 这一半条件。
    interval_extra = getattr(config_obj, "INTERVAL_IRREGULAR_EXTRA", 0.0)
    if interval_n_eff_applied:
        n_eff_factor = round(math.sqrt(1.0 + 1.0 / core["n_eff"]), 3)
        if irr_extra_applied:
            mult_formula = (
                f"{config_obj.INTERVAL_STD_MULT}+{interval_extra}·irr"
                f"({round(irr, 2)})={round(core['interval_mult_applied'], 3)}"
            )
            desc5_extra = (
                f"；含小样本不确定性展宽 √(1+1/n_eff)（F4 校准）"
                f"与不规律附加展宽（irr={round(irr, 2)}）"
            )
        else:
            mult_formula = f"{config_obj.INTERVAL_STD_MULT}（irr=0，无附加展宽）"
            desc5_extra = "；含小样本不确定性展宽 √(1+1/n_eff)（F4 校准）"
    else:
        # 冷启动（n<=1）或 n_eff < 1 的极端降权：区间 = std·INTERVAL_STD_MULT
        n_eff_factor = 1.0
        reason = (
            "冷启动，有效周期不足 2 个"
            if n <= 1
            else f"极端降权，n_eff={round(core['n_eff'], 2)}<1"
        )
        mult_formula = f"{config_obj.INTERVAL_STD_MULT}（未叠加展宽修正：{reason}）"
        desc5_extra = f"；未叠加展宽修正（区间 = std·INTERVAL_STD_MULT：{reason}）"
    steps.append({
        "step": 5,
        "name": "预测区间",
        "value": f"{round(core['predicted_cycle'], 1)} ± {round(core['interval_days'], 1)} 天",
        "formula": (
            f"std={round(core['std'], 2)}, "
            f"mult={mult_formula}, "
            f"n_eff修正={n_eff_factor}"
        ),
        "description": (
            f"标准差{round(core['std'], 2)}（加权，含降权样本），"
            f"区间半宽{round(core['interval_days'], 1)}天"
            + desc5_extra
            + (f"；{core['dropped_illegal']} 个非法周期（<{config_obj.MIN_CYCLE_DAYS}"
               f"或>{config_obj.MAX_CYCLE_DAYS}天）已剔除"
               if core["dropped_illegal"] else "")
        ),
    })

    # Step 6: 置信度（与 predict_next_period 冷启动/正常分支一致）
    # v0.14.0 修 AN-2：刻度取自 config_obj.CONFIDENCE_STD_SCALE（原硬编码 14.0，
    # 与 config 值恰好相同故长期未被发现）；clamp 参数顺序按实现签名
    # _clamp(value, low, high) 如实展示（旧写法把上下限写在了值前面）。
    if n == 0:
        conf_formula = (
            f"冷启动置信度 {config_obj.CONFIDENCE_COLD_1}（无有效周期，按 1 条记录档）"
        )
    elif n == 1:
        # v0.14.2 修 AN-5：同 Step 3 —— 不再用周期数冒充记录数；同时点明常量名
        # 是按"记录数"命名的档位名，避免读者把 CONFIDENCE_COLD_2 读成"2 个周期"。
        conf_formula = (
            f"冷启动置信度 {config_obj.CONFIDENCE_COLD_2}（1 个有效周期，按 2 条记录档）"
        )
    else:
        conf_formula = (
            f"clamp(1.0 - {round(core['std'], 2)}/{config_obj.CONFIDENCE_STD_SCALE}, "
            f"{config_obj.CONFIDENCE_MIN}, {config_obj.CONFIDENCE_MAX})"
        )
    steps.append({
        "step": 6,
        "name": "置信度",
        "value": round(core["confidence"], 3),
        "formula": conf_formula,
        "description": "标准差越小置信度越高",
    })

    return {
        "steps": steps,
        "final_prediction": {
            "predicted_cycle_length": round(core["predicted_cycle"], 2),
            "confidence": round(core["confidence"], 3),
            "std": round(core["std"], 2),
            "interval": round(core["interval_days"], 1),
        },
        "algorithm_stage": core["stage"],
        # v0.14.0 修 AN-1：对外暴露算法真实执行口径（/debug/prediction_trace 顶层透传，
        # 前端与验证脚本据此判断"贝叶斯是否真的生效"，不得由 algorithm_stage 反推）
        "bayes_applied": core["bayes_applied"],
        "n": core["n"],
        "model_version": core["model_version"],
    }
