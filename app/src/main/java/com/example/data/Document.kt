package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val language: String = "bn",
    val lastModified: Long = System.currentTimeMillis()
)
