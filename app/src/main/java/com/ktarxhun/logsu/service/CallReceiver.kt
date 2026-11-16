package com.ktarxhun.logsu.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.ktarxhun.logsu.data.model.CallType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class CallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CallReceiver"
        private const val PREFS_NAME = "call_receiver_state"
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_SAVED_NUMBER = "saved_number"
        private const val KEY_IS_INCOMING = "is_incoming"

        private val stateLock = Any()

        // Yayinlar tek bir is parcaciginda sirayla islenir; OFFHOOK/IDLE sirasi bozulmaz.
        private val serialExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CallReceiver-serial").apply { isDaemon = true }
        }
        private val receiverScope = CoroutineScope(SupervisorJob() + serialExecutor.asCoroutineDispatcher())

        @Volatile
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
        @Volatile
        private var savedNumber: String? = null
        @Volatile
        private var isIncoming = false
        @Volatile
        private var stateLoaded = false
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
            restoreStateIfNeeded(context)

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

            persistState(context)
        }

        if (!shouldProcess) return

        val pendingResult = goAsync()
        receiverScope.launch {
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

    /**
     * RINGING bilgisi surece bagli statik alanlarda tutuldugu icin, cagri calarken
     * surec olurse gelen arama bilgisi kayboluyordu. Durum SharedPreferences'a yazilir.
     */
    private fun restoreStateIfNeeded(context: Context) {
        if (stateLoaded) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastState = prefs.getString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE)
            ?: TelephonyManager.EXTRA_STATE_IDLE
        savedNumber = prefs.getString(KEY_SAVED_NUMBER, null)
        isIncoming = prefs.getBoolean(KEY_IS_INCOMING, false)
        stateLoaded = true
        if (lastState != TelephonyManager.EXTRA_STATE_IDLE || savedNumber != null) {
            Log.i(TAG, "Onceki arama durumu geri yuklendi: state=$lastState, numara=$savedNumber, gelen=$isIncoming")
        }
    }

    private fun persistState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_STATE, lastState)
            .putString(KEY_SAVED_NUMBER, savedNumber)
            .putBoolean(KEY_IS_INCOMING, isIncoming)
            .apply()
    }
}
