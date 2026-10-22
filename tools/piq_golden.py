"""R2-a P3 测量装置：把 golden vector 的 `input` 派发到 `app.algo.*` 的纯函数上。

**同一份文件**在两侧跑：
- 桌面侧（导出期望值）：`sys.path` 指向本目录后 `import piq_golden`；
- 安卓侧（真机取值）：Chaquopy 经 JS 桥调用 `run_json(body)`。

因此两侧调用的是同一份算法源码（`app/src/main/python/app/`，R13.1 起唯一来源），差异只有解释器与宿主。
本轮测量装置，P4 随 spike 脚手架一起删除。
"""
import json
from datetime import date

DISPATCH = {
    "app.algo.stats": (
        "robust_weights", "effective_sample_size", "weighted_mean", "ema_update",
        "cycle_std", "replay_ema", "decay_weights", "normal_conjugate_posterior",
        "predict_core", "predict_next_period",
    ),
    "app.algo.bayesian": ("bayesian_update", "prior_weight", "on_new_cycle_added"),
    "app.algo.eval": ("evaluate_predictions",),
    "app.algo.fertility": ("compute_fertility_window",),
    "app.algo.trace": ("prediction_trace",),
}

_DATE = "%Y-%m-%d"


def _iso(s):
    return date(int(s[0:4]), int(s[5:7]), int(s[8:10]))


def _convert(value, as_date):
    """只转换 vector 显式声明的日期参数（`prediction_trace` 的入参是 str，不能转）。"""
    if as_date and isinstance(value, str) and len(value) == 10:
        return _iso(value)
    return value


def _normalize(obj):
    if isinstance(obj, date):
        return obj.isoformat()
    if isinstance(obj, tuple):
        return [_normalize(x) for x in obj]
    if isinstance(obj, list):
        return [_normalize(x) for x in obj]
    if isinstance(obj, dict):
        return {str(k): _normalize(v) for k, v in obj.items()}
    if isinstance(obj, (int, float, str, bool)) or obj is None:
        return obj
    return str(obj)


def run(spec):
    """spec = {"module": "app.algo.stats", "func": "...", "args": [...], "kwargs": {...},
    "date_args": [位置下标或关键字名]}"""
    import importlib

    module_name = spec["module"]
    func_name = spec["func"]
    if func_name not in DISPATCH.get(module_name, ()):
        raise ValueError("未登记的派发目标 %s.%s" % (module_name, func_name))
    func = getattr(importlib.import_module(module_name), func_name)

    date_args = set(spec.get("date_args") or [])
    args = [_convert(a, i in date_args) for i, a in enumerate(spec.get("args") or [])]
    kwargs = {k: _convert(v, k in date_args) for k, v in (spec.get("kwargs") or {}).items()}
    return _normalize(func(*args, **kwargs))


def run_json(body):
    """body = {"vectors": [{"name": ..., "input": {...}}, ...]} ⇒ {"results": [...]}"""
    payload = json.loads(body) if isinstance(body, str) else body
    results = []
    for vector in payload.get("vectors", []):
        item = {"name": vector.get("name")}
        try:
            item["actual"] = run(vector["input"])
            item["ok"] = True
        except BaseException as exc:
            item["ok"] = False
            item["error_type"] = type(exc).__name__
            item["error"] = str(exc)
        results.append(item)
    import sys

    return json.dumps({
        "python": sys.version.split()[0],
        "count": len(results),
        "results": results,
    }, sort_keys=True)
