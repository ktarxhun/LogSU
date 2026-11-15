package com.ktarxhun.logsu.data.db

import androidx.room.*
import com.ktarxhun.logsu.data.model.CallRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY startTime DESC")
    fun getAllRecordsFlow(): Flow<List<CallRecord>>

    @Query("SELECT * FROM call_records WHERE id = :id")
    suspend fun getRecordById(id: Long): CallRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: CallRecord): Long

    @Update
    suspend fun updateRecord(record: CallRecord)

    @Delete
    suspend fun deleteRecord(record: CallRecord)

    @Query("UPDATE call_records SET isLocked = :isLocked WHERE id = :id")
    suspend fun setLockStatus(id: Long, isLocked: Boolean)

    @Query("UPDATE call_records SET transcript = :transcript WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String)

    // Temizlik sorgusu: Ortam ses kaydı haricinde kilitli olmayan, rehberde olup 3 günden eski veya yabancı olup 7 günden eski olanları listele
    @Query("""
        SELECT * FROM call_records 
        WHERE isLocked = 0 
        AND callType != 'AMBIENT_RECORDING'
        AND (
            (isContact = 1 AND startTime < :contactThreshold) 
            OR 
            (isContact = 0 AND startTime < :unknownThreshold)
        )
    """)
    suspend fun getRecordsToPurge(contactThreshold: Long, unknownThreshold: Long): List<CallRecord>

    @Query("DELETE FROM call_records WHERE id IN (:ids)")
    suspend fun deleteRecordsByIds(ids: List<Long>)
}
