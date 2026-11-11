package com.dydown.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dydown.app.core.Http

/**
 * 抖音网页登录。
 * 登录态保存在 WebView 的 CookieManager 里，Http 层的 CookieJar 会实时读取，
 * 也就是"登录一次，全 App 生效"，不需要手动导出 cookie。
 *
 * 注意：抖音首页 www.douyin.com/ 会自动 302 到 /jingxuan（精选信息流），
 * 这是正常行为，地址栏显示 jingxuan 不算出错。登录入口在页面右上角「登录」按钮。
 *
 * 本页加了地址栏 + 刷新 + 回首页，方便排查"卡在验证页 / 地址不对"的问题。
 */
@SuppressLint("SetJavaScriptEnabled")
class LoginActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var address: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 主题把系统栏设成透明；这里给登录页一个不透明底，避免状态栏区域透出内容
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // 顶部操作栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "登录抖音"
            textSize = 15f
        }
        val reload = Button(this).apply { text = "刷新" }
        val home = Button(this).apply { text = "首页" }
        val done = Button(this).apply {
            text = "完成"
            setOnClickListener {
                runCatching { CookieManager.getInstance().flush() }
                finish()
            }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(reload)
        bar.addView(home)
        bar.addView(done)

        // 地址栏（实时显示当前 URL，方便确认登录态落在哪个域）
        address = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            text = "加载中…"
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = Http.UA
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    url?.let { address.text = it }
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    url?.let { address.text = it }
                    runCatching { CookieManager.getInstance().flush() }
                }
                override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                    if (r?.isForMainFrame == true) {
                        address.text = "加载失败：${e?.description ?: "未知错误"} —— 点「刷新」重试"
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        reload.setOnClickListener { web.reload() }
        home.setOnClickListener { web.loadUrl(START) }

        root.addView(bar)
        root.addView(address)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Android 15+（targetSdk 35）强制 edge-to-edge：传统 View 布局默认从 y=0 开始画，
        // 顶部操作栏会被状态栏压住。把系统栏 insets 转成根布局 padding，整条栏自然下移。
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = maxOf(bars.top, statusBarHeightPx()) + dp(8)
            v.setPadding(v.paddingLeft, top, v.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        web.loadUrl(START)
    }

    /** 少数 ROM 不上报 insets，用系统资源兜底 */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    override fun onDestroy() {
        runCatching { CookieManager.getInstance().flush() }
        super.onDestroy()
    }

    companion object {
        // 抖音首页根路径，会被其自动重定向到 /jingxuan（精选），属正常行为
        private const val START = "https://www.douyin.com/"
    }
}
