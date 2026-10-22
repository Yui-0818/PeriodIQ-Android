package io.github.yui0818.periodiq

import java.io.File

/**
 * 生产源码文本守卫的共用读数器（R13.1 F12）。
 *
 * `code()` 返回**去掉注释与字符串/字符字面量**之后的源码（保留换行与其余字符位置），
 * 这样 `grep` 型守卫不会被"注释里写着正确代码"或"字符串里抄了一遍"骗过。
 * 只服务测试源码集，不进入 APK。
 */
internal object ProductionSource {

    fun read(fileName: String): String =
        mainSourceDir().walkTopDown()
            .firstOrNull { it.isFile && it.name == fileName }
            ?.readText()
            ?: throw IllegalStateException("生产源码里找不到 $fileName，源守卫已失效")

    fun code(fileName: String): String = strip(read(fileName))

    /** 只去注释、保留字符串字面量：用来钉含字段名/日志名的接线（字符串里的注释符不再是陷阱）。 */
    fun commentsRemoved(fileName: String): String = strip(read(fileName), removeStrings = false)

    /** 从去注释源码里截一个函数体（下一个 4 空格或顶层声明为止），供顺序/条件型源守卫使用。 */
    fun functionBody(fileName: String, signature: String): String {
        val code = commentsRemoved(fileName)
        val start = code.indexOf(signature)
        require(start >= 0) { "找不到 $signature，源守卫已失效" }
        val next = Regex("""(?m)^(?: {4}(?:private |internal |override |fun |class |inner )|fun |internal |private |object |class )""")
            .find(code, start + signature.length)?.range?.first
        return code.substring(start, next ?: code.length)
    }

    /** Kotlin 块注释可嵌套；字符串里的 `//` 不是注释 —— 两种都按状态机处理。 */
    fun strip(text: String, removeStrings: Boolean = true): String {
        val out = StringBuilder(text.length)
        var i = 0
        var blockDepth = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            if (blockDepth > 0) {
                when {
                    c == '/' && next == '*' -> { blockDepth += 1; out.append("  "); i += 2 }
                    c == '*' && next == '/' -> { blockDepth -= 1; out.append("  "); i += 2 }
                    else -> { out.append(if (c == '\n' || c == '\r') c else ' '); i += 1 }
                }
                continue
            }
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') { out.append(' '); i += 1 }
                }
                c == '/' && next == '*' -> { blockDepth = 1; out.append("  "); i += 2 }
                c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"' -> {
                    out.append(if (removeStrings) "   " else "\"\"\"")
                    i += 3
                    while (i < text.length) {
                        if (text[i] == '"' && i + 2 < text.length &&
                            text[i + 1] == '"' && text[i + 2] == '"'
                        ) {
                            out.append(if (removeStrings) "   " else "\"\"\"")
                            i += 3
                            break
                        }
                        out.append(
                            if (!removeStrings) text[i]
                            else if (text[i] == '\n' || text[i] == '\r') text[i] else ' ',
                        )
                        i += 1
                    }
                }
                c == '"' -> {
                    out.append(if (removeStrings) ' ' else c)
                    i += 1
                    while (i < text.length) {
                        when {
                            text[i] == '\\' && i + 1 < text.length -> {
                                out.append(if (removeStrings) "  " else text.substring(i, i + 2))
                                i += 2
                            }
                            text[i] == '"' -> { out.append(if (removeStrings) ' ' else '"'); i += 1; break }
                            else -> {
                                out.append(
                                    if (!removeStrings) text[i]
                                    else if (text[i] == '\n' || text[i] == '\r') text[i] else ' ',
                                )
                                i += 1
                            }
                        }
                    }
                }
                c == '\'' -> {
                    out.append(if (removeStrings) ' ' else c)
                    i += 1
                    while (i < text.length) {
                        when {
                            text[i] == '\\' && i + 1 < text.length -> {
                                out.append(if (removeStrings) "  " else text.substring(i, i + 2))
                                i += 2
                            }
                            text[i] == '\'' -> { out.append(if (removeStrings) ' ' else '\''); i += 1; break }
                            else -> {
                                out.append(
                                    if (!removeStrings) text[i]
                                    else if (text[i] == '\n' || text[i] == '\r') text[i] else ' ',
                                )
                                i += 1
                            }
                        }
                    }
                }
                else -> { out.append(c); i += 1 }
            }
        }
        return out.toString()
    }

    private fun mainSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (rel in listOf("src/main/java", "app/src/main/java")) {
                val candidate = File(dir, rel)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("未找到生产源码目录，源守卫无法执行（cwd=${System.getProperty("user.dir")}）")
    }
}
