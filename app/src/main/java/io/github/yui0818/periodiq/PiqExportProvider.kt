package io.github.yui0818.periodiq

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileNotFoundException

/**
 * 导出文件的只读通道（R13.3）：`exported=false` + `grantUriPermissions=true`，
 * 只把导出目录里的普通文件以单 URI 只读授权交给分享目标 / SAF。
 *
 * 路径合法性全部交给 [PiqExport.resolveExportFile]（拒绝穿越、绝对路径、目录、符号链接）。
 * 本类不写任何文件内容到日志。
 */
class PiqExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        if (mode != "r") throw FileNotFoundException(uri.toString())
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val file = PiqExport.resolveExportFile(PiqExport.exportDir(context), name)
            ?: throw FileNotFoundException(uri.toString())
        Log.i(TAG, "export-provider|open|nameLen=${file.name.length}|bytes=${file.length()}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        val name = uri.lastPathSegment ?: return null
        val file = PiqExport.resolveExportFile(PiqExport.exportDir(context), name) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("PiqExportProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("PiqExportProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("PiqExportProvider is read-only")

    private companion object {
        const val TAG = "PIQ"
    }
}
