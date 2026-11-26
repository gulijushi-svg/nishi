package com.fengkao.timetable.data.electricity

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ElectricityAlertSchedulerTest {
    @Test
    fun firstRunTargetsNextEightOrTwenty() {
        assertEquals(Duration.ofMinutes(30), delay(7, 30))
        assertEquals(Duration.ofMinutes(1), delay(19, 59))
        assertEquals(Duration.ofHours(9), delay(23, 0))
    }

    @Test
    fun exactSlotWaitsForFollowingSlot() {
        assertEquals(Duration.ofHours(12), delay(8, 0))
        assertEquals(Duration.ofHours(12), delay(20, 0))
        assertEquals(12L, ElectricityAlertScheduler.PERIOD_HOURS)
    }

    private fun delay(hour: Int, minute: Int): Duration =
        ElectricityAlertScheduler.initialDelay(
            ZonedDateTime.of(2026, 1, 1, hour, minute, 0, 0, ZoneOffset.UTC),
        )
}
