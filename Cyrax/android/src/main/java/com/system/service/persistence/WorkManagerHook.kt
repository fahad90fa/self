// [context: Kotlin, Android API 26+, WorkManager periodic restart]
package com.system.service.persistence

import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

class GuardWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val intent = Intent(applicationContext,
            Class.forName("com.system.service.core.CoreService"))
        applicationContext.startForegroundService(intent)
        return Result.success()
    }
}

class WorkManagerHook(private val context: Context) {

    fun schedulePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val request = PeriodicWorkRequestBuilder<GuardWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag("guard")
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "core_guard",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
