package com.ktarxhun.logsu.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class GuestEventType {
    APP_OPENED,
    CLIPBOARD_COPIED,
    PHOTO_CAPTURED
}

@Entity(
    tableName = "guest_events",
    foreignKeys = [
        ForeignKey(
            entity = GuestSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class GuestEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: GuestEventType,
    val targetPackage: String? = null,
    val appLabel: String? = null,
    val textContent: String? = null,
    val mediaPath: String? = null
)
