package com.dydown.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    @Query("SELECT * FROM tasks ORDER BY id DESC")
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

@Database(entities = [TaskEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(context: Context): AppDb =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "dydown.db")
                    .fallbackToDestructiveMigration()
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
