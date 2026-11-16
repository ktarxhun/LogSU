package com.ktarxhun.logsu.root

import android.os.SystemClock
import android.util.Log
import com.ktarxhun.logsu.data.model.TouchEvent
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class TouchCaptureController {
    companion object {
        private const val TAG = "TouchCaptureController"
    }

    private var captureJob: Job? = null
    private var process: Process? = null
    @Volatile
    private var isCapturing = false

    /**
     * Ekranda hiçbir görsel iz göstermeden, arka planda doğrudan dokunmatik aygıt sürücüsünü
     * dinleyerek gerçek koordinatları (0..1079, 0..2399) .touch dosyasına yazar.
     */
    fun startCapture(outputTouchFile: File, startTimeMs: Long) {
        if (isCapturing) return
        isCapturing = true

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val touchDevice = RootShellManager.findTouchDevice()
                if (touchDevice == null) {
                    Log.w(TAG, "Dokunmatik giris aygiti bulunamadi, dokunma kaydi devre disi birakildi")
                    isCapturing = false
                    return@launch
                }
                Log.i(TAG, "Dokunma yakalama baslatildi, aygit: $touchDevice, cikti: ${outputTouchFile.absolutePath}")
                val proc = ProcessBuilder(
                    "su",
                    "-c",
                    "getevent -l ${RootShellManager.shellQuote(touchDevice)}"
                )
                    .redirectErrorStream(true)
                    .start()
                process = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))

                val initialOffsetMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
                val captureBaseElapsedMs = SystemClock.elapsedRealtime()

                var currentX = 0f
                var currentY = 0f
                var isDown = false
                var wasDown = false

                outputTouchFile.bufferedWriter().use { writer ->
                    var line: String? = reader.readLine()
                    while (isCapturing && line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            val tokens = trimmed.split("\\s+".toRegex())
                            val lastToken = tokens.lastOrNull() ?: ""

                            if (trimmed.contains("ABS_MT_POSITION_X") || trimmed.contains(" 0035 ")) {
                                val hexVal = lastToken.toLongOrNull(16)?.toFloat()
                                if (hexVal != null) currentX = hexVal
                            } else if (trimmed.contains("ABS_MT_POSITION_Y") || trimmed.contains(" 0036 ")) {
                                val hexVal = lastToken.toLongOrNull(16)?.toFloat()
                                if (hexVal != null) currentY = hexVal
                            } else if (trimmed.contains("ABS_MT_TRACKING_ID")) {
                                isDown = !lastToken.contains("ffffffff", ignoreCase = true)
                            } else if (trimmed.contains("BTN_TOUCH")) {
                                if (trimmed.contains("DOWN")) isDown = true
                                else if (trimmed.contains("UP")) isDown = false
                            } else if (trimmed.contains("SYN_REPORT")) {
                                if (isDown || wasDown) {
                                    val relTime = initialOffsetMs + (SystemClock.elapsedRealtime() - captureBaseElapsedMs)
                                    val action = if (isDown) 0 else 1
                                    val event = TouchEvent(relTime, currentX, currentY, action)
                                    writer.write(event.toLogLine())
                                    writer.newLine()
                                    writer.flush()
                                    wasDown = isDown
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                if (isCapturing) {
                    Log.e(TAG, "Dokunma yakalama okuma hatasi", e)
                }
            } finally {
                process?.let { proc ->
                    runCatching { proc.destroy() }
                    if (proc.isAlive) runCatching { proc.destroyForcibly() }
                }
                process = null
            }
        }
    }

    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false

        try {
            process?.let { proc ->
                runCatching { proc.destroy() }
                if (proc.isAlive) runCatching { proc.destroyForcibly() }
            }
            process = null
            captureJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Dokunma yakalama durdurma hatasi", e)
        }
    }
}
