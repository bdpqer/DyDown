package com.dydown.app.parse

import com.dydown.app.core.Asset
import com.dydown.app.core.JsonScan
import com.dydown.app.core.Kind
import com.dydown.app.core.MediaMeta
import org.json.JSONObject

/**
 * 作品 JSON → MediaMeta。
 * 分享页是 snake_case，Web 接口是 camelCase，字段名两套都试，
 * 这样同一份解析逻辑能喂给所有通道，改版时只需要改这里。
 */
object AwemeParser {

    fun fromAweme(item: JSONObject, source: String): MediaMeta {
        val video = JsonScan.obj(item, "video", "videoInfo")
        val images = gallery(item)
        // 实况照片（live photo）：真正的视频藏在 images[].video 里，
        // 顶层 video 往往只是 BGM 容器（play_addr 是 mp3）。抖音"播的是视频"就是它。
        val clips = liveClips(item)
        val kind = when {
            clips.isNotEmpty() -> Kind.VIDEO
            images.isNotEmpty() -> Kind.GALLERY
            else -> Kind.VIDEO
        }
        val videos = when {
            clips.isNotEmpty() -> clips
            kind == Kind.VIDEO -> videoAssets(video).filterNot { isAudio(it.url) }
            else -> emptyList()
        }

        return MediaMeta(
            awemeId = JsonScan.str(item, "aweme_id", "awemeId", "item_id", "itemId") ?: "",
            kind = kind,
            title = JsonScan.str(item, "desc", "title", "caption")?.trim().orEmpty(),
            author = JsonScan.str(JsonScan.obj(item, "author", "authorInfo"), "nickname", "unique_id") ?: "",
            cover = coverOf(video) ?: images.firstOrNull(),
            durationMs = video?.optLong("duration", 0) ?: 0L,
            videos = videos,
            images = images,
            music = musicOf(item),
            source = source
        )
    }

    /** 实况照片/动态图片：images[i].video 里的短片，一个图位一段 */
    private fun liveClips(item: JSONObject): List<Asset> {
        val arr = JsonScan.arr(item, "images", "imageList", "image_list") ?: return emptyList()
        val out = ArrayList<Asset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = JsonScan.obj(o, "video", "videoInfo") ?: continue
            val asset = videoAssets(v).firstOrNull { !isAudio(it.url) } ?: continue
            out.add(Asset(asset.url, "实况${i + 1}"))
        }
        return out
    }

    /** 音频不是视频：BGM 容器的 play_addr 是 mp3，别当成视频下载 */
    private fun isAudio(url: String): Boolean =
        url.contains(".mp3", ignoreCase = true) ||
            url.contains("ies-music", ignoreCase = true) ||
            url.contains("/music/", ignoreCase = true)

    private fun coverOf(video: JSONObject?): String? {
        if (video == null) return null
        val candidates = listOf("cover", "origin_cover", "originCover", "dynamic_cover", "dynamicCover")
        for (k in candidates) {
            JsonScan.firstUrl(JsonScan.obj(video, k))?.let { return JsonScan.normalize(it) }
        }
        return null
    }

    private fun videoAssets(video: JSONObject?): List<Asset> {
        if (video == null) return emptyList()
        val scored = mutableListOf<Pair<Int, Asset>>()

        // 1. bit_rate 是官方给的清晰度档位表
        JsonScan.arr(video, "bit_rate", "bitRate")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val playAddr = JsonScan.obj(o, "play_addr", "playAddr", "play_addr_265", "playAddr265")
                val url = JsonScan.firstUrl(playAddr) ?: continue
                val bitrate = o.optInt("bit_rate", 0)
                val gear = JsonScan.str(o, "gear_name", "gearName")
                    ?: if (bitrate > 0) "${bitrate / 1000}kbps" else "档位${i + 1}"
                scored.add(bitrate to Asset(JsonScan.normalize(url), gear))
            }
        }

        // 2. 兜底：video.play_addr 本身
        JsonScan.obj(video, "play_addr", "playAddr")?.let { pa ->
            JsonScan.firstUrl(pa)?.let { scored.add(0 to Asset(JsonScan.normalize(it), "默认")) }
        }
        JsonScan.obj(video, "play_addr_h264", "playAddrH264")?.let { pa ->
            JsonScan.firstUrl(pa)?.let { scored.add(0 to Asset(JsonScan.normalize(it), "H264")) }
        }

        return scored.sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.url }
    }

    private fun gallery(item: JSONObject): List<String> {
        val arr = JsonScan.arr(item, "images", "imageList", "image_list")
            ?: JsonScan.arr(JsonScan.obj(item, "image_post_info", "imagePostInfo"), "images")
            ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = JsonScan.firstUrl(o)
                ?: JsonScan.obj(o, "display_image", "displayImage", "origin_image", "originImage")
                    ?.let { JsonScan.firstUrl(it) }
                ?: JsonScan.arr(o, "download_url_list", "downloadUrlList")
                    ?.let { list ->
                        (0 until list.length()).firstNotNullOfOrNull {
                            list.optString(it, "").takeIf { s -> s.startsWith("http") }
                        }
                    }
            u?.let { out.add(JsonScan.normalize(it)) }
        }
        return out
    }

    private fun musicOf(item: JSONObject): Asset? {
        val music = JsonScan.obj(item, "music") ?: return null
        val url = JsonScan.firstUrl(JsonScan.obj(music, "play_url", "playUrl")) ?: return null
        val title = JsonScan.str(music, "title", "song_name") ?: "背景音乐"
        return Asset(JsonScan.normalize(url), title)
    }
}
