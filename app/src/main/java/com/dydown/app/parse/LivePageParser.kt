package com.dydown.app.parse

import com.dydown.app.core.LiveInfo
import com.dydown.app.core.ParseException

/**
 * 抖音直播页（live.douyin.com/{webRid}）SSR 直取流地址。
 *
 * 真机验证（2026-11-11）：该页面约 1.1MB，直接内嵌完整流地址，无需签名、无需 WebView：
 *   "stream_url":{"flv_pull_url":{"FULL_HD1":"http://.../xxx_or4.flv?auth_key=...\u0026..."},
 *                 "hls_pull_url_map":{"FULL_HD1":"http://.../xxx.m3u8?..."}}
 *   "web_stream_url":{...} / live_core_sdk_data.pull_data.stream_data 里还有更多档位。
 *
 * 流地址用全文正则抠（不写死字段层级）；URL 里的 \u0026 必须还原成 &，否则 auth_key 校验失败。
 *
 * 房间信息则必须**只从 roomStore.roomInfo.room 对象里取**：整页有 397 个 title，
 * 前 10 个全是页脚导航（广告投放/用户服务协议/抖音电商…），取第一个会把卡片显示成「抖音电商」。
 */
object LivePageParser {

    // 用拼接构造，避开 Kotlin 词法器对 \uXXXX 的预处理
    private val unicodeEsc = Regex("\\\\" + "u([0-9A-Fa-f]{4})")
    private val urlRegex = Regex("""https?://[^\s"'<>\\]{10,800}?\.(?:flv|m3u8)[^\s"'<>\\]{0,800}""")
    private val roomAnchor = Regex(""""room"\s*:\s*\{\s*"id_str"""")
    private val titleRegex = Regex(""""title"\s*:\s*"([^"]{1,120})"""")
    private val nickRegex = Regex(""""nickname"\s*:\s*"([^"]{1,60})"""")
    private val coverRegex = Regex(""""url_list"\s*:\s*\[\s*"(https?://[^"]+)"""")

    /** 页脚/导航文案黑名单：兜底时跳过，别把「抖音电商」当房间标题 */
    private val navWords = setOf(
        "广告投放", "用户服务协议", "隐私政策", "账号找回", "联系我们", "加入我们",
        "友情链接", "站点地图", "下载抖音", "抖音电商", "抖音商城", "网络谣言曝光台",
        "网上有害信息举报", "抖音直播电脑版_抖音直播网页版入口_抖音直播"
    )

    fun parse(html: String, target: LiveTarget): LiveInfo {
        val s = unicodeEsc.replace(html.replace("\\\\", "\\")) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        val urls = urlRegex.findAll(s)
            .map { it.value.trimEnd('}', ')', ',', ']', ';') }
            .distinct()
            .toList()

        val flv = pickBest(urls, ".flv")
        val hls = pickBest(urls, ".m3u8")
        if (flv == null && hls == null) {
            throw ParseException("直播页里没有流地址（可能已下播、需要登录或页面被风控）")
        }

        // 只截 room 对象起始后的一段，避免读到后面别的作品/导航栏字段
        val room = roomAnchor.find(s)?.let { m ->
            s.substring(m.range.first, minOf(s.length, m.range.first + 20_000))
        }.orEmpty()

        val status = firstGroup(room, """"status"\s*:\s*(\d)""")?.toIntOrNull() ?: 0
        return LiveInfo(
            roomId = firstGroup(room, """"id_str"\s*:\s*"(\d{6,})"""")
                ?: target.roomId.ifBlank { target.webRid },
            title = pickTitle(room, s),
            author = pickNickname(room, s),
            living = status == 2 || flv != null || hls != null,
            flv = flv,
            hls = hls,
            cover = firstGroup(room, coverRegex)
        )
    }

    private fun pickTitle(room: String, all: String): String {
        // 1) room 对象里的 title 才是真房间名，例如「欢迎来到小晴的直播间」
        firstGroup(room, titleRegex)?.takeIf { isRoomTitle(it) }?.let { return it }
        // 2) 兜底：全文找第一个不像页脚导航的 title
        for (m in titleRegex.findAll(all)) {
            val v = m.groupValues[1]
            if (isRoomTitle(v)) return v
        }
        return "抖音直播"
    }

    private fun isRoomTitle(v: String): Boolean =
        v.isNotBlank() && v.length >= 4 && v !in navWords &&
            !v.startsWith("抖音直播电脑版") && !v.startsWith("网上有害信息")

    private fun pickNickname(room: String, all: String): String {
        // 页面里第一个 nickname 常是 "$undefined"，要跳过占位值
        val seq = sequenceOf(room, all)
        for (seg in seq) {
            for (m in nickRegex.findAll(seg)) {
                val v = m.groupValues[1]
                if (v.isNotBlank() && v != "\$undefined" && v != "undefined" && v != "null") return v
            }
        }
        return ""
    }

    /** 同档位里取参数更完整（更长）的那条，避免拿到缺 auth_key 的短链 */
    private fun pickBest(urls: List<String>, ext: String): String? {
        var best: String? = null
        for (u in urls) {
            if (ext !in u) continue
            val b = best
            if (b == null || rank(u) < rank(b) || (rank(u) == rank(b) && u.length > b.length)) best = u
        }
        return best
    }

    /** 档位权重：or4 原画 > uhd > hd > sd > ld */
    private fun rank(u: String): Int = when {
        "_or4" in u -> 0
        "_uhd" in u -> 1
        "_hd" in u -> 2
        "_sd" in u -> 3
        "_ld" in u -> 4
        else -> 2
    }

    private fun firstGroup(s: String, regex: Regex): String? =
        runCatching { regex.find(s)?.groupValues?.getOrNull(1) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun firstGroup(s: String, pattern: String): String? = firstGroup(s, Regex(pattern))
}
