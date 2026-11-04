package com.anees.novamobile.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Checks GitHub for a new release every few hours, even when Nova isn't opened, and notifies. */
class UpdateJob : JobService() {
    companion object {
        private const val JOB_ID = 7501

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            runCatching {
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(3 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build())
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "update-check") {
            runCatching { Updater.checkInBackground(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
