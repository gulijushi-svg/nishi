package com.fengkao.timetable.data.electricity

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fengkao.timetable.data.local.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class ElectricityAlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = mutex.withLock { checkElectricity() }

    private suspend fun checkElectricity(): Result {
        val app = applicationContext
        val store = SettingsStore(app)
        val settings = try {
            store.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        val account = ElectricityAccount.normalize(settings.account)
        if (account.isBlank()) return Result.success()
        val requestedDay = LocalDate.now().toEpochDay()
        if (settings.electricityAlertDay == requestedDay) return Result.success()

        return when (val result = ElectricityRepository(app).fetch(account)) {
            is ElectricityResult.Success -> {
                val alertDay = LocalDate.now().toEpochDay()
                val lowRooms = result.snapshot.rooms.filter {
                    it.remainingKwh < settings.electricityThreshold
                }
                if (settings.electricityAlertDay != alertDay &&
                    lowRooms.isNotEmpty() &&
                    ElectricityNotifier.notifyLow(app, lowRooms)
                ) {
                    try {
                        store.setElectricityAlertDay(alertDay)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return Result.success()
                    }
                }
                Result.success()
            }
            ElectricityResult.NotBound,
            ElectricityResult.RoomNotFound,
            -> Result.success()
            ElectricityResult.Unavailable ->
                if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    private companion object {
        val mutex = Mutex()
    }
}
