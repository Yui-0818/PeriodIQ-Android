package io.github.yui0818.periodiq

import androidx.webkit.WebViewAssetLoader

/**
 * 一个「资产根」的取回器：`/theme/` 前缀与根兜底 `/` 共用同一个实例（契约 §7.1 的两条映射）。
 *
 * 存在它是因为主题根有两种位置（内置 `assets/`、安装到 `files/themes/<id>/`），
 * 让 [ApiGatewayHandler] 与 loader 不必关心具体是哪一种。
 */
interface PiqRootSource : WebViewAssetLoader.PathHandler {

    /** 日志里标识当前根：assets 前缀或安装目录的相对写法。 */
    fun rootLabel(): String
}
