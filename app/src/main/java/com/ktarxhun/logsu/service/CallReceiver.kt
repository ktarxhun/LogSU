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
        private val stateLock = Any()
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

        var shouldProcess = false
        var numberUpdateOnly = false
        var callTypeForAction = CallType.INCOMING_GSM
        var numberForAction = "Bilinmeyen"

        synchronized(stateLock) {
            if (!incomingNumber.isNullOrBlank()) {
                savedNumber = incomingNumber
            }

            if (stateStr == lastState) {
                if (stateStr == TelephonyManager.EXTRA_STATE_OFFHOOK && !incomingNumber.isNullOrBlank()) {
                    shouldProcess = true
                    numberUpdateOnly = true
                    callTypeForAction = if (isIncoming) CallType.INCOMING_GSM else CallType.OUTGOING_GSM
                    numberForAction = incomingNumber
                }
            } else {
                shouldProcess = true
                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        isIncoming = true
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        callTypeForAction = if (isIncoming) CallType.INCOMING_GSM else CallType.OUTGOING_GSM
                        numberForAction = savedNumber ?: "Bilinmeyen"
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        isIncoming = false
                        savedNumber = null
                    }
                }
                lastState = stateStr
            }
        }

        if (!shouldProcess) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (numberUpdateOnly) {
                    Log.d(TAG, "OFFHOOK numara guncellendi: $numberForAction")
                    CallMonitorService.startCall(context, numberForAction, callTypeForAction)
                } else {
                    when (stateStr) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            Log.d(TAG, "Telefon caliyor: $savedNumber")
                        }
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            Log.i(TAG, "Arama basladi (OFFHOOK): $numberForAction, Tur: $callTypeForAction")
                            CallMonitorService.startCall(context, numberForAction, callTypeForAction)
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            Log.i(TAG, "Arama sonlandi (IDLE)")
                            CallMonitorService.stopCall(context)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "CallReceiver isleme hatasi", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
