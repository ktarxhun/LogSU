package com.ktarxhun.logsu.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ktarxhun.logsu.LogSuApp
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.CallType
import com.ktarxhun.logsu.data.model.GuestEvent
import com.ktarxhun.logsu.data.model.GuestEventType
import com.ktarxhun.logsu.data.model.GuestSession
import com.ktarxhun.logsu.data.model.TouchEvent
import com.ktarxhun.logsu.ui.FormatUtils
import com.ktarxhun.logsu.ui.LockScreen
import com.ktarxhun.logsu.ui.auth.AppSessionLock
import com.ktarxhun.logsu.ui.auth.AuthManager
import com.ktarxhun.logsu.ui.components.ConfirmDeleteDialog
import com.ktarxhun.logsu.ui.theme.*
import com.ktarxhun.logsu.util.GalleryExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class LogSuPlayerActivity : FragmentActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_GUEST_SESSION_ID = "extra_guest_session_id"
    }

    private var exoPlayer: ExoPlayer? = null
    private val channelProcessor = StereoChannelControlAudioProcessor()
    private var isPromptInFlight = false

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        val guestSessionId = intent.getLongExtra(EXTRA_GUEST_SESSION_ID, -1L)

        setContent {
            LogSuTheme {
                val isUnlocked = AppSessionLock.isUnlocked

                if (!isUnlocked) {
                    LockScreen(onUnlockClicked = { authenticate() })
                } else if (guestSessionId > 0) {
                    GuestSessionPlayerScreen(
                        sessionId = guestSessionId,
                        onBack = { finish() },
                        getExoPlayer = { initExoPlayer() },
                        channelProcessor = channelProcessor
                    )
                } else {
                    CallAudioPlayerScreen(
                        recordId = recordId,
                        onBack = { finish() },
                        getExoPlayer = { initExoPlayer() },
                        channelProcessor = channelProcessor
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AppSessionLock.isUnlocked && !isPromptInFlight) {
            authenticate()
        }
    }

    private fun authenticate() {
        if (isPromptInFlight || AppSessionLock.isUnlocked) return
        isPromptInFlight = true
        AuthManager.authenticate(
            activity = this,
            onSuccess = {
                isPromptInFlight = false
                AppSessionLock.unlock()
            },
            onError = { err ->
                isPromptInFlight = false
                if (err.isNotBlank()) {
                    Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    @OptIn(UnstableApi::class)
    private fun initExoPlayer(): ExoPlayer {
        return exoPlayer ?: run {
            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(channelProcessor))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                }
            }
            ExoPlayer.Builder(this, renderersFactory).build().also {
                exoPlayer = it
            }
        }
    }

    override fun onStop() {
        exoPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

private fun togglePlayWithAutoRestart(exoPlayer: ExoPlayer) {
    if (exoPlayer.isPlaying) {
        exoPlayer.pause()
    } else {
        val dur = exoPlayer.duration
        if (exoPlayer.playbackState == Player.STATE_ENDED ||
            (dur > 0L && exoPlayer.currentPosition >= dur - 250L)
        ) {
            exoPlayer.seekTo(0L)
        }
        exoPlayer.play()
    }
}

@Composable
private fun rememberPlayerState(exoPlayer: ExoPlayer, pollIntervalMs: Long = 100L): State<Triple<Boolean, Long, Long>> {
    val state = remember(exoPlayer) {
        mutableStateOf(
            Triple(
                exoPlayer.isPlaying,
                exoPlayer.currentPosition.coerceAtLeast(0L),
                exoPlayer.duration.takeIf { it > 0L } ?: 0L
            )
        )
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.value = Triple(
                    isPlaying,
                    exoPlayer.currentPosition.coerceAtLeast(0L),
                    exoPlayer.duration.takeIf { it > 0L } ?: 0L
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                state.value = Triple(
                    exoPlayer.isPlaying,
                    exoPlayer.currentPosition.coerceAtLeast(0L),
                    exoPlayer.duration.takeIf { it > 0L } ?: 0L
                )
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            val dur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val playing = exoPlayer.isPlaying
            if (state.value != Triple(playing, pos, dur)) {
                state.value = Triple(playing, pos, dur)
            }
            delay(if (playing) pollIntervalMs else 250L)
        }
    }

    return state
}

@OptIn(UnstableApi::class)
@Composable
fun CallAudioPlayerScreen(
    recordId: Long,
    onBack: () -> Unit,
    getExoPlayer: () -> ExoPlayer,
    channelProcessor: StereoChannelControlAudioProcessor
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf<CallRecord?>(null) }

    var micEnabled by remember { mutableStateOf(true) }
    var callerEnabled by remember { mutableStateOf(true) }
    var audioChannels by remember { mutableIntStateOf(1) }

    val exoPlayer = remember { getExoPlayer() }

    LaunchedEffect(recordId) {
        channelProcessor.leftEnabled = true
        channelProcessor.rightEnabled = true
        channelProcessor.boostEnabled = false

        val rec = withContext(Dispatchers.IO) {
            LogSuApp.instance.repository.getCallRecordById(recordId)
        }
        record = rec
        rec?.audioFilePath?.let { audioPath ->
            val file = File(audioPath)
            if (file.exists()) {
                val channels = withContext(Dispatchers.IO) {
                    GalleryExporter.readWavInfo(file)?.channels ?: 1
                }
                audioChannels = channels
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
            }
        }
    }

    val isAmbient = record?.callType == CallType.AMBIENT_RECORDING

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAmbient) "Ses Kaydı #$recordId" else (record?.contactName ?: record?.phoneNumber ?: "Kayıt #$recordId"),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                    Text(
                        text = record?.let { FormatUtils.formatShortDate(it.startTime) } ?: "",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Aramalar sekmesindeki gibi kompakt küçük aksiyon butonları
                if (record != null) {
                    if (!isAmbient) {
                        val locked = record!!.isLocked
                        IconButton(onClick = {
                            scope.launch {
                                val nextLocked = !locked
                                LogSuApp.instance.repository.setLockStatus(record!!.id, nextLocked)
                                record = record!!.copy(isLocked = nextLocked)
                            }
                        }) {
                            Icon(
                                imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Kilit",
                                tint = if (locked) AccentOrange else TextSecondary
                            )
                        }
                    }

                    IconButton(
                        enabled = !isExporting && record?.audioFilePath != null,
                        onClick = {
                            record?.audioFilePath?.let { audioPath ->
                                isExporting = true
                                scope.launch {
                                    val title = if (isAmbient) "Ses Kaydı #${record!!.id}" else (record!!.contactName ?: record!!.phoneNumber)
                                    val subtitle = FormatUtils.formatShortDate(record!!.startTime)
                                    GalleryExporter.exportAudioAsVideoToGallery(
                                        context = context,
                                        audioFile = File(audioPath),
                                        title = title,
                                        subtitle = subtitle
                                    ) { _, _ ->
                                        isExporting = false
                                    }
                                }
                            }
                        }
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentPrimary)
                        } else {
                            Icon(Icons.Default.Movie, contentDescription = "Videoya Kaydet", tint = AccentPrimary)
                        }
                    }

                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Sil", tint = AccentRed.copy(alpha = 0.85f))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            CallPlaybackCard(exoPlayer = exoPlayer)

            // Ses Kontrolleri: Aramalarda Mikrofon / Arayan seçimi; Ses Kaydında kanal seçimi yok
            if (!isAmbient && audioChannels >= 2) {
                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Ses Kanalları",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ChannelToggleButton(
                                label = "Mikrofon",
                                selected = micEnabled,
                                activeColor = AccentPrimary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    micEnabled = !micEnabled
                                    channelProcessor.leftEnabled = micEnabled
                                }
                            )
                            ChannelToggleButton(
                                label = "Arayan",
                                selected = callerEnabled,
                                activeColor = AccentSecondary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    callerEnabled = !callerEnabled
                                    channelProcessor.rightEnabled = callerEnabled
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Transkript",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = record?.transcript ?: "Transkript metni bulunamadı.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && record != null) {
        ConfirmDeleteDialog(
            title = if (isAmbient) "Ses Kaydını Sil" else "Arama Kaydını Sil",
            message = "Bu kayıt ve ses dosyası kalıcı olarak silinecek. Onaylıyor musunuz?",
            onConfirm = {
                val target = record!!
                showDeleteConfirm = false
                scope.launch {
                    try {
                        exoPlayer.stop()
                        LogSuApp.instance.repository.deleteCallRecord(target)
                        onBack()
                    } catch (e: IOException) {
                        Toast.makeText(context, "Dosya silinemedi, tekrar deneyin.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun CallPlaybackCard(exoPlayer: ExoPlayer) {
    val playerState by rememberPlayerState(exoPlayer, pollIntervalMs = 100L)
    val (isPlaying, currentPosMs, durationMs) = playerState

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${FormatUtils.formatDurationMillis(currentPosMs)} / ${FormatUtils.formatDurationMillis(durationMs)}",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Slider(
                value = if (durationMs > 0) (currentPosMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { frac ->
                    exoPlayer.seekTo((frac * durationMs).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = AccentPrimary,
                    activeTrackColor = AccentPrimary,
                    inactiveTrackColor = BorderColor
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { exoPlayer.seekTo((currentPosMs - 5000L).coerceAtLeast(0L)) },
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(Icons.Default.Replay5, contentDescription = "-5sn", tint = TextPrimary)
                }

                Spacer(modifier = Modifier.width(16.dp))

                FloatingActionButton(
                    onClick = { togglePlayWithAutoRestart(exoPlayer) },
                    containerColor = AccentPrimary,
                    contentColor = ComposeColor.White,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                IconButton(
                    onClick = { exoPlayer.seekTo((currentPosMs + 5000L).coerceAtMost(durationMs)) },
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(Icons.Default.Forward5, contentDescription = "+5sn", tint = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ChannelToggleButton(
    label: String,
    selected: Boolean,
    activeColor: ComposeColor,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) activeColor.copy(alpha = 0.22f) else DarkSurfaceVariant,
            contentColor = if (selected) activeColor else TextSecondary
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Emanet Modu Oturumu Ekranı:
 * Solda 9:20 kompakt telefon videosu, sağda ses/oynatıcı/galeri/silme kontrolleri, altta olaylar listesi.
 */
@OptIn(UnstableApi::class)
@Composable
fun GuestSessionPlayerScreen(
    sessionId: Long,
    onBack: () -> Unit,
    getExoPlayer: () -> ExoPlayer,
    channelProcessor: StereoChannelControlAudioProcessor
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<GuestSession?>(null) }
    var touchEvents by remember { mutableStateOf<List<TouchEvent>>(emptyList()) }
    var isExporting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val eventsList = remember { mutableStateListOf<GuestEvent>() }

    var micEnabled by remember { mutableStateOf(true) }
    var internalEnabled by remember { mutableStateOf(true) }
    var audioChannels by remember { mutableIntStateOf(1) }

    val exoPlayer = remember { getExoPlayer() }

    LaunchedEffect(sessionId) {
        channelProcessor.leftEnabled = true
        channelProcessor.rightEnabled = true
        channelProcessor.boostEnabled = false

        val s = withContext(Dispatchers.IO) {
            LogSuApp.instance.repository.getGuestSessionById(sessionId)
        }
        session = s

        val vFile = s?.videoFilePath?.let { File(it) }?.takeIf { it.exists() }
        val aFile = s?.audioFilePath?.let { File(it) }?.takeIf { it.exists() }

        if (aFile != null) {
            val channels = withContext(Dispatchers.IO) {
                GalleryExporter.readWavInfo(aFile)?.channels ?: 1
            }
            audioChannels = channels
        }

        if (vFile != null) {
            val dataSourceFactory = DefaultDataSource.Factory(context)
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(vFile)))

            if (aFile != null) {
                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(aFile)))
                exoPlayer.setMediaSource(MergingMediaSource(audioSource, videoSource))
            } else {
                exoPlayer.setMediaSource(videoSource)
            }
            exoPlayer.prepare()
        } else if (aFile != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(aFile)))
            exoPlayer.prepare()
        }

        s?.touchFilePath?.let { tPath ->
            val parsedTouches = withContext(Dispatchers.IO) {
                val tFile = File(tPath)
                if (!tFile.exists()) emptyList()
                else buildList {
                    tFile.forEachLine { line ->
                        TouchEvent.fromLogLine(line)?.let { add(it) }
                    }
                }
            }
            touchEvents = parsedTouches
        }

        LogSuApp.instance.repository.getEventsForSession(sessionId).collectLatest { events ->
            eventsList.clear()
            eventsList.addAll(events)
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Emanet Oturumu #$sessionId",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = session?.let { FormatUtils.formatShortDate(it.startTime) } ?: "",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                if (session != null) {
                    if (session!!.videoFilePath != null) {
                        IconButton(
                            enabled = !isExporting,
                            onClick = {
                                session?.videoFilePath?.let { vPath ->
                                    isExporting = true
                                    scope.launch {
                                        val aFile = session?.audioFilePath?.let { File(it) }
                                        GalleryExporter.exportVideoToGallery(
                                            context = context,
                                            videoFile = File(vPath),
                                            audioFile = aFile,
                                            customName = "LogSU_Emanet_Oturum_${session?.id}"
                                        )
                                        isExporting = false
                                    }
                                }
                            }
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentPrimary)
                            } else {
                                Icon(Icons.Default.Movie, contentDescription = "Galeriye Kaydet", tint = AccentPrimary)
                            }
                        }
                    }

                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Sil", tint = AccentRed.copy(alpha = 0.85f))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
        ) {
            val hasVideo = session?.videoFilePath != null
            val hasAudio = session?.audioFilePath != null

            // Sol tarafta 9:20 video, sağ tarafta kompakt kontroller (boşluk kalmaz)
            if (hasVideo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = ComposeColor.Black),
                        modifier = Modifier
                            .height(275.dp)
                            .aspectRatio(1080f / 2400f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            TouchOverlayCanvas(
                                exoPlayer = exoPlayer,
                                touchEvents = touchEvents,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        GuestSideControlsCard(
                            exoPlayer = exoPlayer,
                            hasAudio = hasAudio,
                            audioChannels = audioChannels,
                            micEnabled = micEnabled,
                            internalEnabled = internalEnabled,
                            isExporting = isExporting,
                            totalSwitches = session?.totalAppSwitches ?: 0,
                            totalCopies = session?.totalClipboardCopies ?: 0,
                            onToggleMic = {
                                micEnabled = !micEnabled
                                channelProcessor.leftEnabled = micEnabled
                            },
                            onToggleInternal = {
                                internalEnabled = !internalEnabled
                                channelProcessor.rightEnabled = internalEnabled
                            },
                            onExport = {
                                session?.videoFilePath?.let { vPath ->
                                    isExporting = true
                                    scope.launch {
                                        val aFile = session?.audioFilePath?.let { File(it) }
                                        GalleryExporter.exportVideoToGallery(
                                            context = context,
                                            videoFile = File(vPath),
                                            audioFile = aFile,
                                            customName = "LogSU_Emanet_Oturum_${session?.id}"
                                        )
                                        isExporting = false
                                    }
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else if (hasAudio) {
                CallPlaybackCard(exoPlayer = exoPlayer)
                Spacer(modifier = Modifier.height(10.dp))
            }

            Text(
                text = "Olaylar (${eventsList.size})",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (eventsList.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Bu oturumda kaydedilmiş olay yok.", color = TextSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(eventsList, key = { it.id }) { event ->
                        GuestEventItem(event)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && session != null) {
        ConfirmDeleteDialog(
            title = "Emanet Oturumunu Sil",
            message = "\"Oturum #${session!!.id}\" ve tüm ekran/ses/fotoğraf kayıtları silinecek. Onaylıyor musunuz?",
            onConfirm = {
                val target = session!!
                showDeleteConfirm = false
                scope.launch {
                    try {
                        exoPlayer.stop()
                        LogSuApp.instance.repository.deleteGuestSession(target)
                        onBack()
                    } catch (e: IOException) {
                        Toast.makeText(context, "Dosyalar silinemedi, tekrar deneyin.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun TouchOverlayCanvas(
    exoPlayer: ExoPlayer,
    touchEvents: List<TouchEvent>,
    modifier: Modifier = Modifier
) {
    val playerState by rememberPlayerState(exoPlayer, pollIntervalMs = 45L)
    val currentPlaybackMs = playerState.second

    Canvas(modifier = modifier) {
        if (touchEvents.isEmpty()) return@Canvas
        for (touch in touchEvents) {
            if (touch.action == 0 && Math.abs(touch.timestampMs - currentPlaybackMs) <= 120L) {
                val cx = (touch.x / 1079f) * size.width
                val cy = (touch.y / 2399f) * size.height

                drawCircle(
                    color = AccentSecondary.copy(alpha = 0.5f),
                    radius = 15.dp.toPx(),
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = AccentPrimary.copy(alpha = 0.95f),
                    radius = 5.5.dp.toPx(),
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

@Composable
private fun GuestSideControlsCard(
    exoPlayer: ExoPlayer,
    hasAudio: Boolean,
    audioChannels: Int,
    micEnabled: Boolean,
    internalEnabled: Boolean,
    isExporting: Boolean,
    totalSwitches: Int,
    totalCopies: Int,
    onToggleMic: () -> Unit,
    onToggleInternal: () -> Unit,
    onExport: () -> Unit
) {
    val playerState by rememberPlayerState(exoPlayer, pollIntervalMs = 100L)
    val (isPlaying, currentPlaybackMs, durationMs) = playerState

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledIconButton(
                    onClick = { togglePlayWithAutoRestart(exoPlayer) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentPrimary),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = ComposeColor.White
                    )
                }

                Text(
                    text = "${FormatUtils.formatDurationMillis(currentPlaybackMs)} / ${FormatUtils.formatDurationMillis(durationMs)}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Slider(
                value = if (durationMs > 0) (currentPlaybackMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { frac -> exoPlayer.seekTo((frac * durationMs).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = AccentPrimary,
                    activeTrackColor = AccentPrimary,
                    inactiveTrackColor = BorderColor
                ),
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )

            if (hasAudio) {
                HorizontalDivider(color = BorderColor)

                Text("Ses Kanalları", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactTogglePill(
                        label = "Mikrofon",
                        selected = micEnabled,
                        activeColor = AccentPrimary,
                        modifier = Modifier.weight(1f),
                        onClick = onToggleMic
                    )
                    if (audioChannels >= 2) {
                        CompactTogglePill(
                            label = "Dahili",
                            selected = internalEnabled,
                            activeColor = AccentSecondary,
                            modifier = Modifier.weight(1f),
                            onClick = onToggleInternal
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderColor)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$totalSwitches geçiş • $totalCopies pano", color = AccentSecondary, fontSize = 11.sp)
            }

            Button(
                onClick = onExport,
                enabled = !isExporting,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ComposeColor.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Aktarılıyor...", fontSize = 12.sp)
                } else {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Galeriye Kaydet", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CompactTogglePill(
    label: String,
    selected: Boolean,
    activeColor: ComposeColor,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) activeColor.copy(alpha = 0.22f) else DarkSurfaceVariant,
            contentColor = if (selected) activeColor else TextSecondary
        ),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        modifier = modifier.height(34.dp)
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
            contentDescription = null,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun GuestEventItem(event: GuestEvent) {
    val timeStr = remember(event.timestamp) { FormatUtils.formatEventTime(event.timestamp) }

    val (icon, color, title) = when (event.eventType) {
        GuestEventType.APP_OPENED -> Triple(Icons.Default.Apps, AccentPrimary, "Uygulama Açıldı")
        GuestEventType.CLIPBOARD_COPIED -> Triple(Icons.Default.ContentCopy, AccentOrange, "Pano Kopyalandı")
        GuestEventType.PHOTO_CAPTURED -> Triple(Icons.Default.CameraAlt, AccentGreen, "Ön Kamera Fotoğrafı")
    }

    val detailText = when (event.eventType) {
        GuestEventType.APP_OPENED -> event.appLabel ?: event.targetPackage ?: ""
        GuestEventType.CLIPBOARD_COPIED -> event.textContent ?: ""
        GuestEventType.PHOTO_CAPTURED -> ""
    }

    val bitmapState by produceState<Bitmap?>(initialValue = null, event.mediaPath) {
        val path = event.mediaPath
        value = if (!path.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                val f = File(path)
                if (f.exists()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                } else null
            }
        } else null
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Text(text = timeStr, color = TextSecondary, fontSize = 11.sp)
            }

            if (detailText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = detailText,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            bitmapState?.let { bitmap ->
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Ön Kamera Fotoğrafı",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
