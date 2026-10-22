package io.github.yui0818.periodiq

/**
 * `piq-android` 运行时门控（`docs/契约/跨端契约.md` §4：安卓宿主注入，作用是
 * "关 Service Worker、隐藏桌面专属、切换传输层"）。
 *
 * 门控**只影响行为**，不改文案与业务语义；脚本在文档任何页面脚本之前执行
 * （`WebViewCompat.addDocumentStartJavaScript`），因此前端源码里不需要、也不允许出现安卓分支。
 * 传输层切换（`window.PIQNative` 存在即走桥）属 R3。
 */
object AndroidGate {

    const val CLASS_FLAG: String = "piq-android"

    /** 前端存会话令牌的 localStorage 键（`js/api.js` 的 `AUTH_TOKEN_KEY`，两端同一个键名）。 */
    private const val TOKEN_KEY = "menstrual_auth_token"

    /**
     * 把 `navigator.serviceWorker` 换成"注册必失败"的替身：前端 `js/app.js:94/118` 会无条件
     * 自动注册 SW，而 APK 内没有可写的 SW 存储、也不允许联网后台更新——留着一个每次注册都失败的
     * SW 只会带来 `controllerchange` → `location.reload()` 的重载风险。
     * 替身保留属性本身（`'serviceWorker' in navigator` 仍为 true），只让注册失败并静音监听。
     */
    private const val SW_STUB = """
        (function () {
            var stub = {
                register: function () { return Promise.reject(new TypeError('piq-android: service worker disabled by Android host gate')); },
                getRegistration: function () { return Promise.resolve(undefined); },
                getRegistrations: function () { return Promise.resolve([]); },
                addEventListener: function () { },
                removeEventListener: function () { },
                controller: null,
                ready: Promise.resolve(undefined)
            };
            try {
                Object.defineProperty(Navigator.prototype, 'serviceWorker', {
                    configurable: true,
                    get: function () { return stub; }
                });
            } catch (e) {
                // 静默失败会让"门控生效"与"门控没装上"在读数上不可分辨（R8 L3 的 Q-38）：
                // 宿主把 console 转成 logcat，所以这条 warn 就是判据的读数。
                console.warn('[piq-gate] sw-stub-install-failed|' + e);
            }
        })();
    """

    /**
     * 键盘遮挡的第二半（R8-A1 / R8-B3）：宿主已经把 `ime()` 并进 `--piq-inset-bottom`，
     * 但**页面不会因此把"正在输入的框"挪出键盘底下** ⇒ 必须由门控把聚焦元素滚进可视带。
     *
     * 可视带与 [PiqInsets] 同一口径（CSS 像素）：下沿 =
     * `window.innerHeight - --piq-inset-bottom - .bottom-nav 的高度`（页面没有吸底导航时退化成只减 inset）。
     * edge-to-edge 下窗口不随键盘 resize，所以 `visualViewport` 可能一次事件都不来 ⇒ 触发点必须
     * 同时挂 `focusin` / `focusout` 与宿主每次注入变量后派发的 `piq-insets-updated`；
     * 键盘动画与变量注入都落在下一帧 ⇒ 用有界重试（`MAX_FRAMES`），不用固定 sleep 赌。
     *
     * 保住的是**"聚焦框 + 同一容器内所有比它更低边的可交互元素"这一组**（`groupBottom`；
     * 吸底导航与 `fixed`/`sticky` 件除外 —— 它们本身就是那条"追不到的线"）：
     * 只按聚焦框算位移会滚 21 px、「登录」按钮仍被盖 48.3 px（R9 第 1 版就是这么 FAIL 的）；
     * 给组加"下方 240 dp 以内"的距离窗口同样会 FAIL —— 真机昵称框到「保存资料」是 251.8 dp（R9.1-F01b）。
     *
     * ⚠ 临时下留白（`setTail`）的撤销时机有三个，**每个都有实现点**：
     * ① **没有输入框被聚焦**（`pump` 空手，含 `focusout` 之后）→ `clearTailPreserving('no-focus')`；
     * ② **路由变化**（`hashchange`）→ `clearTail()`（整页换内容，不必再管滚动位置）；
     * ③ **本轮算出聚焦组已经放得下**（`attempt` 里 `group <= lim` 那条支路）或算出不再需要留白
     * （`setTail` 的 `want <= 0`）→ `clearTailPreserving(…)`。键盘收起后 `limit` 回到常态、组自然放得下，
     * 所以③就是"收起键盘 ⇒ 归还留白"的那一跳（R9.2-F01c 报红的原因正是③当初没有实现点）。
     * 撤销一律走 `clearTailPreserving`：留白是从 `paddingBottom` 加上去的，直接撤掉会让 `scrollHeight`
     * 变短、`scrollTop` 被浏览器夹回 ⇒ 页面弹起（R9.1 第 2 版实测 `scrollY 131→49.1`、按钮反被盖 20.1 px）。
     *
     * 读数可分辨（`Role/执行端.md` §5.4 ②，宿主已把 console 转成 logcat）。各条自带什么：
     * `ok` = `bottom`/`group`/`limit`/`tail`/`scrollY`；`scrolled` 与 `failed` = `frame`/`before`/`after`/
     * `limit`/`box`/`tail`/`scrollY`；`clamped` = `need`/`room`/`el`（还想滚多少 / 还能滚多少）；
     * `skipped` = `el`/`height`；`stop` = `frame`/`connected`/`stillFocused`；
     * `tail-cleared` = `reason`/`removed`/`top-before`/`top-after`/`max`/`tail`（撤销留白时页面移动了多少）；
     * `threw` = 异常原文。
     * 级别分两档：`ok`/`scrolled`/`stop`/`tail-cleared` 走 `console.info`，`failed`/`clamped`/`skipped`/`threw` 走 `console.warn` ⇒ "没滚成"只有 warn 那四类保证留红字（`stop` 不算红字；`pump` 的 `if (running) { return; }`（`:263`）不留任何读数）。
     */
    private val KEYBOARD_SCROLL = """
        (function () {
            if (window.__piqKeyboardInstalled) { return; }
            window.__piqKeyboardInstalled = true;
            var GAP = 8;
            var MAX_FRAMES = 10;
            // 聚焦后多久之内仍允许"视口滚动"事件来纠偏（过了就交还给用户自主滚动）。
            // 数值出处：键盘动画 + 宿主变量注入都在 1 s 内收敛（R9 实测两档都 ≤ 0.6 s），留一倍余量。
            var FOCUS_TRANSITION_MS = 1500;
            var TAIL = 'data-piq-keyboard-tail';
            var TAIL_BEFORE = 'data-piq-keyboard-tail-before';
            var running = false;
            var tailBox = null;

            function cssPx(name) {
                var n = parseFloat(getComputedStyle(document.documentElement).getPropertyValue(name));
                return isFinite(n) ? n : 0;
            }
            // R9.1-F01：吸底导航（.bottom-nav）排在**可视区内**、键盘线的上方，所以它和系统 inset
            // 是**相加**关系而不是包含关系 —— 两处实测同证：
            //   业务页 412×915 无键盘：nav.top=835 = 915 − 24(inset) − 56.8(nav 高)
            //   登录页 412×915 键盘弹起：limit=578 = 915 − 337(键盘 inset)，而 nav 底边正压在 537.5+
            // 旧写法只减 inset ⇒ 聚焦组会被停在导航带底下（真机实测「保存资料」被遮 128 px）。
            function bottomReserve() {
                var reserve = cssPx('--piq-inset-bottom');
                var nav = document.querySelector('.bottom-nav');
                if (!nav) { return reserve; }
                var h = nav.getBoundingClientRect().height;
                return reserve + (h > 0 ? h : 0);
            }
            function navTop() {
                var nav = document.querySelector('.bottom-nav');
                if (!nav) { return 0; }
                var r = nav.getBoundingClientRect();
                return (r.height > 0 && r.top > 0 && r.top < window.innerHeight) ? r.top : 0;
            }
            // R9.3-#20：可视带上沿取两种口径的**较小者** —— 推算值（innerHeight − 预留量）与
            // 吸底导航的实测顶边。只可能更小 ⇒ 滚动目标更保守，不会把元素停到带子底下。
            function limit() {
                var reserve = window.innerHeight - bottomReserve();
                var top = navTop();
                return (top > 0 && top < reserve) ? top : reserve;
            }
            function editable(n) {
                if (!n || n === document.body || n === document.documentElement) { return false; }
                var tag = (n.tagName || '').toLowerCase();
                if (tag !== 'input' && tag !== 'textarea' && n.isContentEditable !== true) { return false; }
                return !(n.type === 'hidden' || n.disabled || n.readOnly);
            }
            function focusedEditable() { return editable(document.activeElement) ? document.activeElement : null; }
            function scrollBox(el) {
                var n = el.parentElement;
                while (n) {
                    var s = getComputedStyle(n);
                    if (/(^|\s)(auto|scroll)(\s|$)/.test(s.overflowY) && n.scrollHeight > n.clientHeight + 1) { return n; }
                    n = n.parentElement;
                }
                return document.scrollingElement || document.documentElement;
            }
            function groupBottom(el) {
                var own = el.getBoundingClientRect().bottom;
                var scope = el.parentElement;
                while (scope) {
                    var nodes = scope.querySelectorAll('input, button, select, textarea, a');
                    var lowest = own, found = false;
                    for (var i = 0; i < nodes.length; i++) {
                        var n = nodes[i];
                        if (n === el) { continue; }
                        var r = n.getBoundingClientRect();
                        if (r.height <= 0 || n.type === 'hidden') { continue; }
                        // 吸底导航自己也是"一排在下方"的按钮组，算进聚焦组会逼着页面去追一条永远追不到的线。
                        if (isPinned(n)) { continue; }
                        // 不设距离窗口：真机实测「昵称」框底边到「保存资料」底边 = 251.8 dp，
                        // 旧的 240 窗口刚好把它排除在外 ⇒ 整格不滚、按钮被导航带全吃（可见高度 0 px）。
                        if (r.bottom > own) { lowest = Math.max(lowest, r.bottom); found = true; }
                    }
                    if (found) { return lowest; }
                    scope = scope.parentElement;
                }
                return own;
            }
            function isPinned(n) {
                if (n.closest && n.closest('.bottom-nav')) { return true; }
                var s = getComputedStyle(n);
                return s.position === 'fixed' || s.position === 'sticky';
            }
            function tailNow() {
                if (!tailBox) { return 0; }
                var v = parseFloat(tailBox.getAttribute(TAIL));
                return isFinite(v) ? v : 0;
            }
            function clearTail() {
                if (!tailBox) { return; }
                tailBox.style.paddingBottom = tailBox.getAttribute(TAIL_BEFORE) || '';
                tailBox.removeAttribute(TAIL);
                tailBox.removeAttribute(TAIL_BEFORE);
                tailBox = null;
            }
            function setTail(box, want) {
                if (tailBox && tailBox !== box) { clearTail(); }
                if (!tailBox) { box.setAttribute(TAIL_BEFORE, box.style.paddingBottom || ''); }
                tailBox = box;
                if (want <= 0) { clearTailPreserving('recomputed-zero'); return; }
                var base = parseFloat(box.getAttribute(TAIL_BEFORE));
                box.style.paddingBottom = ((isFinite(base) ? base : 0) + want) + 'px';
                box.setAttribute(TAIL, String(Math.round(want)));
            }
            /**
             * 撤销临时留白，并且**不把页面弹起来**（R9.3-F01c 的硬约束）。
             *
             * 留白是从 `paddingBottom` 加上去的，撤掉它会让 `scrollHeight` 变短；若此刻 `scrollTop`
             * 已经落在新的上限之外，浏览器会自己夹回 —— 那就是"卡片下方空白没了、可页面弹回顶部"
             * （R9.1 第 2 版栽在这里，见本函数上方 KDoc）。所以撤销前先把 `scrollTop` 收到**新上限**
             * 之内：内容位置最多移动"留白那么多"，绝不会跳到顶。
             */
            function clearTailPreserving(reason) {
                if (!tailBox) { return; }
                var box = tailBox;
                var removed = tailNow();
                var before = box.scrollTop;
                clearTail();
                var max = Math.max(0, box.scrollHeight - box.clientHeight);
                var want = Math.min(before, max);
                if (Math.abs(box.scrollTop - want) > 0.5) { box.scrollTop = want; }
                console.info('[piq-keyboard] tail-cleared|reason=' + reason + '|removed=' + removed +
                    '|top-before=' + before.toFixed(1) + '|top-after=' + box.scrollTop.toFixed(1) +
                    '|max=' + max.toFixed(1) + '|tail=' + tailNow());
            }
            function attempt(el, reason, frame) {
                if (!el.isConnected || document.activeElement !== el) {
                    console.info('[piq-keyboard] stop|reason=' + reason + '|frame=' + frame +
                        '|connected=' + el.isConnected + '|stillFocused=' + (document.activeElement === el));
                    return;
                }
                var box = scrollBox(el);
                var lim = limit();
                var ownRect = el.getBoundingClientRect();
                if (ownRect.height <= 0) {
                    console.warn('[piq-keyboard] skipped|reason=' + reason + '|el=' + (el.id || el.tagName) + '|height=0');
                    return;
                }
                var group = groupBottom(el);
                if (group <= lim) {
                    // 第三个撤销时机（R9.2-F01c）：本轮算出"聚焦组已经放得下"⇒ 临时留白不再需要。
                    // 键盘收起后 limit 回到常态、group 自然放得下，这一支就是 tail 的归还点。
                    if (tailBox) { clearTailPreserving('group-fits'); }
                    console.info('[piq-keyboard] ok|reason=' + reason + '|bottom=' + ownRect.bottom.toFixed(1) +
                        '|group=' + group.toFixed(1) + '|limit=' + lim.toFixed(1) + '|tail=' + tailNow() + '|scrollY=' + window.scrollY.toFixed(1));
                    return;
                }
                var deficit = group - lim + GAP;
                var free = box.scrollHeight - (box.scrollTop + box.clientHeight);
                if (free < deficit) { setTail(box, tailNow() + (deficit - free)); }
                // 预留量加上导航带之后滚动距离会变长，故给"聚焦框不被顶出上沿"留一道界：
                // 再滚这么多就会把正在输入的那一格顶到状态栏留白之上，那是更难发现的回归。
                var scrollRoom = Math.max(0, ownRect.top - cssPx('--piq-inset-top') - GAP);
                if (deficit > scrollRoom) {
                    console.warn('[piq-keyboard] clamped|reason=' + reason + '|need=' + deficit.toFixed(1) +
                        '|room=' + scrollRoom.toFixed(1) + '|el=' + (el.id || el.tagName));
                }
                var to = box.scrollTop + Math.min(deficit, scrollRoom);
                if (typeof box.scrollTo === 'function') {
                    box.scrollTo({top: to, behavior: 'instant'});
                } else {
                    var priorBehavior = box.style.scrollBehavior;
                    box.style.scrollBehavior = 'auto';
                    box.scrollTop = to;
                    box.style.scrollBehavior = priorBehavior;
                }
                var after = groupBottom(el);
                if (after <= limit() + 0.5) {
                    console.info('[piq-keyboard] scrolled|reason=' + reason + '|frame=' + frame +
                        '|before=' + group.toFixed(1) + '|after=' + after.toFixed(1) + '|limit=' + limit().toFixed(1) +
                        '|box=' + (box.id || box.tagName) + '|tail=' + tailNow() + '|scrollY=' + window.scrollY.toFixed(1));
                } else if (frame < MAX_FRAMES) {
                    requestAnimationFrame(function () { attempt(el, reason, frame + 1); });
                } else {
                    console.warn('[piq-keyboard] failed|reason=' + reason + '|frame=' + frame +
                        '|before=' + group.toFixed(1) + '|after=' + after.toFixed(1) + '|limit=' + limit().toFixed(1) +
                        '|box=' + (box.id || box.tagName) + '|tail=' + tailNow() + '|scrollY=' + window.scrollY.toFixed(1));
                }
            }
            function pump(reason) {
                if (running) { return; }
                running = true;
                requestAnimationFrame(function () {
                    running = false;
                    var el = focusedEditable();
                    if (!el) { clearTailPreserving('no-focus'); return; }
                    try { attempt(el, reason, 0); }
                    catch (e) { console.warn('[piq-keyboard] threw|reason=' + reason + '|' + e); }
                });
            }
            var lastFocusAt = 0;
            document.addEventListener('focusin', function () { lastFocusAt = Date.now(); pump('focusin'); });
            document.addEventListener('focusout', function () { pump('focusout'); });
            window.addEventListener('hashchange', function () { clearTail(); });
            if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', function () { pump('viewport-resize'); });
                window.visualViewport.addEventListener('scroll', function () {
                    // 提示词要求听 scroll，但键盘期间的用户自主滚动不得被拽回（L2-R1-02）
                    // ⇒ 只在聚焦后的过渡窗内响应，之后交给用户。
                    if (Date.now() - lastFocusAt <= FOCUS_TRANSITION_MS) { pump('viewport-scroll'); }
                });
            } else {
                console.warn('[piq-keyboard] visualViewport-absent');
            }
            window.addEventListener('${PiqInsets.EVENT_NAME}', function () { pump('insets-event'); });
        })();
    """

    /**
     * 自动登录的令牌播种（契约 §7.2 启动序里"静默登录"的那一步，属**行为**不改文案）：
     * 宿主机在进程启动时采用了加密存储里的有效会话 ⇒ 把同一枚令牌写进页内的
     * `localStorage[menstrual_auth_token]`，前端按它自己的既有逻辑（`api.hasToken()`）直接进主页。
     *
     * 为什么必须宿主机播种、不能只靠"上一次登录留下的令牌"：开关关闭那次启动，
     * 端点会回 401，前端自己的 401 处理就会把 localStorage 里的令牌清掉（这是正确行为）；
     * 之后再打开开关并重启，令牌只在加密存储里，页内已经没有 ⇒ 不播种就会看到登录页，
     * 与"开启后下次启动静默登录"的要求相反（R3 真机实测就是这个形态）。
     *
     * ⚠ 令牌文本只进这段内存里执行的脚本，**绝不落日志**；`JSONObject.quote` 负责转义，
     * 避免令牌/异常内容拼出可执行片段。
     */

    fun tokenSeed(token: String?): String {
        if (token.isNullOrEmpty()) return ""
        val key = org.json.JSONObject.quote(TOKEN_KEY)
        val value = org.json.JSONObject.quote(token)
        return """
            try { if (!localStorage.getItem($key)) localStorage.setItem($key, $value); }
            catch (e) { console.warn('[piq-gate] token-seed-failed|' + e); }
        """.trimIndent()
    }

    /**
     * 文档起始处一次注入的五段：SW 替身 → `piq-android` 标记 → 键盘滚动 → 版本串重写 → 令牌播种。
     *
     * 版本串那一段在 `appVersion` 为空时整段省略（[versionRewrite]），其余四段与 R10 同形。
     */
    fun documentStartScript(seedToken: String? = null, appVersion: String? = null): String = """
        $SW_STUB
        try { document.documentElement.classList.add('$CLASS_FLAG'); }
        catch (e) { console.warn('[piq-gate] class-flag-failed|门控未生效|' + e); }
        $KEYBOARD_SCROLL
        ${versionRewrite(appVersion)}
        ${tokenSeed(seedToken)}
    """.trimIndent()

    /**
     * 页内版本串的重写（R10.1-VER）。
     *
     * 快照 `js/app.js:13` 的 `APP_VERSION` 是**桌面前端**的版本串，两处「关于」卡片渲染它
     * （`:636`、`:2229`）。真机 1.1.4 实测读到的是 `版本：v0.22.9` ⇒ 用户在这一行看到的版本
     * 与装机的 APK 对不上。宿主按 [appVersion]（`versionName`）把**渲染结果**改对，不动快照源码。
     *
     * ⚠ 这是门控里唯一一处触碰文案的行为，依据是 R10.1 提示词 §1.5 方案 ①（顾问裁定）；
     *   契约 §4 的"门控只影响行为"其余部分照旧 —— 只改这一行的**值**，不改任何语句与业务语义。
     *
     * 形态：只改**以 `版本：v` 开头**的文本节点，且只在它与目标串不同时才改 ⇒ 幂等，重复触发
     * 只把 `changed` 报 0。读数是 `console.info` 那一行的 `changed=` 与 `want=`
     * （`changed=0` 表示"这一页没有这一行"或"已经是对的"，两种都不是失败）。
     * 用 `TreeWalker` 而不是 `.about-text div` 这类快照类名：宿主不该依赖页面的 class。
     */
    fun versionRewrite(appVersion: String?): String {
        if (appVersion.isNullOrEmpty()) return ""
        val want = org.json.JSONObject.quote("$VERSION_TEXT_PREFIX$appVersion")
        return """
            (function () {
                if (window.__piqVersionInstalled) { return; }
                window.__piqVersionInstalled = true;
                var PREFIX = ${org.json.JSONObject.quote(VERSION_TEXT_PREFIX)};
                var WANT = $want;
                function rewrite() {
                    var changed = 0;
                    try {
                        var walker = document.createTreeWalker(document, NodeFilter.SHOW_TEXT);
                        var n;
                        while ((n = walker.nextNode())) {
                            var raw = n.nodeValue;
                            if (typeof raw !== 'string') { continue; }
                            if (raw.trim().indexOf(PREFIX) === 0 && raw.trim() !== WANT) {
                                n.nodeValue = WANT;
                                changed++;
                            }
                        }
                    } catch (e) {
                        console.warn('[piq-gate] version-rewrite-threw|' + e);
                        return;
                    }
                    console.info('[piq-gate] version-rewrite|changed=' + changed + '|want=' + WANT);
                }
                function pump(reason) {
                    requestAnimationFrame(function () {
                        console.info('[piq-gate] version-pump|reason=' + reason);
                        rewrite();
                    });
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function () { pump('ready'); });
                } else {
                    pump('already');
                }
                // 前端是 hash 单页应用：切路由是重渲染这一行的唯一时机，document-start 时它还不存在。
                window.addEventListener('hashchange', function () { pump('hashchange'); });
            })();
        """.trimIndent()
    }

    /** 页内「关于」卡片里那一行的前缀（快照 `js/app.js:636/2229` 的实际文本，含全角冒号）。 */
    private const val VERSION_TEXT_PREFIX = "版本：v"
}
