package com.dydown.app.parse

import com.dydown.app.core.Http
import com.dydown.app.core.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LinkKind { VIDEO, GALLERY, USER, LIVE, UNKNOWN }

data class Resolved(val kind: LinkKind, val id: String, val url: String)

/** 直播间定位结果：webRid 用于 live.douyin.com/{webRid}，roomId 是 19 位内部房间号 */
data class LiveTarget(val webRid: String, val roomId: String, val pageUrl: String)

/**
 * 把「一段分享文案」变成「确定的作品 ID / 用户 ID / 直播间号」。
 * 用户从抖音分享出来的东西长这样：
 *   3.85 复制打开抖音，看看【xxx的作品】... https://v.douyin.com/iRxxxx/ 复制此链接...
 */
object LinkResolver {

    private val urlRegex = Regex("""https?://[^\s"'<>，。！？、）)】\]】]+""")

    fun firstUrl(text: String): String? = urlRegex.find(text)?.value?.trimEnd('.', ',', '，', '。', '！', '？', ';', '；')

    /** 从任意 URL（含 WebView 跳转后的最终地址）判定内容类型 */
    fun classifyUrl(url: String): LinkKind? {
        if (Regex("""live\.douyin\.com/\d+""").containsMatchIn(url)) return LinkKind.LIVE
        if (Regex("""/user/[^/?#\s]{8,}""").containsMatchIn(url)) return LinkKind.USER
        if (Regex("""sec_uid=([^&\s]{8,})""").containsMatchIn(url)) return LinkKind.USER
        if (Regex("""/(?:share/)?(?:note|slides)/\d{6,}""").containsMatchIn(url)) return LinkKind.GALLERY
        if (Regex("""/(?:share/)?video/\d{6,}""").containsMatchIn(url)) return LinkKind.VIDEO
        if (Regex("""modal_id=\d{6,}""").containsMatchIn(url)) return LinkKind.VIDEO
        return null
    }

    suspend fun resolve(text: String): Resolved = withContext(Dispatchers.IO) {
        val raw = firstUrl(text) ?: throw ParseException("没找到链接，检查一下粘贴的内容")
        // v.douyin.com 短链必须先跳一次才知道是视频、图集还是直播
        val finalUrl = if (raw.contains("v.douyin.com") || raw.contains("v.iesdouyin.com")) {
            runCatching { Http.finalUrl(raw) }.getOrDefault(raw)
        } else raw
        val kind = classifyUrl(finalUrl) ?: classifyUrl(raw) ?: LinkKind.UNKNOWN
        val id = idOf(finalUrl) ?: idOf(raw) ?: ""
        Resolved(kind, id, finalUrl)
    }

    /**
     * 把任意直播输入规范化成 [LiveTarget]。
     *
     * 真机验证的跳转链：
     *   v.douyin.com/VAkMWVBXETU/  --302-->
     *   webcast.amemv.com/douyin/webcast/reflow/7706821259707206435?...
     * reflow 页 SSR 里嵌着 "webRid":"178063390426"，那才是 live.douyin.com 用的号
     * （19 位的 7706821259707206435 是 room_id，不能直接当 web_rid 用）。
     *
     * 支持：纯数字房号 / live.douyin.com 直链 / 带 web_rid 参数 / 短链 / reflow 长链。
     */
    suspend fun resolveLive(text: String): LiveTarget = withContext(Dispatchers.IO) {
        val raw = firstUrl(text) ?: text.trim()

        // 1) 纯数字直播间号
        if (raw.length in 6..20 && raw.all { it.isDigit() }) {
            return@withContext LiveTarget(raw, "", "https://live.douyin.com/$raw")
        }
        // 2) live.douyin.com/{webRid} 直链
        Regex("""live\.douyin\.com/(\d{6,})""").find(raw)?.let {
            val rid = it.groupValues[1]
            return@withContext LiveTarget(rid, "", "https://live.douyin.com/$rid")
        }
        // 3) 查询参数里直接带 web_rid
        Regex("""[?&](?:web_rid|webRid)=(\d{6,})""").find(raw)?.let {
            val rid = it.groupValues[1]
            return@withContext LiveTarget(rid, "", "https://live.douyin.com/$rid")
        }

        // 4) 短链 / reflow 长链：跟 302，再从 reflow 页抠 webRid
        val final = runCatching { Http.finalUrl(raw) }.getOrDefault(raw)
        val roomId = Regex("""/reflow/(\d{10,})""").find(final)?.groupValues[1]
            ?: Regex("""/reflow/(\d{10,})""").find(raw)?.groupValues[1]
            ?: ""

        val reflowUrl = if (roomId.isNotBlank()) {
            // 去掉一堆分享统计参数，避免过长 URL 和反爬差异；实测裸 URL 同样返回 SSR 数据
            "https://webcast.amemv.com/douyin/webcast/reflow/$roomId"
        } else final

        val html = runCatching { Http.getString(reflowUrl, "https://live.douyin.com/") }.getOrDefault("")
        // SSR 里是 \"webRid\":\"178063390426\"，中间允许任意非数字字符
        val webRid = Regex("""webRid[^0-9]{0,12}(\d{9,15})""").find(html)?.groupValues[1]
            ?: Regex("""live\.douyin\.com/(\d{6,})""").find(html)?.groupValues[1]
            ?: Regex("""live\.douyin\.com/(\d{6,})""").find(final)?.groupValues[1]

        if (webRid.isNullOrBlank()) {
            throw ParseException(
                "没能从分享链接解析出直播间号。\n" +
                    "可以打开直播间，从浏览器地址栏复制 live.douyin.com/数字 形式的链接，或直接粘贴那个数字。"
            )
        }
        LiveTarget(webRid, roomId, "https://live.douyin.com/$webRid")
    }

    private fun idOf(url: String): String? {
        Regex("""live\.douyin\.com/(\d+)""").find(url)?.let { return it.groupValues[1] }
        Regex("""/user/([^/?#\s]{8,})""").find(url)?.let { return it.groupValues[1] }
        Regex("""sec_user_id=([^&\s]{8,})""").find(url)?.let { return it.groupValues[1] }
        Regex("""sec_uid=([^&\s]{8,})""").find(url)?.let { return it.groupValues[1] }
        Regex("""/(?:share/)?(?:note|slides|video)/(\d{6,})""").find(url)?.let { return it.groupValues[1] }
        Regex("""modal_id=(\d{6,})""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    /** 从任意 URL 抠出作品/直播/用户 ID（供精确匹配用） */
    fun idFromUrl(url: String): String? = idOf(url)
}
