package com.ktarxhun.logsu.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import com.ktarxhun.logsu.root.RootShellManager
import com.ktarxhun.logsu.root.ScreenRecordController
import com.ktarxhun.logsu.root.TouchCaptureController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.miui.securitycenter"
        )
        private val RESUMED_PKG_REGEX = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""")

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
            context.startForegroundService(intent)
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
    // Baslatma ve durdurma akislarini serilestirir. Oturum yasam dongusu yarislarini onler.
    private val sessionMutex = Mutex()
    private var startSessionJob: Job? = null
    private var stopSessionJob: Job? = null
    private var foregroundWatchJob: Job? = null

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
    // Devam eden yakalamanin kamera kaynaklarini kapatan eylem. Servis kapanirken
    // beklemedeki kamera callback'lerini kaybetmeden kaynaklari serbest birakmayi saglar.
    private val cameraReleaseAction = AtomicReference<(() -> Unit)?>(null)

    private val gracePeriodRunnable = object : Runnable {
        override fun run() {
            if (gracePeriodActive) {
                gracePeriodActive = false
                Log.i(TAG, "Grace period (20sn) sona erdi.")
            }
        }
    }

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
        if (!_isSessionActiveFlow.value) {
            Log.w(TAG, "Emanet oturumu baslatma istegi pasif durumda geldi, yoksayildi")
            return
        }
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

        schedulePhotoTasks()

        startSessionJob = serviceScope.launch {
            // Baslatma ve durdurma ayni kilitle serilestirilir; boylece yarim kalan
            // baslatma mikrofon, ekran kaydi veya dokunma yakalamayi artik birakmaz.
            sessionMutex.withLock {
                if (!_isSessionActiveFlow.value) {
                    Log.w(TAG, "Baslatma sirasinda oturum kapatildi, kaynak acilmadi")
                    return@withLock
                }
                if (activeSessionId > 0) {
                    Log.w(TAG, "Emanet oturumu zaten acik (ID=$activeSessionId), yinelenen baslatma yoksayildi")
                    return@withLock
                }
                val repository = LogSuApp.instance?.repository
                if (repository == null) {
                    Log.e(TAG, "Depo erisilemiyor, emanet oturumu baslatilamadi")
                    return@withLock
                }

                val sessionId = try {
                    repository.startGuestSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Emanet oturumu kaydedilemedi", e)
                    return@withLock
                }
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
                if (RootShellManager.isRootAvailable()) {
                    RootShellManager.execute("appops set ${RootShellManager.shellQuote(packageName)} GET_USAGE_STATS allow 2>/dev/null")
                }
                foregroundWatchJob?.cancel()
                foregroundWatchJob = serviceScope.launch {
                    watchForegroundApps()
                }
                coroutineScope {
                    launch {
                        runCatching { screenController.startRecording(this@GuestGuardService, vFile) }
                            .onFailure { Log.e(TAG, "Ekran kaydi baslatilamadi", it) }
                    }
                    launch {
                        runCatching { audioController.startRecording(aFile, enableDualInternalMic = true) }
                            .onFailure { Log.e(TAG, "Ses kaydi baslatilamadi", it) }
                    }
                }

                if (!_isSessionActiveFlow.value) {
                    Log.w(TAG, "Baslatma sirasinda durdurma istendi, oturum geri aliniyor")
                    closeSessionResourcesAndRecord()
                }
            }
        }
    }

    private fun stopGuestSession() {
        _isSessionActiveFlow.value = false
        getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()
        SystemProtectionTileService.requestRefresh(this)

        foregroundWatchJob?.cancel()
        foregroundWatchJob = null
        cancelPhotoTasks()
        releaseActiveCamera()
        unregisterScreenReceiver()
        removeClipboardListener()
        touchController.stopCapture()

        stopSessionJob = serviceScope.launch {
            try {
                // Durdurma, devam eden baslatma isinin bitmesini bekler ve kaynaklari kapatir.
                withContext(NonCancellable) {
                    sessionMutex.withLock { closeSessionResourcesAndRecord() }
                }
            } finally {
                if (!_isSessionActiveFlow.value) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
        }
    }

    /**
     * Kayit kontrolculerini durdurur, dosya yollarini ve oturum satirini tamamlar.
     * Idempotenttir; baslatma geri alinirken ve servis yok edilirken de cagrilir.
     */
    private suspend fun closeSessionResourcesAndRecord() {
        runCatching { touchController.stopCapture() }
            .onFailure { Log.e(TAG, "Dokunma yakalama durdurulamadi", it) }
        try {
            coroutineScope {
                launch {
                    runCatching { audioController.stopRecordingSuspend() }
                        .onFailure { Log.e(TAG, "Ses kaydi durdurulamadi", it) }
                }
                launch {
                    runCatching { screenController.stopRecording() }
                        .onFailure { Log.e(TAG, "Ekran kaydi durdurulamadi", it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kayit kontrolculeri durdurulurken hata olustu", e)
        }

        val closingSessionId = activeSessionId
        val vFile = videoFile
        val tFile = touchFile
        val aFile = audioFile
        videoFile = null
        touchFile = null
        audioFile = null
        val switches = appSwitchCount
        val copies = clipboardCopyCount

        try {
            if (closingSessionId > 0) {
                val repository = LogSuApp.instance?.repository
                if (repository != null) {
                    try {
                        val session = repository.getGuestSessionById(closingSessionId)
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
                            repository.updateGuestSession(updated)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Oturum guncelleme hatasi: ID $closingSessionId", e)
                    } finally {
                        runCatching { repository.closeActiveGuestSession(closingSessionId) }
                    }
                    Log.i(TAG, "Emanet Modu kapatildi, oturum kaydedildi: ID $closingSessionId")
                }
            }
        } finally {
            activeSessionId = 0
        }
    }

    private fun schedulePhotoTasks() {
        val handler = cameraHandler ?: return
        handler.removeCallbacks(gracePeriodRunnable)
        handler.removeCallbacks(periodicPhotoRunnable)
        handler.postDelayed(gracePeriodRunnable, 20000L)
        handler.postDelayed(periodicPhotoRunnable, PERIODIC_PHOTO_INTERVAL_MS)
    }

    private fun cancelPhotoTasks() {
        cameraHandler?.removeCallbacks(gracePeriodRunnable)
        cameraHandler?.removeCallbacks(periodicPhotoRunnable)
    }

    private fun releaseActiveCamera() {
        val action = cameraReleaseAction.getAndSet(null) ?: return
        runCatching { action.invoke() }
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
        removeClipboardListener()
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

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            isCameraBusy.set(false)
            onFinished(null)
            return
        }

        val photosDir = File(filesDir, "guest_photos")
        if (!photosDir.exists()) photosDir.mkdirs()

        val photoFile = File(photosDir, "guest_${activeSessionId}_${System.currentTimeMillis()}.jpg")

        val callbackCalled = AtomicBoolean(false)
        val activeCamera = AtomicReference<CameraDevice?>(null)
        val activeSession = AtomicReference<CameraCaptureSession?>(null)
        var imageReader: ImageReader? = null

        // Tum kaynaklari tek yerden kapatir; yarim kalan yakalamada kamera acik kalmaz.
        fun releaseCameraResources() {
            cameraReleaseAction.set(null)
            runCatching { activeSession.getAndSet(null)?.close() }
            runCatching { activeCamera.getAndSet(null)?.close() }
            runCatching { imageReader?.close() }
            imageReader = null
        }

        // Tum cikis yollari buradan gecer; onFinished en fazla bir kez cagrilir.
        fun finishWith(path: String?) {
            if (callbackCalled.compareAndSet(false, true)) {
                releaseCameraResources()
                isCameraBusy.set(false)
                onFinished(path)
            }
        }

        cameraReleaseAction.set { finishWith(null) }

        try {
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (frontCameraId == null) {
                finishWith(null)
                return
            }

            val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader = reader

            // Kamera cevap vermezse kaynagin kilitlenmemesi icin guvenlik zaman asimi
            handler.postDelayed({
                if (!callbackCalled.get()) {
                    Log.w(TAG, "On kamera zaman asimina ugradi")
                    finishWith(null)
                }
            }, 5000)

            reader.setOnImageAvailableListener({ imageReaderRef ->
                val image = imageReaderRef.acquireLatestImage()
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
                        runCatching { image.close() }
                    }
                }
                finishWith(savedPath)
            }, handler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (callbackCalled.get()) {
                        runCatching { camera.close() }
                        return
                    }
                    activeCamera.set(camera)
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (callbackCalled.get()) {
                                    runCatching { session.close() }
                                    return
                                }
                                activeSession.set(session)
                                try {
                                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                            super.onCaptureCompleted(s, r, result)
                                        }
                                    }, handler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Kamera yakalama istegi basarisiz", e)
                                    finishWith(null)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                runCatching { session.close() }
                                finishWith(null)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Kamera oturumu kurulamadi", e)
                        finishWith(null)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { finishWith(null) }
                override fun onError(camera: CameraDevice, error: Int) { finishWith(null) }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Sessiz kamera yakalama hatasi", e)
            finishWith(null)
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

    private suspend fun watchForegroundApps() {
        var lastForegroundPkg: String? = null
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val powerManager = getSystemService(PowerManager::class.java)

        while (serviceScope.isActive && _isSessionActiveFlow.value) {
            delay(2000L)
            if (powerManager?.isInteractive == false) continue

            var detectedPkg: String? = null
            if (usageStatsManager != null) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val events = usageStatsManager.queryEvents(now - 5000L, now)
                    val ev = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev)
                        val isResumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                        } else {
                            @Suppress("DEPRECATION")
                            ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                        }
                        if (isResumed && !ev.packageName.isNullOrBlank()) {
                            detectedPkg = ev.packageName
                        }
                    }
                }
            }

            if (detectedPkg.isNullOrBlank() && RootShellManager.isRootAvailable()) {
                val out = RootShellManager.executeSingle(
                    "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 1"
                )
                detectedPkg = RESUMED_PKG_REGEX.find(out)?.groupValues?.getOrNull(1)
            }

            val pkg = detectedPkg
            if (!pkg.isNullOrBlank() &&
                pkg != lastForegroundPkg &&
                pkg !in TRANSIENT_SYSTEM_PACKAGES &&
                pkg != packageName &&
                !pkg.contains("inputmethod", ignoreCase = true)
            ) {
                lastForegroundPkg = pkg
                handleAppOpened(pkg, resolveAppLabel(pkg))
            }
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    override fun onDestroy() {
        foregroundWatchJob?.cancel()
        foregroundWatchJob = null
        cancelPhotoTasks()
        releaseActiveCamera()
        unregisterScreenReceiver()
        removeClipboardListener()
        _isSessionActiveFlow.value = false
        getSharedPreferences("logsu_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_guard_active", false).apply()

        startSessionJob?.cancel()
        startSessionJob = null

        // Servis yok edilirken kayit kontrolculeri kosulsuz kapatilir. Aksi halde stopGuestSession
        // cagrilmadan servis olurse mikrofon, ekran kaydi ve dokunma okuyucu acik kalir.
        runCatching {
            runBlocking {
                withTimeoutOrNull(10_000L) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { touchController.stopCapture() }
                        sessionMutex.withLock { closeSessionResourcesAndRecord() }
                    }
                }
            }
        }

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        stopSessionJob = null
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}
