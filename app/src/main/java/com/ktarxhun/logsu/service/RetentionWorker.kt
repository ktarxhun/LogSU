package com.ktarxhun.logsu.service

import kotlinx.coroutines.CancellationException
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ktarxhun.logsu.LogSuApp

class RetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "logsu_retention_purge_worker"
        private const val TAG = "RetentionWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Otomatik temizlik calisiyor: 3 gun (rehber) / 7 gun (yabanci)...")
            val repository = LogSuApp.instance.repository
            val purgedCount = repository.purgeOldRecords(contactDays = 3, unknownDays = 7)
            Log.i(TAG, "Temizlik tamamlandi, silinen kayit sayisi: $purgedCount")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Temizlik sirasinda hata olustu", e)
            Result.retry()
        }
    }
}
