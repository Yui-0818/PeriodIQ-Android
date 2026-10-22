package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.Envelope
import io.github.yui0818.periodiq.api.contract.genericFailure

import android.content.res.AssetManager
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

private const val TAG = "PIQ"

/** 本文件用的 utf-8 常量。⚠ 不是全仓一处：`FileRootHandler.kt:37`/`:52` 写字面量 `"utf-8"`；两份 `NO_STORE` 也各在各自文件里且都是 private（互相看不见），所以"共用"在可见性上也不成立。 */
private const val UTF8 = "utf-8"

/**
 * 禁缓存：否则换根重载会被 WebView 用旧响应兜住，"切换后标题不变"这类 FAIL 读不出来。
 * `/api` 前缀下的响应也必须带上——路由回答的是**当前**数据，缓存它等于给页面看旧账。
 */
private val NO_STORE = mapOf("Cache-Control" to "no-store, no-cache, must-revalidate")

private const val ONE_BYTE_LIMIT = 0x80
private const val TWO_BYTE_LIMIT = 0x800
private val HIGH_SURROGATE = 0xD800..0xDBFF
private val LOW_SURROGATE = 0xDC00..0xDFFF

/**
 * UTF-8 字节数，按码点逐个累加，**不分配字节数组**：导入体实测可达 12 MB 量级，
 * 为一条日志再复制一份等长数组不值。
 *
 * 与 `toByteArray(Charsets.UTF_8).size` 逐值同形（`Utf8ByteLengthTest` 用 12 个种子的随机串
 * 与编码器对照）：代理对 4 字节；**落单的代理 1 字节** —— JDK 21 实测编码器把不可映射的
 * 代理替换成单个 `?`（`"\uD800"` → 1 B、`"\uDC00\u5468"` → 4 B），不是 3 字节的 U+FFFD。
 * 这个数只进日志，不参与任何协议判定。
 */
internal fun String.utf8ByteLength(): Int {
    var total = 0
    var index = 0
    while (index < length) {
        val code = this[index].code
        when {
            code < ONE_BYTE_LIMIT -> total += 1
            code < TWO_BYTE_LIMIT -> total += 2
            code in HIGH_SURROGATE -> {
                val paired = index + 1 < length && this[index + 1].code in LOW_SURROGATE
                total += if (paired) 4 else 1
                if (paired) index += 1
            }

            code in LOW_SURROGATE -> total += 1

            else -> total += 3
        }
        index += 1
    }
    return total
}

/**
 * 把一个虚拟路径前缀映射到 assets 下的**某个资产根**（`docs/契约/跨端契约.md` §7.1）。
 *
 * 加载层不写死目录（AGENTS.md §1.1 第 1 条）：[prefix] 只由构造参数传入、**文件内没有写入点** ⇒ 换根的实际做法是 `MainActivity.bindThemeRoot()` 新建 handler 并重建 `WebViewAssetLoader`（恰恰要重建）。
 * 取不到资产时返回 **404**（而不是放行到网络）——本路线不申请 `INTERNET`，
 * 放行等于把"缺文件"伪装成"网络失败"，Spike 的退化态判据将读不到数。
 */
class AssetRootHandler(
    private val assets: AssetManager,
    var prefix: String,
) : PiqRootSource {

    override fun rootLabel(): String = prefix

    override fun handle(path: String): WebResourceResponse {
        val assetPath = join(prefix, path)
        return try {
            // 先取流再打日志：否则缺文件时同一请求会先落一行 status=200 再落 404（R1 报告 §6-9）
            val stream = assets.open(assetPath)
            val mime = mimeOf(assetPath)
            Log.i(TAG, "serve|prefix=$prefix|req=$path|asset=$assetPath|mime=$mime|status=200")
            WebResourceResponse(mime, encodingOf(mime), 200, "OK", NO_STORE, stream)
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "serve|prefix=$prefix|req=$path|asset=$assetPath|status=404")
            WebResourceResponse("text/plain", UTF8, 404, "Not Found", NO_STORE, ByteArray(0).inputStream())
        } catch (e: IOException) {
            Log.w(TAG, "serve|prefix=$prefix|req=$path|asset=$assetPath|status=500|err=${e.message}")
            WebResourceResponse("text/plain", UTF8, 500, "Internal Error", NO_STORE, ByteArray(0).inputStream())
        }
    }

    companion object {

        /** 根前缀与请求路径拼成资产相对路径：两段多余的前导/衔接斜杠一律归并。跨文件调用点除生产侧 `ThemeResolver` 外，测试侧还有 `VariantPolicyTest.kt:49-52` 四处直接调用；`FileRootHandler` 只复用 `mimeOf`/`encodingOf`，不调本函数。 */
        fun join(prefix: String, path: String): String {
            val head = prefix.trimStart('/')
            val tail = path.trimStart('/')
            return when {
                head.isEmpty() -> tail
                head.endsWith("/") -> head + tail
                else -> "$head/$tail"
            }
        }

        /** 按路径末段扩展名（大小写不敏感）给出 MIME 字符串；未知或无扩展名一律 application/octet-stream。 */
        fun mimeOf(assetPath: String): String = when (assetPath.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

        /** 由 MIME 推响应编码：以 text 开头的、或 application/json，取 utf-8；其余按 binary 交给 WebView。 */
        fun encodingOf(mime: String): String =
            if (mime.startsWith("text/") || mime == "application/json") UTF8 else "binary"
    }
}

/**
 * 一条"把请求送进本机路由"的通道，形态与 JS 桥一致：`request(method, path, body) -> Envelope`
 * （`docs/契约/跨端契约.md` §5）。用函数而非具体类，是为了让装配方（`MainActivity`）
 * 保持**惰性**：DB 建表与 `Python.start` 只在该通道真的被调用时才发生。
 */
fun interface PiqApiGateway {
    fun request(method: String, path: String, body: String?): Envelope
}

/**
 * 根兜底处理器：`api/` 前缀下的路径**一律交给本机路由**，其余路径转给当前资产根。
 *
 * 为什么不能再"交回网络栈"（R6 F1 的缺陷，`docs/契约/跨端契约.md` §5 订正块）：
 * 本项目不申请 `INTERNET`，放行到网络栈必然 `net::ERR_CACHE_MISS`，
 * 于是"端上其实有实现"被伪装成"取回失败"——实测一次冷启就把这条失败刷了 16 遍（每 3 秒重连一次）；
 * 而任何**没走桥**的前端调用一旦因此把离线位置真，导入/导出整套按钮就会 `disabled`
 * （那条致灰链本轮在基线件上用合成 `offline` 事件实测过：`isOffline=true` 同帧
 * `importDisabled=true|exportDisabled=true`）。
 * **只有路由自己判定未实现的路径**才允许 501 + `implemented=false`。
 *
 * ⚠ 这条通道只服务**非 fetch 形态**的资源请求（EventSource、`<img>`、直接 `fetch`）：
 * `WebViewAssetLoader.PathHandler.handle()` 只给路径，androidx.webkit 1.14.0 实测**拿不到
 * HTTP 方法与请求体** ⇒ 一律按 `GET` 送进路由；写操作（POST/PUT/DELETE）都在
 * `PIQNative.request(method, path, body)` 的 JS 桥上显式传方法与体，不走这里。
 */
class ApiGatewayHandler(
    private val delegate: PiqRootSource,
    private val gateway: PiqApiGateway,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        if (!path.startsWith(API_PREFIX)) return delegate.handle(path)
        // PathHandler 收到的是去掉前导斜杠的路径，而路由吃的是桌面 REST 绝对路径
        val rest = "/$path"
        val envelope = try {
            gateway.request(METHOD_GET, rest, null)
        } catch (e: Throwable) {
            Log.w(TAG, "api-gateway|path=$rest|type=${e.javaClass.name}|message=${e.message}")
            genericFailure(500, "BRIDGE_ERROR")
        }
        // HTTP 响应体 = 桌面 REST 响应体本身（信封里的 json 那层），与桥形态同构但不套壳
        val body = envelope.json.toString()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        // `chars` 是页内字符串长度，`bytes` 是真正写进响应流的 UTF-8 字节数：
        // 中文体一字符占三字节，只记一个数会把体积读小三倍。
        Log.i(TAG, "api-gateway|path=$rest|method=$METHOD_GET|status=${envelope.status}|handled=router|bytes=${bodyBytes.size}|chars=${body.length}")
        return WebResourceResponse(
            JSON_MIME, UTF8, envelope.status, reasonOf(envelope.status), NO_STORE,
            ByteArrayInputStream(bodyBytes),
        )
    }

    companion object {
        private const val API_PREFIX = "api/"
        private const val METHOD_GET = "GET"
        private const val JSON_MIME = "application/json"

        fun reasonOf(status: Int): String = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            500 -> "Internal Error"
            501 -> "Not Implemented"
            else -> "Response"
        }
    }
}
