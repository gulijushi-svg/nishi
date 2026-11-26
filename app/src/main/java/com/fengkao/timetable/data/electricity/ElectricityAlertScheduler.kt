package com.fengkao.timetable.data.electricity

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ElectricityAlertScheduler {
    const val WORK_NAME = "electricity_alert_periodic"
    const val PERIOD_HOURS = 12L

    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<ElectricityAlertWorker>(
            Duration.ofHours(PERIOD_HOURS),
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(initialDelay())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    internal fun initialDelay(now: ZonedDateTime = ZonedDateTime.now()): Duration {
        val targetHour = when (now.hour) {
            in 0..7 -> 8
            in 8..19 -> 20
            else -> 8
        }
        var next = atHour(now, targetHour)
        if (!next.isAfter(now)) next = atHour(now.plusDays(1), 8)
        return Duration.between(now, next)
    }

    private fun atHour(value: ZonedDateTime, hour: Int): ZonedDateTime = value
        .withHour(hour)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
}
