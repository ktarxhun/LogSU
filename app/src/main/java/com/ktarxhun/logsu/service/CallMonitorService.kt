package com.ktarxhun.logsu.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ktarxhun.logsu.LogSuApp
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.root.AudioCaptureController
import com.ktarxhun.logsu.root.RootShellManager
import com.ktarxhun.logsu.stt.OfflineSpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class CallMonitorService : Service() {

    companion object {
        private const val TAG = "CallMonitorService"
        const val ACTION_START_CALL = "com.ktarxhun.logsu.ACTION_START_CALL"
        const val ACTION_STOP_CALL = "com.ktarxhun.logsu.ACTION_STOP_CALL"
        const val ACTION_START_AMBIENT = "com.ktarxhun.logsu.ACTION_START_AMBIENT"
        const val ACTION_STOP_AMBIENT = "com.ktarxhun.logsu.ACTION_STOP_AMBIENT"

        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_CALL_TYPE = "extra_call_type"

        private const val CALL_LOG_RETRY_DELAY_MS = 500L
        private const val CALL_LOG_QUERY_ATTEMPTS = 4
        private const val CALL_LOG_STRICT_DISTANCE_MS = 5_000L
        private const val CALL_LOG_MATCH_WINDOW_MS = 120_000L

        private val _isAmbientRecordingFlow = MutableStateFlow(false)
        val isAmbientRecordingFlow = _isAmbientRecordingFlow.asStateFlow()

        fun startCall(context: Context, phoneNumber: String, callType: CallType) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_CALL_TYPE, callType.name)
            }
            var started = false
            try {
                context.startForegroundService(intent)
                started = true
            } catch (e: Exception) {
                Log.w(TAG, "Standart startForegroundService basarisiz (${e.javaClass.simpleName}: ${e.message}), Root Shell deneniyor")
            }

            if (!started) {
                startViaRoot(context, ACTION_START_CALL, phoneNumber, callType)
            }
        }

        fun stopCall(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            var stopped = false
            try {
                context.startService(intent)
                stopped = true
            } catch (e: Exception) {
                Log.w(TAG, "Standart stopService basarisiz (${e.javaClass.simpleName}: ${e.message}), Root Shell deneniyor")
            }

            if (!stopped) {
                stopViaRoot(context)
            }
        }

        private fun startViaRoot(context: Context, action: String, phoneNumber: String, callType: CallType) {
            val pkg = context.packageName
            val cls = CallMonitorService::class.java.name
            val cmd = "am start-foreground-service -a ${RootShellManager.shellQuote(action)} --es ${RootShellManager.shellQuote(EXTRA_PHONE_NUMBER)} ${RootShellManager.shellQuote(phoneNumber)} --es ${RootShellManager.shellQuote(EXTRA_CALL_TYPE)} ${RootShellManager.shellQuote(callType.name)} ${RootShellManager.shellQuote("$pkg/$cls")}"
            RootShellManager.execute(cmd)
        }

        private fun stopViaRoot(context: Context) {
            val pkg = context.packageName
            val cls = CallMonitorService::class.java.name
            val cmd = "am start-service -a ${RootShellManager.shellQuote(ACTION_STOP_CALL)} ${RootShellManager.shellQuote("$pkg/$cls")}"
            RootShellManager.execute(cmd)
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
            context.startForegroundService(intent)
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
    private val sessionMutex = Mutex()
    private val audioController = AudioCaptureController()
    @Volatile
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
        if (isRecording) {
            if (phoneNumber.isNotBlank() && phoneNumber != "Bilinmeyen" &&
                (currentPhoneNumber == "Bilinmeyen" || currentPhoneNumber.isBlank())
            ) {
                currentPhoneNumber = phoneNumber
            }
            return
        }
        isRecording = true
        startTimeMs = System.currentTimeMillis()
        currentPhoneNumber = phoneNumber
        currentCallType = callType

        if (callType == CallType.AMBIENT_RECORDING) {
            _isAmbientRecordingFlow.value = true
        }

        val notification = buildNotification(callType)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        1001,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (e: SecurityException) {
                    val rootReady = RootShellManager.isRootCached() || RootShellManager.isRootAvailable()
                    val audioPathInfo = if (rootReady) {
                        "Cagri sesi root yardimci kaydedici ile alinacak."
                    } else {
                        "Root yok, uygulama UID'sindeki mikrofon sistem tarafindan sessize alinabilir."
                    }
                    Log.e(
                        TAG,
                        "MICROPHONE FGS tipi reddedildi, specialUse ile devam ediliyor. " +
                            "root=$rootReady. $audioPathInfo Hata: ${e.message}",
                        e
                    )
                    startForeground(
                        1001,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground basarisiz oldu", e)
            isRecording = false
            stopSelf()
            return
        }

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
            var startFailed = false
            sessionMutex.withLock {
                if (!isRecording) return@withLock
                val started = audioController.startRecording(
                    targetFile,
                    enableDualInternalMic = isDualChannel,
                    useRootCapture = isDualChannel,
                    rootApkPath = packageCodePath
                )
                if (!started) {
                    Log.w(TAG, "Ses kaydi baslatilamadi, bos oturum kapatiliyor")
                    startFailed = true
                    isRecording = false
                    if (callType == CallType.AMBIENT_RECORDING) {
                        _isAmbientRecordingFlow.value = false
                    }
                    targetFile.delete()
                    audioFile = null
                }
            }
            if (startFailed) {
                shutdownIfIdle("kayit baslatilamadi")
            }
        }
    }

    private fun stopRecordingSession() {
        if (!isRecording) return
        isRecording = false
        _isAmbientRecordingFlow.value = false

        val endTimeMs = System.currentTimeMillis()
        val capturedFile = audioFile
        val capturedType = currentCallType
        val capturedStart = startTimeMs

        serviceScope.launch {
            sessionMutex.withLock {
                audioController.stopRecordingSuspend()
            }

            val capturedNumber = currentPhoneNumber
            val wavDurationSec = computeWavDurationSeconds(capturedFile)
            val wallDurationSec = ((endTimeMs - capturedStart) / 1000L).coerceAtLeast(0L)
            val durationSec = if (wavDurationSec > 0) wavDurationSec else wallDurationSec

            val hasValidAudio = capturedFile != null && capturedFile.exists() && capturedFile.length() > 44L
            // Boş veya 1 saniyeden kısa hatalı kayıtları (00:00 hayalet kayıtları) kaydetme
            if (!hasValidAudio || durationSec < 1L) {
                Log.w(TAG, "Bos veya 0 saniyelik kayit elendi (size=${capturedFile?.length() ?: 0}, sure=${durationSec}s)")
                capturedFile?.delete()
                shutdownIfIdle("gecersiz kayit")
                return@launch
            }

            var finalNumber = capturedNumber
            var finalContactName: String? = null
            var finalCallType = capturedType

            if (capturedType == CallType.AMBIENT_RECORDING) {
                finalContactName = "Yüz Yüze / Ortam Kaydı"
            } else {
                if (finalNumber == "Bilinmeyen" || finalNumber.isBlank()) {
                    val logInfo = resolveCallInfoFromLog(capturedStart, endTimeMs)
                    if (logInfo != null) {
                        finalNumber = logInfo.number
                        finalContactName = logInfo.cachedName
                        logInfo.callType?.let { finalCallType = it }
                    }
                }
                if (finalContactName.isNullOrBlank()) {
                    finalContactName = getContactName(finalNumber)
                }
            }
            val isContact = !finalContactName.isNullOrBlank()

            val record = CallRecord(
                phoneNumber = finalNumber,
                contactName = finalContactName,
                isContact = isContact,
                callType = finalCallType,
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

            val transcriptText = OfflineSpeechTranscriber.transcribeCallSuspend(capturedFile)
            if (transcriptText.isNotBlank()) {
                LogSuApp.instance.repository.updateTranscript(recordId, transcriptText)
            }

            shutdownIfIdle("kayit tamamlandi")
        }
    }

    /**
     * Servisi yalnizca bu sirada baska bir kayit baslamadiysa kapatir.
     * Onceki oturumun kapanis coroutine'i yeni oturumu oldurmemelidir.
     */
    private suspend fun shutdownIfIdle(reason: String) {
        sessionMutex.withLock {
            if (isRecording) {
                Log.i(TAG, "Baska kayit aktif, servis kapatilmiyor ($reason)")
                return@withLock
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

    private data class CallLogInfo(
        val number: String,
        val cachedName: String?,
        val callType: CallType?,
        val dateMs: Long,
        val durationSec: Long
    )

    /**
     * Kaydin baslangic/bitis zamanina en yakin CallLog satirini secer.
     * CallLog satiri IDLE yayinindan sonra 0.5-2 sn gecikmeyle yazilabildigi icin
     * once dar, sonra genis pencerede birkac kez denenir.
     */
    private suspend fun resolveCallInfoFromLog(startMs: Long, endMs: Long): CallLogInfo? {
        repeat(CALL_LOG_QUERY_ATTEMPTS) { attempt ->
            val strictMatch = queryClosestCallLog(startMs, endMs, CALL_LOG_STRICT_DISTANCE_MS)
            if (strictMatch != null) {
                if (attempt > 0) Log.d(TAG, "CallLog kaydi ${attempt + 1}. denemede bulundu")
                return strictMatch
            }
            if (attempt < CALL_LOG_QUERY_ATTEMPTS - 1) {
                delay(CALL_LOG_RETRY_DELAY_MS)
            }
        }

        val looseMatch = queryClosestCallLog(startMs, endMs, CALL_LOG_MATCH_WINDOW_MS)
        if (looseMatch != null) {
            Log.i(TAG, "CallLog kaydi genis pencerede eslesti (kayit=$startMs, log=${looseMatch.dateMs})")
        } else {
            Log.w(TAG, "CallLog eslesmesi bulunamadi (kayit=$startMs)")
        }
        return looseMatch
    }

    private fun queryClosestCallLog(startMs: Long, endMs: Long, maxDistanceMs: Long): CallLogInfo? {
        val windowStart = startMs - CALL_LOG_MATCH_WINDOW_MS
        return try {
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(windowStart.toString()),
                "${CallLog.Calls.DATE} DESC"
            )
            val expectedDurationSec = ((endMs - startMs) / 1000L).coerceAtLeast(0L)
            cursor?.use {
                val numberIdx = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                var best: CallLogInfo? = null
                var bestScore = Long.MAX_VALUE

                while (it.moveToNext()) {
                    val num = it.getString(numberIdx)
                    if (num.isNullOrBlank()) continue

                    val dateMs = it.getLong(dateIdx)
                    val distance = when {
                        dateMs in startMs..endMs -> 0L
                        dateMs < startMs -> startMs - dateMs
                        else -> dateMs - endMs
                    }
                    if (distance > maxDistanceMs) continue

                    val durationSec = it.getLong(durationIdx)
                    val durationDiff = abs(durationSec - expectedDurationSec).coerceAtMost(999L)
                    val score = distance * 1000L + durationDiff
                    if (score < bestScore) {
                        bestScore = score
                        val typeInt = it.getInt(typeIdx)
                        val inferredType = when (typeInt) {
                            CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING_GSM
                            CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING_GSM
                            else -> null
                        }
                        best = CallLogInfo(
                            number = num,
                            cachedName = it.getString(nameIdx),
                            callType = inferredType,
                            dateMs = dateMs,
                            durationSec = durationSec
                        )
                    }
                }
                best
            }
        } catch (e: Exception) {
            Log.w(TAG, "CallLog sorgulanamadi: ${e.message}")
            null
        }
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
        isRecording = false
        val hadActiveRecording = audioController.isActiveRecording()
        serviceScope.cancel()
        if (hadActiveRecording) {
            // serviceScope iptal edildigi icin aktif kayit bagimsiz kapsamda duzgun kapatilir (WAV basligi yazilir).
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                sessionMutex.withLock {
                    runCatching { audioController.stopRecordingSuspend() }
                }
                Log.i(TAG, "Servis kapanirken aktif ses kaydi durduruldu")
            }
        }
        super.onDestroy()
    }
}
