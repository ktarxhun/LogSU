package com.ktarxhun.logsu

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ktarxhun.logsu.data.db.LogSuDatabase
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.data.repository.RecordRepository
import com.ktarxhun.logsu.service.CallMonitorService
import com.ktarxhun.logsu.service.CallReceiver
import com.ktarxhun.logsu.service.GuestGuardService
import com.ktarxhun.logsu.service.RetentionWorker
import com.ktarxhun.logsu.ui.auth.AppSessionLock
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LogSuApp : Application() {

    companion object {
        private const val TAG = "LogSuApp"
        private const val VOIP_START_DELAY_MS = 600L
        private const val VOIP_STOP_DEBOUNCE_MS = 1500L
        const val CHANNEL_CALL_MONITOR = "logsu_call_monitor"
        const val CHANNEL_GUEST_GUARD = "logsu_guest_guard"
        lateinit var instance: LogSuApp
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var voipStartJob: Job? = null
    private var voipStopJob: Job? = null
    @Volatile
    private var isVoipCallActive = false
    val database by lazy { LogSuDatabase.getDatabase(this) }
    val repository by lazy { RecordRepository(database.callRecordDao(), database.guestSessionDao(), database.guestEventDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        registerActivityLifecycleCallbacks(AppSessionLock)

        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        createNotificationChannels()
        scheduleRetentionWorker()
        GuestGuardService.initPrefs(this)
        appScope.launch {
            runCatching { CallReceiver.resetStateIfStale(this@LogSuApp) }
        }
        if (!GuestGuardService.isSessionActive) {
            appScope.launch {
                runCatching { repository.closeOrphanedGuestSessions() }
            }
        }
        setupVoipAudioModeWatcher()
    }

    private fun setupVoipAudioModeWatcher() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(this)) { mode ->
                handleAudioModeChanged(mode, audioManager)
            }
        } else {
            appScope.launch {
                var lastMode = audioManager.mode
                while (isActive) {
                    delay(1500L)
                    val currentMode = audioManager.mode
                    if (currentMode != lastMode) {
                        lastMode = currentMode
                        handleAudioModeChanged(currentMode, audioManager)
                    }
                }
            }
        }
    }

    private fun handleAudioModeChanged(mode: Int, audioManager: AudioManager) {
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            // Mod geri geldiyse bekleyen durdurma islemi iptal edilir.
            voipStopJob?.cancel()
            voipStopJob = null
            if (isVoipCallActive) return
            voipStartJob?.cancel()
            voipStartJob = appScope.launch {
                delay(VOIP_START_DELAY_MS)
                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) return@launch
                if (isGsmCallBusy(audioManager)) {
                    Log.d(TAG, "GSM aramasi suruyor, VoIP kaydi baslatilmiyor")
                    return@launch
                }
                if (!isVoipCallActive) {
                    isVoipCallActive = true
                    CallMonitorService.startCall(this@LogSuApp, "WhatsApp Görüşmesi", CallType.WHATSAPP_CALL)
                }
            }
        } else {
            voipStartJob?.cancel()
            voipStartJob = null
            if (!isVoipCallActive) return
            // Cikista aninda durdurma yok: hoparlor/ahize/Bluetooth gecisleri ses modunu kisa sure degistirebilir.
            voipStopJob?.cancel()
            voipStopJob = appScope.launch {
                delay(VOIP_STOP_DEBOUNCE_MS)
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    Log.d(TAG, "Ses modu geri geldi, VoIP kaydi surduruluyor")
                    return@launch
                }
                if (isGsmCallBusy(audioManager)) {
                    // GSM aramasinin kaydini VoIP izleyicisi durdurmamali; kapatmayi CallReceiver yonetir.
                    // Bayrak korunur, boylece VoIP gorusmesi kapandiginda kayit kapanabilir.
                    Log.i(TAG, "GSM aramasi suruyor, VoIP izleyicisi kaydi durdurmuyor")
                    return@launch
                }
                if (isVoipCallActive) {
                    isVoipCallActive = false
                    CallMonitorService.stopCall(this@LogSuApp)
                }
            }
        }
    }

    private fun isGsmCallBusy(audioManager: AudioManager): Boolean {
        if (audioManager.mode == AudioManager.MODE_IN_CALL) return true
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            tm.callState != TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(false)
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val callChannel = NotificationChannel(
            CHANNEL_CALL_MONITOR,
            "Arama Servisi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Telefon araması kayıt ve loglama bildirimi"
            setShowBadge(false)
        }

        val guestChannel = NotificationChannel(
            CHANNEL_GUEST_GUARD,
            "Sistem Koruması",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Emanet modu ve sistem güvenlik bildirimi"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        notificationManager.createNotificationChannel(callChannel)
        notificationManager.createNotificationChannel(guestChannel)
    }

    private fun scheduleRetentionWorker() {
        val retentionRequest = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RetentionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            retentionRequest
        )
    }
}
