package com.ktarxhun.logsu.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ktarxhun.logsu.LogSuApp
import com.ktarxhun.logsu.data.model.GuestEvent
import com.ktarxhun.logsu.data.model.GuestEventType
import com.ktarxhun.logsu.root.AudioCaptureController
import com.ktarxhun.logsu.root.ScreenRecordController
import com.ktarxhun.logsu.root.TouchCaptureController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GuestGuardService : Service() {

    companion object {
        private const val TAG = "GuestGuardService"
        const val ACTION_START_GUARD = "com.ktarxhun.logsu.ACTION_START_GUARD"
        const val ACTION_STOP_GUARD = "com.ktarxhun.logsu.ACTION_STOP_GUARD"
        private const val PERIODIC_PHOTO_INTERVAL_MS = 45000L

        private val _isSessionActiveFlow = MutableStateFlow(false)
        val isSessionActiveFlow = _isSessionActiveFlow.asStateFlow()

        val isSessionActive: Boolean
            get() = _isSessionActiveFlow.value

        @Volatile
        private var activeSessionId: Long = 0
        @Volatile
        private var instance: GuestGuardService? = null

        fun initPrefs(context: Context) {
            val prefs = context.getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE)
            val savedActive = prefs.getBoolean("is_guard_active", false)
            // Servis süreçte canlı değilse stale true durumunu sıfırla
            if (savedActive && instance == null) {
                prefs.edit().putBoolean("is_guard_active", false).apply()
                _isSessionActiveFlow.value = false
            } else {
                _isSessionActiveFlow.value = savedActive
            }
        }

        fun onAppOpened(packageName: String, appLabel: String) {
            instance?.handleAppOpened(packageName, appLabel)
        }

        fun toggleService(context: Context) {
            if (_isSessionActiveFlow.value) {
                stopService(context)
            } else {
                startService(context)
            }
        }

        fun startService(context: Context, triggerTileRefresh: Boolean = true) {
            _isSessionActiveFlow.value = true
            context.getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", true).apply()
            if (triggerTileRefresh) {
                SystemProtectionTileService.requestRefresh(context)
            }
            val intent = Intent(context, GuestGuardService::class.java).apply {
                action = ACTION_START_GUARD
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context, triggerTileRefresh: Boolean = true) {
            _isSessionActiveFlow.value = false
            context.getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()
            if (triggerTileRefresh) {
                SystemProtectionTileService.requestRefresh(context)
            }
            if (instance != null) {
                val intent = Intent(context, GuestGuardService::class.java).apply {
                    action = ACTION_STOP_GUARD
                }
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    instance?.stopSelf()
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val screenController = ScreenRecordController()
    private val touchController = TouchCaptureController()
    private val audioController = AudioCaptureController()
    private var stopSessionJob: Job? = null

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var gracePeriodActive = true
    @Volatile
    private var lastPhotoTime: Long = 0
    @Volatile
    private var appSwitchCount = 0
    @Volatile
    private var clipboardCopyCount = 0
    private var sessionStartTime: Long = 0

    private var videoFile: File? = null
    private var touchFile: File? = null
    private var audioFile: File? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val isCameraBusy = AtomicBoolean(false)

    private val periodicPhotoRunnable = object : Runnable {
        override fun run() {
            if (!_isSessionActiveFlow.value) return
            val isInteractive = (getSystemService(PowerManager::class.java)?.isInteractive) != false
            if (!gracePeriodActive && isInteractive && (System.currentTimeMillis() - lastPhotoTime >= PERIODIC_PHOTO_INTERVAL_MS)) {
                lastPhotoTime = System.currentTimeMillis()
                captureFrontPhoto("Periyodik Kontrol") { savedPath ->
                    if (savedPath != null && activeSessionId > 0) {
                        serviceScope.launch {
                            LogSuApp.instance.repository.logGuestEvent(
                                GuestEvent(
                                    sessionId = activeSessionId,
                                    eventType = GuestEventType.PHOTO_CAPTURED,
                                    mediaPath = savedPath
                                )
                            )
                        }
                    }
                }
            }
            cameraHandler?.postDelayed(this, PERIODIC_PHOTO_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraThread = HandlerThread("LogSuCameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GUARD -> startGuestSession()
            ACTION_STOP_GUARD -> stopGuestSession()
            null -> {
                if (activeSessionId <= 0) {
                    _isSessionActiveFlow.value = false
                    getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startGuestSession() {
        _isSessionActiveFlow.value = true
        gracePeriodActive = true
        appSwitchCount = 0
        clipboardCopyCount = 0
        lastPhotoTime = 0
        sessionStartTime = System.currentTimeMillis()

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        2002,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Camera/Microphone FGS type reddedildi, specialUse ile devam ediliyor", e)
                    startForeground(
                        2002,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    2002,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(2002, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground basarisiz oldu", e)
            _isSessionActiveFlow.value = false
            getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()
            SystemProtectionTileService.requestRefresh(this)
            stopSelf()
            return
        }

        Log.i(TAG, "Sistem Korumasi (Emanet Modu) baslatildi.")

        registerScreenReceiver()
        setupClipboardListener()

        cameraHandler?.postDelayed({
            if (gracePeriodActive) {
                gracePeriodActive = false
                Log.i(TAG, "Grace period (20sn) sona erdi.")
            }
        }, 20000)
        cameraHandler?.postDelayed(periodicPhotoRunnable, PERIODIC_PHOTO_INTERVAL_MS)

        serviceScope.launch {
            stopSessionJob?.join()
            val sessionId = LogSuApp.instance.repository.startGuestSession()
            activeSessionId = sessionId

            val guestDir = getGuestRecordingsDir()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(sessionStartTime))
            val vFile = File(guestDir, "guest_${sessionId}_${timeStamp}.mp4")
            val tFile = File(guestDir, "guest_${sessionId}_${timeStamp}.touch")
            val aFile = File(guestDir, "guest_${sessionId}_${timeStamp}.wav")

            videoFile = vFile
            touchFile = tFile
            audioFile = aFile

            val isInteractive = (getSystemService(PowerManager::class.java)?.isInteractive) != false
            screenController.setScreenInteractive(isInteractive)

            touchController.startCapture(tFile, sessionStartTime)
            coroutineScope {
                launch { screenController.startRecording(this@GuestGuardService, vFile) }
                launch { audioController.startRecording(aFile, enableDualInternalMic = true) }
            }
        }
    }

    private fun stopGuestSession() {
        _isSessionActiveFlow.value = false
        getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()
        SystemProtectionTileService.requestRefresh(this)

        cameraHandler?.removeCallbacksAndMessages(null)
        unregisterScreenReceiver()
        removeClipboardListener()
        touchController.stopCapture()

        val closingSessionId = activeSessionId
        val vFile = videoFile
        val tFile = touchFile
        val aFile = audioFile
        videoFile = null
        touchFile = null
        audioFile = null
        val switches = appSwitchCount
        val copies = clipboardCopyCount

        stopSessionJob = serviceScope.launch {
            coroutineScope {
                launch { audioController.stopRecordingSuspend() }
                launch { screenController.stopRecording() }
            }

            if (closingSessionId > 0) {
                val session = LogSuApp.instance.repository.getGuestSessionById(closingSessionId)
                if (session != null) {
                    val updated = session.copy(
                        endTime = System.currentTimeMillis(),
                        totalAppSwitches = switches,
                        totalClipboardCopies = copies,
                        videoFilePath = vFile?.takeIf { it.exists() && it.length() > 1024 }?.absolutePath,
                        touchFilePath = tFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                        audioFilePath = aFile?.takeIf { it.exists() && it.length() > 44 }?.absolutePath,
                        isActive = false
                    )
                    LogSuApp.instance.repository.updateGuestSession(updated)
                }
                LogSuApp.instance.repository.closeActiveGuestSession(closingSessionId)
                Log.i(TAG, "Emanet Modu kapatildi, oturum kaydedildi: ID $closingSessionId")
            }

            if (!_isSessionActiveFlow.value) {
                activeSessionId = 0
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun getGuestRecordingsDir(): File {
        val dir = File(filesDir, "guest_recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Ekran kapandi, video kaydi duraklatiliyor (ortam sesi suruyor).")
                        screenController.setScreenInteractive(false)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Ekran acildi, video kaydi devam ettiriliyor.")
                        screenController.setScreenInteractive(true)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.i(TAG, "Kilit acildi, emanet kullanici aktif!")
                        screenController.setScreenInteractive(true)
                        gracePeriodActive = false
                        lastPhotoTime = System.currentTimeMillis()
                        val sid = activeSessionId
                        captureFrontPhoto("Kilit Açıldı") { savedPath ->
                            if (savedPath != null && sid > 0) {
                                serviceScope.launch {
                                    LogSuApp.instance.repository.logGuestEvent(
                                        GuestEvent(
                                            sessionId = sid,
                                            eventType = GuestEventType.PHOTO_CAPTURED,
                                            mediaPath = savedPath
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            screenReceiver = null
        }
    }

    private fun setupClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (!_isSessionActiveFlow.value || activeSessionId <= 0) return@OnPrimaryClipChangedListener

            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank()) {
                    clipboardCopyCount++
                    val sid = activeSessionId
                    serviceScope.launch {
                        val event = GuestEvent(
                            sessionId = sid,
                            eventType = GuestEventType.CLIPBOARD_COPIED,
                            textContent = text
                        )
                        LogSuApp.instance.repository.logGuestEvent(event)
                    }
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun removeClipboardListener() {
        clipboardListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
            clipboardListener = null
        }
    }

    fun handleAppOpened(packageName: String, appLabel: String) {
        if (!_isSessionActiveFlow.value || activeSessionId <= 0) return
        appSwitchCount++

        val sid = activeSessionId
        val now = System.currentTimeMillis()
        val shouldCapturePhoto = !gracePeriodActive && (now - lastPhotoTime > 15000L)
        if (shouldCapturePhoto) {
            lastPhotoTime = now
            captureFrontPhoto(appLabel) { savedPath ->
                serviceScope.launch {
                    LogSuApp.instance.repository.logGuestEvent(
                        GuestEvent(
                            sessionId = sid,
                            eventType = GuestEventType.APP_OPENED,
                            targetPackage = packageName,
                            appLabel = appLabel,
                            mediaPath = savedPath
                        )
                    )
                }
            }
        } else {
            serviceScope.launch {
                LogSuApp.instance.repository.logGuestEvent(
                    GuestEvent(
                        sessionId = sid,
                        eventType = GuestEventType.APP_OPENED,
                        targetPackage = packageName,
                        appLabel = appLabel,
                        mediaPath = null
                    )
                )
            }
        }
    }

    private fun captureFrontPhoto(contextTag: String, onFinished: (String?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onFinished(null)
            return
        }
        if (!isCameraBusy.compareAndSet(false, true)) {
            onFinished(null)
            return
        }

        val handler = cameraHandler
        if (handler == null) {
            isCameraBusy.set(false)
            onFinished(null)
            return
        }

        val photosDir = File(filesDir, "guest_photos")
        if (!photosDir.exists()) photosDir.mkdirs()

        val photoFile = File(photosDir, "guest_${activeSessionId}_${System.currentTimeMillis()}.jpg")
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            isCameraBusy.set(false)
            onFinished(null)
            return
        }

        try {
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (frontCameraId == null) {
                isCameraBusy.set(false)
                onFinished(null)
                return
            }

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            val callbackCalled = AtomicBoolean(false)
            val activeCamera = AtomicReference<CameraDevice?>(null)

            fun finishWith(camera: CameraDevice?, path: String?) {
                if (callbackCalled.compareAndSet(false, true)) {
                    try { (camera ?: activeCamera.get())?.close() } catch (_: Exception) {}
                    try { imageReader.close() } catch (_: Exception) {}
                    isCameraBusy.set(false)
                    onFinished(path)
                }
            }

            // Kamera cevap vermezse kaynağın kilitlenmemesi için güvenlik zaman aşımı
            handler.postDelayed({
                if (!callbackCalled.get()) {
                    Log.w(TAG, "On kamera zaman asimina ugradi")
                    finishWith(activeCamera.get(), null)
                }
            }, 5000)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                var savedPath: String? = null
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        FileOutputStream(photoFile).use { it.write(bytes) }
                        if (photoFile.exists() && photoFile.length() > 0) {
                            savedPath = photoFile.absolutePath
                            Log.i(TAG, "Sessiz on kamera fotografi kaydedildi: ${photoFile.name} ($contextTag)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fotograf dosyaya yazilamadi", e)
                    } finally {
                        image.close()
                    }
                }
                finishWith(activeCamera.get(), savedPath)
            }, handler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (callbackCalled.get()) {
                        try { camera.close() } catch (_: Exception) {}
                        return
                    }
                    activeCamera.set(camera)
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                            super.onCaptureCompleted(s, r, result)
                                        }
                                    }, handler)
                                } catch (_: Exception) {
                                    finishWith(camera, null)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                finishWith(camera, null)
                            }
                        }, handler)
                    } catch (_: Exception) {
                        finishWith(camera, null)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { finishWith(camera, null) }
                override fun onError(camera: CameraDevice, error: Int) { finishWith(camera, null) }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Kamera izni eksik", e)
            isCameraBusy.set(false)
            onFinished(null)
        } catch (e: Exception) {
            Log.e(TAG, "Sessiz kamera yakalama hatasi", e)
            isCameraBusy.set(false)
            onFinished(null)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, LogSuApp.CHANNEL_GUEST_GUARD)
            .setContentTitle("Sistem Koruması")
            .setContentText("Güvenlik ve arka plan optimizasyonu devrede")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        cameraHandler?.removeCallbacksAndMessages(null)
        unregisterScreenReceiver()
        removeClipboardListener()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        serviceScope.cancel()
        _isSessionActiveFlow.value = false
        getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()
        instance = null
        super.onDestroy()
    }
}
