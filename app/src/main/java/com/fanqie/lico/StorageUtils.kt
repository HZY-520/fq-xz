package com.fanqie.lico

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import java.io.File

/** 存储权限与目录选择的工具方法 */
object StorageUtils {

    /** 是否已获得「所有文件访问权限」（或旧系统的读写权限） */
    fun hasAllFilesAccess(c: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        } else {
            ContextCompat.checkSelfPermission(
                c,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 把 SAF 的目录 tree uri 转换为真实文件系统路径 */
    fun treeUriToPath(c: Context, uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.isEmpty()) return null
            val type = parts[0]
            val rel = if (parts.size > 1) parts[1] else ""
            val base = if (type.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$type"
            }
            val path = if (rel.isEmpty()) base else "$base/$rel"
            val f = File(path)
            if (!f.exists()) f.mkdirs()
            if (f.exists()) path else null
        } catch (_: Throwable) {
            null
        }
    }
}