package com.everydayecho.memorykeeper.transcribe

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.everydayecho.memorykeeper.data.FileRepository
import java.util.concurrent.TimeUnit

/** Runs while the phone is charging and idle: transcribes whatever speech segments are still pending. */
class TranscribeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!ModelManager.isReady(applicationContext)) return Result.success()
        Transcriber(applicationContext, FileRepository(applicationContext)).transcribePending()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TranscribeWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("mk_transcribe", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
