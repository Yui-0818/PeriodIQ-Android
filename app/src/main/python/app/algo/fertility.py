"""排卵/易孕期/相对不易孕期推算（agent.md 第4.5章，v0.8.0 用户授权新增）。

**只使用已有月经周期数据**：不要求也不使用任何新增输入（基础体温、宫颈黏液、
LH 试纸、同房记录等均不参与），完全由「最新记录日期 + 预测下次月经日期 + 周期标准差」推出。

方法：标准日历法（Ogino-Knaus），医学口径如下——
- 排卵日 = 预测下次月经来潮前 `LUTEAL_PHASE_DAYS`（14）天，基于「黄体期长度对个体相对恒定」的假设；
  该假设的个体误差实测可在 ±2~3 天浮动，因此本模块同时返回 `ovulation_sigma_days`（前端在 ⓘ 弹窗展示）。
- 易孕期（fertile window）= 排卵前 `FERTILE_PRE_OVULATION_DAYS`（5，精子存活上限）
  至排卵后 `FERTILE_POST_OVULATION_DAYS`（1，卵子存活上限），共 7 天含端点。
- 相对不易受孕期 = 本次月经结束至易孕期前（卵泡期）、易孕期后至预测来潮前（黄体期）。
- 预测经期（v0.8.1 新增）= 历史经期天数中位数（`predicted_period_days`，由 routes 层从
  period_days 非空记录计算后传入）；本次与下次经期的预测区间由 `predicted_period_windows`
  返回，前端以淡粉标注（解决「看不出经期还有多久可能结束」）。

诚实性要求（第9章「不得制造虚假确定感」精神）：
- 未填 `period_end` 时，卵泡期起点改用**预测经期结束日**（窗口标 `basis: "predicted"`），
  而非任何与用户数据无关的固定默认值；无历史经期记录（`predicted_period_days=None`）
  时仍不返回卵泡期段。
- 周期标准差越大 → `ovulation_sigma_days` 越大（ⓘ 弹窗展示不确定度）。
- 日历法单独使用的失败率显著高于屏障与激素避孕方式，**不可作为唯一避孕依据**；
  本模块不做任何「安全」承诺，措辞统一为「相对不易受孕期」。
"""

from datetime import date, timedelta
from typing import Any

from app import config


def compute_fertility_window(
    predicted_period_date: date | None,
    current_period_start: date | None,
    current_period_end: date | None,
    cycle_std: float,
    config_obj: Any = config,
    predicted_period_days: int | None = None,
) -> dict:
    """推算本周期排卵日、易孕期、相对不易受孕期与预测经期区间。

    Args:
        predicted_period_date: 预测下次月经开始日（None 表示无预测/冷启动无数据）。
        current_period_start: 本次（最新一条）月经开始日。
        current_period_end: 本次月经结束日，None 表示未填结束日。
        cycle_std: 周期标准差（天），用于给排卵日附带不确定幅度。
        config_obj: 配置对象（默认 app.config 模块）。
        predicted_period_days: 预测经期天数（历史 period_days 中位数，由 routes 层计算；
            None 表示无历史经期记录，不推算经期区间）。

    Returns:
        {
          "available": bool,                  # 无预测时为 False，其余字段为 None/空
          "ovulation_date": str | None,
          "ovulation_sigma_days": float,      # 排卵日不确定幅度（= 周期 std，钳到 ≥0）
          "fertile_window": {"start","end"} | None,
          "less_certain_windows": [           # 相对不易受孕期，可能 0-2 段
              {"phase": "follicular"|"luteal", "basis": "actual"|"predicted"|
                        None, "start", "end"}
          ],
          "predicted_period_days": int | None,     # 历史经期天数中位数
          "current_period_predicted_end": str|None,  # 本次经期结束（实际优先，否则预测）
          "next_period_predicted_end": str|None,     # 下次经期预测结束日
          "predicted_period_windows": [              # 前端淡粉标注的区间（0-2 段）
              {"start", "end"}
          ],
          "basis": "calendar_rule",           # 明确标注推算方法（非个体化监测）
        }
    """
    if predicted_period_date is None or current_period_start is None:
        return {
            "available": False,
            "ovulation_date": None,
            "ovulation_sigma_days": 0.0,
            "fertile_window": None,
            "less_certain_windows": [],
            "predicted_period_days": predicted_period_days,
            "current_period_predicted_end": None,
            "next_period_predicted_end": None,
            "predicted_period_windows": [],
            "basis": "calendar_rule",
        }

    ovulation = predicted_period_date - timedelta(days=config_obj.LUTEAL_PHASE_DAYS)
    fertile_start = ovulation - timedelta(days=config_obj.FERTILE_PRE_OVULATION_DAYS)
    fertile_end = ovulation + timedelta(days=config_obj.FERTILE_POST_OVULATION_DAYS)

    windows: list[dict] = []

    # 预测经期区间（v0.8.1）：本次（未填结束日时）与下次经期，前端淡粉标注
    predicted_period_windows: list[dict] = []
    current_period_predicted_end: str | None = None
    next_period_predicted_end: str | None = None
    if predicted_period_days is not None and predicted_period_days >= 1:
        if current_period_end is not None:
            current_period_predicted_end = current_period_end.isoformat()
        else:
            cur_end = current_period_start + timedelta(days=predicted_period_days - 1)
            current_period_predicted_end = cur_end.isoformat()
            predicted_period_windows.append({
                "start": current_period_start.isoformat(),
                "end": cur_end.isoformat(),
            })
        nxt_end = predicted_period_date + timedelta(days=predicted_period_days - 1)
        next_period_predicted_end = nxt_end.isoformat()
        predicted_period_windows.append({
            "start": predicted_period_date.isoformat(),
            "end": nxt_end.isoformat(),
        })

    # 边界防御（v0.8.1 review W1）：预测周期 ≤ 黄体期+1 天时，排卵日落在本次经期
    # 开始日之前，日历法失效——不输出倒挂的排卵日/易孕期，仅保留预测经期区间
    if ovulation <= current_period_start:
        return {
            "available": True,
            "ovulation_date": None,
            "ovulation_sigma_days": round(max(float(cycle_std), 0.0), 2),
            "fertile_window": None,
            "less_certain_windows": [],
            "predicted_period_days": predicted_period_days,
            "current_period_predicted_end": current_period_predicted_end,
            "next_period_predicted_end": next_period_predicted_end,
            "predicted_period_windows": predicted_period_windows,
            "basis": "calendar_rule",
        }

    # 边界防御：极短预测周期可能使易孕期起点早于本次经期开始，钳到经期开始日
    if fertile_start < current_period_start:
        fertile_start = current_period_start

    # 卵泡期：本次月经结束次日起，至易孕期前一天。
    # 结束日优先用实际填写值（basis=actual）；未填时用预测经期结束日
    # （basis=predicted，数据来自用户自身历史而非固定默认值）；
    # 连预测经期都不可用时才不返回该段（不臆造起点）。
    follicular_end_basis: date | None = None
    follicular_basis_label: str | None = None
    if current_period_end is not None:
        follicular_end_basis = current_period_end
        follicular_basis_label = "actual"
    elif current_period_predicted_end is not None:
        follicular_end_basis = date.fromisoformat(current_period_predicted_end)
        follicular_basis_label = "predicted"
    if follicular_end_basis is not None:
        fs = follicular_end_basis + timedelta(days=1)
        fe = fertile_start - timedelta(days=1)
        if fs <= fe:
            windows.append({
                "phase": "follicular",
                "basis": follicular_basis_label,
                "start": fs.isoformat(),
                "end": fe.isoformat(),
            })

    # 黄体期：易孕期次日起，至预测来潮前一天。
    ls = fertile_end + timedelta(days=1)
    le = predicted_period_date - timedelta(days=1)
    if ls <= le:
        windows.append({
            "phase": "luteal",
            "basis": None,
            "start": ls.isoformat(),
            "end": le.isoformat(),
        })

    return {
        "available": True,
        "ovulation_date": ovulation.isoformat(),
        "ovulation_sigma_days": round(max(float(cycle_std), 0.0), 2),
        "fertile_window": {
            "start": fertile_start.isoformat(),
            "end": fertile_end.isoformat(),
        },
        "less_certain_windows": windows,
        "predicted_period_days": predicted_period_days,
        "current_period_predicted_end": current_period_predicted_end,
        "next_period_predicted_end": next_period_predicted_end,
        "predicted_period_windows": predicted_period_windows,
        "basis": "calendar_rule",
    }
