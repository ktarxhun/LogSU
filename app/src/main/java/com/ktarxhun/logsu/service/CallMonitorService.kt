package com.ktarxhun.logsu.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ktarxhun.logsu.LogSuApp
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.root.AudioCaptureController
import com.ktarxhun.logsu.stt.OfflineSpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val ACTION_START_CALL = "com.ktarxhun.logsu.ACTION_START_CALL"
        const val ACTION_STOP_CALL = "com.ktarxhun.logsu.ACTION_STOP_CALL"
        const val ACTION_START_AMBIENT = "com.ktarxhun.logsu.ACTION_START_AMBIENT"
        const val ACTION_STOP_AMBIENT = "com.ktarxhun.logsu.ACTION_STOP_AMBIENT"

        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALL_TYPE = "extra_call_type"

        private val _isAmbientRecordingFlow = MutableStateFlow(false)
        val isAmbientRecordingFlow = _isAmbientRecordingFlow.asStateFlow()

        fun startCall(context: Context, phoneNumber: String, callType: CallType) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_CALL_TYPE, callType.name)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCall(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            context.startService(intent)
        }

        fun toggleAmbientRecording(context: Context) {
            if (_isAmbientRecordingFlow.value) {
                stopAmbientRecording(context)
            } else {
                startAmbientRecording(context)
            }
        }

        fun startAmbientRecording(context: Context) {
            _isAmbientRecordingFlow.value = true
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_START_AMBIENT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopAmbientRecording(context: Context) {
            _isAmbientRecordingFlow.value = false
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_STOP_AMBIENT
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioController = AudioCaptureController()
    private var currentPhoneNumber: String = "Bilinmeyen"
    private var currentCallType: CallType = CallType.INCOMING_GSM
    private var startTimeMs: Long = 0
    private var audioFile: File? = null
    @Volatile
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Bilinmeyen"
                val typeStr = intent.getStringExtra(EXTRA_CALL_TYPE) ?: CallType.INCOMING_GSM.name
                val type = try { CallType.valueOf(typeStr) } catch (e: Exception) { CallType.INCOMING_GSM }
                startRecordingSession(number, type)
            }
            ACTION_STOP_CALL -> {
                stopRecordingSession()
            }
            ACTION_START_AMBIENT -> {
                startRecordingSession("Ortam Ses Kaydı", CallType.AMBIENT_RECORDING)
            }
            ACTION_STOP_AMBIENT -> {
                stopRecordingSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecordingSession(phoneNumber: String, callType: CallType) {
        if (isRecording) return
        isRecording = true
        startTimeMs = System.currentTimeMillis()
        currentPhoneNumber = phoneNumber
        currentCallType = callType

        if (callType == CallType.AMBIENT_RECORDING) {
            _isAmbientRecordingFlow.value = true
        }

        startForeground(1001, buildNotification(callType))

        val dir = getRecordingsDir()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(startTimeMs))
        val prefix = when (callType) {
            CallType.WHATSAPP_CALL -> "whatsapp"
            CallType.AMBIENT_RECORDING -> "ambient"
            else -> "call"
        }

        val targetFile = File(dir, "${prefix}_${timeStamp}.wav")
        audioFile = targetFile
        val isDualChannel = callType != CallType.AMBIENT_RECORDING
        Log.i(TAG, "Ses kaydi oturumu baslatildi: $phoneNumber ($callType, dual=$isDualChannel)")

        serviceScope.launch {
            val started = audioController.startRecording(targetFile, enableDualInternalMic = isDualChannel)
            if (!started) {
                Log.w(TAG, "Ses kaydi baslatilamadi, bos oturum kapatiliyor")
                isRecording = false
                if (callType == CallType.AMBIENT_RECORDING) {
                    _isAmbientRecordingFlow.value = false
                }
                targetFile.delete()
                audioFile = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopRecordingSession() {
        if (!isRecording) return
        isRecording = false
        _isAmbientRecordingFlow.value = false

        val endTimeMs = System.currentTimeMillis()
        val capturedFile = audioFile
        val capturedNumber = currentPhoneNumber
        val capturedType = currentCallType
        val capturedStart = startTimeMs

        serviceScope.launch {
            audioController.stopRecordingSuspend()

            val wavDurationSec = computeWavDurationSeconds(capturedFile)
            val wallDurationSec = ((endTimeMs - capturedStart) / 1000L).coerceAtLeast(0L)
            val durationSec = if (wavDurationSec > 0) wavDurationSec else wallDurationSec

            val hasValidAudio = capturedFile != null && capturedFile.exists() && capturedFile.length() > 44L
            // Boş veya 1 saniyeden kısa hatalı kayıtları (00:00 hayalet kayıtları) kaydetme
            if (!hasValidAudio || durationSec < 1L) {
                Log.w(TAG, "Bos veya 0 saniyelik kayit elendi (size=${capturedFile?.length() ?: 0}, sure=${durationSec}s)")
                capturedFile?.delete()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val contactName = if (capturedType == CallType.AMBIENT_RECORDING) {
                "Yüz Yüze / Ortam Kaydı"
            } else {
                getContactName(capturedNumber)
            }
            val isContact = !contactName.isNullOrBlank()

            val record = CallRecord(
                phoneNumber = capturedNumber,
                contactName = contactName,
                isContact = isContact,
                callType = capturedType,
                startTime = capturedStart,
                endTime = endTimeMs,
                durationSeconds = durationSec,
                audioFilePath = capturedFile!!.absolutePath,
                videoFilePath = null,
                touchFilePath = null,
                transcript = null,
                isLocked = false
            )

            val recordId = LogSuApp.instance.repository.insertCallRecord(record)
            Log.i(TAG, "Ses kaydi veritabanina eklendi, ID: $recordId (${durationSec}s)")

            OfflineSpeechTranscriber.transcribeCall(capturedFile) { text ->
                serviceScope.launch {
                    LogSuApp.instance.repository.updateTranscript(recordId, text)
                }
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun computeWavDurationSeconds(wavFile: File?): Long {
        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) return 0L
        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                val header = ByteArray(44)
                if (raf.read(header) < 44) return 0L
                val channels = ((header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)).coerceIn(1, 2)
                val sampleRate = ((header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)).coerceAtLeast(8000)
                val audioBytes = wavFile.length() - 44L
                val bytesPerSec = sampleRate.toLong() * channels.toLong() * 2L
                if (bytesPerSec > 0L) audioBytes / bytesPerSec else 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun getRecordingsDir(): File {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank() || phoneNumber == "Bilinmeyen" || phoneNumber == "Ortam Ses Kaydı") return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor: Cursor? = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildNotification(callType: CallType): Notification {
        val title = if (callType == CallType.AMBIENT_RECORDING) "Ortam Ses Kaydı" else "Arama Servisi"
        val desc = if (callType == CallType.AMBIENT_RECORDING) "Arka planda ses kaydediliyor" else "Arama ses kaydı aktif"

        return NotificationCompat.Builder(this, LogSuApp.CHANNEL_CALL_MONITOR)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        _isAmbientRecordingFlow.value = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
