package com.fazalelahi92.apptracker.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val repository = UsageRepository(applicationContext)
            if (!repository.hasUsagePermission()) return@withContext Result.success()
            val today = UsageRepository.startOfDay(System.currentTimeMillis())
            repository.loadDay(today)
            repository.loadDay(today - 86_400_000L)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
