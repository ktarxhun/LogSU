package com.ktarxhun.logsu.data.repository

import com.ktarxhun.logsu.data.db.*
import com.ktarxhun.logsu.data.model.*
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordRepositoryTest {
    private val deleted = mutableListOf<Long>()
    private var candidates = emptyList<CallRecord>()
    private inline fun <reified T> dao(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T

    private fun repository() = RecordRepository(
        dao<CallRecordDao> { name, args ->
            when (name) {
                "deleteRecord" -> { deleted.add((args[0] as CallRecord).id); Unit }
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

    private fun record(id: Long, path: String?) = CallRecord(
        id, "", null, false, CallType.AMBIENT_RECORDING,
        0, 0, 0, path, null, null, null
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

    @Test fun failedDeletionKeepsRowAndEarlierPurgesComplete() = runBlocking {
        val directory = File.createTempFile("logsu-test", ".dir")
        directory.delete()
        check(directory.mkdir())
        try {
            candidates = listOf(record(1, null), record(2, directory.path))
            try {
                repository().purgeOldRecords()
                fail("Directory must not be deleted as media")
            } catch (expected: IOException) { }
            assertTrue(directory.exists())
            assertEquals(listOf(1L), deleted)
        } finally { directory.delete() }
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
