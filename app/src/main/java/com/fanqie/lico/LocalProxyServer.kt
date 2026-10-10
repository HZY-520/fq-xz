package com.fanqie.lico

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置本地代理：
 *  - 托管随 APK 打包的新版 Web UI 静态资源（assets/webui）
 *  - 将 /api/ 、 /download/ 、 /download-zip/ 前缀的请求反向代理到本地内嵌后端
 *
 * 这样 WebView 只需访问本机回环地址，即可同时得到新前端与本地下载器后端。
 */
class LocalProxyServer(
    private val context: Context,
    private val backendBase: String,
) : NanoHTTPD("127.0.0.1", 0) {

    companion object {
        private const val TAG = "LocalProxy"
        private val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade",
        )
    }

    val port: Int get() = listeningPort

    private fun isApi(uri: String): Boolean =
        uri.startsWith("/api/") || uri.startsWith("/download/") || uri.startsWith("/download-zip/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return if (isApi(uri)) proxy(session) else serveStatic(uri)
    }

    // ── 静态资源 ─────────────────────────────────────────────
    private fun serveStatic(uri: String): Response {
        var rel = uri.trimStart('/')
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html"
        if (rel.contains("..")) return notFound()

        val assetPath = "webui/$rel"
        val stream = openAsset(assetPath)
        if (stream != null) {
            return newFixedLengthResponse(Response.Status.OK, mimeOf(rel), stream, -1)
        }
        // SPA 回退：无扩展名的前端路由（/search、/jobs…）统一返回 index.html
        val last = rel.substringAfterLast('/', "")
        if (!last.contains('.')) {
            val idx = openAsset("webui/index.html")
            if (idx != null) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", idx, -1)
            }
        }
        return notFound()
    }

    private fun openAsset(path: String): InputStream? = try {
        context.assets.open(path)
    } catch (_: IOException) {
        null
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "not found")

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    // ── 反向代理 ─────────────────────────────────────────────
    private fun proxy(session: IHTTPSession): Response {
        val method = session.method?.name ?: "GET"
        val query = session.queryParameterString
        val urlStr = buildString {
            append(backendBase)
            append(session.uri)
            if (!query.isNullOrEmpty()) {
                append('?')
                append(query)
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 0
            conn.doInput = true
            conn.setRequestProperty("Accept-Encoding", "identity")

            for ((key, value) in session.headers) {
                val lk = key.lowercase()
                if (lk in HOP_BY_HOP || lk == "accept-encoding" || lk == "host") continue
                conn.setRequestProperty(key, value)
            }

            if (method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE") {
                val body = HashMap<String, String>()
                session.parseBody(body)
                val post = body["postData"]
                if (!post.isNullOrEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }
                }
            }

            val code = conn.responseCode
            val status = Response.Status.lookup(code) ?: Response.Status.OK

            if (code == 204 || code == 304) {
                val empty = newFixedLengthResponse(status, "text/plain", "")
                copyCookies(conn, empty)
                return empty
            }

            val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
                .ifEmpty { "application/octet-stream" }
            val stream: InputStream = when {
                code in 200..299 -> conn.inputStream ?: ByteArrayInputStream(ByteArray(0))
                else -> conn.errorStream ?: conn.inputStream ?: ByteArrayInputStream(ByteArray(0))
            }
            val total = conn.contentLengthLong

            val resp = newFixedLengthResponse(status, mime, stream, total)
            copyCookies(conn, resp)
            return resp
        } catch (e: Exception) {
            Log.w(TAG, "proxy failed: $method $urlStr", e)
            val msg = e.message?.replace("\"", "'") ?: "unknown"
            return newFixedLengthResponse(
                Response.Status.lookup(502) ?: Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
                "{\"error\":\"本地代理转发失败：$msg\"}",
            )
        }
    }

    private fun copyCookies(conn: HttpURLConnection, resp: Response) {
        try {
            var i = 0
            while (true) {
                val key = conn.getHeaderFieldKey(i) ?: break
                if (key.equals("Set-Cookie", ignoreCase = true)) {
                    resp.addHeader("Set-Cookie", conn.getHeaderField(i))
                }
                i++
            }
        } catch (_: Throwable) {
        }
    }
}