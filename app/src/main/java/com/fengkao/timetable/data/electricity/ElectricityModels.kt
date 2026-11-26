package com.fengkao.timetable.data.electricity

import kotlinx.serialization.Serializable

object ElectricityAccount {
    private val studentIdPattern = Regex("""\d{4,32}""")

    fun normalize(raw: String): String = raw.trim().takeIf(studentIdPattern::matches).orEmpty()
}

@Serializable
data class ElectricityRoom(
    val roomName: String,
    val usedKwh: Double,
    val remainingKwh: Double,
    val isPoweredOn: Boolean,
)

@Serializable
data class ElectricitySnapshot(
    val rooms: List<ElectricityRoom> = emptyList(),
    val fetchedAt: Long = 0L,
)

@Serializable
sealed interface ElectricityParseResult {
    @Serializable
    data class Success(val snapshot: ElectricitySnapshot) : ElectricityParseResult

    @Serializable
    data object NotBound : ElectricityParseResult

    @Serializable
    data object RoomNotFound : ElectricityParseResult

    @Serializable
    data object Malformed : ElectricityParseResult

    @Serializable
    data object Unrecognized : ElectricityParseResult
}
