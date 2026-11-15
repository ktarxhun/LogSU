package com.ktarxhun.logsu.root

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class AudioCaptureController {
    companion object {
        private const val TAG = "AudioCaptureController"
        const val SAMPLE_RATE = 44100
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_QUEUED_CHUNKS = 32
    }

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var internalAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var internalReadJob: Job? = null
    private var outputFile: File? = null
    private var recordedChannels: Int = 2

    @Volatile
    private var isRecording = false

    /**
     * Ses kaydı başlatır.
     * enableDualInternalMic = true: Stereo WAV (Sol=Mikrofon, Sağ=Karşı Taraf / Dahili Ses)
     * enableDualInternalMic = false: Tek kanallı Mono WAV (Ortam Mikrofonu)
     */
    @SuppressLint("MissingPermission")
    fun startRecording(targetWavFile: File, enableDualInternalMic: Boolean = true): Boolean {
        if (isRecording) return true
        outputFile = targetWavFile

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

            audioRecord = micOnly
            recordedChannels = 1
            isRecording = true
            micOnly.startRecording()

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                writePcmToWav(micOnly, targetWavFile, monoBufSize)
            }
            Log.i(TAG, "Mono ortam ses kaydi baslatildi: ${targetWavFile.name}")
            return true
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
            audioRecord = micRec
            micRec.startRecording()

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
                isRecording = true
                val internalQueue = ConcurrentLinkedQueue<ShortArray>()
                val queuedChunks = AtomicInteger(0)

                // Ikinci kaynak ayri is parcaciginda okunur; sessizlikte veya bloke oldugunda mikrofonu dondurmaz.
                internalReadJob = CoroutineScope(Dispatchers.IO).launch {
                    val tempBuf = ShortArray(monoBufSize / 2)
                    var zeroReadCount = 0
                    while (isRecording) {
                        val read = try {
                            activeIntRec.read(tempBuf, 0, tempBuf.size)
                        } catch (_: Exception) {
                            -1
                        }
                        if (read > 0) {
                            zeroReadCount = 0
                            val copy = tempBuf.copyOf(read)
                            internalQueue.offer(copy)
                            if (queuedChunks.incrementAndGet() > MAX_QUEUED_CHUNKS) {
                                if (internalQueue.poll() != null) {
                                    queuedChunks.decrementAndGet()
                                }
                            }
                        } else if (read < 0) {
                            break
                        } else {
                            // HAL (orn. arama yokken VOICE_CALL) surekli 0 donduruyorsa kaynak serbest birakilir.
                            zeroReadCount++
                            if (zeroReadCount >= 5) {
                                Log.w(TAG, "Dahili ses kaynagi veri uretmiyor (read=0), serbest birakiliyor.")
                                break
                            }
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

        audioRecord = fallbackRecord
        recordedChannels = 2
        isRecording = true
        fallbackRecord.startRecording()

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

        outputFile?.let { updateWavHeader(it, recordedChannels) }
        Log.i(TAG, "Ses kaydi tamamlandi: ${outputFile?.absolutePath}")
    }

    fun stopRecording() {
        runBlocking(Dispatchers.IO) {
            stopRecordingSuspend()
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
