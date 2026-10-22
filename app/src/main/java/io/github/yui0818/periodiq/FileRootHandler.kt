package io.github.yui0818.periodiq

import android.util.Log
import android.webkit.WebResourceResponse
import java.io.File
import java.io.IOException

private const val FILE_TAG = "PIQ"

/**
 * 把 `files/themes/<id>/` 下的**已安装主题包**当作资产根对外提供（契约 §7.1 的安装位置）。
 *
 * 与 [AssetRootHandler] 保持同一套对外语义：缺文件返回 **404**（不放行网络）、禁缓存、
 * 同一行 `serve|...` 日志；差别只在取流方式。
 * 根外的路径一律 404 —— 请求来自 WebView 里的第三方脚本，`../` 越界不得读到应用私有文件。
 */
class FileRootHandler(
    private val dir: File,
    private val label: String,
) : PiqRootSource {

    override fun rootLabel(): String = label

    override fun handle(path: String): WebResourceResponse {
        val target = resolve(path)
        if (target == null) {
            Log.w(FILE_TAG, "serve|prefix=$label|req=$path|status=404|reason=out-of-root")
            return notFound(path)
        }
        return try {
            val stream = target.inputStream()
            val mime = AssetRootHandler.mimeOf(target.name)
            Log.i(FILE_TAG, "serve|prefix=$label|req=$path|file=${target.path}|mime=$mime|status=200")
            WebResourceResponse(mime, AssetRootHandler.encodingOf(mime), 200, "OK", NO_STORE, stream)
        } catch (e: IOException) {
            Log.w(FILE_TAG, "serve|prefix=$label|req=$path|status=500|err=${e.message}")
            WebResourceResponse("text/plain", "utf-8", 500, "Internal Error", NO_STORE, ByteArray(0).inputStream())
        }
    }

    /** 越界判定只用 canonicalPath：候选不在 `dir` 子树内就返回 null，由两个调用点据此出 **404**（不是"落在根外才放行"）；`%2e%2e` 的解码发生在 loader 侧，本函数内没有那个实现点。 */
    private fun resolve(path: String): File? {
        if (!dir.isDirectory) return null
        val root = dir.canonicalPath
        val candidate = runCatching { File(dir, path.trimStart('/')).canonicalPath }.getOrNull() ?: return null
        if (candidate != root && !candidate.startsWith(root + File.separator)) return null
        return File(candidate).takeIf { it.isFile }
    }

    private fun notFound(path: String): WebResourceResponse {
        Log.w(FILE_TAG, "serve|prefix=$label|req=$path|status=404")
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArray(0).inputStream())
    }

    companion object {
        private val NO_STORE = mapOf("Cache-Control" to "no-store, no-cache, must-revalidate")
    }
}
