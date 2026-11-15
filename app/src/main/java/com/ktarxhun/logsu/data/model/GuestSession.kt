package com.ktarxhun.logsu.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "guest_sessions")
data class GuestSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val initialPhotoPath: String? = null,
    val videoFilePath: String? = null,
    val touchFilePath: String? = null,
    val audioFilePath: String? = null,
    val totalAppSwitches: Int = 0,
    val totalClipboardCopies: Int = 0,
    val isActive: Boolean = true
)
