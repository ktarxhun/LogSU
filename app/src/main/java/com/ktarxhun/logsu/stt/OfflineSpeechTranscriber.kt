package com.ktarxhun.logsu.stt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object OfflineSpeechTranscriber {
    private const val TAG = "OfflineTranscriber"

    /**
     * Mono veya stereo WAV dosyasını ayrıştırarak sol (Sen) ve sağ (Karşı Taraf) kanallarının
     * ses enerjisini ve konuşma parçalarını yerel olarak çözümler.
     */
    suspend fun transcribeCall(wavFile: File, onComplete: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!wavFile.exists() || wavFile.length() <= 44) {
            onComplete("")
            return@withContext
        }

        try {
            Log.i(TAG, "Lokal transkripsiyon baslatildi: ${wavFile.name}")

            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(44)
                val headerRead = fis.read(header)
                if (headerRead < 44) {
                    onComplete("")
                    return@withContext
                }

                val channels = ((header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)).coerceIn(1, 2)
                val sampleRate = ((header[24].toInt() and 0xFF) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[27].toInt() and 0xFF) shl 24)).toLong().coerceAtLeast(8000L)
                val frameBytes = channels * 2

                val buffer = ByteArray(4096)
                var bytesRead = fis.read(buffer)
                var sampleIndex = 0L

                val transcriptBuilder = StringBuilder()
                transcriptBuilder.append("--- Çevrimdışı Kayıt Özeti ---\n")

                var leftSpeaking = false
                var rightSpeaking = false

                while (bytesRead > 0) {
                    var leftEnergy = 0L
                    var rightEnergy = 0L
                    var frames = 0
                    var i = 0

                    while (i <= bytesRead - frameBytes) {
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

                    if (frames > 0) {
                        val currentSec = sampleIndex / sampleRate
                        val threshold = 1500L * frames

                        val nowLeft = leftEnergy > threshold
                        val nowRight = channels == 2 && rightEnergy > threshold

                        if (nowLeft && !leftSpeaking) {
                            leftSpeaking = true
                            val timeTag = String.format("[%02d:%02d]", currentSec / 60, currentSec % 60)
                            val label = if (channels == 1) "[Mikrofon]" else "[Sen]"
                            transcriptBuilder.append("$timeTag $label: (Ses algılandı)\n")
                        } else if (!nowLeft && leftSpeaking) {
                            leftSpeaking = false
                        }

                        if (nowRight && !rightSpeaking) {
                            rightSpeaking = true
                            val timeTag = String.format("[%02d:%02d]", currentSec / 60, currentSec % 60)
                            transcriptBuilder.append("$timeTag [Karşı Taraf]: (Ses algılandı)\n")
                        } else if (!nowRight && rightSpeaking) {
                            rightSpeaking = false
                        }
                    }

                    bytesRead = fis.read(buffer)
                }

                if (transcriptBuilder.length <= 40) {
                    transcriptBuilder.append("[00:00] Ses kaydı alındı (Sessiz veya düşük seviye).")
                }

                val finalTranscript = transcriptBuilder.toString()
                Log.i(TAG, "Lokal transkripsiyon tamamlandi.")
                onComplete(finalTranscript)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transkripsiyon hatasi", e)
            onComplete("[Hata]: Ses transkripsiyonu tamamlanamadı.")
        }
    }
}
