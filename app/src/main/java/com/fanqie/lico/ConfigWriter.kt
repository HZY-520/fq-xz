package com.fanqie.lico

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 写入后端配置文件（config.yml）中的下载目录。
 *
 * 后端加载配置时会用默认值合并用户 YAML，因此这里只需维护 save_path 一行，
 * 其它用户在设置页修改过的字段会被保留。
 */
object ConfigWriter {
    private const val TAG = "ConfigWriter"

    fun writeSavePath(c: Context, dir: String) {
        try {
            val dataDir = BackendManager.dataDir(c).apply { mkdirs() }
            val f = File(dataDir, "config.yml")
            val quoted = yamlQuote(dir)
            val line = "save_path: $quoted"
            val text = if (f.exists()) f.readText() else ""
            val regex = Regex("(?m)^save_path:.*$")
            val out = if (regex.containsMatchIn(text)) {
                regex.replace(text, line)
            } else {
                val head = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
                head + line + "\n"
            }
            f.writeText(out)
        } catch (e: Exception) {
            Log.e(TAG, "write config.yml failed", e)
        }
    }

    private fun yamlQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}