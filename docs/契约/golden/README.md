# Golden vectors

Deterministic input/expected-output pairs for the cycle algorithm. They were generated from the
Python implementation and contain **no real identity information**: real-data cases were shifted
in time while preserving interval relationships.

| File | Contents |
|---|---|
| `stats_primitives.json` | Robust weights, EMA, posterior, core prediction primitives |
| `predict_next_period.json` | End-to-end next-period prediction |
| `eval_accuracy.json` | Prediction accuracy metrics |
| `fertility_trace.json` | Fertility window and trace |
| `real_desensitized.json` | Desensitized real-world sequence |

The Kotlin/Java side must match these values within an explicit floating-point tolerance
(see `docs/契约/跨端契约.md` §3). Do not loosen tolerance to make a test green.
