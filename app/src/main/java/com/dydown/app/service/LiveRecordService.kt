package com.dydown.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dydown.app.DyDownApp
import com.dydown.app.data.HStatus
import com.dydown.app.data.HistoryRepo
import com.dydown.app.data.TStatus
import com.dydown.app.data.TaskRepo
import com.dydown.app.download.Ffmpeg
import com.dydown.app.download.LiveRecorder
import com.dydown.app.download.MediaStoreWriter
import com.dydown.app.download.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 直播录制服务：前台服务，录制到停止为止（没有时长上限）。
 * 录完先落 .flv 保命，再用 ffmpeg 无损转封装成 mp4；ffmpeg 失败就保留 flv。
 */
class LiveRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                return START_NOT_STICKY
            }
            else -> {
                val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
                val url = intent?.getStringExtra(EXTRA_URL)
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "直播录制"
                if (taskId > 0 && !url.isNullOrEmpty()) {
                    startRecord(taskId, url, title)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startRecord(taskId: Long, url: String, title: String) {
        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            notification(title, 0, "录制中…"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        job = scope.launch {
            val tmp = File(cacheDir, "live_$taskId.flv")
            runCatching { tmp.delete() }
            try {
                TaskRepo.setStatus(this@LiveRecordService, taskId, TStatus.RUNNING)
                LiveRecorder.record(url, tmp) { bytes ->
                    TaskRepo.setProgress(this@LiveRecordService, taskId, 0, bytes, -1)
                    updateNotification(title, bytes)
                }
                withContext(NonCancellable) { finalize(taskId, tmp) }
            } catch (e: CancellationException) {
                // 用户主动停止 = 正常结束，已经录到的内容要保住
                withContext(NonCancellable) { finalize(taskId, tmp) }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val msg = e.message ?: "录制失败"
                    TaskRepo.setStatus(this@LiveRecordService, taskId, TStatus.FAILED, msg)
                    HistoryRepo.finishByTask(this@LiveRecordService, taskId, HStatus.FAILED, error = msg)
                    runCatching { tmp.delete() }
                }
            } finally {
                ServiceCompat.stopForeground(this@LiveRecordService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun finalize(taskId: Long, flv: File) {
        if (!flv.exists() || flv.length() < 1024) {
            TaskRepo.setStatus(this, taskId, TStatus.FAILED, "没有录到数据")
            HistoryRepo.finishByTask(this, taskId, HStatus.FAILED, error = "没有录到数据")
            return
        }
        val mp4 = File(cacheDir, "live_$taskId.mp4")
        val converted = Ffmpeg.toMp4(this, flv, mp4)
        val src = if (converted) mp4 else flv

        val task = TaskRepo.byId(this, taskId)
        val base = (task?.title?.ifBlank { "live" } ?: "live")
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .take(50)
        val name = "${base}_$taskId.${if (converted) "mp4" else "flv"}"

        // 直播成品跟随设置页的视频目录（此前硬编码 Movies/DyDown，设置项对直播无效）
        val dir = (application as DyDownApp).prefs.saveDir.trim().trimEnd('/')
            .ifBlank { "Movies/DyDown" } + "/live"
        val bytes = src.length()
        runCatching {
            MediaStoreWriter(this).saveVideo(
                src, name, dir,
                if (converted) "video/mp4" else "video/x-flv"
            )
        }.onSuccess { uri ->
            TaskRepo.setStatus(this, taskId, TStatus.DONE)
            HistoryRepo.finishByTask(this, taskId, HStatus.OK, uri.toString(), dir, bytes)
        }.onFailure {
            val msg = "写入相册失败：${it.message}"
            TaskRepo.setStatus(this, taskId, TStatus.FAILED, msg)
            HistoryRepo.finishByTask(this, taskId, HStatus.FAILED, savedDir = dir, error = msg)
        }

        runCatching {
            flv.delete()
            if (converted) mp4.delete()
        }
    }

    private fun notification(title: String, bytes: Long, text: String) =
        Notifier.build(
            this,
            Notifier.CH_LIVE,
            title,
            "$text ${size(bytes)}",
            -1,
            true,
            listOf(
                NotificationCompat.Action.Builder(
                    0, "停止录制", Notifier.stopAction(this, ACTION_STOP)
                ).build()
            )
        )

    private fun updateNotification(title: String, bytes: Long) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTI_ID, notification(title, bytes, "已录制"))
    }

    private fun size(bytes: Long): String =
        if (bytes < 1024 * 1024) String.format(Locale.US, "%.0f KB", bytes / 1024f)
        else String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTI_ID = 2001
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP = "com.dydown.action.STOP_RECORD"

        fun start(context: Context, taskId: Long, url: String, title: String) {
            val i = Intent(context, LiveRecordService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LiveRecordService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
