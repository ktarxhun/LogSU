package com.ktarxhun.logsu.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.ktarxhun.logsu.data.model.CallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallReceiver"
        @Volatile
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
        @Volatile
        private var savedNumber: String? = null
        @Volatile
        private var isIncoming = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        if (!incomingNumber.isNullOrBlank()) {
            savedNumber = incomingNumber
        }

        if (stateStr == lastState) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        isIncoming = true
                        Log.d(TAG, "Telefon caliyor: $savedNumber")
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        // Arama açıldı veya giden arama başladı
                        val callType = if (isIncoming) CallType.INCOMING_GSM else CallType.OUTGOING_GSM
                        val number = savedNumber ?: "Bilinmeyen"
                        Log.i(TAG, "Arama basladi (OFFHOOK): $number, Tur: $callType")
                        CallMonitorService.startCall(context, number, callType)
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        // Arama sonlandı
                        Log.i(TAG, "Arama sonlandi (IDLE)")
                        CallMonitorService.stopCall(context)
                        isIncoming = false
                        savedNumber = null
                    }
                }
                lastState = stateStr
            } catch (e: Exception) {
                Log.e(TAG, "CallReceiver isleme hatasi", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
