"""精度评估模块（agent.md 第4.3章）。

纯函数实现，不依赖 db/routes。
公式严格按 agent.md 第4.3章。
"""

from datetime import date
from typing import Any


def _to_date(d: Any) -> date:
    """将输入转换为 date 对象。

    支持 date 对象和 ISO 8601 字符串（YYYY-MM-DD）。
    """
    if isinstance(d, date):
        return d
    if isinstance(d, str):
        return date.fromisoformat(d)
    raise ValueError(f"无法解析日期: {d}")


def evaluate_predictions(
    predictions: list[dict],
    n_days: list[int] | None = None,
) -> dict:
    """评估预测精度（agent.md 4.3）。

    公式：
    - evaluated = [p for p if p['actual_date'] is not None]
    - total=0 → return {'error': 'no_evaluated_predictions'}
    - hit_rate_pm{n} = 命中数/total，命中 = |actual - predicted| <= n
    - mae = mean(|actual - predicted|)
    - interval_coverage = actual 落在 [lower, upper] 内的占比
    - bias = mean(actual - predicted)，正=偏晚（实际比预测晚）

    Args:
        predictions: 预测记录列表，每个 dict 需包含:
            - predicted_date: 预测日期（date 或 ISO 字符串）
            - predicted_start_lower: 区间下限
            - predicted_start_upper: 区间上限
            - actual_date: 实际日期（None 表示未验证）
        n_days: 命中率阈值列表，默认 [1, 2, 3, 5, 7]。

    Returns:
        评估结果 dict。无已验证预测时返回 {'error': 'no_evaluated_predictions'}。
    """
    if n_days is None:
        n_days = [1, 2, 3, 5, 7]

    # 筛选已验证预测（actual_date 不为 None）
    evaluated = [p for p in predictions if p.get("actual_date") is not None]
    # v0.22.4 修 R33-fix1：trend 必须**与输入顺序无关**，故在计算 mae_early /
    # mae_late **之前**先按**规范序**排序（本模块是纯函数、不 import db/routes，
    # 见文件头，故内联同一份口径；定义与理由见 app/db/crud.py 的
    # _prediction_order_key / get_predictions_chronological）：
    #   predicted_date ASC → created_at ASC → id ASC。
    # 为何主键用 predicted_date 而不是 created_at：
    #   ① 历史迁移/导入会把多条预测的 created_at 写成同一值（本次事故形态：
    #      用户库 4 条已验证预测 created_at 全为 2026-07-31 18:13:22），此时
    #      created_at 无法定序，次序退化为插入序（未定义）；
    #   ② 本应用「为下一次月经做预测」（一次只前瞻一个周期），predicted_date 的
    #      先后等价于预测作出的先后，且它是数据自带、不依赖插入顺序的锚。
    # 不变量：**同一组数据无论以何顺序传入，本函数结果必须逐字段相同**。
    evaluated = sorted(evaluated, key=lambda p: (
        str(p.get("predicted_date") or ""),
        str(p.get("created_at") or ""),
        int(p.get("id") or 0),
    ))
    total = len(evaluated)

    # 无已验证预测
    if total == 0:
        return {"error": "no_evaluated_predictions"}

    # 解析日期
    parsed = []
    for p in evaluated:
        parsed.append({
            "predicted_date": _to_date(p["predicted_date"]),
            "predicted_start_lower": _to_date(p["predicted_start_lower"]),
            "predicted_start_upper": _to_date(p["predicted_start_upper"]),
            "actual_date": _to_date(p["actual_date"]),
        })

    result: dict[str, Any] = {
        "total_predictions": len(predictions),
        "evaluated": total,
    }

    # 命中率：hit_rate_pm{n} = 命中数/total
    errors = [abs((p["actual_date"] - p["predicted_date"]).days) for p in parsed]
    for n in n_days:
        hits = sum(1 for e in errors if e <= n)
        result[f"hit_rate_pm{n}"] = round(hits / total, 4)

    # MAE: 平均绝对误差
    result["mae"] = round(sum(errors) / total, 4)

    # 区间覆盖率：actual 落在 [lower, upper] 内的占比
    in_interval = sum(
        1
        for p in parsed
        if p["predicted_start_lower"] <= p["actual_date"] <= p["predicted_start_upper"]
    )
    result["interval_coverage"] = round(in_interval / total, 4)

    # 偏差：bias = mean(actual - predicted)，正=偏晚
    biases = [(p["actual_date"] - p["predicted_date"]).days for p in parsed]
    result["bias"] = round(sum(biases) / total, 4)

    # 趋势（v0.13.1 修 G13：真实计算；v0.22.4 修 R33-fix1：改按**规范序**而非
    # 「列表序」分半）。
    # 口径：把已验证预测按**规范序**（predicted_date ASC → created_at ASC → id ASC，
    # 已在函数前段排序）分前后两半，比较两半的 MAE：
    #   后半比前半好 ≥0.5 天 → "improving"（模型随数据积累在变准）
    #   后半比前半差 ≥0.5 天 → "degrading"（近期表现变差，值得用户留意）
    #   其余（含样本 <4 不足以判断）→ "stable"
    # 0.5 天绝对差下限避免「0.0 vs 0.2」这类小数抖动被放大成趋势。
    # 判据一个字都不能改：total >= 4 才判定；阈值仍恒为 ±0.5 天；
    # total < 4 ⇒ "stable"。
    # 不变量：同一组数据无论以何顺序传入，结果必须逐字段相同（顺序无关）。
    if total >= 4:
        half = total // 2
        mae_early = sum(errors[:half]) / half
        mae_late = sum(errors[half:]) / (total - half)
        if mae_late <= mae_early - 0.5:
            result["trend"] = "improving"
        elif mae_late >= mae_early + 0.5:
            result["trend"] = "degrading"
        else:
            result["trend"] = "stable"
    else:
        result["trend"] = "stable"

    return result
