package com.dydown.app.download

import com.dydown.app.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 直播录制：不解析 FLV 结构，直接把 HTTP 流按字节写到磁盘。
 * 这样做的好处是任何协议变体都不影响录制（HLS 除外，HLS 走 ffmpeg）。
 *
 * 注意：不能复用 Http.client —— 它 readTimeout=30s，直播是长连接，
 * 主播稍一卡顿/静音就会被掐断。这里用读超时为 0（无限）的专用 client。
 */
object LiveRecorder {

    private val liveClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun record(
        url: String,
        dest: File,
        onBytes: suspend (Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val request = Http.request(url, referer = "https://live.douyin.com/")
        liveClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("直播流 HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("直播流为空")

            var total = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (currentCoroutineContext().isActive) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        out.flush()
                        total += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 500) {
                            lastEmit = now
                            onBytes(total)
                        }
                    }
                    onBytes(total)
                }
            }
        }
        dest
    }
}
