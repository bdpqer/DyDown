package com.dydown.app.download

import android.content.Context
import android.net.Uri
import com.dydown.app.data.Prefs
import com.dydown.app.data.TaskEntity
import kotlinx.coroutines.CancellationException
import java.io.File

/** 下载完成后的成品信息，由 DownloadService 回填到历史记录 */
data class DownloadResult(
    val fileUri: String? = null,
    val savedDir: String? = null,
    val bytes: Long = 0
)

/**
 * 一个任务 = 视频 / 图集（图集额外带 BGM）。
 * 进度回调是 suspend 的，方便直接写库和刷通知。
 */
class DownloadEngine(
    private val context: Context,
    private val prefs: Prefs
) {

    private val writer = MediaStoreWriter(context)

    suspend fun run(task: TaskEntity, onProgress: suspend (Int, Long, Long) -> Unit): DownloadResult =
        when (task.kind) {
            "GALLERY" -> gallery(task, onProgress)
            else -> video(task, onProgress)
        }

    private suspend fun video(task: TaskEntity, onProgress: suspend (Int, Long, Long) -> Unit): DownloadResult {
        val url = task.videoUrl?.takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("任务缺少视频地址")

        val tmp = File(context.cacheDir, "dy_${task.id}.mp4")
        try {
            Downloader.toFile(url, tmp) { bytes, total ->
                onProgress(percent(bytes, total), bytes, total)
            }
            val name = buildName(task, "mp4")
            val bytes = tmp.length()
            val uri = writer.saveVideo(tmp, name, prefs.saveDir)
            return DownloadResult(uri.toString(), prefs.saveDir, bytes)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private suspend fun gallery(task: TaskEntity, onProgress: suspend (Int, Long, Long) -> Unit): DownloadResult {
        val urls = task.images.split('\n').filter { it.startsWith("http") }
        if (urls.isEmpty()) throw IllegalStateException("图集地址为空")

        // 图集落到设置页单独指定的目录（默认 Pictures/DyDown，相册扫描区），
        // 不再从视频目录推导；每个作品再建一个 gallery_<id> 子目录避免图片混在一起
        val base = prefs.imageDir.trim().replace('\\', '/').trim('/').ifBlank { "Pictures/DyDown" }
        val rel = "$base/gallery_${task.awemeId}"
        var done = 0
        var accBytes = 0L
        var firstUri: String? = null

        for ((index, url) in urls.withIndex()) {
            val ext = imgExt(url)
            val tmp = File(context.cacheDir, "dy_${task.id}_$index.$ext")
            try {
                Downloader.toFile(url, tmp, referer = "https://www.douyin.com/") { bytes, _ ->
                    val pct = (index * 100) / urls.size
                    onProgress(pct.coerceIn(0, 99), accBytes + bytes, -1)
                }
                val uri = writer.saveImage(tmp, "${task.awemeId}_${index + 1}.$ext", rel, imgMime(ext))
                if (firstUri == null) firstUri = uri.toString()
                done++
                accBytes += tmp.length()
                onProgress((done * 100 / urls.size), accBytes, -1)
            } finally {
                runCatching { tmp.delete() }
            }
        }

        // 图集自带的 BGM 一起收下
        task.musicUrl?.takeIf { it.startsWith("http") }?.let { m ->
            val tmp = File(context.cacheDir, "dy_${task.id}_bgm.mp3")
            try {
                Downloader.toFile(m, tmp, referer = "https://www.douyin.com/") { _, _ -> }
                writer.saveAudio(tmp, "${task.awemeId}_bgm.mp3", "Music/DyDown")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // BGM 拿不到不影响主流程
            } finally {
                runCatching { tmp.delete() }
            }
        }
        return DownloadResult(firstUri, rel, accBytes)
    }

    private fun percent(bytes: Long, total: Long): Int =
        if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0

    /** 按 URL 特征识别图片扩展名（抖音图集常见 webp/png/jpg） */
    private fun imgExt(url: String): String = when {
        url.contains(".webp", true) -> "webp"
        url.contains(".png", true) -> "png"
        url.contains(".jpeg", true) -> "jpeg"
        else -> "jpg"
    }

    private fun imgMime(ext: String): String = when (ext) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }

    private fun buildName(task: TaskEntity, ext: String): String {
        val base = task.title.ifBlank { "douyin_${task.awemeId}" }
        val safe = base.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").take(60).trim()
        return "${safe}_${task.awemeId}.$ext"
    }

    @Suppress("unused")
    private fun Uri.describe() = toString()
}
