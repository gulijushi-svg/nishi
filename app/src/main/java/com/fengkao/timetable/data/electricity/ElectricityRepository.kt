package com.fengkao.timetable.data.electricity

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException

sealed interface ElectricityResult {
    data class Success(val snapshot: ElectricitySnapshot) : ElectricityResult
    data object NotBound : ElectricityResult
    data object RoomNotFound : ElectricityResult
    data object Unavailable : ElectricityResult
}

class ElectricityRepository(
    context: Context,
    private val api: ElectricityApi = ElectricityApi(),
    private val store: ElectricityStore = ElectricityStore(context),
) {
    suspend fun fetch(account: String): ElectricityResult = withContext(Dispatchers.IO) {
        val studentId = ElectricityAccount.normalize(account)
        if (studentId.isBlank()) return@withContext ElectricityResult.RoomNotFound
        try {
            when (val parsed = ElectricityParser.parse(api.fetch(studentId))) {
                is ElectricityParseResult.Success -> {
                    runCatching { store.save(parsed.snapshot) }
                    ElectricityResult.Success(parsed.snapshot)
                }
                ElectricityParseResult.NotBound -> {
                    runCatching { store.clear() }
                    ElectricityResult.NotBound
                }
                ElectricityParseResult.RoomNotFound -> {
                    runCatching { store.clear() }
                    ElectricityResult.RoomNotFound
                }
                ElectricityParseResult.Malformed,
                ElectricityParseResult.Unrecognized,
                -> ElectricityResult.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            ElectricityResult.Unavailable
        } catch (_: IOException) {
            ElectricityResult.Unavailable
        } catch (_: Exception) {
            ElectricityResult.Unavailable
        }
    }
}
