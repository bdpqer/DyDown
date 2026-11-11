package com.dydown.app.core

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 网络层。
 *
 * 关键设计：Cookie 不自己维护，直接读 WebView 的 CookieManager。
 * 用户在「登录」页扫码之后，这里所有请求自动带登录态 —— 不需要导出 cookie 文本。
 */
object Http {

    // 桌面版 Chrome UA：抖音网页端对移动 UA 会甩"下载APP/电脑版"落地页，
    // 既无登录入口、也无页面内签名器（byted_acrawler），导致登录页和解析通道 B 双双失效。
    // 换成桌面 UA 后，抖音吐出真正的站点 + 右上角登录二维码，与 DouyinApi 的
    // commonQuery（browser_platform=Win32）也一致。
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun isDouyin(host: String) =
        host.contains("douyin") || host.contains("amemv") || host.contains("snssdk") || host.contains("bytedance")

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!isDouyin(url.host)) return emptyList()
            val header = runCatching {
                CookieManager.getInstance().getCookie("https://${url.host}")
            }.getOrNull().orEmpty()
            if (header.isEmpty()) return emptyList()
            return header.split(";").mapNotNull { part ->
                val kv = part.trim()
                if (kv.isEmpty() || !kv.contains("=")) null else Cookie.parse(url, kv)
            }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cookieJar(jar)
            .build()
    }

    fun request(url: String, referer: String? = "https://www.douyin.com/"): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { if (referer != null) header("Referer", referer) }
            .build()

    suspend fun getString(url: String, referer: String? = "https://www.douyin.com/"): String =
        withContext(Dispatchers.IO) {
            client.newCall(request(url, referer)).execute().use { resp ->
                if (!resp.isSuccessful) throw ParseException("HTTP ${resp.code}")
                resp.body?.string() ?: throw ParseException("空响应")
            }
        }

    /** 追随短链跳转，返回最终 URL */
    suspend fun finalUrl(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, null)).execute().use { it.request.url.toString() }
    }

    /** 是否已有抖音网页登录态（用于解析前提醒，避免无意义地跑四条通道） */
    fun hasSession(): Boolean {
        val header = runCatching {
            CookieManager.getInstance().getCookie("https://www.douyin.com")
        }.getOrNull().orEmpty()
        return header.split(";").any { part ->
            val k = part.trim().substringBefore("=")
            k in setOf("sessionid_ss", "sid_tt", "sid_guard", "passport_csrf_token", "ttwid", "odin_tt")
        }
    }

    /** 导出 Netscape 格式 cookie 给 yt-dlp 用 */
    fun exportNetscape(dir: File): File {
        val file = File(dir, "douyin_cookies.txt")
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        val header = runCatching {
            CookieManager.getInstance().getCookie("https://www.douyin.com")
        }.getOrNull().orEmpty()
        header.split(";").forEach { part ->
            val kv = part.trim()
            if (kv.contains("=")) {
                val k = kv.substringBefore("=")
                val v = kv.substringAfter("=")
                if (k.isNotEmpty()) sb.append(".douyin.com\tTRUE\t/\tTRUE\t0\t$k\t$v\n")
            }
        }
        file.writeText(sb.toString())
        return file
    }
}
