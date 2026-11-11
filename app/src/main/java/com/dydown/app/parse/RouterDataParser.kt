package com.dydown.app.parse

import com.dydown.app.core.JsonScan
import com.dydown.app.core.Kind
import com.dydown.app.core.LiveInfo
import com.dydown.app.core.MediaMeta
import com.dydown.app.core.ParseException
import com.dydown.app.core.PostPage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 从 _ROUTER_DATA / RENDER_DATA 内嵌 JSON 里抠出作品 / 直播 / 主页作品。
 *
 * 抖音改版频繁，这里不写死任何具体路径，全部用递归按特征字段定位：
 *   - 作品对象 = 有 aweme_id 且带 video / images
 *   - 直播房间 = 有 owner + status + title
 *   - 流地址对象 = 有 flv_pull_url
 * 改版挪层级时只需在这里补候选 key，不用改上层。
 */
object RouterDataParser {

    /** 从页面内嵌 JSON 抠出一条作品；expectedId 用于精确匹配，避免误取推荐流 */
    fun extractAweme(json: String, expectedId: String? = null): MediaMeta {
        val root = JSONObject(json)
        // 1) 优先按 expectedId 精确匹配
        if (!expectedId.isNullOrBlank()) {
            findFirst(root) { isAweme(it) && idOf(it) == expectedId }?.let {
                return AwemeParser.fromAweme(it, "页面")
            }
        }
        // 2) 视频详情页主结构：某个对象带 videoInfoRes.item_list
        val main = findFirst(root) { it.has("videoInfoRes") }
            ?.let { JsonScan.arr(JsonScan.obj(it, "videoInfoRes"), "item_list") }
            ?.optJSONObject(0)
        // 3) 兜底：递归找第一个作品对象
        val item = main ?: findFirst(root) { isAweme(it) }
            ?: throw ParseException("页面数据里找不到作品（可能已删除/设为私密，或需要重新登录）")
        return AwemeParser.fromAweme(item, "页面")
    }

    private fun idOf(o: JSONObject): String? =
        JsonScan.str(o, "aweme_id", "awemeId", "item_id", "itemId")

    /**
     * 从直播页内嵌 JSON / 接口响应里抠直播信息。
     * 兼容 __captures__（WebView 抓包）与 web/enter（接口）两种形态，不写死字段层级：
     * 直接递归全文找 http 开头的 .flv/.m3u8 地址，抖音改版挪字段也不影响。
     */
    fun extractLive(json: String, fallbackRid: String = ""): LiveInfo {
        val root = JSONObject(json)
        val urls = ArrayList<String>()
        collectStreamUrls(root, urls, 0)
        val flv = pickBest(urls, ".flv")
        val hls = pickBest(urls, ".m3u8")
        if (flv == null && hls == null) {
            throw ParseException("直播数据为空，可能已下播或需要重新登录")
        }

        val room = findFirst(root) {
            it.has("title") && (it.has("owner") || it.has("status") || it.has("id_str"))
        }
        val owner = JsonScan.obj(room, "owner", "owner_info", "anchor_info")
        return LiveInfo(
            roomId = JsonScan.str(room, "id_str", "roomIdStr", "id") ?: fallbackRid,
            title = JsonScan.str(room, "title", "room_title") ?: "抖音直播",
            author = JsonScan.str(owner, "nickname") ?: JsonScan.str(room, "nickname") ?: "",
            living = room?.optInt("status", 0) == 2 || flv != null || hls != null,
            flv = flv,
            hls = hls,
            cover = JsonScan.firstUrl(JsonScan.obj(room, "cover", "room_cover", "cover_url"))
        )
    }

    /** 递归收集流地址；live_core_sdk_data.pull_data.stream_data 是嵌套 JSON 字符串，需二次解析 */
    private fun collectStreamUrls(node: Any?, out: MutableList<String>, depth: Int) {
        if (depth > 18 || out.size > 400) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) collectStreamUrls(node.opt(keys.next()), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) {
                collectStreamUrls(node.opt(i), out, depth + 1)
            }
            is String -> {
                val v = node.trim()
                when {
                    v.startsWith("http") && (".flv" in v || ".m3u8" in v) && v.length < 2000 -> out.add(v)
                    v.length > 40 && (v.startsWith("{") || v.startsWith("[")) &&
                        (v.contains("flv") || v.contains("m3u8") || v.contains("stream_data")) ->
                        runCatching { collectStreamUrls(JSONTokener(v).nextValue(), out, depth + 1) }
                }
            }
        }
    }

    private fun pickBest(urls: List<String>, ext: String): String? {
        var best: String? = null
        for (u in urls) {
            if (ext !in u) continue
            val b = best
            if (b == null || liveRank(u) < liveRank(b) ||
                (liveRank(u) == liveRank(b) && u.length > b.length)
            ) best = u
        }
        return best
    }

    private fun liveRank(u: String): Int = when {
        "_or4" in u -> 0
        "_uhd" in u -> 1
        "_hd" in u -> 2
        "_sd" in u -> 3
        "_ld" in u -> 4
        else -> 2
    }

    /**
     * 从用户主页数据里合并所有作品（支持滚动加载的多个 captures）。
     *
     * 真机 dump 的教训：主页 WebView 除了 /aweme/post/ 还会发关注动态、直播、
     * "喜欢/猜你喜欢"等接口，它们的响应同样带 aweme_list。旧实现递归扫到数组就并，
     * 结果 323 条里混进了 20 条来自 18 个陌生账号的推荐作品（上古王朝、科技补全123…）。
     * 现在两道闸：① 只认本人作品列表接口/结构；② 每条作品按 author.sec_uid 复核。
     */
    fun extractUserPosts(json: String, secUid: String = ""): PostPage {
        val root = JSONObject(json)
        val items = ArrayList<MediaMeta>()
        val seen = HashSet<String>()
        val captures = root.optJSONArray("__captures__")

        // 1) 按接口地址筛（注入脚本会给每条响应挂 __dy_url__）
        // 2) 同时按响应结构筛：老版本/注入失败没有 URL 标记时兜底，
        //    也能接住抖音换了 endpoint 路径但响应形态不变的情况
        val sources = ArrayList<JSONObject>()
        if (captures != null) {
            for (i in 0 until captures.length()) {
                val c = captures.optJSONObject(i) ?: continue
                if (isUserPostUrl(c.optString("__dy_url__", "")) ||
                    looksLikeUserPostResponse(c)
                ) sources.add(c)
            }
        }

        val arrays = if (sources.isNotEmpty()) {
            // 只取每个响应的顶层 aweme_list，不递归钻进单条作品里的"相关推荐"数组
            sources.mapNotNull { JsonScan.arr(it, "aweme_list", "awemeList") }
        } else {
            // 兼容早期 dump（没有 __captures__ 包装），仍按老办法递归找数组
            findAllAwemeArrays(root)
        }

        arrays.forEach { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!isAweme(o)) continue
                if (!belongsToUser(o, secUid)) continue
                val id = idOf(o) ?: continue
                if (!seen.add(id)) continue  // 去重
                runCatching { AwemeParser.fromAweme(o, "主页") }
                    .getOrNull()
                    ?.takeIf { m -> m.kind == Kind.VIDEO || m.kind == Kind.GALLERY }
                    ?.let { items.add(it) }
            }
        }
        if (items.isEmpty()) {
            throw ParseException("没抓到该主页的作品：可能页面被风控，或滚动时加载的是推荐流。重新登录后再试。")
        }
        // 滚动已到底（最后一页 has_more=0），不再暴露「再拉一页」以免走被风控的 API
        return PostPage(items, false, 0L)
    }

    /** 本人作品列表接口：/aweme/v1/web/aweme/post/，明确排除喜欢/收藏/推荐/关注等旁支 */
    private fun isUserPostUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val path = url.substringBefore('?')  // 只看路径，避开查询参数里的 follow/like 等字样
        if (Regex("""/(favorite|like|recommend|related|feed|tab|search|collection|follow|history|mix|hot)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(path)
        ) return false
        return path.contains("/aweme/post") || path.contains("/user/post")
    }

    /** 无 URL 标记时按响应结构判断（/aweme/post/ 响应特有字段） */
    private fun looksLikeUserPostResponse(c: JSONObject): Boolean =
        JsonScan.arr(c, "aweme_list", "awemeList") != null &&
            (c.has("post_serial") || c.has("time_list") ||
                c.has("request_item_cursor") || c.has("replace_series_cover"))

    /**
     * 作品是否属于目标账号。作者 sec_uid 缺失时放行（配合上面的接口过滤），
     * 有值且对不上就一定是别人家的作品 —— 推荐流、喜欢列表、相关推荐都在这里被挡掉。
     */
    fun belongsToUser(o: JSONObject, secUid: String): Boolean {
        if (secUid.isBlank()) return true
        val author = JsonScan.obj(o, "author", "authorInfo") ?: return true
        val sid = JsonScan.str(author, "sec_uid", "secUid", "sec_user_id") ?: return true
        return sid == secUid
    }

    /** 递归找出所有"作品密集数组" */
    private fun findAllAwemeArrays(node: Any?, result: MutableList<JSONArray> = mutableListOf()): List<JSONArray> {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONArray && isAwemeArray(v)) result.add(v)
                    findAllAwemeArrays(v, result)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findAllAwemeArrays(node.opt(i), result)
            }
        }
        return result
    }

    private fun isAweme(o: JSONObject): Boolean {
        val hasId = o.has("aweme_id") || o.has("awemeId") || o.has("item_id") || o.has("itemId")
        val hasMedia = o.has("video") || o.has("videoInfo") || o.has("images") || o.has("image_post_info")
        return hasId && hasMedia
    }

    /** 主页作品数组：前面若干个元素里作品对象密集出现 */
    private fun isAwemeArray(a: JSONArray): Boolean {
        if (a.length() == 0) return false
        var cnt = 0
        for (i in 0 until minOf(a.length(), 12)) {
            val o = a.optJSONObject(i) ?: continue
            if (isAweme(o)) cnt++
        }
        return cnt >= 2
    }

    private fun findFirst(node: Any?, pred: (JSONObject) -> Boolean): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (pred(node)) return node
                val keys = node.keys()
                while (keys.hasNext()) {
                    findFirst(node.opt(keys.next()), pred)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findFirst(node.opt(i), pred)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstArray(node: Any?, pred: (JSONArray) -> Boolean): JSONArray? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONArray && pred(v)) return v
                    findFirstArray(v, pred)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findFirstArray(node.opt(i), pred)?.let { return it }
            }
        }
        return null
    }
}
