package com.ktarxhun.logsu.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallType {
    INCOMING_GSM,
    OUTGOING_GSM,
    MISSED_GSM,
    WHATSAPP_CALL,
    AMBIENT_RECORDING // Yüz yüze görüşme veya ortam ses kaydı
}

@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val contactName: String?,
    val isContact: Boolean,
    val callType: CallType,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Long,
    val audioFilePath: String?,
    val videoFilePath: String?,
    val touchFilePath: String?,
    val transcript: String?,
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
