package com.dydown.app.core

/** 一条可下载的媒体资源（视频档位 / 音频） */
data class Asset(val url: String, val label: String)

enum class Kind { VIDEO, GALLERY, LIVE }

/** 统一解析结果，四条通道都归一到这个结构 */
data class MediaMeta(
    val awemeId: String = "",
    val kind: Kind = Kind.VIDEO,
    val title: String = "",
    val author: String = "",
    val cover: String? = null,
    val durationMs: Long = 0L,
    /** 已按清晰度从高到低排序 */
    val videos: List<Asset> = emptyList(),
    val images: List<String> = emptyList(),
    val music: Asset? = null,
    /** 通道标识：分享页 / 页面接口 / yt-dlp */
    val source: String = ""
)

data class LiveInfo(
    val roomId: String = "",
    val title: String = "",
    val author: String = "",
    val living: Boolean = false,
    val flv: String? = null,
    val hls: String? = null,
    val cover: String? = null
)

/** 主页批量分页结果 */
data class PostPage(
    val items: List<MediaMeta>,
    val hasMore: Boolean,
    val cursor: Long
)

class ParseException(message: String) : Exception(message)
