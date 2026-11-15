package com.ktarxhun.logsu.root

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections

class ScreenRecordController {
    companion object {
        private const val TAG = "ScreenRecordController"
    }

    private val stateMutex = Mutex()
    @Volatile
    private var shouldRecord = false
    @Volatile
    private var isScreenInteractive: Boolean = true
    @Volatile
    private var activePid: Int = -1
    @Volatile
    private var activeStopFile: File? = null
    private var supervisorJob: Job? = null
    private var targetOutputFile: File? = null
    private val segmentFiles = Collections.synchronizedList(mutableListOf<File>())

    fun setScreenInteractive(interactive: Boolean) {
        isScreenInteractive = interactive
    }

    /**
     * Root yetkisiyle arka planda sürekli ekran kaydı başlatır.
     * Android 13+ üzerinde VirtualDisplay tabanlı RootDisplayRecorder ile tüm korumalı katmanlar dahil
     * 180sn sınırına takılmadan kayıt alır; gerekirse native screenrecord yedeğine geçer.
     */
    suspend fun startRecording(
        context: Context,
        outputFile: File,
        bitRate: Int = 10000000,
        size: String = "1080x2400"
    ): Boolean = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            if (shouldRecord) return@withContext true

            targetOutputFile = outputFile
            segmentFiles.clear()
            activeStopFile = null
            shouldRecord = true

            // Yalnızca takılı kalmış eski screenrecord süreci varsa temizle
            RootShellManager.execute("if pidof screenrecord >/dev/null 2>&1; then killall -2 screenrecord 2>/dev/null; sleep 0.2; fi")

            val powerManager = context.getSystemService(PowerManager::class.java)
            val apkPath = context.packageCodePath
            val sizeParts = size.split("x")
            val width = sizeParts.getOrNull(0)?.toIntOrNull() ?: 1080
            val height = sizeParts.getOrNull(1)?.toIntOrNull() ?: 2400

            supervisorJob = CoroutineScope(Dispatchers.IO).launch {
                var partIdx = 0
                var useVirtualDisplay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !apkPath.isNullOrBlank()

                while (shouldRecord && isActive) {
                    // Ekran kapaliyken veya etkilesimsizken bekle
                    if (!isScreenInteractive || (powerManager?.isInteractive == false)) {
                        delay(250)
                        continue
                    }

                    val segFile = File(
                        outputFile.parentFile,
                        "${outputFile.nameWithoutExtension}_seg${partIdx++}.mp4"
                    )
                    val errFile = File(context.cacheDir, "screenrecord_err.log")
                    val stopFile = File(context.cacheDir, "rec_stop_${System.nanoTime()}.flag")
                    segmentFiles.add(segFile)

                    val usingDisplayRecorder = useVirtualDisplay
                    val cmd = if (usingDisplayRecorder) {
                        activeStopFile = stopFile
                        "rm -f '${stopFile.absolutePath}'; CLASSPATH='$apkPath' app_process /system/bin com.ktarxhun.logsu.root.RootDisplayRecorder '${segFile.absolutePath}' $width $height $bitRate '${stopFile.absolutePath}' >/dev/null 2>'${errFile.absolutePath}' & echo \$!"
                    } else {
                        activeStopFile = null
                        val timeLimitArg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "0" else "180"
                        "screenrecord --bit-rate $bitRate --size $size --time-limit $timeLimitArg '${segFile.absolutePath}' >/dev/null 2>'${errFile.absolutePath}' & echo \$!"
                    }

                    val pidStr = RootShellManager.executeSingle(cmd).trim()
                    val pid = pidStr.lines().lastOrNull()?.trim()?.toIntOrNull() ?: -1

                    if (pid <= 0) {
                        Log.e(TAG, "Ekran kaydi PID alinamadi: '$pidStr'")
                        if (usingDisplayRecorder) {
                            useVirtualDisplay = false
                            activeStopFile = null
                        }
                        delay(300)
                        continue
                    }

                    activePid = pid
                    Log.i(TAG, "Ekran kaydi baslatildi (${if (usingDisplayRecorder) "VirtualDisplay" else "screenrecord"}): PID=$pid, dosya=${segFile.name}")

                    // Surec calistigi surece izle (250ms periyot, ekran kapanirsa hemen duraklat)
                    var tickCount = 0
                    while (shouldRecord && isActive) {
                        delay(250)
                        if (!isScreenInteractive || (powerManager?.isInteractive == false)) {
                            Log.i(TAG, "Ekran kapandi, segment kaydi duraklatiliyor: PID=$pid")
                            stopActiveRecorderProcess(pid, activeStopFile)
                            activePid = -1
                            activeStopFile = null
                            RootShellManager.execute("chmod 666 '${segFile.absolutePath}' 2>/dev/null")
                            break
                        }
                        tickCount++
                        if (tickCount % 4 == 0) {
                            val alive = RootShellManager.executeSingle("if [ -d /proc/$pid ]; then echo 1; else echo 0; fi").trim()
                            if (alive != "1") {
                                break
                            }
                        }
                    }

                    if (activePid > 0) {
                        activePid = -1
                        RootShellManager.execute("chmod 666 '${segFile.absolutePath}' 2>/dev/null")
                    }

                    val isScreenOn = isScreenInteractive && (powerManager?.isInteractive != false)
                    if (shouldRecord && isScreenOn && (!segFile.exists() || segFile.length() <= 1024L)) {
                        val errMsg = RootShellManager.executeSingle("cat '${errFile.absolutePath}' 2>/dev/null").trim()
                        if (errMsg.isNotEmpty()) {
                            Log.w(TAG, "Ekran kaydi erken cikti: $errMsg")
                        }
                        if (usingDisplayRecorder) {
                            Log.w(TAG, "VirtualDisplay kaydedici basarisiz, native screenrecord yedegine geciliyor")
                            useVirtualDisplay = false
                            activeStopFile = null
                        }
                        delay(250)
                    }
                }
            }
            true
        }
    }

    private suspend fun stopActiveRecorderProcess(pid: Int, stopFile: File?) {
        if (stopFile != null) {
            RootShellManager.execute("touch '${stopFile.absolutePath}'")
            if (pid > 0) {
                repeat(50) {
                    val alive = RootShellManager.executeSingle("if [ -d /proc/$pid ]; then echo 1; else echo 0; fi").trim()
                    if (alive != "1") return@repeat
                    delay(50)
                }
            }
            RootShellManager.execute("rm -f '${stopFile.absolutePath}'")
        }

        val stillAlive = if (pid > 0) {
            RootShellManager.executeSingle("if [ -d /proc/$pid ]; then echo 1; else echo 0; fi").trim() == "1"
        } else {
            stopFile == null
        }

        if (stillAlive) {
            val killCmd = if (pid > 0) {
                "kill -2 $pid 2>/dev/null; killall -2 screenrecord 2>/dev/null"
            } else {
                "killall -2 screenrecord 2>/dev/null"
            }
            RootShellManager.execute(killCmd)

            if (pid > 0) {
                repeat(40) {
                    val alive = RootShellManager.executeSingle("if [ -d /proc/$pid ]; then echo 1; else echo 0; fi").trim()
                    if (alive != "1") return@repeat
                    delay(50)
                }
            } else {
                delay(400)
            }
        }
    }

    /**
     * Ekran kaydını zarifçe durdurur, MP4 moov atomunun kapanmasını bekler
     * ve varsa çoklu segmentleri tek dosyada birleştirir.
     */
    suspend fun stopRecording(): Unit = withContext(Dispatchers.IO) {
        shouldRecord = false
        stateMutex.withLock {
            val stopFile = activeStopFile
            val pid = activePid
            activeStopFile = null
            activePid = -1
            try {
                if (pid > 0 || stopFile != null) {
                    stopActiveRecorderProcess(pid, stopFile)
                }

                supervisorJob?.cancel()
                withTimeoutOrNull(3000) {
                    supervisorJob?.join()
                }

                val finalOutput = targetOutputFile
                val validSegments = segmentFiles.toList().filter { seg ->
                    RootShellManager.execute("chmod 666 '${seg.absolutePath}' 2>/dev/null")
                    seg.exists() && seg.length() > 1024L
                }

            if (finalOutput != null && validSegments.isNotEmpty()) {
                if (validSegments.size == 1) {
                    val single = validSegments.first()
                    if (single.absolutePath != finalOutput.absolutePath) {
                        RootShellManager.execute("mv -f '${single.absolutePath}' '${finalOutput.absolutePath}' && chmod 666 '${finalOutput.absolutePath}'")
                        if (!finalOutput.exists() && single.exists()) {
                            single.copyTo(finalOutput, overwrite = true)
                            single.delete()
                        }
                    }
                } else {
                    val merged = mergeMp4Segments(validSegments, finalOutput)
                    if (!merged) {
                        val largest = validSegments.maxByOrNull { it.length() }
                        largest?.let {
                            RootShellManager.execute("cp -f '${it.absolutePath}' '${finalOutput.absolutePath}' && chmod 666 '${finalOutput.absolutePath}'")
                        }
                    }
                    validSegments.forEach { it.delete() }
                }
            }

            // Kalan boş/geçici segmentleri temizle
            segmentFiles.forEach { seg ->
                if (seg.exists() && seg.absolutePath != finalOutput?.absolutePath) {
                    seg.delete()
                }
            }
                if (finalOutput != null && finalOutput.exists()) {
                    RootShellManager.execute("chmod 666 '${finalOutput.absolutePath}' 2>/dev/null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screenrecord durdurma hatasi", e)
            } finally {
                activePid = -1
                supervisorJob = null
                segmentFiles.clear()
            }
            Unit
        }
    }

    private fun mergeMp4Segments(segments: List<File>, outputFile: File): Boolean {
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var muxerStarted = false
            var timeOffsetUs = 0L
            val bufferInfo = MediaCodec.BufferInfo()
            val sampleBuf = ByteBuffer.allocate(2 * 1024 * 1024)

            for (segment in segments) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.absolutePath)
                    var segVideoTrack = -1
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) {
                            segVideoTrack = i
                            if (!muxerStarted) {
                                videoTrackIndex = muxer.addTrack(fmt)
                                muxer.start()
                                muxerStarted = true
                            }
                            break
                        }
                    }
                    if (segVideoTrack < 0 || !muxerStarted) continue

                    extractor.selectTrack(segVideoTrack)
                    var lastSegPtsUs = 0L
                    while (true) {
                        val size = extractor.readSampleData(sampleBuf, 0)
                        if (size < 0) break

                        val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                        lastSegPtsUs = maxOf(lastSegPtsUs, ptsUs)

                        val extractorFlags = extractor.sampleFlags
                        var codecFlags = 0
                        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        }

                        bufferInfo.set(0, size, timeOffsetUs + ptsUs, codecFlags)
                        muxer.writeSampleData(videoTrackIndex, sampleBuf, bufferInfo)
                        extractor.advance()
                    }
                    timeOffsetUs += lastSegPtsUs + 33333L
                } catch (e: Exception) {
                    Log.w(TAG, "Segment atlandi: ${segment.name}", e)
                } finally {
                    extractor.release()
                }
            }
            return muxerStarted
        } catch (e: Exception) {
            Log.e(TAG, "MP4 segment birlestirme hatasi", e)
            return false
        } finally {
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }
}
