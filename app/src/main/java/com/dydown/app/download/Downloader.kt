package com.dydown.app.download

import com.dydown.app.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 断点续传下载。
 * 抖音 CDN 支持 Range，所以中断后重入只补尾部，不用从头再来。
 */
object Downloader {

    suspend fun toFile(
        url: String,
        dest: File,
        referer: String? = "https://www.douyin.com/",
        onProgress: suspend (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        var start = if (dest.exists()) dest.length() else 0L

        val builder = Http.request(url, referer).newBuilder()
        if (start > 0) builder.header("Range", "bytes=$start-")
        val request: Request = builder.build()

        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code}")
            }
            val append = start > 0 && resp.code == 206
            if (!append) start = 0L

            val body = resp.body ?: throw IOException("响应为空")
            val total = if (body.contentLength() > 0) start + body.contentLength() else -1L

            body.byteStream().use { input ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var written = start
                    var lastEmit = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250) {
                            lastEmit = now
                            out.flush()
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, total)
                }
            }
        }
        dest
    }
}
