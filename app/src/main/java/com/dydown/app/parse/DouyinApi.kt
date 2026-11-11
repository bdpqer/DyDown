package com.dydown.app.parse

import com.dydown.app.core.JsonScan
import com.dydown.app.core.LiveInfo
import com.dydown.app.core.MediaMeta
import com.dydown.app.core.ParseException
import com.dydown.app.core.PostPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 通道 B：走页面上下文调 Web 接口。
 * 覆盖分享页拿不到的三块：作品详情、主页作品列表（翻页）、直播间流地址。
 */
class DouyinApi(private val bridge: PageBridge) {

    private val commonQuery =
        "device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1" +
            "&version_code=190500&version_name=19.5.0&cookie_enabled=true" +
            "&screen_width=1080&screen_height=1920&browser_language=zh-CN" +
            "&browser_platform=Win32&browser_name=Chrome&browser_version=126.0.0.0" +
            "&browser_online=true&engine_name=Blink&engine_version=126.0.0.0&os_name=Windows&os_version=10"

    suspend fun detail(awemeId: String): MediaMeta = withContext(Dispatchers.IO) {
        val text = bridge.fetch("https://www.douyin.com/aweme/v1/web/aweme/detail/?$commonQuery&aweme_id=$awemeId")
        val root = JSONObject(text)
        val item = JsonScan.obj(root, "aweme_detail", "awemeDetail")
            ?: throw ParseException("详情接口没有 aweme_detail")
        AwemeParser.fromAweme(item, "页面接口")
    }

    suspend fun userPosts(secUid: String, cursor: Long, count: Int = 18): PostPage =
        withContext(Dispatchers.IO) {
            val text = bridge.fetch(
                "https://www.douyin.com/aweme/v1/web/aweme/post/?$commonQuery" +
                    "&sec_user_id=$secUid&max_cursor=$cursor&count=$count&locate_query=false&show_live_replay_strategy=1"
            )
            val root = JSONObject(text)
            // 风控/错误响应必须抛异常让上层降级，不能静默返回空页
            val status = root.optInt("status_code", 0)
            if (status != 0) {
                throw ParseException("接口异常 status_code=$status ${root.optString("status_msg", "")}")
            }
            val list = JsonScan.arr(root, "aweme_list", "awemeList")
                ?: throw ParseException("接口未返回 aweme_list（可能被风控或需要重新登录）")

            val items = ArrayList<MediaMeta>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                // 风控时接口偶尔会吐推荐流：作者 sec_uid 对不上的一律不要
                if (!RouterDataParser.belongsToUser(o, secUid)) continue
                // 主页里会混进置顶/推荐，单条解析失败不该影响整页
                runCatching { AwemeParser.fromAweme(o, "主页") }
                    .getOrNull()
                    ?.takeIf { it.kind == com.dydown.app.core.Kind.VIDEO || it.kind == com.dydown.app.core.Kind.GALLERY }
                    ?.let { items.add(it) }
            }
            // 有列表但全是别人家的作品 = 接口返回了推荐流，必须降级到页面抓取
            if (list.length() > 0 && items.isEmpty()) {
                throw ParseException("接口返回的都不是该主页的作品（疑似推荐流）")
            }
            PostPage(
                items = items,
                hasMore = root.optInt("has_more", 0) == 1,
                cursor = parseCursor(root, cursor)
            )
        }

    /** max_cursor 可能是 number 或 string（大整数防精度丢失），统一安全取值 */
    private fun parseCursor(root: JSONObject, fallback: Long): Long {
        val v = root.opt("max_cursor") ?: return fallback
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    suspend fun live(rid: String): LiveInfo = withContext(Dispatchers.IO) {
        val text = bridge.fetch(
            "https://live.douyin.com/webcast/room/web/enter/?aid=6383&app_name=douyin_web&live_id=1" +
                "&device_platform=web&language=zh-CN&enter_from=web_live&cookie_enabled=true" +
                "&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32" +
                "&browser_name=Chrome&browser_version=126.0.0.0&web_rid=$rid",
            // 加载具体直播间页而不是首页，页面上下文/签名器才与目标房间匹配
            pageOrigin = "https://live.douyin.com/$rid"
        )
        // 风控时 fetch 拿到空串会被替换成 "__EMPTY__"，直接 JSONObject() 会抛
        // "Value __EMPTY__ of type java.lang.String cannot be converted to JSONObject"，必须先挡住
        val t = text.trim()
        if (t.isEmpty() || t == "__EMPTY__" || !t.startsWith("{")) {
            throw ParseException("直播间接口返回空或非 JSON（可能被风控、需要登录或已下播）")
        }
        RouterDataParser.extractLive(t, rid)
    }
}
