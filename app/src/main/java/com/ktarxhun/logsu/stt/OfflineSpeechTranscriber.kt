package com.ktarxhun.logsu.stt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale

object OfflineSpeechTranscriber {
    private const val TAG = "OfflineTranscriber"

    /**
     * Mono veya stereo WAV dosyasını ayrıştırarak sol (Sen) ve sağ (Karşı Taraf) kanallarının
     * ses enerjisini ve konuşma parçalarını yerel olarak çözümler.
     */
    suspend fun transcribeCall(wavFile: File, onComplete: (String) -> Unit) {
        onComplete(transcribeCallSuspend(wavFile))
    }

    suspend fun transcribeCallSuspend(wavFile: File): String = withContext(Dispatchers.IO) {
        if (!wavFile.exists() || wavFile.length() <= 44) {
            return@withContext ""
        }

        try {
            Log.i(TAG, "Lokal transkripsiyon baslatildi: ${wavFile.name}")

            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(44)
                val headerRead = fis.read(header)
                if (headerRead < 44) {
                    return@withContext ""
                }

                val channels = ((header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)).coerceIn(1, 2)
                val sampleRate = ((header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)).toLong().coerceAtLeast(8000L)
                val frameBytes = channels * 2

                val buffer = ByteArray(4096)
                var carryOver = 0
                var sampleIndex = 0L

                val transcriptBuilder = StringBuilder()
                transcriptBuilder.append("--- Çevrimdışı Kayıt Özeti ---\n")

                var leftSpeaking = false
                var rightSpeaking = false

                while (true) {
                    val readNow = fis.read(buffer, carryOver, buffer.size - carryOver)
                    if (readNow <= 0) break

                    val totalBytes = carryOver + readNow
                    val usableBytes = (totalBytes / frameBytes) * frameBytes
                    val remainder = totalBytes - usableBytes

                    var leftEnergy = 0L
                    var rightEnergy = 0L
                    var frames = 0
                    var i = 0

                    while (i < usableBytes) {
                        val leftSample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                        leftEnergy += Math.abs(leftSample.toShort().toLong())
                        if (channels == 2) {
                            val rightSample = (buffer[i + 2].toInt() and 0xFF) or (buffer[i + 3].toInt() shl 8)
                            rightEnergy += Math.abs(rightSample.toShort().toLong())
                        }
                        i += frameBytes
                        sampleIndex++
                        frames++
                    }

                    if (remainder > 0) {
                        System.arraycopy(buffer, usableBytes, buffer, 0, remainder)
                    }
                    carryOver = remainder

                    if (frames > 0) {
                        val currentSec = sampleIndex / sampleRate
                        val threshold = 1500L * frames

                        val nowLeft = leftEnergy > threshold
                        val nowRight = channels == 2 && rightEnergy > threshold

                        if (nowLeft && !leftSpeaking) {
                            leftSpeaking = true
                            val timeTag = String.format(Locale.US, "[%02d:%02d]", currentSec / 60, currentSec % 60)
                            val label = if (channels == 1) "[Mikrofon]" else "[Sen]"
                            transcriptBuilder.append("$timeTag $label: (Ses algılandı)\n")
                        } else if (!nowLeft && leftSpeaking) {
                            leftSpeaking = false
                        }

                        if (nowRight && !rightSpeaking) {
                            rightSpeaking = true
                            val timeTag = String.format(Locale.US, "[%02d:%02d]", currentSec / 60, currentSec % 60)
                            transcriptBuilder.append("$timeTag [Karşı Taraf]: (Ses algılandı)\n")
                        } else if (!nowRight && rightSpeaking) {
                            rightSpeaking = false
                        }
                    }
                }

                if (transcriptBuilder.length <= 40) {
                    transcriptBuilder.append("[00:00] Ses kaydı alındı (Sessiz veya düşük seviye).")
                }

                val finalTranscript = transcriptBuilder.toString()
                Log.i(TAG, "Lokal transkripsiyon tamamlandi.")
                finalTranscript
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transkripsiyon hatasi", e)
            "[Hata]: Ses transkripsiyonu tamamlanamadı."
        }
    }
}
