package com.dydown.app.ui

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dydown.app.DyDownApp
import com.dydown.app.core.Kind
import com.dydown.app.core.LiveInfo
import com.dydown.app.core.MediaMeta
import com.dydown.app.data.CoverCache
import com.dydown.app.data.HStatus
import com.dydown.app.data.HType
import com.dydown.app.data.HistoryEntity
import com.dydown.app.data.HistoryRepo
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
import kotlinx.coroutines.flow.catch
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

    /** 最近一次解析/直播的输入原文，历史记录重新解析全靠它 */
    private var lastParseLink: String = ""
    private var lastLiveLink: String = ""

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
    var batchSkipDownloaded by mutableStateOf(prefs.batchSkipDownloaded)
        private set
    var toast by mutableStateOf<String?>(null)

    // Room Flow 的异常如果没人捕获，会被 WebView 注册的 JavaExceptionReporter 当成
    // 未捕获异常直接杀进程。这里兜底成空列表 + 提示，保证数据库再出问题也只是列表不显示。
    val tasks: StateFlow<List<TaskEntity>> = TaskRepo.observe(ctx)
        .catch { e ->
            Log.e("DyDown", "tasks flow failed", e)
            toast = "任务列表读取失败：${e.message ?: "数据库异常"}"
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    val history: StateFlow<List<HistoryEntity>> = HistoryRepo.observe(ctx)
        .catch { e ->
            Log.e("DyDown", "history flow failed", e)
            toast = "历史记录读取失败：${e.message ?: "数据库异常"}"
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    fun clearToast() { toast = null }

    fun parse(text: String) {
        if (text.isBlank()) {
            parseState = UiState.Err("先粘贴一条抖音链接")
            return
        }
        lastParseLink = text.trim()
        parseState = UiState.Loading
        viewModelScope.launch {
            try {
                val meta = repo.parse(text)
                qualityIndex = 0
                parseState = UiState.Ok(meta)
                val id = HistoryRepo.insert(
                    ctx,
                    HistoryEntity(
                        type = HType.PARSE,
                        awemeId = meta.awemeId,
                        title = meta.title.ifBlank { "抖音作品" },
                        author = meta.author,
                        sourceLink = lastParseLink,
                        kind = meta.kind.name,
                        status = HStatus.OK,
                        count = mediaCount(meta),
                        finishedAt = System.currentTimeMillis()
                    )
                )
                cacheCover(id, meta.awemeId, meta.cover)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "解析失败"
                parseState = UiState.Err(msg)
                HistoryRepo.insert(
                    ctx,
                    HistoryEntity(
                        type = HType.PARSE,
                        sourceLink = lastParseLink,
                        status = HStatus.FAILED,
                        error = msg,
                        finishedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun selectQuality(index: Int) {
        qualityIndex = index
        prefs.qualityIndex = index
    }

    /** 单条下载：不拦截重复，想重下随时可以 */
    fun enqueue(meta: MediaMeta, index: Int = 0, silent: Boolean = false) {
        if (meta.videos.isEmpty() && meta.images.isEmpty()) return
        val task = taskFrom(meta, index)
        val link = linkFor(meta.awemeId)
        viewModelScope.launch {
            val taskId = TaskRepo.insert(ctx, task)
            val cover = CoverCache.save(ctx, meta.awemeId, meta.cover)
            HistoryRepo.insert(
                ctx,
                HistoryEntity(
                    type = HType.DOWNLOAD,
                    awemeId = meta.awemeId,
                    title = task.title,
                    author = task.author,
                    sourceLink = link,
                    kind = meta.kind.name,
                    status = HStatus.PENDING,
                    count = mediaCount(meta),
                    coverPath = cover,
                    taskId = taskId
                )
            )
            DownloadService.start(ctx)
            if (!silent) toast = "已加入下载队列"
        }
    }

    fun loadProfile(text: String) {
        if (text.isBlank()) {
            batch = batch.copy(error = "先粘贴主页链接")
            return
        }
        val link = text.trim()
        batch = BatchUi(loading = true)
        viewModelScope.launch {
            try {
                val secUid = repo.resolveUser(link)
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
                val items = all.distinctBy { it.awemeId }
                batch = BatchUi(
                    loading = false,
                    secUid = secUid,
                    items = items,
                    selected = items.map { it.awemeId }.toSet(),
                    error = null,
                    pages = pagesDone,
                    hasMore = page.hasMore,
                    cursor = cursor,
                    total = items.size
                )
                val id = HistoryRepo.insert(
                    ctx,
                    HistoryEntity(
                        type = HType.BATCH,
                        awemeId = secUid,
                        secUid = secUid,
                        title = "主页作品 ${items.size} 条",
                        author = items.firstOrNull()?.author.orEmpty(),
                        sourceLink = link,
                        status = HStatus.OK,
                        count = items.size,
                        finishedAt = System.currentTimeMillis()
                    )
                )
                cacheCover(id, secUid, items.firstOrNull()?.cover)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "拉取失败"
                batch = BatchUi(loading = false, error = msg)
                HistoryRepo.insert(
                    ctx,
                    HistoryEntity(
                        type = HType.BATCH,
                        sourceLink = link,
                        status = HStatus.FAILED,
                        error = msg,
                        finishedAt = System.currentTimeMillis()
                    )
                )
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

    /** 批量下载：按已成功下载的 awemeId 静默跳过，失败/取消的不算 */
    fun downloadSelected() {
        val chosen = batch.items.filter { batch.selected.contains(it.awemeId) }
        if (chosen.isEmpty()) {
            toast = "还没选作品"
            return
        }
        viewModelScope.launch {
            val skip: Set<String> = if (prefs.batchSkipDownloaded) {
                HistoryRepo.downloadedIds(ctx).toHashSet()
            } else emptySet()
            val targets = chosen.filterNot { skip.contains(it.awemeId) }
            val skipped = chosen.size - targets.size
            if (targets.isEmpty()) {
                toast = "全部 ${chosen.size} 条已下载过，已跳过"
                return@launch
            }
            var added = 0
            targets.forEach { meta ->
                if (meta.videos.isEmpty() && meta.images.isEmpty()) return@forEach
                val taskId = TaskRepo.insert(ctx, taskFrom(meta, 0))
                HistoryRepo.insert(
                    ctx,
                    HistoryEntity(
                        type = HType.DOWNLOAD,
                        awemeId = meta.awemeId,
                        title = meta.title.ifBlank { "抖音作品" },
                        author = meta.author,
                        // 批量没有单条分享文案，用可解析的作品长链兜底
                        sourceLink = "https://www.douyin.com/video/${meta.awemeId}",
                        kind = meta.kind.name,
                        status = HStatus.PENDING,
                        count = mediaCount(meta),
                        taskId = taskId
                    )
                )
                added++
            }
            DownloadService.start(ctx)
            toast = "已加入 $added 个任务" +
                if (skipped > 0) "，跳过 $skipped 条已下载" else ""
        }
    }

    fun resolveLive(text: String) {
        if (text.isBlank()) {
            liveState = UiState.Err("先粘贴直播间链接")
            return
        }
        lastLiveLink = text.trim()
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
            // 直播任务直接置 RUNNING：否则会被下载队列当 QUEUED 任务捞走并失败
            val taskId = TaskRepo.insert(
                ctx,
                TaskEntity(
                    awemeId = info.roomId,
                    kind = "LIVE",
                    title = info.title,
                    author = info.author,
                    cover = info.cover,
                    liveUrl = url,
                    quality = "直播",
                    status = TStatus.RUNNING
                )
            )
            val cover = CoverCache.save(ctx, info.roomId, info.cover)
            HistoryRepo.insert(
                ctx,
                HistoryEntity(
                    type = HType.LIVE,
                    awemeId = info.roomId,
                    title = info.title,
                    author = info.author,
                    sourceLink = lastLiveLink,
                    kind = "LIVE",
                    status = HStatus.PENDING,
                    coverPath = cover,
                    taskId = taskId
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

    /** 失败重试：CDN 地址会过期，优先用历史里的原始链接重新解析 */
    fun retry(task: TaskEntity) {
        viewModelScope.launch {
            val link = HistoryRepo.sourceLinkByTask(ctx, task.id)
            if (link.isNullOrBlank()) {
                TaskRepo.requeue(ctx, task.id)
                DownloadService.start(ctx)
                toast = "已用原地址重试（可能已过期）"
                return@launch
            }
            try {
                val meta = repo.parse(link)
                lastParseLink = link
                parseState = UiState.Ok(meta)
                val newTask = task.copy(
                    id = 0,
                    awemeId = meta.awemeId.ifBlank { task.awemeId },
                    kind = meta.kind.name,
                    title = meta.title.ifBlank { task.title },
                    author = meta.author.ifBlank { task.author },
                    cover = meta.cover ?: task.cover,
                    videoUrl = meta.videos.firstOrNull()?.url,
                    images = meta.images.joinToString("\n"),
                    musicUrl = meta.music?.url,
                    status = TStatus.QUEUED,
                    progress = 0,
                    bytes = 0,
                    total = 0,
                    error = null,
                    createdAt = System.currentTimeMillis()
                )
                val newId = TaskRepo.insert(ctx, newTask)
                HistoryRepo.insert(
                    ctx,
                    HistoryEntity(
                        type = HType.DOWNLOAD,
                        awemeId = newTask.awemeId,
                        title = newTask.title,
                        author = newTask.author,
                        sourceLink = link,
                        kind = newTask.kind,
                        status = HStatus.PENDING,
                        taskId = newId
                    )
                )
                TaskRepo.delete(ctx, task.id)
                DownloadService.start(ctx)
                toast = "已重新解析并入队"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TaskRepo.requeue(ctx, task.id)
                DownloadService.start(ctx)
                toast = "重新解析失败，已用原地址重试：${e.message}"
            }
        }
    }

    fun remove(task: TaskEntity) {
        viewModelScope.launch { TaskRepo.delete(ctx, task.id) }
    }

    fun clearFinished() {
        viewModelScope.launch { TaskRepo.clearFinished(ctx) }
    }

    /* ----------------------------- 历史记录 ----------------------------- */

    fun deleteHistory(h: HistoryEntity) {
        viewModelScope.launch { HistoryRepo.delete(ctx, h.id) }
    }

    /** 只删记录，不动相册里的成品文件 */
    fun clearHistory() {
        viewModelScope.launch {
            HistoryRepo.clear(ctx)
            toast = "历史已清空，已保存的文件保留"
        }
    }

    fun updateBatchSkipDownloaded(v: Boolean) {
        prefs.batchSkipDownloaded = v
        batchSkipDownloaded = v
    }

    /** UI 从历史页点「重新解析」时用原文再解析一次 */
    fun reparse(link: String) {
        lastParseLink = link
        parse(link)
    }

    private fun taskFrom(meta: MediaMeta, index: Int) = TaskEntity(
        awemeId = meta.awemeId,
        kind = meta.kind.name,
        title = meta.title.ifBlank { "抖音作品" },
        author = meta.author,
        cover = meta.cover,
        videoUrl = meta.videos.getOrNull(index)?.url,
        images = meta.images.joinToString("\n"),
        musicUrl = meta.music?.url,
        quality = meta.videos.getOrNull(index)?.label
            ?: if (meta.images.isNotEmpty()) "${meta.images.size} 张图" else ""
    )

    private fun mediaCount(meta: MediaMeta): Int =
        if (meta.kind == Kind.GALLERY) meta.images.size else meta.videos.size

    /** 单条解析下载用原始分享文案；批量作品用可解析的长链兜底 */
    private fun linkFor(awemeId: String): String {
        val current = (parseState as? UiState.Ok)?.data
        return when {
            current != null && current.awemeId == awemeId && lastParseLink.isNotBlank() -> lastParseLink
            awemeId.isNotBlank() -> "https://www.douyin.com/video/$awemeId"
            else -> ""
        }
    }

    /** 封面异步缓存，不阻塞历史记录写入 */
    private fun cacheCover(id: Long, key: String, url: String?) {
        viewModelScope.launch {
            val path = CoverCache.save(ctx, key, url)
            if (path != null) HistoryRepo.updateCover(ctx, id, path)
        }
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
