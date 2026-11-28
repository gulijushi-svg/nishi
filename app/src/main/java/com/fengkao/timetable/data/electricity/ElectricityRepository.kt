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

    suspend fun zones(account: String): List<ElectricityArea> = withContext(Dispatchers.IO) {
        runCatching { ElectricityBindingParser.parseAreas(api.zones(account)) }.getOrDefault(emptyList())
    }

    suspend fun builds(account: String, zoneId: Int): List<ElectricityArea> = withContext(Dispatchers.IO) {
        runCatching { ElectricityBindingParser.parseAreas(api.builds(account, zoneId)) }.getOrDefault(emptyList())
    }

    suspend fun floors(account: String, buildId: Int): List<ElectricityArea> = withContext(Dispatchers.IO) {
        runCatching { ElectricityBindingParser.parseAreas(api.floors(account, buildId)) }.getOrDefault(emptyList())
    }

    suspend fun rooms(account: String, floorId: Int): List<ElectricityArea> = withContext(Dispatchers.IO) {
        runCatching { ElectricityBindingParser.parseAreas(api.rooms(account, floorId)) }.getOrDefault(emptyList())
    }

    suspend fun bindRoom(account: String, zoneId: Int, buildId: Int, floorId: Int, roomNum: Int): ElectricityBindOutcome =
        withContext(Dispatchers.IO) {
            try {
                ElectricityBindingParser.parseBind(api.bindRoom(account, zoneId, buildId, floorId, roomNum))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ElectricityBindOutcome.Unavailable
            }
        }

    suspend fun unbindRoom(account: String, roomVerify: String): ElectricityBindOutcome =
        withContext(Dispatchers.IO) {
            try {
                ElectricityBindingParser.parseBind(api.unbindRoom(account, roomVerify))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ElectricityBindOutcome.Unavailable
            }
        }

    suspend fun roomByAccount(account: String, accountNum: String): ElectricityArea? = withContext(Dispatchers.IO) {
        runCatching { ElectricityBindingParser.parseRoomByAccount(api.roomByAccount(account, accountNum)) }.getOrNull()
    }
}
