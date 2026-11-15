package com.ktarxhun.logsu.ui.auth

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ktarxhun.logsu.service.GuardTileLaunchActivity

/**
 * Uygulama içi oturum kilidi:
 * Kullanıcı uygulamayı ilk açtığında kilidi açtıktan sonra uygulama içi ekran geçişlerinde
 * (örn. MainActivity <-> LogSuPlayerActivity) tekrar parmak izi istemez.
 * Uygulama tamamen arka plana geçtiğinde (Ev tuşu, başka uygulama, ekran kilidi) oturum tekrar kilitlenir.
 */
object AppSessionLock : Application.ActivityLifecycleCallbacks {
    var isUnlocked by mutableStateOf(false)
        private set

    private var startedActivityCount = 0

    fun unlock() {
        isUnlocked = true
    }

    fun lock() {
        isUnlocked = false
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is GuardTileLaunchActivity) return
        startedActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is GuardTileLaunchActivity) return
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        // Yapılandırma değişimi (ekran döndürme vb.) değilse ve hiç görünür Activity kalmadıysa kilitle
        if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
            isUnlocked = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
