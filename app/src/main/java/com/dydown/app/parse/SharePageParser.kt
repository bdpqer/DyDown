package com.dydown.app.parse

import com.dydown.app.core.Http
import com.dydown.app.core.JsonScan
import com.dydown.app.core.MediaMeta
import com.dydown.app.core.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 通道 A：分享页 SSR。
 * 优点：不需要签名，不需要登录（带 cookie 更稳）。
 * 缺点：只覆盖单条作品，且风控严格时会返回空 item_list。
 */
class SharePageParser {

    suspend fun parse(awemeId: String, gallery: Boolean): MediaMeta = withContext(Dispatchers.IO) {
        val path = if (gallery) "note" else "video"
        val html = Http.getString(
            "https://www.iesdouyin.com/share/$path/$awemeId/",
            referer = "https://www.iesdouyin.com/"
        )

        val root = JsonScan.extractAfter(html, "window._ROUTER_DATA")
            ?: throw ParseException("分享页没返回数据（可能被重定向到验证页）")

        val list = JsonScan.findArray(root, "item_list")
            ?: throw ParseException("分享页结构变了，找不到 item_list")

        if (list.length() == 0) {
            throw ParseException("item_list 为空：cookie 失效或被限流，去「设置」重新登录")
        }

        AwemeParser.fromAweme(list.getJSONObject(0), "分享页")
    }

    /** 老接口，偶尔还能用，作为分享页的第二层兜底 */
    suspend fun parseByLegacyApi(awemeId: String): MediaMeta = withContext(Dispatchers.IO) {
        val text = Http.getString(
            "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$awemeId",
            referer = "https://www.iesdouyin.com/"
        )
        val root = JSONObject(text)
        val list = JsonScan.arr(root, "item_list")
        if (list == null || list.length() == 0) throw ParseException("接口返回空列表")
        AwemeParser.fromAweme(list.getJSONObject(0), "老接口")
    }
}
