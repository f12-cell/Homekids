package com.homekids.child.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class DailyResetWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        LocalStatsManager(applicationContext).resetBlockedCount()
        return Result.success()
    }
}
