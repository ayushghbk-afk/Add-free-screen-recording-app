package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val timestamp: Long,
    val durationMs: Long,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fps: Int,
    val isTrimmed: Boolean = false
)
