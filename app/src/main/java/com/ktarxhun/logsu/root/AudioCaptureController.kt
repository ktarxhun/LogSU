package com.ktarxhun.logsu.root

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioCaptureController {
    companion object {
        private const val TAG = "AudioCaptureController"
        const val SAMPLE_RATE = 44100
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_QUEUED_CHUNKS = 32
        private const val INTERNAL_RETRY_DELAY_MS = 150L
        private const val INTERNAL_STARTUP_TOLERANCE_MS = 4000L
        private const val ROOT_HELPER_CLASS = "com.ktarxhun.logsu.root.RootAudioRecorder"
        private const val APP_PACKAGE = "com.ktarxhun.logsu"
        private const val ROOT_STARTUP_WAIT_MS = 40L
        private const val ROOT_STARTUP_ATTEMPTS = 25
        private const val ROOT_STOP_WAIT_ATTEMPTS = 60
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var internalAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var internalReadJob: Job? = null
    private var outputFile: File? = null
    private var recordedChannels: Int = 2
    private val rightSamplesCaptured = AtomicLong(0)
    @Volatile
    private var dualChannelWriter = false
    @Volatile
    private var rootRecording = false
    @Volatile
    private var rootRecorderPid: Int = -1
    @Volatile
    private var rootStopFile: File? = null

    @Volatile
    private var isRecording = false

    /**
     * Ses kaydı başlatır.
     * enableDualInternalMic = true: Stereo WAV (Sol=Mikrofon, Sağ=Karşı Taraf / Dahili Ses)
     * enableDualInternalMic = false: Tek kanallı Mono WAV (Ortam Mikrofonu)
     * useRootCapture = true: Cihaz rootluysa çağrı sesini root yardımcı süreciyle (VOICE_CALL) kaydeder.
     * rootApkPath: Root yardımcı sürecinin CLASSPATH değeri için APK yolu.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        targetWavFile: File,
        enableDualInternalMic: Boolean = true,
        useRootCapture: Boolean = false,
        rootApkPath: String? = null
    ): Boolean {
        if (isRecording) return true
        outputFile = targetWavFile
        rightSamplesCaptured.set(0)
        dualChannelWriter = false
        rootRecording = false
        rootRecorderPid = -1
        rootStopFile = null

        val monoBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT)
            .coerceAtLeast(4096) * 2

        if (!enableDualInternalMic) {
            val micOnly = createMonoRecord(
                intArrayOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                ),
                monoBufSize
            ) ?: run {
                Log.e(TAG, "Ortam mikrofonu baslatilamadi")
                return false
            }

            try {
                micOnly.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Ortam mikrofonu startRecording hatasi: ${e.message}")
                runCatching { micOnly.release() }
                return false
            }

            audioRecord = micOnly
            recordedChannels = 1
            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                writePcmToWav(micOnly, targetWavFile, monoBufSize)
            }
            Log.i(TAG, "Mono ortam ses kaydi baslatildi: ${targetWavFile.name}")
            return true
        }

        // Cagri kaydi icin root yolu: uygulama UID'si yerine app_process (UID 0) altinda calisir.
        if (useRootCapture && hasRootAccess()) {
            val apkPath = rootApkPath ?: resolveApkPath()
            if (!apkPath.isNullOrBlank() && startRootRecording(targetWavFile, apkPath)) {
                Log.i(TAG, "Cagri sesi root yetkisiyle kaydediliyor: ${targetWavFile.name}")
                return true
            }
            Log.w(TAG, "Root ses kaydedici baslatilamadi, normal AudioRecord yoluna donuluyor")
        }

        // 1. Ortam / Kullanıcı Mikrofonu (Sol Kanal)
        val micRec = createMonoRecord(
            intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT
            ),
            monoBufSize
        )

        // 2. Cihaz İçi / Karşı Taraf Sesi (Sağ Kanal)
        val intRec = createMonoRecord(
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                MediaRecorder.AudioSource.VOICE_DOWNLINK
            ),
            monoBufSize
        )

        if (micRec != null) {
            try {
                micRec.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Mikrofon startRecording hatasi: ${e.message}")
                runCatching { micRec.release() }
                runCatching { intRec?.release() }
                return false
            }
            audioRecord = micRec

            var startedIntRec: AudioRecord? = null
            if (intRec != null) {
                try {
                    intRec.startRecording()
                    startedIntRec = intRec
                } catch (e: Exception) {
                    Log.w(TAG, "Dahili ses kaydi baslatilamadi, sadece mikrofon kullanilacak: ${e.message}")
                    try { intRec.release() } catch (_: Exception) {}
                }
            }

            internalAudioRecord = startedIntRec
            val activeIntRec = startedIntRec

            if (activeIntRec != null) {
                recordedChannels = 2
                dualChannelWriter = true
                isRecording = true
                val internalQueue = ConcurrentLinkedQueue<ShortArray>()
                val queuedChunks = AtomicInteger(0)

                // Ikinci kaynak ayri is parcaciginda okunur; sessizlikte veya bloke oldugunda mikrofonu dondurmaz.
                internalReadJob = CoroutineScope(Dispatchers.IO).launch {
                    val tempBuf = ShortArray(monoBufSize / 2)
                    val readStartedAt = SystemClock.elapsedRealtime()
                    var emptyReadCount = 0
                    var warnedNotProducing = false
                    while (isRecording) {
                        val read = try {
                            activeIntRec.read(tempBuf, 0, tempBuf.size)
                        } catch (_: Exception) {
                            -1
                        }
                        if (read > 0) {
                            emptyReadCount = 0
                            warnedNotProducing = false
                            rightSamplesCaptured.addAndGet(read.toLong())
                            val copy = tempBuf.copyOf(read)
                            internalQueue.offer(copy)
                            if (queuedChunks.incrementAndGet() > MAX_QUEUED_CHUNKS) {
                                if (internalQueue.poll() != null) {
                                    queuedChunks.decrementAndGet()
                                }
                            }
                        } else {
                            // Cagri ses yolu henuz kurulmamis olabilir. Kaynagi kapatmak yerine kisa bekle ve tekrar dene.
                            emptyReadCount++
                            val elapsedMs = SystemClock.elapsedRealtime() - readStartedAt
                            if (!warnedNotProducing && elapsedMs > INTERNAL_STARTUP_TOLERANCE_MS) {
                                warnedNotProducing = true
                                Log.w(
                                    TAG,
                                    "Dahili ses kaynagi henuz veri uretmiyor (read=$read, bosOkuma=$emptyReadCount), denenmeye devam ediliyor"
                                )
                            }
                            delay(INTERNAL_RETRY_DELAY_MS)
                        }
                    }
                    try { activeIntRec.stop() } catch (_: Exception) {}
                    try { activeIntRec.release() } catch (_: Exception) {}
                    if (internalAudioRecord === activeIntRec) {
                        internalAudioRecord = null
                    }
                }

                recordingJob = CoroutineScope(Dispatchers.IO).launch {
                    writeDualPcmToWav(micRec, targetWavFile, monoBufSize, internalQueue, queuedChunks)
                }
                Log.i(TAG, "Cift kanalli kayit baslatildi (Sol=Mic, Sag=Aktif)")
                return true
            } else {
                recordedChannels = 1
                isRecording = true
                recordingJob = CoroutineScope(Dispatchers.IO).launch {
                    writePcmToWav(micRec, targetWavFile, monoBufSize)
                }
                Log.i(TAG, "Mono mikrofon kaydi baslatildi (Dahili ses baslatilamadi): ${targetWavFile.name}")
                return true
            }
        }

        // Son çare fallback: doğrudan stereo AudioRecord
        val stereoBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AUDIO_FORMAT)
            .coerceAtLeast(8192) * 2
        val stereoSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )

        var fallbackRecord: AudioRecord? = null
        for (source in stereoSources) {
            try {
                val candidate = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AUDIO_FORMAT, stereoBufSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    fallbackRecord = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stereo kaynak $source acilamadi: ${e.message}")
            }
        }

        if (fallbackRecord == null) {
            Log.e(TAG, "Hicbir ses kaynagi baslatilamadi!")
            return false
        }

        try {
            fallbackRecord.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Yedek stereo startRecording hatasi: ${e.message}")
            runCatching { fallbackRecord.release() }
            return false
        }

        audioRecord = fallbackRecord
        recordedChannels = 2
        isRecording = true

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            writePcmToWav(fallbackRecord, targetWavFile, stereoBufSize)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createMonoRecord(sources: IntArray, bufferSize: Int): AudioRecord? {
        for (source in sources) {
            try {
                val candidate = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate
                }
                candidate.release()
            } catch (e: Exception) {
                Log.w(TAG, "Mono kaynak $source acilamadi: ${e.message}")
            }
        }
        return null
    }

    suspend fun stopRecordingSuspend() = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext
        isRecording = false

        if (rootRecording) {
            stopRootRecording()
            outputFile?.let { wavFile ->
                if (wavFile.exists() && wavFile.length() > 44L) {
                    recordedChannels = 1
                    updateWavHeader(wavFile, 1)
                }
            }
            Log.i(TAG, "Root cagri ses kaydi tamamlandi: ${outputFile?.absolutePath}")
            return@withContext
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            internalAudioRecord?.stop()
        } catch (_: Exception) {}

        withTimeoutOrNull(1500) {
            recordingJob?.join()
        }
        internalReadJob?.cancel()
        withTimeoutOrNull(500) {
            internalReadJob?.join()
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Mic release hatasi", e)
        } finally {
            audioRecord = null
        }

        try {
            internalAudioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Dahili ses release hatasi", e)
        } finally {
            internalAudioRecord = null
        }

        if (dualChannelWriter && recordedChannels == 2 && rightSamplesCaptured.get() == 0L) {
            val converted = outputFile?.let { downmixStereoToMono(it) } ?: false
            if (converted) {
                recordedChannels = 1
                Log.w(TAG, "Sag kanal hic veri uretmedi, WAV mono olarak sonlandiriliyor")
            }
        }

        outputFile?.let { updateWavHeader(it, recordedChannels) }
        Log.i(TAG, "Ses kaydi tamamlandi: ${outputFile?.absolutePath}")
    }

    fun stopRecording() {
        runBlocking(Dispatchers.IO) {
            stopRecordingSuspend()
        }
    }

    private fun hasRootAccess(): Boolean {
        return try {
            RootShellManager.isRootCached() || RootShellManager.isRootAvailable()
        } catch (e: Exception) {
            Log.w(TAG, "Root kontrolu yapilamadi: ${e.message}")
            false
        }
    }

    private fun resolveApkPath(): String? {
        return try {
            RootShellManager.executeSingle("pm path ${RootShellManager.shellQuote(APP_PACKAGE)}")
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "APK yolu okunamadi: ${e.message}")
            null
        }
    }

    /**
     * RootDisplayRecorder ile ayni app_process desenini kullanarak cagri sesini UID 0 altinda kaydeder.
     * Basarili olursa isRecording true yapilir ve normal AudioRecord yolu atlanir.
     */
    private fun startRootRecording(targetWavFile: File, apkPath: String): Boolean {
        val parentDir = targetWavFile.parentFile ?: return false
        if (!parentDir.exists()) parentDir.mkdirs()

        val stopFile = File(parentDir, "${targetWavFile.name}.stop")
        val errFile = File(parentDir, "root_audio_${System.currentTimeMillis()}.log")

        runCatching { if (stopFile.exists()) stopFile.delete() }
        runCatching { if (targetWavFile.exists()) targetWavFile.delete() }

        val cmd = "CLASSPATH=${RootShellManager.shellQuote(apkPath)} " +
            "app_process /system/bin $ROOT_HELPER_CLASS " +
            "${RootShellManager.shellQuote(targetWavFile.absolutePath)} " +
            "${RootShellManager.shellQuote(stopFile.absolutePath)} " +
            "${Process.myPid()} " +
            ">/dev/null 2>${RootShellManager.shellQuote(errFile.absolutePath)} & echo \$!"

        val pidStr = RootShellManager.executeSingle(cmd).trim()
        val pid = pidStr.lineSequence().map { it.trim() }.lastOrNull()?.toIntOrNull() ?: -1
        if (pid <= 0) {
            Log.e(TAG, "Root ses kaydedici PID alinamadi: '$pidStr'")
            cleanupRootArtifacts(stopFile, errFile, targetWavFile)
            return false
        }

        var started = false
        for (i in 0 until ROOT_STARTUP_ATTEMPTS) {
            Thread.sleep(ROOT_STARTUP_WAIT_MS)
            if (!File("/proc/$pid").exists()) break
            // 44 baytlik WAV basligi yalnizca AudioRecord basariyla acildiktan sonra yazilir.
            if (targetWavFile.exists() && targetWavFile.length() >= 44L) {
                started = true
                break
            }
        }

        if (!started) {
            Log.e(TAG, "Root ses kaydedici baslayamadi (PID=$pid): ${readRootError(errFile)}")
            runCatching { RootShellManager.execute("touch ${RootShellManager.shellQuote(stopFile.absolutePath)}") }
            Thread.sleep(120)
            runCatching { RootShellManager.execute("kill -9 $pid 2>/dev/null") }
            cleanupRootArtifacts(stopFile, errFile, targetWavFile)
            return false
        }

        rootRecorderPid = pid
        rootStopFile = stopFile
        rootRecording = true
        dualChannelWriter = false
        recordedChannels = 1
        isRecording = true
        Log.i(TAG, "Root ses kaydedici baslatildi: PID=$pid, dosya=${targetWavFile.name}")
        return true
    }

    private fun stopRootRecording() {
        val stopFile = rootStopFile
        val pid = rootRecorderPid
        rootStopFile = null
        rootRecorderPid = -1
        rootRecording = false

        if (stopFile != null) {
            runCatching {
                RootShellManager.execute("touch ${RootShellManager.shellQuote(stopFile.absolutePath)}")
            }
        }

        if (pid > 0) {
            var stillAlive = true
            for (i in 0 until ROOT_STOP_WAIT_ATTEMPTS) {
                if (!File("/proc/$pid").exists()) {
                    stillAlive = false
                    break
                }
                Thread.sleep(50)
            }
            if (stillAlive) {
                Log.w(TAG, "Root ses kaydedici zamaninda kapanmadi, sonlandiriliyor: PID=$pid")
                runCatching { RootShellManager.execute("kill -9 $pid 2>/dev/null") }
                Thread.sleep(100)
            }
        }

        if (stopFile != null) {
            runCatching {
                RootShellManager.execute("rm -f ${RootShellManager.shellQuote(stopFile.absolutePath)}")
            }
        }

        outputFile?.let { wavFile ->
            runCatching {
                RootShellManager.execute("chmod 666 ${RootShellManager.shellQuote(wavFile.absolutePath)} 2>/dev/null")
            }
        }
    }

    private fun readRootError(errFile: File): String {
        return try {
            RootShellManager.executeSingle("cat ${RootShellManager.shellQuote(errFile.absolutePath)} 2>/dev/null")
                .trim()
                .take(300)
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanupRootArtifacts(stopFile: File?, errFile: File?, output: File?) {
        val paths = listOfNotNull(stopFile?.absolutePath, errFile?.absolutePath, output?.absolutePath)
        if (paths.isEmpty()) return
        val cmd = "rm -f " + paths.joinToString(" ") { RootShellManager.shellQuote(it) }
        runCatching { RootShellManager.execute(cmd) }
    }

    /**
     * Sag kanal hic ornek uretmediyse stereo yazilmis WAV verisini sol kanala indirger.
     * Boylece basliga sahte iki kanal yazilmaz ve oynat hizi bozulmaz.
     */
    private fun downmixStereoToMono(wavFile: File): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44L) return false
        val tempFile = File(wavFile.parentFile, "${wavFile.name}.mono.tmp")
        return try {
            FileInputStream(wavFile).use { input ->
                val header = ByteArray(44)
                var headerRead = 0
                while (headerRead < 44) {
                    val n = input.read(header, headerRead, 44 - headerRead)
                    if (n <= 0) break
                    headerRead += n
                }

                FileOutputStream(tempFile).use { output ->
                    output.write(ByteArray(44))
                    val inBuf = ByteArray(64 * 1024)
                    var leftover = 0
                    while (true) {
                        val read = input.read(inBuf, leftover, inBuf.size - leftover)
                        if (read <= 0) break
                        val total = leftover + read
                        val frames = total / 4
                        if (frames > 0) {
                            val mono = ByteArray(frames * 2)
                            for (frame in 0 until frames) {
                                mono[frame * 2] = inBuf[frame * 4]
                                mono[frame * 2 + 1] = inBuf[frame * 4 + 1]
                            }
                            output.write(mono, 0, mono.size)
                        }
                        leftover = total - frames * 4
                        if (leftover > 0) {
                            System.arraycopy(inBuf, frames * 4, inBuf, 0, leftover)
                        }
                    }
                }
            }
            if (!tempFile.renameTo(wavFile)) {
                tempFile.copyTo(wavFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stereo WAV mono donusum hatasi", e)
            tempFile.delete()
            false
        }
    }

    private fun writeDualPcmToWav(
        micRecord: AudioRecord,
        file: File,
        monoBufSize: Int,
        internalQueue: ConcurrentLinkedQueue<ShortArray>,
        queuedChunks: AtomicInteger
    ) {
        val fos = FileOutputStream(file)
        fos.write(ByteArray(44))

        val micBuf = ShortArray(monoBufSize / 2)
        val stereoBytes = ByteArray(micBuf.size * 4)
        var currentIntChunk: ShortArray? = null
        var intOffset = 0

        try {
            while (isRecording) {
                val readMic = micRecord.read(micBuf, 0, micBuf.size)
                if (readMic > 0) {
                    for (i in 0 until readMic) {
                        val sampleLeft = micBuf[i]
                        var sampleRight: Short = 0

                        if (currentIntChunk == null || intOffset >= currentIntChunk.size) {
                            currentIntChunk = internalQueue.poll()
                            if (currentIntChunk != null) {
                                queuedChunks.decrementAndGet()
                            }
                            intOffset = 0
                        }
                        if (currentIntChunk != null && intOffset < currentIntChunk.size) {
                            sampleRight = currentIntChunk[intOffset++]
                        }

                        val base = i * 4
                        stereoBytes[base] = (sampleLeft.toInt() and 0xFF).toByte()
                        stereoBytes[base + 1] = ((sampleLeft.toInt() shr 8) and 0xFF).toByte()
                        stereoBytes[base + 2] = (sampleRight.toInt() and 0xFF).toByte()
                        stereoBytes[base + 3] = ((sampleRight.toInt() shr 8) and 0xFF).toByte()
                    }
                    fos.write(stereoBytes, 0, readMic * 4)
                } else if (readMic < 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cift kanal PCM yazma hatasi", e)
        } finally {
            try { fos.flush() } catch (_: Exception) {}
            try { fos.close() } catch (_: Exception) {}
        }
    }

    private fun writePcmToWav(record: AudioRecord, file: File, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val fos = FileOutputStream(file)
        fos.write(ByteArray(44))

        try {
            while (isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos.write(buffer, 0, read)
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PCM yazma hatasi", e)
        } finally {
            try { fos.flush() } catch (_: Exception) {}
            try { fos.close() } catch (_: Exception) {}
        }
    }

    private fun updateWavHeader(wavFile: File, channels: Int) {
        if (!wavFile.exists() || wavFile.length() < 44) return

        val totalAudioLen = wavFile.length() - 44
        val totalDataLen = totalAudioLen + 36
        val byteRate = SAMPLE_RATE * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV basligi guncelleme hatasi", e)
        }
    }
}
