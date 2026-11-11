package com.dydown.app.data

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("dydown", Context.MODE_PRIVATE)

    /** 视频保存目录（MediaStore 相对路径） */
    var saveDir: String
        get() = sp.getString("saveDir", "Movies/DyDown") ?: "Movies/DyDown"
        set(v) = sp.edit().putString("saveDir", v).apply()

    /** 图集保存目录（MediaStore 相对路径，独立于视频） */
    var imageDir: String
        get() = sp.getString("imageDir", "Pictures/DyDown") ?: "Pictures/DyDown"
        set(v) = sp.edit().putString("imageDir", v).apply()

    /** 批量下载时按 awemeId 静默跳过已下载成功的作品 */
    var batchSkipDownloaded: Boolean
        get() = sp.getBoolean("batchSkipDownloaded", true)
        set(v) = sp.edit().putBoolean("batchSkipDownloaded", v).apply()

    /** 默认选择的清晰度下标 */
    var qualityIndex: Int
        get() = sp.getInt("qualityIndex", 0)
        set(v) = sp.edit().putInt("qualityIndex", v).apply()

    /** 分享页通道失败时是否用 yt-dlp 兜底 */
    var ytDlpFallback: Boolean
        get() = sp.getBoolean("ytDlpFallback", true)
        set(v) = sp.edit().putBoolean("ytDlpFallback", v).apply()

    var ytDlpVersion: String
        get() = sp.getString("ytDlpVersion", "未初始化") ?: "未初始化"
        set(v) = sp.edit().putString("ytDlpVersion", v).apply()
}
