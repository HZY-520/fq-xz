package com.fanqie.lico

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 内嵌后端进程管理。
 *
 * 后端为官方 Android arm64/arm32 可执行文件，以 libtomato.so 形式随 APK 打包，
 * 安装时被解压到应用私有 nativeLibraryDir。为了确保可执行权限，
 * 启动时把它复制到 filesDir 下并 chmod +x，再从该副本运行。
 */
object BackendManager {
    const val PORT = 18423
    private const val TAG = "Backend"

    @Volatile
    private var process: Process? = null

    fun dataDir(c: Context): File = File(c.filesDir, "tomato-data")

    fun isRunning(): Boolean = process?.isAlive == true

    @Synchronized
    fun start(c: Context): Boolean {
        if (isRunning()) return true

        val src = File(c.applicationInfo.nativeLibraryDir, "libtomato.so")
        if (!src.exists()) {
            Log.e(TAG, "backend binary not found: ${src.absolutePath}")
            return false
        }

        val exe = try {
            prepareExecutable(c, src)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to prepare backend executable", t)
            null
        } ?: return false

        val dir = dataDir(c).apply { mkdirs() }
        val pb = ProcessBuilder(exe.absolutePath, "--server", "--data-dir", dir.absolutePath)
        pb.directory(dir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["TOMATO_WEB_ADDR"] = "127.0.0.1:$PORT"
        env["HOME"] = c.filesDir.absolutePath
        env["TMPDIR"] = c.cacheDir.absolutePath

        return try {
            val p = pb.start()
            process = p
            Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { Log.i(TAG, it) }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                isDaemon = true
                name = "tomato-backend-log"
                start()
            }
            Log.i(TAG, "backend started: ${exe.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start backend", t)
            false
        }
    }

    @Synchronized
    fun stop() {
        val p = process ?: return
        try {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: Throwable) {
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        process = null
    }

    /**
     * 把 nativeLibraryDir 中的后端复制到 filesDir（应用完全可写），并授予执行权限。
     * 已存在且大小一致时直接复用，避免每次启动都复制 9MB 文件。
     */
    private fun prepareExecutable(c: Context, src: File): File? {
        val binDir = File(c.filesDir, "bin").apply { mkdirs() }
        val dst = File(binDir, "tomato")
        if (!dst.exists() || dst.length() != src.length()) {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dst.setExecutable(true, false)
        dst.setReadable(true, false)
        if (!dst.canExecute()) {
            Log.e(TAG, "prepared binary not executable: ${dst.absolutePath}")
            return null
        }
        return dst
    }

    /** 轮询 /api/status 直到后端可用 */
    fun waitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive == false) return false
            var conn: HttpURLConnection? = null
            try {
                conn = URL("http://127.0.0.1:$PORT/api/status").openConnection() as HttpURLConnection
                conn.connectTimeout = 1200
                conn.readTimeout = 1200
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code in 200..499) return true
            } catch (_: Throwable) {
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }
}