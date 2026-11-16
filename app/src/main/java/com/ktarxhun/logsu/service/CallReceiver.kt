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
        private const val KEY_SAVED_AT = "saved_at"

        /** Bu suredden eski kalici durum artik guvenilmez sayilir (ornek: surec RINGING'de olmus). */
        private const val STALE_STATE_MS = 120_000L

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

        /**
         * Surec baslangicinda telefon bos ise ve kalici arama durumu yas sinirini asmisse temizler.
         * Boylece onceki RINGING kalintisi yeni giden aramayi gelen sanmasina engel olur.
         */
        fun resetStateIfStale(context: Context) {
            synchronized(stateLock) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val callIdle = runCatching {
                    @Suppress("DEPRECATION")
                    tm == null || tm.callState == TelephonyManager.CALL_STATE_IDLE
                }.getOrDefault(true)
                if (!callIdle) return

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
                if (savedAt > 0L && System.currentTimeMillis() - savedAt > STALE_STATE_MS) {
                    prefs.edit().clear().apply()
                    lastState = TelephonyManager.EXTRA_STATE_IDLE
                    savedNumber = null
                    isIncoming = false
                    stateLoaded = false
                    Log.i(TAG, "Surec baslangicinda eski arama durumu temizlendi")
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // Prefs okuma/yazma ve arama isleme ana thread'de kalmaz; goAsync ile alinan arka plan
        // suresi icinde tek is parcaciginda sirayla yurutulur.
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                processPhoneState(context, stateStr, incomingNumber)
            } catch (e: Exception) {
                Log.e(TAG, "CallReceiver isleme hatasi", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun processPhoneState(context: Context, stateStr: String, incomingNumber: String?) {
        var shouldProcess = false
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

    /**
     * RINGING bilgisi surece bagli statik alanlarda tutuldugu icin, cagri calarken
     * surec olurse gelen arama bilgisi kayboluyordu. Durum SharedPreferences'a yazilir.
     */
    private fun restoreStateIfNeeded(context: Context) {
        if (stateLoaded) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (savedAt > 0L && System.currentTimeMillis() - savedAt > STALE_STATE_MS) {
            // Surec RINGING/OFFHOOK ile olmus olabilir; eski kayit yeni gelen aramayi
            // yanlis tur ve yanlis numara ile yazdirtmasin.
            prefs.edit().clear().apply()
            lastState = TelephonyManager.EXTRA_STATE_IDLE
            savedNumber = null
            isIncoming = false
            stateLoaded = true
            Log.i(TAG, "Eski arama durumu temizlendi (yas siniri asildi)")
            return
        }
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
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }
}
