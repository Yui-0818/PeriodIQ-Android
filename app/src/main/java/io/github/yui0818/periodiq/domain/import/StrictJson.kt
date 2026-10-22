package io.github.yui0818.periodiq.domain.import

/**
 * **严格** JSON 文法检查（RFC 8259 语法 + 与桌面 Python `json.loads` 的实测对齐）。
 *
 * 为什么需要它：`org.json` 的 `JSONObject(String)` 是宽松的，实测接受
 * 尾随垃圾 / 单引号字符串 / `//` 与块注释 / 十六进制数 / 前导零 / 未加引号的键，
 * 而桌面 `json.loads`（Python 3.11.15 实测，11 种形态的接受集见 R9 报告 §3）**全部拒绝**
 * ⇒ 同一份文件两端答案不同
 * （R8 的 REG-03）。本类只回答"像不像一份合法 JSON 文档"，真正的解析仍交给 `org.json`
 * （不新增依赖，`AGENTS.md` §7 第 7 条）。
 *
 * 两条口径与它的**净效果**（都由 `ImportBodyGate` 决定最终答案）：
 *  1. `NaN` / `Infinity` / `-Infinity` 在本文法里**放行**（桌面 `json.loads` 默认放行）。
 *     ⚠ R9 设备实测：装机 WebView 的 `org.json` **拒**这两个字面量 ⇒ 安卓最终回 422，
 *     净效果比桌面**严一档**。这一档不挡住任何真实备份：页内 `JSON.stringify` 把 `NaN`
 *     写成 `null`，两端导出器都不可能产出带裸 `NaN` 的备份文件（读数见 R9 报告 §3 的
 *     `libcore-NaN` / `libcore-Infinity` 两行）。
 *  2. **BOM 不在这里管**：调用方先剥 BOM 再校验（安卓比桌面宽一档，桌面 `json.loads`
 *     对带 BOM 的串报错）。两条差异都登记在 `docs/契约/跨端契约.md` 的 R9 追加节。
 *
 * 顶层允许标量（Python 也允许）；"是不是对象"由调用方按错误档位给文案。
 */
object StrictJson {

    /** 首个非法字符的下标；**-1 表示整份文档语法合法**。 */
    fun firstSyntaxError(text: String): Int {
        val cursor = Cursor(text)
        cursor.skipSpace()
        if (cursor.atEnd()) return cursor.index
        if (!value(cursor, 0)) return cursor.index
        cursor.skipSpace()
        // 值之后只允许空白（桌面对应 `Extra data`）
        return if (cursor.atEnd()) -1 else cursor.index
    }

    /**
     * 容器嵌套上限：`[[[[…` 这种 8 MiB 的深嵌套若一路递归下去会 `StackOverflowError`，
     * 被路由的兜底 catch 收成 500 `INTERNAL_ERROR` —— 用户读到的是"本机出了点问题"而不是
     * "这份文件不合格"（L2 第 1 轮）。超深度即判非法 ⇒ 落到可读的 422 档。
     */
    private const val MAX_DEPTH = 100

    /** 顶层值的种类（只给"是 JSON 但不是对象"那一档文案取名，不参与解析）。 */
    fun topLevelKind(text: String): String {
        val cursor = Cursor(text)
        cursor.skipSpace()
        return when (cursor.peek()) {
            '{' -> "对象"
            '[' -> "数组"
            '"' -> "字符串"
            't' -> "字面量 true"
            'f' -> "字面量 false"
            'n' -> "字面量 null"
            null -> "空文档"
            else -> "数字或 NaN/Infinity"
        }
    }

    private fun value(cursor: Cursor, depth: Int): Boolean = when (cursor.peek()) {
        '{' -> objectValue(cursor, depth)
        '[' -> arrayValue(cursor, depth)
        '"' -> stringValue(cursor)
        else -> literalOrNumber(cursor)
    }

    private fun objectValue(cursor: Cursor, depth: Int): Boolean {
        if (depth >= MAX_DEPTH) return false
        cursor.advance() // '{'
        cursor.skipSpace()
        if (cursor.consume('}')) return true
        while (true) {
            cursor.skipSpace()
            if (cursor.peek() != '"') return false
            if (!stringValue(cursor)) return false
            cursor.skipSpace()
            if (!cursor.consume(':')) return false
            cursor.skipSpace()
            if (!value(cursor, depth + 1)) return false
            cursor.skipSpace()
            if (cursor.consume('}')) return true
            if (!cursor.consume(',')) return false
        }
    }

    private fun arrayValue(cursor: Cursor, depth: Int): Boolean {
        if (depth >= MAX_DEPTH) return false
        cursor.advance() // '['
        cursor.skipSpace()
        if (cursor.consume(']')) return true
        while (true) {
            cursor.skipSpace()
            if (!value(cursor, depth + 1)) return false
            cursor.skipSpace()
            if (cursor.consume(']')) return true
            if (!cursor.consume(',')) return false
        }
    }

    /** 字符串必须双引号；转义只认 JSON 的八种加 `uXXXX`（单引号串、`x41` 之类一律非法）。 */
    private fun stringValue(cursor: Cursor): Boolean {
        if (!cursor.consume('"')) return false
        while (true) {
            val c = cursor.next() ?: return false
            when {
                c == '"' -> return true
                c == '\\' -> {
                    when (val esc = cursor.next() ?: return false) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            repeat(4) {
                                val hex = cursor.next()
                                if (hex == null || !isHexDigit(hex)) return false
                            }
                        }
                        else -> return false
                    }
                }
                c < ' ' -> return false
            }
        }
    }

    private fun literalOrNumber(cursor: Cursor): Boolean {
        if (cursor.consumeWord("true") || cursor.consumeWord("false") || cursor.consumeWord("null")) return true
        if (cursor.consumeWord("NaN") || cursor.consumeWord("Infinity")) return true
        val start = cursor.index
        if (cursor.consume('-') && cursor.consumeWord("Infinity")) return true
        cursor.resetTo(start)
        return number(cursor)
    }

    /**
     * 数字：`-?(0|非零开头的一串数字)(. 小数)?(e 指数)?`。
     * 十六进制 `0x1f`、前导零 `01`、`.5`、`1.`、`+1` 都非法（与桌面实测一致）。
     */
    private fun number(cursor: Cursor): Boolean {
        val start = cursor.index
        if (cursor.peek() == '-') cursor.advance()
        if (cursor.peek() == '0') {
            cursor.advance()
        } else if (!digits(cursor)) {
            cursor.resetTo(start)
            return false
        }
        if (cursor.peek() == '.') {
            cursor.advance()
            if (!digits(cursor)) {
                cursor.resetTo(start)
                return false
            }
        }
        if (cursor.peek() == 'e' || cursor.peek() == 'E') {
            cursor.advance()
            if (cursor.peek() == '+' || cursor.peek() == '-') cursor.advance()
            if (!digits(cursor)) {
                cursor.resetTo(start)
                return false
            }
        }
        return cursor.index > start
    }

    private fun digits(cursor: Cursor): Boolean {
        val begin = cursor.index
        while (cursor.peek()?.let { it in '0'..'9' } == true) cursor.advance()
        return cursor.index > begin
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** 只前进的游标；候选失败由调用方用 [Cursor.resetTo] 回退。 */
    private class Cursor(private val text: String) {
        var index: Int = 0

        fun atEnd(): Boolean = index >= text.length
        fun peek(): Char? = if (atEnd()) null else text[index]
        fun next(): Char? = if (atEnd()) null else text[index++]
        fun advance() { if (!atEnd()) index++ }
        fun resetTo(position: Int) { index = position }

        /** Python 只认这四个空白（其余控制字符不算空白）。 */
        fun skipSpace() {
            while (true) {
                val c = peek() ?: return
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return
                index++
            }
        }

        fun consume(c: Char): Boolean = if (peek() == c) { index++; true } else false

        fun consumeWord(word: String): Boolean {
            if (!text.startsWith(word, index)) return false
            index += word.length
            return true
        }
    }
}
