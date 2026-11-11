package com.dydown.app.parse

import android.content.Context
import com.dydown.app.core.Http
import com.dydown.app.core.LiveInfo
import com.dydown.app.core.MediaMeta
import com.dydown.app.core.ParseException
import com.dydown.app.core.PostPage
import com.dydown.app.data.Prefs
import com.dydown.app.ytdlp.YtDlp

/**
 * 解析总调度。
 *
 * 主通道：已登录 WebView 打开真实页面，抓 _ROUTER_DATA 内嵌 JSON（过 JSVM + 同域 cookie + 自带签名）。
 * 这是唯一能稳定过风控的路。旧的 OkHttp 分享页 / JSON API 因 JSVM 与签名问题降级为兜底，
 * yt-dlp 内置 python 过旧仅作最后一层参考。
 */
class ParseRepository(
    private val context: Context,
    private val prefs: Prefs
) {

    private val bridge = PageBridge(context)
    private val api = DouyinApi(bridge)

    suspend fun parse(text: String): MediaMeta {
        val raw = LinkResolver.firstUrl(text)
            ?: throw ParseException("没找到链接，检查一下粘贴的内容")

        if (!Http.hasSession()) {
            throw ParseException("尚未登录抖音：请先到「设置 → 打开抖音网页登录」扫码登录，再回来解析。")
        }

        val errors = mutableListOf<String>()

        // 主通道：WebView 抓页面内嵌数据
        runCatching {
            val res = bridge.loadAndScrape(raw)
            val kind = LinkResolver.classifyUrl(res.finalUrl) ?: LinkKind.VIDEO
            return when (kind) {
                LinkKind.LIVE -> throw ParseException("这是直播间链接，请到「录播」页")
                LinkKind.USER -> throw ParseException("这是主页链接，请到「批量」页")
                else -> {
                    // 用链接里的作品 ID 精确匹配，避免误取页面里的推荐流
                    val expectedId = res.id ?: LinkResolver.idFromUrl(res.finalUrl) ?: LinkResolver.idFromUrl(raw)
                    // 落到推荐首页且全程没解析出作品 ID 说明短链没解析到视频，
                    // 多半是分享短码已过期/失效，抖音把失效短码回退到首页。
                    val landedHome = res.finalUrl.contains("recommend") ||
                        Regex("""^https?://(www\.)?douyin\.com/?$""").matches(res.finalUrl.trim())
                    if (expectedId == null && landedHome) {
                        throw ParseException(
                            "这条分享链接没能解析到具体视频 —— 多半是短链已过期/失效。\n" +
                                "请回到抖音 App 重新「分享 → 复制链接」，把完整的分享文案粘贴进来重试。"
                        )
                    }
                    RouterDataParser.extractAweme(res.json, expectedId)
                }
            }
        }.onFailure { errors.add("· 页面抓取：${it.message ?: it.javaClass.simpleName}") }

        // 兜底：yt-dlp（内置 python 3.8 过旧，多数情况不可用，仅供参考）
        if (prefs.ytDlpFallback) {
            runCatching { YtDlp.probe(context, raw) }.onSuccess { return it }
            errors.add("· yt-dlp：内置 python 过旧，暂不可用（可忽略）")
        }

        throw ParseException("解析失败：\n" + errors.joinToString("\n"))
    }

    /** 主页批量：优先 Web API 翻页，失败则滚动加载一次性拿全部 */
    suspend fun userPosts(secUid: String, cursor: Long, count: Int = 18): PostPage {
        val errors = mutableListOf<String>()
        // 主通道：Web API 翻页（有真实 cursor）
        runCatching {
            val page = api.userPosts(secUid, cursor, count)
            if (page.items.isNotEmpty() || cursor > 0L) return page
            errors.add("· 接口翻页：返回空列表")
        }.onFailure { errors.add("· 接口翻页：${it.message ?: it.javaClass.simpleName}") }
        // 兜底：WebView 滚动加载全部（cursor=0 时才有意义）
        if (cursor == 0L) {
            runCatching {
                val res = bridge.loadAndScrapeUserPage("https://www.douyin.com/user/$secUid")
                return RouterDataParser.extractUserPosts(res.json, secUid)
            }.onFailure { errors.add("· 页面抓取：${it.message ?: it.javaClass.simpleName}") }
        }
        throw ParseException("主页解析失败：\n" + errors.joinToString("\n"))
    }

    /**
     * 直播：先把任意形式的输入（短链 / reflow 长链 / 直链 / 纯数字）规范化成 webRid，
     * 再按「直播页 SSR 直取 → WebView 抓包 → web/enter 接口」三级降级。
     *
     * 之前把输入框原文直接当房间号拼成 live.douyin.com/{整条短链}，导致两条通道全挂：
     * 页面抓到空数据、接口因畸形 web_rid 返回 __EMPTY__ 又触发 JSONObject 转换异常。
     */
    suspend fun live(text: String): LiveInfo {
        val errors = mutableListOf<String>()
        val target = LinkResolver.resolveLive(text)  // 解析失败会带可操作提示直接冒泡

        // 通道 1：直播页 SSR 直取（实测 1.1MB 页面内嵌 flv_pull_url，无需签名、最快最稳）
        runCatching {
            val html = Http.getString(target.pageUrl, "https://live.douyin.com/")
            dumpLive(target.pageUrl, html)
            return LivePageParser.parse(html, target)
        }.onFailure { errors.add("· 直播页：${it.message ?: it.javaClass.simpleName}") }

        // 通道 2：WebView 打开直播页，搬页面自己发出的接口响应
        runCatching {
            val res = bridge.loadAndScrape(target.pageUrl)
            return RouterDataParser.extractLive(res.json, target.webRid)
        }.onFailure { errors.add("· 页面抓取：${it.message ?: it.javaClass.simpleName}") }

        // 通道 3：web/enter 接口（页面上下文 fetch）
        runCatching { return api.live(target.webRid) }
            .onFailure { errors.add("· 接口：${it.message ?: it.javaClass.simpleName}") }

        throw ParseException("直播解析失败：\n" + errors.joinToString("\n"))
    }

    suspend fun resolveUser(text: String): String {
        val link = LinkResolver.resolve(text)
        if (link.kind != LinkKind.USER) throw ParseException("这不是主页链接")
        return link.id
    }

    /** 直播页诊断：失败时可用 adb run-as cat 拉 last_live.txt 判断是页面没拿到还是正则没匹配 */
    private fun dumpLive(url: String, html: String) {
        runCatching {
            val dir = java.io.File(context.filesDir, "dydown_debug").apply { mkdirs() }
            java.io.File(dir, "last_live.txt").writeText(
                "URL: $url\nlen=${html.length}\n" +
                    "flv_pull_url=${html.contains("flv_pull_url")} " +
                    "flv=${html.contains(".flv")} webRid=${html.contains("webRid")}\n" +
                    "head=${html.take(300)}"
            )
        }
    }

    suspend fun close() {
        runCatching { bridge.close() }
    }
}
