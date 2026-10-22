# BDD 场景清单 — PeriodIQ 安卓端

> **建立日期**：2026-10-06（R0 骨架，**空表**）　**首次登记**：2026-10-08（顾问，R1 四个场景）
> **依据**：`AGENTS.md` §3.6（BDD 先行）+ 桌面端 BDD 清单（27 个 `bdd` 场景可作模板）
> **执行器**：**CDP 直连安卓 WebView 是否可行 = R1 的 Spike C 正在验证**；
> 可行 ⇒ 复刻桌面 `tools/frontend_test.py` 形态；不可行 ⇒ 退到 `adb` + `uiautomator` 路线（R1 结论后定）

---

## 0 这份清单解决什么问题

桌面项目的实测教训：**S7 全绿 ≠ 用户没意见**。
断言对着的是**功能契约**，而用户看的是**场景**（"手机上底栏不该盖住内容"）。
所以安卓侧从第一天就分开登记：**清单里 ❌ 的条目 = 下一轮的首选靶子**。

**"没测成"≠"通过"**：判定态五类 —— `PASS / FAIL / SKIP / 未覆盖 / 不适用`，
**未覆盖必须写明"为什么没测成 + 需要什么条件"**。

---

## 1 视口矩阵（安卓）

| 视口 | 典型机型 |
|---|---|
| 390×844 | 主流中端（iPhone 尺寸等价，桌面项目已用） |
| 375×667 | 小屏 |
| 360×800 | 常见 720p 安卓 |
| 412×915 | 大屏安卓 |
| 390×600 | **异形短视口**（仅作边界检查，不参与常规矩阵） |

**真机与模拟器纪律**：触摸 / 通知 / 返回键 / 深色模式 / 字体缩放 / 省电行为 **必须真机**；
报告里必须写清每条判据跑在**真机（型号 + Android 版本）**还是**模拟器（镜像 + API 级别）**。

---

## 2 场景登记表（R1 起逐条填）

| # | 场景 id | 一句话 | 视口 | 路由/页面 | 哨兵 | 判定 |
|---|---|---|---|---|---|---|
| 1 | `S-AND-R1-BUILD-INSTALL` | 能在真机上装起来并打开 | 412×915 @dpr2.625 | `MainActivity` → 真实前端 | ✅ **R2-a 起有自动哨兵**：`r2_assert.ps1 -Suite render/assets`、`gate` | ✅ **PASS**（顾问 2026-10-09 复跑：APK 33,376,989 B · 零权限 · `tests=7/0/0` · 三条套件读数与报告一致） |
| 2 | `S-AND-R1-ASSET-ROOT-SWITCH` | 资产根真的能切换（定制 UI 的前提） | 同上 | `/theme/index.html` 两态 | ✅ 自动（`-Suite assets` + 双态 `ui_variant`） | ✅ **PASS**（两态标题不同 / 白名单外值回落 `default` 且不写盘 / 根兜底映射已验证） |
| 3 | `S-AND-R1-BRIDGE` | JS 桥可用且三种情形都有可读结果 | 同上 | 同上 | ⚠ 部分：三态哨兵在 **R1 尖刺装置**上做过，**现已随 P4 拆除** ⇒ 需在 R3 的正式桥（30 端点）上重建 | ✅ **PASS（R1 时）** · ⚠ **复跑能力未覆盖**（R3 重建） |
| 4 | `S-AND-R1-CDP` | 桌面那 76 条前端哨兵能不能指向安卓 WebView | 同上 | 同上 | ✅ 自动（`r2_assert.ps1` + `cdp_eval.py`；`suppress_origin` 直连 9230） | ✅ **PASS ⇒ 可复用**（R1 结论 + R2-a 起已实际用于全部真机判据） |
| 5 | `S-AND-R2-FRONTEND-RENDER` | 真实前端在真机上真的渲染出来 | 412×915 @dpr2.625 | `/theme/index.html` | ✅ 自动（`-Suite render`） | ✅ **PASS**（可见节点 97 / 总节点 160 / `#page-content` HTML 1920 B / `.auth-screen` 在场 / 无横向溢出） |
| 6 | `S-AND-R2-ASSET-ROOTS` | 资产与虚拟路径都可达、别名同一文档 | 同上 | `/theme`、`/js`、`/assets` | ✅ 自动（`-Suite assets`） | ✅ **PASS**（`/theme`＝`/index.html` 11,617 B；`/js/app.js` 200；`/assets/*.webp` 200） |
| 7 | `S-AND-R2-SENTINELS` | 哨兵本身能 PASS 也能 FAIL | — | — | ✅ 自动（退出码 0/1/3 + `--mutate` 类开关） | **部分达成**：`gate` 套件 exit 0 ✓；`render` 套件因早期 `innerText>200` 判据为 exit 1（**判据已订正，见下**） |
| 8 | `S-AND-R2-ALGO-GOLDEN` | 安卓与桌面算法逐值一致 | — | `app.algo.*`（Chaquopy py3.11） | ✅ 自动（`tools/piq_golden.py`，容差相对 `1e-9`） | ✅ **PASS**（**全量逐值通过**；条数**用时现查** `docs/契约/golden/*.json`，不写死数字 —— R8 审计 REG-17 订正了这一格原来的"70/70"） |

> **⚠ 判据订正（R2-a 实测，顾问 2026-10-09）**：旧的「`body.innerText.length > 200`」在**中文 UI 上不成立**
> （登录页全文 42–51 字，且标语是打字动画 ⇒ 同一构建三次跑出 42/45/51）。
> **一律改用与语言/长度无关的口径**，四条同时成立才算渲染：
> ① 可见节点数 `> 20`；② 总节点数 `> 60`；③ 目标容器（`#page-content`）`innerHTML` 字节 `> 500`；
> ④ 关键容器（如 `.auth-screen`）在场。**白屏判定的下限（`innerText < 50`）保留**——它测的是"是不是空白页"，不是文案长度。
>
> **遗留（R3 的处理项）**：场景 3 的桥三态哨兵随 R1 尖刺装置拆除，**目前无复跑能力**；
> 场景 7 的 `render` 套件在订正判据后需重跑一次确认 exit 0。


### 2.1 R1 四个场景的机器可解析块（判定待 R1 报告回填）

```bdd
id: S-AND-R1-BUILD-INSTALL
title: 能在真机上装起来并打开
given: 真机（Android 15 / API 35 · 逻辑视口 ≈411×914 dp；型号/序列号用时现查，规则文件不写实测值）· 已装 SDK(build-tools 35.0.0)/JDK 21 · 真机已授权
when:  执行 gradlew assembleDebug 与 installDebug，然后 am start 启动应用
then:  真机屏幕出现 WebView 页面（截图注明机型与 Android 版本）；且 aapt2 dump badging 的 uses-permission 列表不含 android.permission.INTERNET
views: [411x914]
routes: ["appassets.androidplatform.net/assets/www/index.html"]
sentinel: r1_build_install
status: pass
```

```bdd
id: S-AND-R1-ASSET-ROOT-SWITCH
title: 资产根真的能切换（定制 UI 的前提）
given: ui_variant 取值为 default / custom 两态；切换入口 = MainActivity 启动 extra（顾问裁定，见 R1 修复提示词1 §B1）
when:  分别以 --es ui_variant default / custom 启动应用，并用显式条件等待页面加载完成
then:  两次 document.title 分别为 PIQ-DEFAULT / PIQ-CUSTOM，两者必须不同（相同 = FAIL）
views: [412x915]
routes: ["appassets.androidplatform.net/assets/www-custom/index.html"]
sentinel: r1_asset_root_switch
status: pass
```

```bdd
id: S-AND-R2-ALGO-GOLDEN
title: 同一份备份在两端的算法结果必须逐值一致
given: 算法源码 = app/src/main/python/app/（R13.1 起唯一来源，直接经 Chaquopy 打包；无构建期副本核验）；设备侧 Chaquopy py3.11
when:  用 tools/piq_golden.py 在同一批 golden vectors（docs/契约/golden/，条数用时现查）上取值，与桌面导出的 expect 比对
then:  逐值相对误差 <= 1e-9（显式声明、不得放宽）；名称集合相同；任一项不符即 FAIL 并给两侧原始数字
views: [-]
routes: ["app.algo.*"]
sentinel: r2_golden.py
status: pass
```

```bdd
id: S-AND-R1-BRIDGE
title: JS 桥可用且三种情形都有可读结果
given: 页面通过 window.PIQNative.request(method, path, body) 调用桥；桥返回固定假数据
when:  ① 正常调用 ② 桥返回 ~1 MB 字符串 ③ 桥内部抛异常
then:  三种情形页面均有可读结果（不得白屏）：① 渲染出 JSON ② 记录是否截断/崩溃的实测值 ③ 页面拿到可读错误；且桥方法所在线程名 != main（证明不在 UI 线程执行）
views: [411x914]
routes: ["appassets.androidplatform.net/assets/www/index.html"]
sentinel: r1_bridge_tristate
status: pass
```

```bdd
id: S-AND-R1-CDP
title: 桌面那 76 条前端哨兵能不能指向安卓 WebView
given: adb forward 已建立（9230 = 浏览器级 socket；页面级转发见 R1 修复提示词1 §B2）
when:  用 CDP 在页面里求值 document.title
then:  拿到 PIQ-DEFAULT / PIQ-CUSTOM 之一；否则结论必须写成「不可复用」（附补转发对照实验后的原始报错）或「未覆盖」（附卡点与所需条件）
views: [411x914]
routes: ["appassets.androidplatform.net/assets/www/index.html"]
sentinel: r1_cdp_evaluate
status: pass
```


### 机器可解析块格式（照抄桌面惯例）

````text
```bdd
id: S-AND-<主题>
title: <用户会怎么说这件事>
given: <设备/视口/前置数据>
when:  <用户动作>
then:  <可测量的可见结果，含数值与单位>
views: [390x844]
routes: ["/"]
sentinel: <执行器里的断言名>
status: pass | fail | skip | 未覆盖 | 不适用
```
````

**硬性要求**：
- 每个场景必须**指明视口与路由**；
- 「then」必须是**可直接测的量**（px / 次数 / 布尔），**禁止**「看起来协调」「更美观」；
- 场景 id、哨兵名必须与执行器**逐字符对齐**（否则清单与代码不一致 = 假 FAIL）；
- 新增场景**必须在同一轮**同时更新执行器映射与本清单的判定列（**不要另建"计数快照文件"**，见 `AGENTS.md` §0.0）。

---

## 5 R8（2026-10-13）追加的场景与哨兵状态（执行端追加，不改他人行）

> 依据 `AGENTS.md` §3.6 与提示词 §2 白名单第 8 项：**只追加、不删改他人已写的行**。
> ⚠ 同轮核对读数：本文件第 8 行附近写死的"golden **70/70**"已过期 ——
> 现查 `docs/契约/golden/*.json` 逐文件 `len` 相加 = **73**（`10 + 7 + 18 + 3 + 35`）。
> 该行由顾问维护，故此处**登记而不改写**（`AGENTS.md` §3.9 第 2/3 条：文档不得写会变的数）。

```bdd
id: S-AND-R8-IMPORT-EMPTY-GATE
title: 近乎空的备份不会抹掉我已有的一切
given: 模拟器 emulator-5556 · 逻辑视口 412x915 dp · 库内已有 cycles=3 predictions=1 · 导入文件内容仅 {"cycles": [], "model_params": {}}
when:  页内 POST /api/v1/import 提交该文件
then:  返回 status=400 且 code=EMPTY_IMPORT 且 detail="导入数据不能为空"；四张业务表行数与提交前逐表相等（cycles 仍=3、predictions 仍=1）
views: [412x915]
routes: ["/#/settings"]
sentinel: ImportParityTest.empty cycles plus empty model_params object is not an importable payload
status: pass
```

```bdd
id: S-AND-R8-IMPORT-SHAPE-GATE
title: 类型写错的导入文件必须先被拒绝，而不是被"看成空的"
given: 同上空库前置 · 导入文件形如 {"cycles": "abc", "model_params": {"mean_cycle": 28}}
when:  页内 POST /api/v1/import
then:  返回 status=422 且 code=VALIDATION_ERROR（与桌面 pydantic 同码）；库内行数逐表不变，绝不出现"200 成功导入 0 条"
views: [412x915]
routes: ["/#/settings"]
sentinel: ImportParityTest.a string cycles must be rejected with 422 rather than silently emptied
status: pass
```

```bdd
id: S-AND-R8-DATE-DOMAIN-PARITY
title: 非法日期不会被我记成合法日子
given: 导入或 POST /cycles 提交 period_start 取 {"2026-09-02ABC", "2026-09-02T10:30", "", "0000-01-01", "20260902", "2025-W02-7"} 六形
when:  逐个提交并读回库内该行的 period_start
then:  前四形必为 400 INVALID_IMPORT；后两形（桌面 fromisoformat 接受集）必为 200 且入库值等于 2026-09-02 / 2025-01-12
views: [412x915]
routes: ["/#/settings", "/#/calendar"]
sentinel: ImportParityTest.import parser accepts the iso and basic formats and nothing junkier
status: pass
```

```bdd
id: S-AND-R8-EXPORT-DISK
title: 点"导出备份"后文件真的在机器里
given: 模拟器 emulator-5556（debug 插桩件）· 已登录且有 cycles>=1 · 目录 Android/data/io.github.yui0818.periodiq/files/Download 已清空
when:  用户在设置页点「导出备份」（JSON），等待回执出现
then:  Download 目录出现 1 个 .json 文件，字节数>0 且内容可被 JSON 解析、含契约 §1 的 7 个顶层键；且宿主给出可读回执（Toast 文案含文件名与大小）
views: [412x915]
routes: ["/#/settings"]
sentinel: r8_export_cdp.py
status: 未覆盖
```

```bdd
id: S-AND-R8-BACK-NAVIGATE
title: 在子页面按返回键应该往回走，不是退出应用
given: 模拟器 emulator-5556 · 已从首页导航到 1 个以上的页内历史（location.hash 变化 >=2 次）
when:  执行 adb shell input keyevent KEYCODE_BACK 一次
then:  应用仍在前台（dumpsys 的 mCurrentFocus 仍是本包）且 location.hash 等于上一个页面的 hash；在首页再按一次则应用退出（canGoBack=false 分支）
views: [412x915]
routes: ["/#/history", "/#/settings"]
sentinel: (无 JVM 哨兵，需设备侧) 
status: 未覆盖
```

```bdd
id: S-AND-R8-PDF-501
title: PDF 导入这条按钮要说人话
given: 模拟器 emulator-5556 · 已登录 · 进入导入中枢，存在 data-testid=transfer-opt-pdf 的入口
when:  页内 POST /api/v1/import/pdf
then:  返回 status=501、code=NOT_IMPLEMENTED、implemented=false、detail 为中文且含"JSON"字样；页面渲染出的错误句子中不含 "POST" 或 "/api/v1"
views: [412x915]
routes: ["/#/import/pdf"]
sentinel: (无 JVM 哨兵，需设备侧)
status: 未覆盖
```

```bdd
id: S-AND-R8-IME-INSET
title: 键盘不该盖住我正在打字的那个框
given: 模拟器 emulator-5556 · 进入含输入框的表单页 · 手势导航形态
when:  聚焦输入框使软键盘弹出，读取 --piq-inset-bottom 与输入框 getBoundingClientRect().bottom
then:  --piq-inset-bottom 相比键盘收起时增大 >= 键盘高度 - 导航栏高度 - 2px，且输入框 bottom <= window.innerHeight - 键盘高度 + 2px
views: [412x915, 390x600]
routes: ["/#/calendar"]
sentinel: (无 JVM 哨兵，需设备侧)
status: 未覆盖
```

```bdd
id: S-AND-R8-AUTOSAVE-FAILURE
title: 自动登录存不下时不该假装成功
given: 模拟器 emulator-5556 · 当前无活动会话（未登录）· 设置页内自动登录开关为 off
when:  页内调用 PIQNative.setSetting('app_settings.auto_login','true')
then:  返回体 ok=false 且 code=NO_ACTIVE_SESSION；SharedPreferences piq_settings 中 app_settings.auto_login 为 false；屏幕出现一条中文提示（Toast 文本可在 logcat 佐证）
views: [412x915]
routes: ["/#/settings"]
sentinel: (无 JVM 哨兵，需设备侧)
status: 未覆盖
```

**同轮同值说明**：`S-AND-R8-EXPORT-DISK` / `BACK-NAVIGATE` / `PDF-501` / `IME-INSET` / `AUTOSAVE-FAILURE`
五条的实现已落地（见 `docs/提示词/R8/报告回传/缺陷台账/R8-F07…F11`），
但**运行时判据依赖设备**，本轮设备窗口不稳定（模拟器 qemu 反复段错误）⇒ 按 §3.3 记 `未覆盖`，
**不得**因为"代码改了"就写 `pass`。前三条的 JVM 侧口径（`IMPORT-EMPTY-GATE` / `IMPORT-SHAPE-GATE` /
`DATE-DOMAIN-PARITY`）已有 80 条单测中的逐值断言覆盖，故写 `pass`。

---

## 6 R9.1（2026-10-14）追加的场景与哨兵状态（执行端追加，不改他人行）

> 判定态五类照旧：`pass / fail / SKIP / 未覆盖 / 不适用`。
> **只写有原始输出的**；设备侧读数没回来的一律 `未覆盖`，等交叉验证那轮再改状态。

```bdd
id: S-AND-R91-GATE-PACKAGED
title: 隐私闸门必须在"打包之后"也看得见容器内部的泄漏
given: 工作区干净；变异样本只落仓库外 .dsh/r9_1_probe/mutants/（三种件各占独立子目录）
when:  用 tools/privacy_gate.py 分别按"改前目标清单（源树三点）"与"改后目标清单（合并资产树 + outputs/apk）"扫
       ①源树 ②APK 平铺 assets/leak_probe.db ③assets/chaquopy/app.imy 内层 .db + 备份形态 json ④未变异副本
then:  ①GATE|PASS（源树看不见内层）②③GATE|FAIL 且 HIT 行点名 `…apk!assets/leak_probe.db` 与
       `…apk<-assets/chaquopy/app.imy!app/leak_inner.db` ④GATE|PASS（判据不过宽）
       且 `./gradlew assembleR`（缩写名）与 `packageRelease` 直达形态都会触发这道 finalizer
sentinel: 无 JVM（构建图行为）；三态读数在 .dsh/r9_1_probe/logs/reg16_three_state.log
status: pass（三态 + 缩写名实测均触发；`package*` 直达的复跑读数【待第 3 轮补】）
```

```bdd
id: S-AND-R91-GATE-NOTHING-SCANNED
title: 一个文件都没扫到不能算"干净"
given: 工作区任意状态；参数指向两个不存在的目录
when:  python tools/privacy_gate.py does/not/exist1 does/not/exist2
then:  退出码 = 1 且打印 `GATE|FAIL|reason=nothing-scanned|targets=2`；
       同一脚本扫真实源树仍 `GATE|PASS|scanned=266 / exit=0`
sentinel: 无 JVM（tools/）；阳性对照 = 缺席必红、真实态必绿
status: pass
```

```bdd
id: S-AND-R91-FIXTURE-DATE-BASE
title: 导入夹具的日期一律相对"今天"生成（契约 §11.3）
given: 主机 Python 现场运行 .dsh/r9_1_probe/make_fixtures.py（不传 --base）
when:  生成九类夹具并读 manifest.json
then:  九条的 base_date 全等于当天日期；合法夹具的 period_start 全部 <= 今天；
       future-date 夹具含一条"今天 + N 天"；oversize 体积 > 8 MiB；
       bom 前 3 字节 = EF BB BF；truncated 与 gbk 不能被 UTF-8/JSON 读回（生成器自检即阴性对照）
sentinel: 脚本内置 8 条 assert（`R9.1报告1.md` §3.4 贴了生成输出）
status: pass
```

```bdd
id: S-AND-R91-FUTURE-DATE-LAYER
title: 未来日期是独立一层，allow_anomalies 放行不了它
given: 模拟器 emulator-5554 · debug 终态件 · 已有一次成功导入的基线（cycles = 3）
when:  同一份 future-date 夹具各打一次 `allow_anomalies=false` / `=true`
then:  两次都被拒且错误码同为 INVALID_IMPORT（不得因放行开关变成接受）；cycles 行数两次都不变
sentinel: 需设备（桥直调 + 整组拷贝法数行数）
status: 未覆盖（探针 p41_c1/c2 已跑过、读数在 .dsh/r9_1_probe/emu/logs/，正在汇总进报告 §3.4）
```

```bdd
id: S-AND-R91-501-NO-PATH
title: 未实现分支的响应体不回显内部路径
given: 零权限 WebView · 桥 `PIQNative.request` 严格 3 参 · 令牌由宿主持有
when:  页内直调 POST /api/v1/nope（404）与 POST /api/v1/import/pdf（501）
then:  501 的体里键集恰为 {detail, code, implemented}，不含 path，也不含 "/api/v1" 或方法名字面；
       404 的体同理不含请求路径与方法名
sentinel: RouterResponseShapeTest（4 条，含"带 path 的信封必须被这条判据拒掉"的阴性对照）
status: JVM pass / 设备读数【待第 3 轮交叉验证】（探针 p42_reg06 已跑，汇总中）
```

```bdd
id: S-AND-R91-WEIGHT-NONFINITE
title: 体重不接受 NaN（与桌面 ge/le 等价）
given: 桌面 ProfileUpdate.weight_kg = Optional[float] Field(None, ge=25, le=300)
when:  依次送 "NaN" / "Infinity" / "70.5" / " 70.5 " / "1e2" / true / "" / [] / 24.999 / 300.001
then:  前两个必须 422（改前 "NaN" 会被放行入库 —— IEEE 754 下 NaN 与任何数比较恒假）；
       数字字符串两端同判为接受；bool/空串/容器/越界两端同判为拒
sentinel: NumericFieldAcceptanceTest（9 条）+ 桌面侧 588 条实测 jsonl
status: pass（变异自证：删掉 isFinite 后 2 条报红，读数见 R9.1报告1 §3.3）
```

```bdd
id: S-AND-R91-STALE-CORRECTION-NO-500
title: 陈旧预测修正不得整段 500（P1 回归）
given: 库里有一条"预测日已过、还没回填实际日"的预测，且重算出的新预测日与它不同
when:  打开首页触发 GET /predictions/current
then:  返回 200 且新预测落库；model_params.stale_correction.corrected_at 形如 `…+00:00` 秒级；
       不得出现 500 INTERNAL_ERROR，也不得"修正悄悄丢失"
sentinel: StaleCorrectionTimestampTest（4 条，前两条永久断言基线表达式必抛 UnsupportedTemporalTypeException）
status: JVM pass / 设备端到端【待第 3 轮：模拟器改时钟造陈旧预测】
```

```bdd
id: S-AND-R91-GATE-MESSAGE-NUMBERS
title: 门禁文案里的天数与桌面逐值一致
given: 桌面 gate.py 用 f"{x:.0f}"（四舍六入五取偶）与 f"{x:.1f}"
when:  周期长度 6.7 触发 G1、经期时长 10.7 触发 G5；并把设备默认 locale 设成 de_DE 跑一轮
then:  文案印"7 天""11 天"（改前是截断的"6 天""10 天"）；小数点必须是 `.`（改前 de_DE 下印 `22,3`）
sentinel: GateMessageFormatTest（4 条）+ 主机 Python 现跑的期望值表
status: pass（变异自证：还原成 .toInt() 与默认 locale 后 4 条全红）
```

```bdd
id: S-AND-R91-KEYBOARD-VIS-BLOCKED
title: 键盘弹起时"保存资料"不得被自家吸底导航遮住
given: 真机（物理序列号用时现查 `adb devices -l`）与模拟器 piq35 各一次（411.4×914 dp），设置/我的页，键盘已弹起
when:  点击「体重（kg）」输入框聚焦
then:  ①聚焦框 bottom <= 键盘上沿（原判据不变）②同组最低可交互元素（保存按钮）bottom <= 吸底导航带 top
       ③按钮与导航带交叠像素 = 0 ④键盘收起后导航带回到屏幕底边（不残留临时 padding）
sentinel: 需设备（release 件无 CDP，DOM 侧四量要在 debug 件模拟器上量）
status: 未覆盖（真机实测交叠 128 px = 缺陷 R9.1-F01，诊断读数在 .dsh/r9_1_probe/f01/geometry.md，修完复跑）
```

---

## 7 R10 系列（2026-10-18 / 19）追加的场景与哨兵状态（顾问追加，不改他人行）

> 这四轮（R10 / R10.1 / R10.2 / R10.3）的场景 id 此前只活在提示词与报告里（报告不入 git），清单漏登记
> 属**顾问的欠账**（三轮执行端都在报告 §6 里点过名）。此处一次补齐，判定只写已闭合的终态；
> 需读数的场景一律标注"设备探针"，**不要**把它当成自动哨兵。

| # | 场景 id | 一句话 | 哨兵 | 状态 |
|---|---|---|---|---|
| 1 | `S-AND-R10-SESSION-EXPIRED` | 过期会话下首次调用不闪错、落到登录空壳帧 | 设备探针 | **PASS**（空壳帧 1.5–3 s = 契约 §14 的已知特性，不是缺陷） |
| 2 | `S-AND-R10-SESSION-LIVE` | 同件同账号、会话未过期的对照组 | 设备探针 | **PASS**（对照） |
| 3 | `S-AND-R10-THEME-ENTRY-MALFORMED` | 自定义主题入口损坏时回退默认 | 设备探针 | **PASS**（形态闸门） |
| 4 | `S-AND-R10-BLOB-REVOKE-EVICT` | 页面句柄被 revoke 后不崩、可恢复 | 设备探针 | **PASS** |
| 5 | `S-AND-R10-L2D-F1-ESC-FALLBACK-UNREACHABLE` | `esc` 恒等兜底在安卓侧不可达 | —— | **不适用**（不可达，不是缺陷） |
| 6 | `S-AND-R10.1-L2E05` | 前后台切换 / 被回收后恢复，不重放副作用 | 设备探针（RSS 量化） | **PASS** |
| 7 | `S-AND-R10.1-VER` | 页内版本串 = 安卓 `versionName`，不写死前端版本 | JVM 哨兵 + 引擎侧注入（`AndroidGate.versionRewrite`） | **PASS** |
| 8 | `S-AND-R10.1-P3` | R10 遗留 P3 逐条终态 | ——（逐条登记） | **PASS** |
| 9 | `S-AND-R10.1-STRUCT` | 结构性复检（文件数 / 行数 / TODO） | ——（结构表） | **PASS** |
| 10 | `S-AND-R10-L2E08-DOUBLE-CHOOSER` | 连点两次导入不叠起两层系统选择器 | 设备探针 | 模拟器**实测驳回**；真机 **未覆盖** |
| 11 | `S-AND-R10-L2E09-TOGGLE-REBOUND-VISIBLE` | 凭证回弹后自动登录开关不点亮 | 设备探针 | **PASS** |
| 12 | `S-AND-R10.2-L2E07-FD-RECREATE` | Activity 重建不多留库句柄 | 设备探针 + JVM 哨兵 | R10.2 **FAIL**（`db` 3→7→11）→ R10.3 复测 **PASS**（恒 3） |
| 13 | `S-AND-R10.3-FD-RECREATE` | 同上；进程级单实例之后的复测形态 | 设备探针 + `ProcessSingletonSlotTest` | **PASS**（两轮 × 3 轮重建，`db_groups` 恒 3） |
| 14 | `S-AND-R10.3-REGRESS` | 重建修复不破坏登录 / 导出 / 导入 / 删号 | 设备探针 | **PASS** |
| 15 | `S-AND-R10.3-JVM` | "复用"这条不变量在 JVM 侧能被判红 | `ProcessSingletonSlotTest`（4 条） | **PASS**（变异 2/4 红） |

### 7.1 R10.3 三条场景的机器可解析块

```bdd
id: S-AND-R10.3-FD-RECREATE
title: Activity 重建不再多留一套库句柄（每进程一个 PiqDb）
given: 模拟器 piq35（API 35 · 412×915 dp）· debug 件 · adb root · 冷启动 + 夹具账号已登录
when:  用 settings put system font_scale 做 3 轮 1.00→1.05→1.10→1.15→1.00（每轮触发两次 MainActivity 重建）
then:  ① /proc/<pid>/fd 里 app.db 相关句柄组数每点都 = 3（每组 = app.db / -wal / -shm 各 1 个 fd），
        且 pid 全程不变（变了该点作废重取） ② 序列不得单调不减、末值 − 基线 < 6 ③ 冷启动 db-open = 1，
        清缓冲后的重建段 db-open = 0 而句柄仍在（清理点必须写明）
sentinel: 设备探针（仓库外）；JVM 面由 ProcessSingletonSlotTest 承担
status: PASS（2026-10-19，两轮各 3 轮重建；db_groups 恒 3，theme-bound/services-ready 每轮 +2 作阳性对照）
```

```bdd
id: S-AND-R10.3-REGRESS
title: 库句柄改成进程级单实例后，四条用户路径语义不变
given: 同一 debug 件 · 同一模拟器 · 一次性夹具账号（删号腿会销毁它，可重建）
when:  登录 → 导出备份 JSON → 导入同一份 → 删除账号；全程看 logcat 崩溃缓冲
then:  ① 登录 200 ② 导出返回 ok 且落盘字节数与返回一致 ③ 导入（含二次导入）都 200 且周期数不翻倍
        ④ 删号 200 且六表归 0，删号前后 pid 不变 ⑤ 崩溃缓冲非空行 = 0
sentinel: 设备探针（仓库外）
status: PASS（2026-10-19；删号腿在独立 delete 段完成，探针首次调用因路由名写错拿到 404，已登记）
```

```bdd
id: S-AND-R10.3-JVM
title: "进程级单实例"这条不变量在 JVM 上可被判红
given: 无 Robolectric（SQLiteDatabase 是 android.jar 桩类）⇒ 把单槽抽成与 Android 解耦的 ProcessSlot
when:  跑 :app:testDebugUnitTest --rerun；并把 ProcessSlot.get 的"有则复用"改成"每次新建"
then:  ① 正常件 4 条全绿 ② 变异件至少 2 条红（复用 / 并发只建一次） ③ 恒等型那条（无关闭入口）KDoc 自曝红路径
sentinel: ProcessSingletonSlotTest（JVM，无设备）
status: PASS（2026-10-19；RED 1/4 → GREEN → 变异 2/4 → 回归全绿）
```

---

## 8 R11（2026-10-19 / 20）通知：三类提醒的场景与哨兵状态（顾问追加）

> id 由 R11 提示词与修复提示词 2 给出；判定取自 R11 报告与顾问复核（2026-10-20）。
> 该轮的设备读数在**模拟器**上取（真机按当轮纪律不动），所以"真机到点弹出 / 厂商后台限制"两条
> 按 `AGENTS.md` §4 的 R11 例外记 **`未覆盖`**，不记 PASS。

| # | 场景 id | 一句话 | 哨兵 | 状态 |
|---|---|---|---|---|
| 1 | `S-AND-R11-NOTIFY-PLAN` | 三类提醒各自独立排程（提前 N 天 / 当日 / 逾期 D+1、D+3），已过只丢自己 | JVM `PiqReminderPlanTest`（16 条）+ 变异 A/B/C | **PASS**（顾问复跑 189 用例 0 失败） |
| 2 | `S-AND-R11-SETTINGS` | 设置页通知区折叠/展开、五键默认值、总开关关时子项禁用、权限三态 | 设备探针（CDP 读 DOM）+ 5 键原始返回值 | **PASS**（模拟器；真机仅只读核对） |
| 3 | `S-AND-R11-NOTIFY-DEVICE` | 到点在通知栏出现、点开回 App、开机重挂、改开关 alarm 增减 | 设备探针（`dumpsys alarm` / `dumpsys notification` / `adb reboot`） | **PASS**（模拟器全量；**真机 2026-10-20 用户实测到点弹出**，设备日志 `reminder-fire|id=1|posted=true`）；仍存疑：长时间 Doze/MIUI 清理下能否按时 |
| 4 | `S-AND-R11-CANCEL-ON-EXIT` | 关总开关 / 登出 / 删号 ⇒ 挂起的 alarm 全部撤下（隐私） | 设备探针 + `PiqReminderRoutes.action` 的 JVM 用例 | 关总开关与登出 **PASS**；删号腿 **未覆盖**（未在设备上走） |

```bdd
id: S-AND-R11-NOTIFY-DEVICE
title: 三类提醒到点真的弹通知，且能被开关精确撤下
given: 模拟器 piq35（API 35）· 1.1.7/12 · 夹具账号 + 5 个历史周期 ⇒ 预测 2026-11-06
when:  打开通知总开关（并分别打开/关闭当日、逾期）；必要时把设备时钟移到触发前 2 分钟
then:  ① 提前=11-03 09:00、当日=11-06 09:00、逾期=11-07 与 11-09 09:00 各一条 alarm
        ② 只关某一类 ⇒ 只少该类，其余时刻不变 ③ 关总开关 ⇒ alarm 归 0
        ④ 到点后系统通知栏出现该条（id 1–4、渠道 periodiq_reminder），点开回同一条 task
sentinel: 设备探针（仓库外 .dsh/r11_probe/）+ 顾问 2026-10-20 复跑的 arm/cancel 读数
status: PASS（模拟器全量）；真机 2026-10-20 用户手动改日期实测到点弹出（`reminder-fire|id=1|posted=true`）；
        长时间 Doze/MIUI 清理下能否按时仍 `未覆盖`
```

