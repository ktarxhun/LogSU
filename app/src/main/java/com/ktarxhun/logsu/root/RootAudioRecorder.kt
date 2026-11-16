package com.ktarxhun.logsu.root

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/**
 * Root (app_process) altinda calisan, Context gerektirmeyen cagri sesi kaydedici.
 * Uygulama UID'si yerine UID 0 ile AudioRecord actigi icin Android 14/15 arka plan
 * mikrofon kisitlarindan (App op 27, setSilenced) ve CAPTURE_AUDIO_OUTPUT izin
 * sinirindan bagimsiz olarak VOICE_CALL / VOICE_DOWNLINK / VOICE_UPLINK kaynaklarini
 * kullanabilir. PCM verisi 44 baytlik WAV basliginin arkasina mono olarak yazilir.
 *
 * Kullanim: RootAudioRecorder <outputWav> <stopFile> <parentPid>
 * Cikis kodlari: 0 basarili, 1 kayit hatasi, 2 arguman hatasi, 3 ses kaynagi yok.
 */
object RootAudioRecorder {
    private const val SAMPLE_RATE = 44100
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val READ_BUFFER_SAMPLES = 2048
    private const val EMPTY_READ_SLEEP_MS = 50L

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("Kullanim: RootAudioRecorder <outputWav> <stopFile> <parentPid>")
            exitProcess(2)
        }

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Throwable) {
        }

        val outputFile = File(args[0])
        val stopFile = File(args[1])
        val parentPid = args[2].toIntOrNull() ?: -1

        var record: AudioRecord? = null
        var fos: FileOutputStream? = null
        var exitCode = 0

        try {
            outputFile.parentFile?.mkdirs()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AUDIO_FORMAT
            )
            val bufferSizeBytes = if (minBuffer > 0) minBuffer.coerceAtLeast(4096) * 2 else 16384

            val source = createRecord(bufferSizeBytes)
            if (source == null) {
                System.err.println("Hicbir ses kaynagi acilamadi (VOICE_CALL/UPLINK/DOWNLINK/MIC)")
                exitProcess(3)
            }
            record = source

            source.startRecording()
            if (source.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord kayit durumuna gecemedi")
            }

            val out = FileOutputStream(outputFile)
            fos = out
            out.write(ByteArray(44))

            val sampleBuffer = ShortArray(READ_BUFFER_SAMPLES)
            val byteBuffer = ByteArray(READ_BUFFER_SAMPLES * 2)
            var loggedWaiting = false

            while (true) {
                if (stopFile.exists()) break
                if (parentPid > 0 && !File("/proc/$parentPid").exists()) {
                    System.err.println("Ust surec kapandi, kayit sonlandiriliyor")
                    break
                }

                val read = try {
                    source.read(sampleBuffer, 0, sampleBuffer.size)
                } catch (t: Throwable) {
                    -1
                }

                if (read > 0) {
                    loggedWaiting = false
                    for (i in 0 until read) {
                        val sample = sampleBuffer[i].toInt()
                        byteBuffer[i * 2] = (sample and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    out.write(byteBuffer, 0, read * 2)
                } else {
                    if (!loggedWaiting) {
                        loggedWaiting = true
                        System.err.println("Ses kaynagi veri uretmiyor (read=$read), bekleniyor")
                    }
                    Thread.sleep(EMPTY_READ_SLEEP_MS)
                }
            }

            try {
                out.flush()
            } catch (_: Throwable) {
            }
            try {
                out.fd.sync()
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            exitCode = 1
        } finally {
            try {
                fos?.close()
            } catch (_: Throwable) {
            }
            try {
                record?.stop()
            } catch (_: Throwable) {
            }
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            try {
                updateWavHeader(outputFile)
            } catch (t: Throwable) {
                t.printStackTrace(System.err)
                if (exitCode == 0) exitCode = 1
            }
            try {
                Os.chmod(outputFile.absolutePath, 438)
            } catch (_: Throwable) {
            }
            try {
                if (stopFile.exists()) stopFile.delete()
            } catch (_: Throwable) {
            }
        }

        exitProcess(exitCode)
    }

    private fun createRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_UPLINK,
            MediaRecorder.AudioSource.MIC
        )

        for (source in sources) {
            try {
                val candidate = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    System.err.println("Ses kaynagi secildi: $source")
                    return candidate
                }
                candidate.release()
            } catch (t: Throwable) {
                System.err.println("Kaynak $source acilamadi: ${t.message}")
            }
        }
        return null
    }

    private fun updateWavHeader(wavFile: File) {
        if (!wavFile.exists() || wavFile.length() < 44) return

        val channels = 1
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

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}
