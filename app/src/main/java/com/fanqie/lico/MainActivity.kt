package com.fanqie.lico

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var setupView: LinearLayout
    private lateinit var loadingView: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var permBtn: Button
    private lateinit var dirBtn: Button
    private lateinit var setupStatus: TextView

    private var proxy: LocalProxyServer? = null

    @Volatile
    private var started = false

    private val main = Handler(Looper.getMainLooper())

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val pickDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val path = StorageUtils.treeUriToPath(this, uri)
                if (path == null) {
                    toast("无法解析所选目录，请换一个位置再试")
                } else {
                    Prefs.setSaveDir(this, path)
                    toast("下载目录已设为：$path")
                    refreshSetup()
                    if (started) restartService() else maybeAutoStart()
                    return@registerForActivityResult
                }
            }
            refreshSetup()
        }

    private val reqPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshSetup()
            maybeAutoStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (this@MainActivity::webView.isInitialized &&
                        webView.visibility == View.VISIBLE && webView.canGoBack()
                    ) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        refreshSetup()
        maybeAutoStart()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        maybeAutoStart()
    }

    override fun onDestroy() {
        if (isFinishing) {
            BackendManager.stop()
            proxy?.stop()
            proxy = null
        }
        super.onDestroy()
    }

    // ── UI ───────────────────────────────────────────────────
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#15151F"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }
        bar.addView(
            TextView(this).apply {
                text = "小番茄"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        bar.addView(
            TextView(this).apply {
                text = "⋮"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(16), dp(0), dp(16), dp(0))
                setOnClickListener { showMenu(it) }
            },
        )
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        val container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))

        webView = WebView(this).apply {
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        configureWebView()
        container.addView(webView)

        loadingView = buildLoading()
        container.addView(loadingView)

        setupView = buildSetup()
        container.addView(setupView)

        setContentView(root)
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val u = request.url
                val host = u.host ?: return false
                if (host == "127.0.0.1" || host == "localhost") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Throwable) {
                }
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.setDownloadListener(
            DownloadListener { url, _, _, _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Throwable) {
                }
            },
        )
    }

    private fun buildLoading(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#15151F"))
            visibility = View.GONE
        }
        v.addView(
            ProgressBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            },
        )
        loadingText = TextView(this).apply {
            text = "正在启动本地下载器后端…"
            setTextColor(Color.parseColor("#CFCFCF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        v.addView(loadingText)
        return v
    }

    private fun buildSetup(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#15151F"))
            visibility = View.GONE
        }
        v.addView(
            TextView(this).apply {
                text = "小番茄"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                gravity = Gravity.CENTER
            },
        )
        v.addView(
            TextView(this).apply {
                text = "番茄小说下载器 · 安卓版"
                setTextColor(Color.parseColor("#9E9E9E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(20))
            },
        )
        v.addView(
            TextView(this).apply {
                text = "首次使用需完成以下两步：\n① 授予「所有文件访问权限」，用于把小说保存到任意目录\n② 选择一个下载目录"
                setTextColor(Color.parseColor("#CFCFCF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(20))
            },
        )
        permBtn = Button(this).apply {
            text = "① 授予所有文件访问权限"
            setOnClickListener { requestAllFilesAccess() }
        }
        v.addView(permBtn, LinearLayout.LayoutParams(MATCH, WRAP))

        dirBtn = Button(this).apply {
            text = "② 选择下载目录"
            setOnClickListener { pickDir.launch(null) }
        }
        v.addView(
            dirBtn,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) },
        )

        setupStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        v.addView(setupStatus)
        return v
    }

    private fun refreshSetup() {
        if (!this::permBtn.isInitialized) return
        val hasPerm = StorageUtils.hasAllFilesAccess(this)
        permBtn.text = if (hasPerm) "① 已授予文件访问权限 ✓" else "① 授予所有文件访问权限"
        permBtn.isEnabled = !hasPerm
        val dir = Prefs.getSaveDir(this)
        dirBtn.text = if (dir != null) "② 下载目录：$dir" else "② 选择下载目录"
        setupStatus.text =
            if (hasPerm && dir != null) "准备就绪，正在启动…" else "完成以上两步后将自动进入应用"
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("重新选择下载目录")
        popup.menu.add("存储权限设置")
        popup.menu.add("重启本地服务")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "重新选择下载目录" -> {
                    pickDir.launch(null); true
                }
                "存储权限设置" -> {
                    requestAllFilesAccess(); true
                }
                "重启本地服务" -> {
                    restartService(); true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ── 流程控制 ─────────────────────────────────────────────
    private fun maybeAutoStart() {
        val ready = StorageUtils.hasAllFilesAccess(this) && Prefs.getSaveDir(this) != null
        if (!ready || started) return
        setupView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        startService()
    }

    private fun startService() {
        if (started) return
        val dir = Prefs.getSaveDir(this)
        if (dir == null) return
        started = true
        setLoading("正在启动本地下载器后端…")

        Thread {
            ConfigWriter.writeSavePath(this, dir)
            val ok = BackendManager.start(this) && BackendManager.waitReady(45000)
            if (!ok) {
                main.post {
                    started = false
                    loadingView.visibility = View.GONE
                    setupView.visibility = View.VISIBLE
                    setupStatus.text = "本地后端启动失败，请检查权限后重试"
                    toast("本地后端启动失败")
                }
                return@Thread
            }
            setLoading("正在启动内置本地代理…")
            val p = LocalProxyServer(this, "http://127.0.0.1:${BackendManager.PORT}")
            try {
                p.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: Exception) {
                main.post {
                    started = false
                    loadingView.visibility = View.GONE
                    setupView.visibility = View.VISIBLE
                    setupStatus.text = "本地代理启动失败：${e.message}"
                }
                return@Thread
            }
            proxy = p
            val url = "http://127.0.0.1:${p.port}/"
            main.post {
                loadingView.visibility = View.GONE
                setupView.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(url)
            }
        }.apply {
            isDaemon = true
            name = "tomato-boot"
            start()
        }
    }

    private fun restartService() {
        started = false
        webView.visibility = View.INVISIBLE
        BackendManager.stop()
        proxy?.stop()
        proxy = null
        maybeAutoStart()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            reqPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun setLoading(text: String) {
        main.post { if (this::loadingText.isInitialized) loadingText.text = text }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}