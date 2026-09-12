package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaTaskHistoryDao {
    @Query("SELECT * FROM media_tasks_history ORDER BY updatedAt DESC")
    fun getAllHistory(): Flow<List<MediaTaskHistory>>

    @Query("SELECT * FROM media_tasks_history WHERE taskType = :type ORDER BY updatedAt DESC")
    fun getHistoryByType(type: String): Flow<List<MediaTaskHistory>>

    @Query("SELECT * FROM media_tasks_history WHERE id = :id")
    suspend fun getTaskById(id: Long): MediaTaskHistory?

    @Query("SELECT * FROM media_tasks_history WHERE status = 'IN_PROGRESS' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestUnfinishedTask(): MediaTaskHistory?

    @Query("SELECT * FROM media_tasks_history ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestTask(): MediaTaskHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: MediaTaskHistory): Long

    @Update
    suspend fun updateTask(task: MediaTaskHistory)

    @Delete
    suspend fun deleteTask(task: MediaTaskHistory)

    @Query("DELETE FROM media_tasks_history WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("DELETE FROM media_tasks_history")
    suspend fun clearAll()
}
