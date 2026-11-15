package com.ktarxhun.logsu.data.repository

import com.ktarxhun.logsu.data.db.CallRecordDao
import com.ktarxhun.logsu.data.db.GuestEventDao
import com.ktarxhun.logsu.data.db.GuestSessionDao
import com.ktarxhun.logsu.data.model.CallRecord
import com.ktarxhun.logsu.data.model.GuestEvent
import com.ktarxhun.logsu.data.model.GuestSession
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
            if (file.exists() && (!file.isFile || !file.delete())) {
                throw IOException("Kayıt dosyası silinemedi")
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
        for (record in toPurge) {
            // Her başarılı silme ayrı tamamlanır; sonraki hata önceki satırları yetim bırakmaz.
            deleteCallRecord(record)
        }
        return toPurge.size
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
