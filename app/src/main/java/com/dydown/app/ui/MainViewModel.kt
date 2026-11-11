package com.dydown.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dydown.app.DyDownApp
import com.dydown.app.core.LiveInfo
import com.dydown.app.core.MediaMeta
import com.dydown.app.data.TStatus
import com.dydown.app.data.TaskEntity
import com.dydown.app.data.TaskRepo
import com.dydown.app.parse.ParseRepository
import com.dydown.app.service.DownloadService
import com.dydown.app.service.LiveRecordService
import com.dydown.app.ytdlp.YtDlp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Ok<T>(val data: T) : UiState<T>
    data class Err(val message: String) : UiState<Nothing>
}

data class BatchUi(
    val loading: Boolean = false,
    val secUid: String = "",
    val items: List<MediaMeta> = emptyList(),
    val selected: Set<String> = emptySet(),
    val error: String? = null,
    val pages: Int = 0,
    val hasMore: Boolean = false,
    val cursor: Long = 0L,
    val total: Int = 0,
    val loadingMore: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val prefs = (app as DyDownApp).prefs
    private val repo = ParseRepository(ctx, prefs)

    var parseState by mutableStateOf<UiState<MediaMeta>>(UiState.Idle)
        private set
    var qualityIndex by mutableStateOf(prefs.qualityIndex)
        private set
    var batch by mutableStateOf(BatchUi())
        private set
    var liveState by mutableStateOf<UiState<LiveInfo>>(UiState.Idle)
        private set
    var recording by mutableStateOf(false)
        private set
    var ytDlpVersion by mutableStateOf(prefs.ytDlpVersion)
        private set
    var updatingYtDlp by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    val tasks: StateFlow<List<TaskEntity>> = TaskRepo.observe(ctx)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    fun clearToast() { toast = null }

    fun parse(text: String) {
        if (text.isBlank()) {
            parseState = UiState.Err("先粘贴一条抖音链接")
            return
        }
        parseState = UiState.Loading
        viewModelScope.launch {
            try {
                val meta = repo.parse(text)
                qualityIndex = 0
                parseState = UiState.Ok(meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                parseState = UiState.Err(e.message ?: "解析失败")
            }
        }
    }

    fun selectQuality(index: Int) {
        qualityIndex = index
        prefs.qualityIndex = index
    }

    fun enqueue(meta: MediaMeta, index: Int = 0, silent: Boolean = false) {
        if (meta.videos.isEmpty() && meta.images.isEmpty()) return
        val task = TaskEntity(
            awemeId = meta.awemeId,
            kind = meta.kind.name,
            title = meta.title.ifBlank { "抖音作品" },
            author = meta.author,
            cover = meta.cover,
            videoUrl = meta.videos.getOrNull(index)?.url,
            images = meta.images.joinToString("\n"),
            musicUrl = meta.music?.url,
            quality = meta.videos.getOrNull(index)?.label ?: if (meta.images.isNotEmpty()) "${meta.images.size} 张图" else ""
        )
        viewModelScope.launch {
            TaskRepo.insert(ctx, task)
            DownloadService.start(ctx)
            if (!silent) toast = "已加入下载队列"
        }
    }

    fun loadProfile(text: String) {
        if (text.isBlank()) {
            batch = batch.copy(error = "先粘贴主页链接")
            return
        }
        batch = BatchUi(loading = true)
        viewModelScope.launch {
            try {
                val secUid = repo.resolveUser(text)
                var cursor = 0L
                var page = repo.userPosts(secUid, cursor)
                val all = ArrayList<MediaMeta>(page.items)
                var pagesDone = 1
                cursor = page.cursor
                // 接口翻页可用时一直翻到底；999 只是防死循环的安全阀，不是用户可见的页数上限
                while (page.hasMore && cursor != 0L && pagesDone < 999) {
                    kotlinx.coroutines.delay(600)  // 翻页间隔，避免连续请求触发风控
                    try {
                        page = repo.userPosts(secUid, cursor)
                    } catch (e: Exception) {
                        break  // 翻页被风控/失败时保留已拉到的条目，不要全清
                    }
                    all.addAll(page.items)
                    cursor = page.cursor
                    pagesDone++
                }
                batch = BatchUi(
                    loading = false,
                    secUid = secUid,
                    items = all.distinctBy { it.awemeId },
                    selected = all.map { it.awemeId }.toSet(),
                    error = null,
                    pages = pagesDone,
                    hasMore = page.hasMore,
                    cursor = cursor,
                    total = all.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                batch = BatchUi(loading = false, error = e.message ?: "拉取失败")
            }
        }
    }

    fun loadMore() {
        val b = batch
        if (b.loadingMore || !b.hasMore || b.secUid.isEmpty() || b.cursor == 0L) return
        batch = b.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = repo.userPosts(b.secUid, b.cursor)
                val merged = (b.items + page.items).distinctBy { it.awemeId }
                batch = b.copy(
                    loadingMore = false,
                    items = merged,
                    pages = b.pages + 1,
                    cursor = page.cursor,
                    hasMore = page.hasMore,
                    total = merged.size
                )
            } catch (e: Exception) {
                batch = b.copy(loadingMore = false, error = e.message)
            }
        }
    }

    fun toggle(id: String) {
        val s = batch.selected.toMutableSet()
        if (!s.add(id)) s.remove(id)
        batch = batch.copy(selected = s)
    }

    fun selectAll(all: Boolean) {
        batch = batch.copy(selected = if (all) batch.items.map { it.awemeId }.toSet() else emptySet())
    }

    fun downloadSelected() {
        val chosen = batch.items.filter { batch.selected.contains(it.awemeId) }
        if (chosen.isEmpty()) {
            toast = "还没选作品"
            return
        }
        viewModelScope.launch {
            chosen.forEach { meta ->
                if (meta.videos.isEmpty() && meta.images.isEmpty()) return@forEach
                TaskRepo.insert(
                    ctx,
                    TaskEntity(
                        awemeId = meta.awemeId,
                        kind = meta.kind.name,
                        title = meta.title.ifBlank { "抖音作品" },
                        author = meta.author,
                        cover = meta.cover,
                        videoUrl = meta.videos.firstOrNull()?.url,
                        images = meta.images.joinToString("\n"),
                        musicUrl = meta.music?.url,
                        quality = meta.videos.firstOrNull()?.label ?: ""
                    )
                )
            }
            DownloadService.start(ctx)
            toast = "已加入 ${chosen.size} 个任务"
        }
    }

    fun resolveLive(text: String) {
        if (text.isBlank()) {
            liveState = UiState.Err("先粘贴直播间链接")
            return
        }
        liveState = UiState.Loading
        viewModelScope.launch {
            try {
                liveState = UiState.Ok(repo.live(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                liveState = UiState.Err(e.message ?: "解析直播间失败")
            }
        }
    }

    fun startRecord(info: LiveInfo) {
        val url = info.flv ?: info.hls
        if (url == null) {
            toast = "没有拿到流地址"
            return
        }
        viewModelScope.launch {
            val taskId = TaskRepo.insert(
                ctx,
                TaskEntity(
                    awemeId = info.roomId,
                    kind = "LIVE",
                    title = info.title,
                    author = info.author,
                    cover = info.cover,
                    liveUrl = url,
                    quality = "直播"
                )
            )
            LiveRecordService.start(ctx, taskId, url, info.title)
            recording = true
            toast = "开始录制"
        }
    }

    fun stopRecord() {
        LiveRecordService.stop(ctx)
        recording = false
    }

    fun retry(task: TaskEntity) {
        viewModelScope.launch {
            TaskRepo.requeue(ctx, task.id)
            DownloadService.start(ctx)
        }
    }

    fun remove(task: TaskEntity) {
        viewModelScope.launch { TaskRepo.delete(ctx, task.id) }
    }

    fun clearFinished() {
        viewModelScope.launch { TaskRepo.clearFinished(ctx) }
    }

    fun updateYtDlp() {
        if (updatingYtDlp) return
        updatingYtDlp = true
        viewModelScope.launch {
            try {
                val v = YtDlp.update(ctx)
                prefs.ytDlpVersion = v
                ytDlpVersion = v
                toast = "yt-dlp 已更新：$v"
            } catch (e: Exception) {
                toast = "更新失败：${e.message}"
            } finally {
                updatingYtDlp = false
            }
        }
    }

    fun saveDir(): String = prefs.saveDir

    fun imageDir(): String = prefs.imageDir

    /** 视频/图集两个目录一起保存，只弹一次提示 */
    fun saveDirs(video: String, image: String) {
        prefs.saveDir = cleanDir(video, "Movies/DyDown")
        prefs.imageDir = cleanDir(image, "Pictures/DyDown")
        toast = "保存目录已更新"
    }

    private fun cleanDir(v: String, fallback: String): String =
        v.trim().replace('\\', '/').replace("..", "")
            .trim('/').trim().ifBlank { fallback }

    fun statusText(task: TaskEntity): String = when (task.status) {
        TStatus.QUEUED -> "排队中"
        TStatus.RUNNING -> if (task.kind == "LIVE") "录制中" else "下载中"
        TStatus.DONE -> "已完成"
        TStatus.FAILED -> task.error ?: "失败"
        TStatus.CANCELED -> "已取消"
        else -> task.status
    }

    override fun onCleared() {
        // viewModelScope 已经被取消，用进程级的 scope 收尾
        (getApplication<Application>() as DyDownApp).appScope.launch { repo.close() }
        super.onCleared()
    }
}
