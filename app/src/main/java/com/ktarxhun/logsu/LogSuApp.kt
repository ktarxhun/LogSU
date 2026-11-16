package com.ktarxhun.logsu

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ktarxhun.logsu.data.db.LogSuDatabase
import com.ktarxhun.logsu.data.repository.RecordRepository
import com.ktarxhun.logsu.service.GuestGuardService
import com.ktarxhun.logsu.service.RetentionWorker
import com.ktarxhun.logsu.ui.auth.AppSessionLock
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LogSuApp : Application() {

    companion object {
        const val CHANNEL_CALL_MONITOR = "logsu_call_monitor"
        const val CHANNEL_GUEST_GUARD = "logsu_guest_guard"
        lateinit var instance: LogSuApp
            private set
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        if (!GuestGuardService.isSessionActive) {
            appScope.launch {
                runCatching { repository.closeOrphanedGuestSessions() }
            }
        }
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
