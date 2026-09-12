package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_tasks_history")
data class MediaTaskHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String = "TRANSCRIBE", // "TRANSCRIBE" or "DUBBING"
    val title: String,
    val filePath: String? = null,
    val fileUri: String? = null,
    val targetLanguage: String = "bn",
    val srtContent: String = "",
    val subtitleCount: Int = 0,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val status: String = "SAVED", // "IN_PROGRESS", "COMPLETED", "INTERRUPTED", "SAVED"
    val savedOutputPath: String? = null,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
