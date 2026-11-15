package com.ktarxhun.logsu.data.db

import androidx.room.*
import com.ktarxhun.logsu.data.model.GuestSession
import kotlinx.coroutines.flow.Flow

@Dao
interface GuestSessionDao {
    @Query("SELECT * FROM guest_sessions ORDER BY startTime DESC")
    fun getAllSessionsFlow(): Flow<List<GuestSession>>

    @Query("SELECT * FROM guest_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): GuestSession?

    @Query("SELECT * FROM guest_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): GuestSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: GuestSession): Long

    @Update
    suspend fun updateSession(session: GuestSession)

    @Delete
    suspend fun deleteSession(session: GuestSession)

    @Query("UPDATE guest_sessions SET isActive = 0, endTime = :endTime WHERE id = :id")
    suspend fun closeSession(id: Long, endTime: Long)
}
