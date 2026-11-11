package com.dydown.app.ytdlp

import android.content.Context
import com.dydown.app.core.Asset
import com.dydown.app.core.Http
import com.dydown.app.core.Kind
import com.dydown.app.core.MediaMeta
import com.dydown.app.core.ParseException
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通道 C：内置 yt-dlp（python + ffmpeg 打进 APK）。
 *
 * 它是"长期稳定性"的来源：抖音改版后通常几天内上游就修好，
 * 你只需要在设置页点一次「更新 yt-dlp」，不用重新发版。
 */
object YtDlp {

    @Volatile
    private var initialized = false

    @Throws(Exception::class)
    fun init(context: Context) {
        if (initialized) return
        YoutubeDL.getInstance().init(context.applicationContext)
        initialized = true
    }

    suspend fun version(context: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            init(context)
            YoutubeDL.getInstance().version(context) ?: "未知"
        }.getOrDefault("未就绪")
    }

    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        init(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context)
        val version = YoutubeDL.getInstance().version(context) ?: "未知"
        "$version (${status.name})"
    }

    /** 只解析不下载，拿到直链后交给自己的下载引擎（这样可以做断点续传和落库） */
    suspend fun probe(context: Context, url: String): MediaMeta = withContext(Dispatchers.IO) {
        init(context)
        val cookies = runCatching { Http.exportNetscape(context.filesDir) }.getOrNull()

        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-warnings")
        request.addOption("--no-playlist")
        request.addOption("--socket-timeout", "15")
        if (cookies != null && cookies.length() > 60) {
            request.addOption("--cookies", cookies.absolutePath)
        }

        val response = YoutubeDL.getInstance().execute(request)
        val out = response.out ?: throw ParseException("yt-dlp 没有输出")
        toMeta(out)
    }

    private fun toMeta(json: String): MediaMeta {
        val o = JSONObject(json)
        val formats = o.optJSONArray("formats") ?: JSONArray()
        val scored = ArrayList<Pair<Int, Asset>>()

        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val u = f.optString("url", "")
            if (!u.startsWith("http")) continue
            if (f.optString("vcodec", "none") == "none") continue
            val h = f.optInt("height", 0)
            val note = f.optString("format_note", "")
            val label = when {
                h > 0 -> "${h}p"
                note.isNotEmpty() -> note
                else -> "档位${i + 1}"
            }
            scored.add(h to Asset(u, label))
        }

        // 没有 formats（抖音有时只给一个合并流）就用 url 字段
        if (scored.isEmpty()) {
            val direct = o.optString("url", "")
            if (direct.startsWith("http")) scored.add(0 to Asset(direct, "默认"))
        }

        val thumbs = o.optString("thumbnail", "")
        val videos = scored.sortedByDescending { it.first }.map { it.second }.distinctBy { it.url }

        return MediaMeta(
            awemeId = o.optString("id", ""),
            kind = Kind.VIDEO,
            title = o.optString("title", "").ifEmpty { o.optString("description", "") },
            author = o.optString("uploader", "").ifEmpty { o.optString("channel", "") },
            cover = thumbs.ifEmpty { null },
            durationMs = (o.optDouble("duration", 0.0) * 1000).toLong(),
            videos = videos,
            source = "yt-dlp"
        )
    }

    /** 供需要完整元数据时使用（备用） */
    @Suppress("unused")
    suspend fun info(context: Context, url: String): VideoInfo = withContext(Dispatchers.IO) {
        init(context)
        YoutubeDL.getInstance().getInfo(url)
    }
}
