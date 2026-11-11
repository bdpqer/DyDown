package com.dydown.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 落库到系统相册/媒体库。
 * API 29+ 用 MediaStore + IS_PENDING，不需要任何存储权限；
 * API 26-28 退回公共目录 + 扫描。
 */
class MediaStoreWriter(private val context: Context) {

    fun saveVideo(
        src: File,
        displayName: String,
        relativePath: String,
        mime: String = "video/mp4"
    ): Uri = save(src, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, displayName, mime, relativePath, true)

    fun saveImage(src: File, displayName: String, relativePath: String, mime: String = "image/jpeg"): Uri =
        save(src, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, displayName, mime, relativePath, false)

    fun saveAudio(src: File, displayName: String, relativePath: String): Uri =
        save(src, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, displayName, "audio/mpeg", relativePath, false)

    private fun save(
        src: File,
        collection: Uri,
        displayName: String,
        mime: String,
        relativePath: String,
        isVideo: Boolean
    ): Uri {
        val resolver = context.contentResolver
        val path = if (relativePath.endsWith("/")) relativePath else "$relativePath/"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore 插入失败")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out, 128 * 1024) }
                } ?: throw IllegalStateException("无法写入 $uri")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return uri
        }

        // 旧系统：直接写公共目录
        val base = if (isVideo) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        val dir = File(base, path.removeSuffix("/")).apply { mkdirs() }
        val file = File(dir, displayName)
        src.copyTo(file, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(file)
    }
}
