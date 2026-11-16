package com.ktarxhun.logsu.data.repository

import com.ktarxhun.logsu.data.db.*
import com.ktarxhun.logsu.data.model.*
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordRepositoryTest {
    private val deleted = mutableListOf<Long>()
    private var candidates = emptyList<CallRecord>()
    private val currentById = mutableMapOf<Long, CallRecord>()

    private inline fun <reified T> dao(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T

    private fun repository() = RecordRepository(
        dao<CallRecordDao> { name, args ->
            when (name) {
                "deleteRecord" -> { deleted.add((args[0] as CallRecord).id); Unit }
                "deleteUnlockedRecordById" -> {
                    val id = args[0] as Long
                    val rec = currentById[id] ?: candidates.firstOrNull { it.id == id }
                    if (rec != null && !rec.isLocked) {
                        deleted.add(id)
                        1
                    } else {
                        0
                    }
                }
                "getRecordById" -> {
                    val id = args[0] as Long
                    currentById[id] ?: candidates.firstOrNull { it.id == id }
                }
                "getRecordsToPurge" -> candidates
                "getAllRecordsFlow" -> emptyFlow<List<CallRecord>>()
                else -> error(name)
            }
        },
        dao<GuestSessionDao> { name, _ ->
            if (name == "getAllSessionsFlow") emptyFlow<List<GuestSession>>()
            else error("Unexpected session access")
        },
        dao<GuestEventDao> { _, _ -> error("Unexpected event access") }
    )

    private fun record(id: Long, path: String?, isLocked: Boolean = false) = CallRecord(
        id, "", null, false, CallType.AMBIENT_RECORDING,
        0, 0, 0, path, null, null, null, isLocked
    )

    @Test fun deletesMediaBeforeDatabaseRow() = runBlocking {
        val file = File.createTempFile("logsu-test", ".wav")
        try {
            repository().deleteCallRecord(record(1, file.path))
            assertFalse(file.exists())
            assertEquals(listOf(1L), deleted)
        } finally { file.delete() }
    }

    @Test fun missingMediaAllowsRetry() = runBlocking {
        val file = File.createTempFile("logsu-test", ".wav")
        file.delete()
        repository().deleteCallRecord(record(2, file.path))
        assertEquals(listOf(2L), deleted)
    }

    @Test fun failedDeletionKeepsRowAndContinuesRemainingPurges() = runBlocking {
        val directory = File.createTempFile("logsu-test", ".dir")
        directory.delete()
        check(directory.mkdir())
        try {
            candidates = listOf(record(1, null), record(2, directory.path), record(3, null))
            val purgedCount = repository().purgeOldRecords()
            assertEquals(2, purgedCount)
            assertTrue(directory.exists())
            assertEquals(listOf(1L, 3L), deleted)
        } finally { directory.delete() }
    }

    @Test fun lockedRecordIsPreservedDuringPurge() = runBlocking {
        val unlocked = record(10, null, isLocked = false)
        val lockedLater = record(11, null, isLocked = false)
        candidates = listOf(unlocked, lockedLater)
        currentById[11L] = lockedLater.copy(isLocked = true)

        val purgedCount = repository().purgeOldRecords()
        assertEquals(1, purgedCount)
        assertEquals(listOf(10L), deleted)
    }

    @Test fun rejectsInvalidRetentionBeforeAccessingDatabase() = runBlocking {
        for (days in listOf(0, -1, Int.MAX_VALUE)) {
            try {
                repository().purgeOldRecords(contactDays = days)
                fail("Invalid retention accepted")
            } catch (expected: IllegalArgumentException) { }
        }
        assertTrue(deleted.isEmpty())
    }
}
