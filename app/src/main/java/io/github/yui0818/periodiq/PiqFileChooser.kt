package io.github.yui0818.periodiq

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast

/**
 * 宿主文件选择通道：把 WebView 里 `<input type=file>` 的选择请求接到系统文件选择器，
 * 再把选中的 `content://` URI 回填给 WebView（R6 缺失的能力 —— 当时点了没有任何反应）。
 *
 * **零权限**：`ACTION_GET_CONTENT` / `ACTION_OPEN_DOCUMENT` 由系统选择器代持读授权，
 * 返回的 URI 自带一次性读权限 ⇒ 本工程不声明 `READ_EXTERNAL_STORAGE`
 * 或 `MANAGE_EXTERNAL_STORAGE`（`AGENTS.md` §5）。
 *
 * **同时只保留一个挂起请求**：WebView 在收到 `onReceiveValue` 之前不会再允许下一次选择，
 * 所以新请求到来时先把旧回调以 `null` 收尾（否则前端永久等待 + 回调泄漏）。
 *
 * 落点在 `Activity.startActivityForResult` 而非 `registerForActivityResult`：后者要求
 * `androidx.activity.ComponentActivity`，而本工程只依赖 `androidx.webkit`（加依赖超出本轮白名单）。
 */
class PiqFileChooser(private val activity: Activity) {

    private var pending: ValueCallback<Array<Uri>>? = null

    /**
     * 发起一次选择。返回 `false` 表示没有应用可承接该意图（此时回调已以 `null` 收尾，
     * WebView 不会挂起）。
     */
    fun open(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        closePending()
        val intent = params.createIntent()
        val multiple = isMultipleMode(params)
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val accept = params.acceptTypes.joinToString(",")
        pending = callback
        return try {
            activity.startActivityForResult(intent, REQUEST_CODE)
            Log.i(TAG, "file-chooser|open|accept=[$accept]|multiple=$multiple|action=${intent.action}")
            true
        } catch (e: ActivityNotFoundException) {
            closePending()
            Log.w(TAG, "file-chooser|open-rejected|accept=[$accept]|action=${intent.action}|reason=${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 把选择结果回填给 WebView：取消（含系统侧任何非 OK 结果）一律回填 `null`，
     * 选中单文件取 `data`、多选取 `ClipData`。
     *
     * ⚠ **挂起的回调不在**（Activity 因配置变更/进程重建换过实例）⇒ 结果**必须打成另一条日志**，
     * 不能沿用 `result|count=1|canceled=false`——那条和"成功交付"在读数上逐字相同，
     * 用户看到的是"选了文件但页面毫无反应"（R9.2 的 L2E-01）。
     */
    fun finish(resultCode: Int, data: Intent?) {
        val uris = if (resultCode == Activity.RESULT_OK) selectedUris(data) else null
        if (uris != null && pending == null) {
            Log.w(TAG, "file-chooser|result-dropped|reason=no-pending-callback|count=${uris.size}")
            return
        }
        Log.i(TAG, "file-chooser|result|count=${uris?.size ?: 0}|canceled=${uris == null}")
        if (uris == null) closePending() else deliver(uris)
    }

    @Suppress("DEPRECATION")
    private fun isMultipleMode(params: WebChromeClient.FileChooserParams): Boolean =
        params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

    private fun selectedUris(data: Intent?): Array<Uri>? {
        val clip = data?.clipData
        if (clip != null && clip.itemCount > 0) {
            val items = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (items.isNotEmpty()) return items.toTypedArray()
        }
        return data?.data?.let { arrayOf(it) }
    }

    private fun closePending() {
        val previous = pending
        pending = null
        previous?.onReceiveValue(null)
    }

    private fun deliver(uris: Array<Uri>) {
        val callback = pending
        pending = null
        val sizes = uris.map(::sizeOf)
        val known = sizes.filterNotNull()
        if (anyOverLimit(known)) {
            Log.w(
                TAG,
                "file-chooser|result-rejected|reason=too-large|count=${uris.size}" +
                    "|max=${known.maxOrNull()}|limit=${PiqConfig.MAX_IMPORT_BYTES}",
            )
            val limitMb = PiqConfig.MAX_IMPORT_BYTES / (1024 * 1024)
            Toast.makeText(activity, "文件超过 $limitMb MB，没有交给页面。", Toast.LENGTH_LONG).show()
            callback?.onReceiveValue(null)
            return
        }
        if (sizes.any { it == null }) {
            Log.i(TAG, "file-chooser|size=unknown|count=${sizes.count { it == null }}")
        }
        callback?.onReceiveValue(uris)
    }

    /** 通过系统文档提供方读 `OpenableColumns.SIZE`；查询失败/无值都算 unknown，不误拒。 */
    private fun sizeOf(uri: Uri): Long? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "PIQ"

        /** 任一已知大小超限即拒绝；null（未知）不参与判定。上限复用 [PiqConfig.MAX_IMPORT_BYTES]。 */
        internal fun anyOverLimit(sizes: List<Long?>, limit: Long = PiqConfig.MAX_IMPORT_BYTES): Boolean =
            sizes.any { it != null && it > limit }

        /** 本通道独占的请求码（不与 [MainActivity] 其它 `startActivityForResult` 冲突）。 */
        const val REQUEST_CODE = 0x51C0
    }
}
