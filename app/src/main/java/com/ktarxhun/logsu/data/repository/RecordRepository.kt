package com.ktarxhun.logsu.data.repository

import android.util.Log
import com.ktarxhun.logsu.data.db.CallRecordDao
import com.ktarxhun.logsu.data.db.GuestEventDao
import com.ktarxhun.logsu.data.db.GuestSessionDao
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.GuestEvent
import com.ktarxhun.logsu.data.model.GuestSession
import com.ktarxhun.logsu.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.io.File

class RecordRepository(
    private val callRecordDao: CallRecordDao,
    private val guestSessionDao: GuestSessionDao,
    private val guestEventDao: GuestEventDao
) {
    companion object {
        private const val TAG = "RecordRepository"
    }

    val allCallRecords: Flow<List<CallRecord>> = callRecordDao.getAllRecordsFlow()
    val allGuestSessions: Flow<List<GuestSession>> = guestSessionDao.getAllSessionsFlow()

    suspend fun insertCallRecord(record: CallRecord): Long = callRecordDao.insertRecord(record)

    suspend fun getCallRecordById(id: Long): CallRecord? = callRecordDao.getRecordById(id)

    suspend fun updateCallRecord(record: CallRecord) = callRecordDao.updateRecord(record)

    suspend fun setLockStatus(id: Long, isLocked: Boolean) = callRecordDao.setLockStatus(id, isLocked)

    suspend fun updateTranscript(id: Long, transcript: String) = callRecordDao.updateTranscript(id, transcript)

    private suspend fun deleteFiles(paths: List<String?>) = withContext(Dispatchers.IO) {
        for (path in paths.filterNotNull().distinct()) {
            val file = File(path)
            // Eksik dosyalar tekrar denemeyi engellemez. Silinemeyen dosya varsa DB kaydı korunur.
            if (file.exists()) {
                if (!file.isFile) {
                    throw IOException("Kayıt dosyası silinemedi")
                }
                if (!file.delete()) {
                    val hasRoot = runCatching { RootShellManager.isRootCached() }.getOrDefault(false)
                    if (hasRoot) {
                        runCatching {
                            RootShellManager.execute("rm -f ${RootShellManager.shellQuote(file.absolutePath)}")
                        }
                    }
                    if (file.exists()) {
                        throw IOException("Kayıt dosyası silinemedi")
                    }
                }
            }
        }
    }

    suspend fun deleteCallRecord(record: CallRecord) {
        deleteFiles(listOf(record.audioFilePath, record.videoFilePath, record.touchFilePath))
        callRecordDao.deleteRecord(record)
    }

    suspend fun purgeOldRecords(contactDays: Int = 3, unknownDays: Int = 7): Int {
        require(contactDays in 1..36500 && unknownDays in 1..36500) {
            "Saklama süresi 1 ile 36500 gün arasında olmalıdır"
        }
        val now = System.currentTimeMillis()
        val dayMillis = 24L * 60 * 60 * 1000
        val toPurge = callRecordDao.getRecordsToPurge(
            now - contactDays * dayMillis, now - unknownDays * dayMillis
        )
        var purgedCount = 0
        for (record in toPurge) {
            val current = callRecordDao.getRecordById(record.id) ?: continue
            if (current.isLocked) continue
            try {
                if (purgeRecord(current)) {
                    purgedCount++
                }
            } catch (e: IOException) {
                runCatching { Log.w(TAG, "Kayit temizlenemedi (ID=${current.id}): ${e.message}") }
            }
        }
        return purgedCount
    }

    /**
     * Temizlikte kilit yarisini onler: dosyalar once gecici ada tasinir, DB satiri yalnizca
     * kilit hala kapaliysa silinir. Kullanici bu arada "Korumaya Al" derse satir silinmez
     * ve dosyalar geri tasinarak kilitli kayit dosyasiz birakilmaz.
     */
    private suspend fun purgeRecord(current: CallRecord): Boolean = withContext(Dispatchers.IO) {
        val files = listOf(current.audioFilePath, current.videoFilePath, current.touchFilePath)
            .filterNotNull()
            .distinct()
            .map { File(it) }
            .filter { it.exists() }

        if (files.any { !it.isFile }) {
            throw IOException("Kayıt dosyası silinemedi")
        }

        val quarantined = mutableListOf<Pair<File, File>>()
        try {
            for (file in files) {
                val temp = File(file.parentFile, "${file.name}.purge_${System.nanoTime()}")
                if (!moveFile(file, temp)) {
                    throw IOException("Kayıt dosyası silinemedi")
                }
                quarantined.add(file to temp)
            }
            val removed = callRecordDao.deleteUnlockedRecordById(current.id)
            if (removed > 0) {
                for ((_, temp) in quarantined) {
                    deleteFileQuietly(temp)
                }
                true
            } else {
                // Kayit bu arada kilitlendi: dosyalar geri tasinir, kayit delil olarak korunur.
                for ((original, temp) in quarantined) {
                    if (!moveFile(temp, original)) {
                        runCatching { Log.w(TAG, "Kilitlenen kaydin dosyasi geri tasinamadi: ${original.name}") }
                    }
                }
                false
            }
        } catch (e: Exception) {
            for ((original, temp) in quarantined) {
                moveFile(temp, original)
            }
            throw if (e is IOException) e else IOException("Kayıt temizlenemedi: ${e.message}", e)
        }
    }

    private fun moveFile(from: File, to: File): Boolean {
        if (!from.exists()) return true
        if (from.renameTo(to)) return true
        val hasRoot = runCatching { RootShellManager.isRootAvailable() }.getOrDefault(false)
        if (hasRoot) {
            runCatching {
                RootShellManager.execute(
                    "mv -f ${RootShellManager.shellQuote(from.absolutePath)} ${RootShellManager.shellQuote(to.absolutePath)} 2>/dev/null"
                )
            }
        }
        return !from.exists() && to.exists()
    }

    private fun deleteFileQuietly(file: File) {
        if (!file.exists()) return
        if (file.delete()) return
        val hasRoot = runCatching { RootShellManager.isRootAvailable() }.getOrDefault(false)
        if (hasRoot) {
            runCatching {
                RootShellManager.execute("rm -f ${RootShellManager.shellQuote(file.absolutePath)} 2>/dev/null")
            }
        }
    }

    // Emanet Oturumu Fonksiyonları
    suspend fun startGuestSession(): Long {
        val session = GuestSession(startTime = System.currentTimeMillis())
        return guestSessionDao.insertSession(session)
    }

    suspend fun getActiveGuestSession(): GuestSession? = guestSessionDao.getActiveSession()

    suspend fun closeActiveGuestSession(sessionId: Long) {
        guestSessionDao.closeSession(sessionId, System.currentTimeMillis())
    }

    suspend fun closeOrphanedGuestSessions() {
        guestSessionDao.closeAllActiveSessions(System.currentTimeMillis())
    }

    suspend fun getGuestSessionById(id: Long): GuestSession? = guestSessionDao.getSessionById(id)

    fun getEventsForSession(sessionId: Long): Flow<List<GuestEvent>> = guestEventDao.getEventsBySessionFlow(sessionId)

    suspend fun logGuestEvent(event: GuestEvent): Long = guestEventDao.insertEvent(event)

    suspend fun updateGuestSession(session: GuestSession) = guestSessionDao.updateSession(session)

    suspend fun deleteGuestSession(session: GuestSession) {
        val events = guestEventDao.getEventsBySession(session.id)
        deleteFiles(events.map { it.mediaPath } + listOf(
            session.initialPhotoPath, session.videoFilePath,
            session.audioFilePath, session.touchFilePath
        ))
        guestSessionDao.deleteSession(session)
    }
}
