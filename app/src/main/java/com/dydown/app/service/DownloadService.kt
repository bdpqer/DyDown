package com.dydown.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.dydown.app.DyDownApp
import com.dydown.app.data.HStatus
import com.dydown.app.data.HistoryRepo
import com.dydown.app.data.TStatus
import com.dydown.app.data.TaskRepo
import com.dydown.app.download.DownloadEngine
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

/**
 * 下载队列。
 *
 * 为什么不用 WorkManager：批量下大文件会撞后台任务 10 分钟上限，
 * 而前台服务没有这个限制，且进度通知本来就是我们要的。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTI_ID,
            Notifier.build(this, Notifier.CH_DOWNLOAD, "DyDown 下载中", "准备中…", 0),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (job?.isActive != true) {
            job = scope.launch { runQueue() }
        }
        return START_STICKY
    }

    private suspend fun runQueue() {
        val app = application as DyDownApp
        val engine = DownloadEngine(this, app.prefs)

        try {
            while (true) {
                val task = TaskRepo.next(this) ?: break
                TaskRepo.setStatus(this, task.id, TStatus.RUNNING)
                updateNotification("${task.author} · ${task.title}", 0)

                try {
                    val result = engine.run(task) { pct, bytes, total ->
                        TaskRepo.setProgress(this, task.id, pct, bytes, total)
                        updateNotification(task.title, pct)
                    }
                    TaskRepo.setStatus(this, task.id, TStatus.DONE)
                    HistoryRepo.finishByTask(
                        this, task.id, HStatus.OK,
                        result.fileUri, result.savedDir, result.bytes
                    )
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        TaskRepo.setStatus(this@DownloadService, task.id, TStatus.CANCELED)
                        HistoryRepo.finishByTask(
                            this@DownloadService, task.id, HStatus.CANCELED, error = "已取消"
                        )
                    }
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: "未知错误"
                    TaskRepo.setStatus(this, task.id, TStatus.FAILED, msg)
                    HistoryRepo.finishByTask(this, task.id, HStatus.FAILED, error = msg)
                }
            }
        } finally {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(title: String, pct: Int) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(NOTI_ID, Notifier.build(this, Notifier.CH_DOWNLOAD, "下载中 ${pct}%", title, pct))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTI_ID = 1001
        const val ACTION_START = "com.dydown.action.START_QUEUE"
        const val ACTION_STOP = "com.dydown.action.STOP_QUEUE"

        fun start(context: Context) {
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
