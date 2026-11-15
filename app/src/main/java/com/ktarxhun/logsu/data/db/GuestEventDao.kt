package com.ktarxhun.logsu.data.db

import androidx.room.*
import com.ktarxhun.logsu.data.model.GuestEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface GuestEventDao {
    @Query("SELECT * FROM guest_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getEventsBySessionFlow(sessionId: Long): Flow<List<GuestEvent>>

    @Query("SELECT * FROM guest_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsBySession(sessionId: Long): List<GuestEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: GuestEvent): Long

    @Delete
    suspend fun deleteEvent(event: GuestEvent)
}
