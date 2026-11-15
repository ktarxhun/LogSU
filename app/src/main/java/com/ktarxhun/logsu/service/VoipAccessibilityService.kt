package com.ktarxhun.logsu.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ktarxhun.logsu.data.model.CallType

class VoipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoipAccessibility"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.miui.securitycenter"
        )
    }

    private var isInWhatsAppCall = false
    private var lastForegroundPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callEndWatcher = object : Runnable {
        override fun run() {
            if (!isInWhatsAppCall) return
            if (!isAudioInCommunicationMode()) {
                isInWhatsAppCall = false
                Log.i(TAG, "WhatsApp aramasi ses modu kapandigi icin sonlandirildi.")
                CallMonitorService.stopCall(this@VoipAccessibilityService)
            } else {
                mainHandler.postDelayed(this, 1500)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkgName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. WhatsApp Sesli/Görüntülü Arama Tespiti
        if (pkgName == WHATSAPP_PKG) {
            val isCallActivity = className.contains("VoipActivity", ignoreCase = true) ||
                className.contains("CallActivity", ignoreCase = true) ||
                className.contains("InCall", ignoreCase = true)

            val isMainOrChatActivity = className.contains("HomeActivity", ignoreCase = true) ||
                className.contains("Conversation", ignoreCase = true) ||
                className.contains("Main", ignoreCase = true) ||
                className.contains("Settings", ignoreCase = true)

            if (isCallActivity && !isInWhatsAppCall) {
                isInWhatsAppCall = true
                mainHandler.removeCallbacks(callEndWatcher)
                Log.i(TAG, "WhatsApp aramasi algilandi: $className")
                CallMonitorService.startCall(this, "WhatsApp Görüşmesi", CallType.WHATSAPP_CALL)
            } else if (isMainOrChatActivity && isInWhatsAppCall) {
                if (!isAudioInCommunicationMode()) {
                    isInWhatsAppCall = false
                    mainHandler.removeCallbacks(callEndWatcher)
                    Log.i(TAG, "WhatsApp aramasi sonlandi: $className")
                    CallMonitorService.stopCall(this)
                } else {
                    scheduleCallEndWatcher()
                }
            }
        } else if (isInWhatsAppCall && pkgName !in TRANSIENT_SYSTEM_PACKAGES) {
            if (!isAudioInCommunicationMode()) {
                isInWhatsAppCall = false
                mainHandler.removeCallbacks(callEndWatcher)
                Log.i(TAG, "WhatsApp aramasi sonlandi (baska uygulama): $pkgName")
                CallMonitorService.stopCall(this)
            } else {
                scheduleCallEndWatcher()
            }
        }

        // 2. Emanet Modu: Ön Plan Uygulama Değişimini Yakalama
        if (pkgName != lastForegroundPackage &&
            pkgName !in TRANSIENT_SYSTEM_PACKAGES &&
            pkgName != packageName &&
            !pkgName.contains("inputmethod", ignoreCase = true)
        ) {
            lastForegroundPackage = pkgName
            val appLabel = getAppLabel(pkgName)
            GuestGuardService.onAppOpened(pkgName, appLabel)
        }
    }

    private fun scheduleCallEndWatcher() {
        mainHandler.removeCallbacks(callEndWatcher)
        mainHandler.postDelayed(callEndWatcher, 1500)
    }

    private fun isAudioInCommunicationMode(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacks(callEndWatcher)
        Log.w(TAG, "Erisilebilirlik servisi kesintiye ugradi.")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(callEndWatcher)
        super.onDestroy()
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
