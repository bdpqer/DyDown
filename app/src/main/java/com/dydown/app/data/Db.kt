package com.dydown.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object TStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val awemeId: String,
    /** VIDEO / GALLERY / LIVE */
    val kind: String,
    val title: String,
    val author: String,
    val cover: String? = null,
    val videoUrl: String? = null,
    val images: String = "",
    val musicUrl: String? = null,
    val liveUrl: String? = null,
    val quality: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = TStatus.QUEUED,
    val progress: Int = 0,
    val bytes: Long = 0,
    val total: Long = 0,
    val error: String? = null
)

@Dao
interface TaskDao {
    /**
     * 列表只读摘要：不取 images / videoUrl / musicUrl / liveUrl 这些下载用的长字段。
     * 早期用 SELECT * 读全表，累计到 1500+ 行时 Android 的 2MB CursorWindow 装不下，
     * Room 遍历到窗口外那一行会抛 IllegalStateException 并杀掉进程（row 449, col 0）。
     * 下载执行仍走 nextQueued()/byId() 的完整查询，不受影响。
     */
    @Query(
        """SELECT `id`, `awemeId`, `kind`, `title`, `author`, `cover`,
                   NULL AS `videoUrl`, '' AS `images`, NULL AS `musicUrl`, NULL AS `liveUrl`,
                   `quality`, `createdAt`, `status`, `progress`, `bytes`, `total`, `error`
           FROM `tasks` ORDER BY `id` DESC LIMIT 800"""
    )
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status = 'QUEUED' ORDER BY id ASC LIMIT 1")
    suspend fun nextQueued(): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE status IN ('QUEUED','RUNNING')")
    suspend fun pendingCount(): Int

    @Insert
    suspend fun insert(t: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String? = null)

    @Query("UPDATE tasks SET progress = :progress, bytes = :bytes, total = :total WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Int, bytes: Long, total: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tasks WHERE status IN ('DONE','FAILED','CANCELED')")
    suspend fun clearFinished()

    @Query("UPDATE tasks SET status = 'QUEUED', progress = 0, error = NULL WHERE id = :id")
    suspend fun requeue(id: Long)
}

/* ----------------------------- 历史记录 ----------------------------- */

object HType {
    const val PARSE = "PARSE"
    const val DOWNLOAD = "DOWNLOAD"
    const val BATCH = "BATCH"
    const val LIVE = "LIVE"
}

object HStatus {
    const val PENDING = "PENDING"
    const val OK = "OK"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"
}

/**
 * 与 tasks 表分离：tasks 是会被「清理已完成」删掉的下载队列，
 * history 是用户主动清空才消失的长期记录。
 * sourceLink 保存用户粘贴的原始分享文案，重新下载靠它重新解析，
 * 而不是复用几小时就过期的 CDN 地址。
 */
@Entity(
    tableName = "history",
    indices = [
        Index("awemeId", name = "idx_h_aweme"),
        Index("type", name = "idx_h_type"),
        Index("createdAt", name = "idx_h_created")
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** HType：PARSE / DOWNLOAD / BATCH / LIVE */
    val type: String,
    val awemeId: String = "",
    val secUid: String? = null,
    val title: String = "",
    val author: String = "",
    val sourceLink: String = "",
    /** VIDEO / GALLERY / LIVE */
    val kind: String = "",
    /** HStatus：PENDING / OK / FAILED / CANCELED */
    val status: String = HStatus.OK,
    val fileUri: String? = null,
    val savedDir: String? = null,
    val bytes: Long = 0,
    /** 批量条数 / 图集张数 */
    val count: Int = 0,
    /** 本地缓存封面路径（远程封面地址会过期） */
    val coverPath: String? = null,
    val taskId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val error: String? = null
)

@Dao
interface HistoryDao {
    /** 同理：列表只读摘要，剔除 UI 用不到的 secUid / savedDir，并限制最大行数防止 CursorWindow 溢出 */
    @Query(
        """SELECT `id`, `type`, `awemeId`, NULL AS `secUid`, `title`, `author`, `sourceLink`,
                   `kind`, `status`, `fileUri`, NULL AS `savedDir`, `bytes`, `count`,
                   `coverPath`, `taskId`, `createdAt`, `finishedAt`, `error`
           FROM `history` ORDER BY `createdAt` DESC, `id` DESC LIMIT 2000"""
    )
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(h: HistoryEntity): Long

    @Query(
        """UPDATE history SET status = :status,
           fileUri = COALESCE(:fileUri, fileUri),
           savedDir = COALESCE(:savedDir, savedDir),
           bytes = CASE WHEN :bytes > 0 THEN :bytes ELSE bytes END,
           finishedAt = :at, error = :error
           WHERE taskId = :taskId"""
    )
    suspend fun finishByTask(
        taskId: Long,
        status: String,
        fileUri: String?,
        savedDir: String?,
        bytes: Long,
        at: Long,
        error: String?
    )

    /** 批量去重：只认真正下载成功的作品，失败/取消的允许重下 */
    @Query("SELECT DISTINCT awemeId FROM history WHERE type = 'DOWNLOAD' AND status = 'OK' AND awemeId <> ''")
    suspend fun downloadedIds(): List<String>

    /** 只查 ID 一列，不受列表查询 LIMIT 影响，供批量页实时显示「已下载」标记 */
    @Query("SELECT DISTINCT awemeId FROM history WHERE type = 'DOWNLOAD' AND status = 'OK' AND awemeId <> ''")
    fun observeDownloadedIds(): Flow<List<String>>

    @Query("SELECT sourceLink FROM history WHERE taskId = :taskId AND sourceLink <> '' ORDER BY id DESC LIMIT 1")
    suspend fun sourceLinkByTask(taskId: Long): String?

    @Query("UPDATE history SET coverPath = :path WHERE id = :id")
    suspend fun updateCover(id: Long, path: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

/** 只新增表，绝不改 tasks 结构；显式迁移，避免 fallbackToDestructiveMigration 清库 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type` TEXT NOT NULL,
                `awemeId` TEXT NOT NULL,
                `secUid` TEXT,
                `title` TEXT NOT NULL,
                `author` TEXT NOT NULL,
                `sourceLink` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `fileUri` TEXT,
                `savedDir` TEXT,
                `bytes` INTEGER NOT NULL,
                `count` INTEGER NOT NULL,
                `coverPath` TEXT,
                `taskId` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `finishedAt` INTEGER,
                `error` TEXT
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_h_aweme` ON `history` (`awemeId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_h_type` ON `history` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_h_created` ON `history` (`createdAt`)")

        // 老版本 tasks 里已结束的下载回填成历史记录，避免升级后「以前的记录不见了」。
        // 旧记录没有原始分享文案，sourceLink 留空（只能去重，不能重新解析）。
        db.execSQL(
            """INSERT INTO `history`
               (`type`,`awemeId`,`title`,`author`,`sourceLink`,`kind`,`status`,
                `bytes`,`count`,`createdAt`,`finishedAt`,`error`)
               SELECT 'DOWNLOAD',`awemeId`,`title`,`author`,'',`kind`,
                 CASE `status` WHEN 'DONE' THEN 'OK' WHEN 'FAILED' THEN 'FAILED' ELSE 'CANCELED' END,
                 `bytes`,0,`createdAt`,`createdAt`,`error`
               FROM `tasks` WHERE `status` IN ('DONE','FAILED','CANCELED')"""
        )
    }
}

@Database(
    entities = [TaskEntity::class, HistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(context: Context): AppDb =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "dydown.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { inst = it }
            }
    }
}

object TaskRepo {
    private fun dao(context: Context) = AppDb.get(context).taskDao()

    fun observe(context: Context): Flow<List<TaskEntity>> = dao(context).observeAll()
    suspend fun insert(context: Context, t: TaskEntity): Long = dao(context).insert(t)
    suspend fun byId(context: Context, id: Long) = dao(context).byId(id)
    suspend fun next(context: Context) = dao(context).nextQueued()
    suspend fun pending(context: Context) = dao(context).pendingCount()
    suspend fun setStatus(context: Context, id: Long, status: String, error: String? = null) =
        dao(context).setStatus(id, status, error)

    suspend fun setProgress(context: Context, id: Long, p: Int, b: Long, t: Long) =
        dao(context).setProgress(id, p, b, t)

    suspend fun delete(context: Context, id: Long) = dao(context).delete(id)
    suspend fun clearFinished(context: Context) = dao(context).clearFinished()
    suspend fun requeue(context: Context, id: Long) = dao(context).requeue(id)
}

object HistoryRepo {
    private fun dao(context: Context) = AppDb.get(context).historyDao()

    fun observe(context: Context): Flow<List<HistoryEntity>> = dao(context).observeAll()
    suspend fun insert(context: Context, h: HistoryEntity): Long = dao(context).insert(h)
    suspend fun finishByTask(
        context: Context,
        taskId: Long,
        status: String,
        fileUri: String? = null,
        savedDir: String? = null,
        bytes: Long = 0,
        error: String? = null
    ) = dao(context).finishByTask(
        taskId, status, fileUri, savedDir, bytes, System.currentTimeMillis(), error
    )

    suspend fun downloadedIds(context: Context): List<String> = dao(context).downloadedIds()
    fun observeDownloadedIds(context: Context): Flow<List<String>> = dao(context).observeDownloadedIds()
    suspend fun sourceLinkByTask(context: Context, taskId: Long): String? = dao(context).sourceLinkByTask(taskId)
    suspend fun updateCover(context: Context, id: Long, path: String) = dao(context).updateCover(id, path)
    suspend fun delete(context: Context, id: Long) = dao(context).delete(id)
    suspend fun clear(context: Context) = dao(context).clear()
}
