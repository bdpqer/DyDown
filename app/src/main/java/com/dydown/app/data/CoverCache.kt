package com.dydown.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 抖音封面地址带签名，几小时就失效。历史记录只存本地缓存路径，
 * 列表读 filesDir/covers/ 下的文件，隔天再看也不会变空白。
 */
object CoverCache {

    suspend fun save(context: Context, key: String, url: String?): String? = withContext(Dispatchers.IO) {
        if (key.isBlank() || url.isNullOrBlank() || !url.startsWith("http")) return@withContext null
        val safeKey = key.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(64)
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "$safeKey.jpg")
        if (file.exists() && file.length() > 0) return@withContext file.absolutePath

        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "https://www.douyin.com/")
            conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out, 32 * 1024) } }
            conn.disconnect()
            file.absolutePath.takeIf { file.length() > 0 }
        }.getOrNull()
    }
}
