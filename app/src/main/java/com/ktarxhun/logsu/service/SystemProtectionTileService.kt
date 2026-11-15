package com.ktarxhun.logsu.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.ktarxhun.logsu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class SystemProtectionTileService : TileService() {

    private var tileScope: CoroutineScope? = null
    private var stateObserverJob: Job? = null
    private var lastAppliedState: Boolean? = null

    companion object {
        fun requestRefresh(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SystemProtectionTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        lastAppliedState = null
        applyTileState(GuestGuardService.isSessionActive)

        stateObserverJob?.cancel()
        val scope = tileScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also {
            tileScope = it
        }
        stateObserverJob = scope.launch {
            GuestGuardService.isSessionActiveFlow.collectLatest { active ->
                applyTileState(active)
            }
        }
    }

    override fun onStopListening() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        lastAppliedState = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Hedef durumu servis durumundan once hesapla ki tile rengi ve metni aninda degissin
        val targetActive = !GuestGuardService.isSessionActive
        if (targetActive) {
            applyTileState(true)
            if (!isLocked) {
                launchGuardActivityCompat()
            } else {
                GuestGuardService.startService(this, triggerTileRefresh = false)
            }
        } else {
            applyTileState(false)
            GuestGuardService.stopService(this, triggerTileRefresh = false)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchGuardActivityCompat() {
        try {
            val intent = Intent(this, GuardTileLaunchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    2002,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            GuestGuardService.startService(this, triggerTileRefresh = false)
        }
    }

    private fun applyTileState(isActive: Boolean) {
        if (lastAppliedState == isActive) return
        val tile = qsTile ?: return
        lastAppliedState = isActive
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_system_protection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) "Açık" else "Kapalı"
        }
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_lock)
        tile.updateTile()
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        tileScope?.cancel()
        tileScope = null
        super.onDestroy()
    }
}
