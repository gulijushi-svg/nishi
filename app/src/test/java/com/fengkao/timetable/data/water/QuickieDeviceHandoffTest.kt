package com.fengkao.timetable.data.water

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickieDeviceHandoffTest {
    @Test fun quickieRawValueKeepsLegacyDeviceIdResolution() {
        val samples = listOf(
            "https://i.ilife798.com/qr/use?id=202611260012345678",
            "ilife://devices/WM202611260001",
            "{\"deviceId\":\"DRINK20261126002\"}",
        )
        samples.forEach { rawValue ->
            val legacyScanInput = rawValue
            val quickieRawValue = rawValue
            assertEquals(
                DeviceIdParser.normalize(legacyScanInput),
                DeviceIdParser.normalize(quickieRawValue),
            )
        }
    }
}
