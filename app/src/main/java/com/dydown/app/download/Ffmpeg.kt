package com.dydown.app.download

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * FLV → MP4 无损转封装（-c copy，不重新编码）。
 *
 * youtubedl-android 0.14.0 只暴露了 FFmpeg.init()，没有 execute()。
 * 它的 ffmpeg 二进制是打包成 libffmpeg.zip.so、init 时解压到私有目录的，
 * 所以这里做一次"找出可执行文件 + ProcessBuilder"的尽力而为。
 *
 * 找不到 / 没有执行权限都无所谓：录制结果本身就是合法 FLV，
 * 保留 .flv 也能在 App 内（ExoPlayer）正常播放，只是相册不认。
 */
object Ffmpeg {

    @Volatile
    private var binary: File? = null

    @Volatile
    private var probed = false

    private fun locate(context: Context): File? {
        if (probed) return binary
        probed = true
        binary = runCatching {
            FFmpeg.getInstance().init(context.applicationContext)

            // 1) 优先用 nativeLibraryDir 里随 APK 打包的可执行体（有执行权限保证）
            val native = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            if (native.exists() && native.length() > 100_000) return@runCatching native

            // 2) 退一步：init() 解压出来的目录
            context.filesDir.walkTopDown()
                .maxDepth(4)
                .firstOrNull { it.isFile && it.name == "ffmpeg" && it.length() > 100_000 }
        }.getOrNull()
        return binary
    }

    suspend fun toMp4(context: Context, src: File, dest: File): Boolean = withContext(Dispatchers.IO) {
        if (!src.exists() || src.length() < 65_536) return@withContext false
        val bin = locate(context) ?: return@withContext false

        runCatching {
            val process = ProcessBuilder(
                bin.absolutePath,
                "-y",
                "-i", src.absolutePath,
                "-c", "copy",
                "-movflags", "+faststart",
                dest.absolutePath
            ).redirectErrorStream(true).start()

            val finished = process.waitFor(180, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && dest.exists() && dest.length() > 0
        }.getOrDefault(false)
    }
}
