package com.ktarxhun.logsu.root

import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RootShellManager {
    private const val TAG = "RootShellManager"
    @Volatile
    private var cachedTouchDevice: String? = null
    private val interactiveMutex = Mutex()

    fun isRootCached(): Boolean {
        return try {
            Shell.getCachedShell()?.isRoot == true
        } catch (_: Exception) {
            false
        }
    }

    fun isRootAvailable(): Boolean {
        val isMainThread = runCatching {
            val main = Looper.getMainLooper()
            main != null && Looper.myLooper() == main
        }.getOrDefault(false)

        if (isMainThread) {
            Log.w(TAG, "isRootAvailable ana thread uzerinde cagrildi, onbellek donuluyor")
            return isRootCached()
        }

        return try {
            Shell.getCachedShell()?.isRoot ?: Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.e(TAG, "Root kontrol hatasi", e)
            false
        }
    }

    /**
     * Ön plan arayüzünden çağrılır: daha önce non-root shell önbelleklendiyse temizler
     * ve Magisk/KernelSU üzerinden kullanıcıya root onay penceresi çıkarır.
     */
    suspend fun ensureRootInteractive(): Boolean = withContext(Dispatchers.IO) {
        interactiveMutex.withLock {
            try {
                val cached = Shell.getCachedShell()
                if (cached != null && cached.isRoot) {
                    return@withLock true
                }
                if (cached != null) {
                    try {
                        cached.close()
                    } catch (_: Exception) {}
                }
                Shell.getShell().isRoot
            } catch (e: Exception) {
                Log.e(TAG, "Etkilesimli root isteme hatasi", e)
                false
            }
        }
    }

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun execute(cmd: String): List<String> {
        return try {
            Shell.cmd(cmd).exec().out
        } catch (e: Exception) {
            Log.e(TAG, "Komut calistirma hatasi: $cmd", e)
            emptyList()
        }
    }

    fun executeSingle(cmd: String): String {
        return execute(cmd).joinToString("\n")
    }

    fun findTouchDevice(): String? {
        cachedTouchDevice?.let { return it }
        val out = execute("getevent -p")
        var currentDevice: String? = null
        var fallbackCandidate: String? = null

        for (line in out) {
            if (line.startsWith("add device")) {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    currentDevice = parts[1].trim()
                }
            }
            if (line.contains("ABS_MT_POSITION_X") || line.contains(" 0035 ")) {
                if (currentDevice != null) {
                    cachedTouchDevice = currentDevice
                    return currentDevice
                }
            }
            if (fallbackCandidate == null && currentDevice != null &&
                (line.contains("input_mt", ignoreCase = true) || line.contains("TOUCH", ignoreCase = true))
            ) {
                fallbackCandidate = currentDevice
            }
        }
        if (fallbackCandidate != null) {
            cachedTouchDevice = fallbackCandidate
            return fallbackCandidate
        }
        val hasEvent1 = executeSingle("if [ -e /dev/input/event1 ]; then echo 1; fi").trim() == "1"
        return if (hasEvent1) "/dev/input/event1" else null
    }
}
