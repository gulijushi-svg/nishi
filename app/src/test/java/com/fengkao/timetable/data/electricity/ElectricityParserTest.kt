package com.fengkao.timetable.data.electricity

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectricityParserTest {
    @Test
    fun parsesMultipleRoomsFromObjectBody() {
        val body = JSONObject().put(
            "roomlist",
            JSONArray()
                .put(room("南校区_1号宿舍_101", "100.5", "12.25", "1"))
                .put(room("南校区_2号宿舍_202", "88", "0", "0")),
        )
        val raw = JSONObject().put("result_", "true").put("body", body)

        val result = ElectricityParser.parse(raw, 123L) as ElectricityParseResult.Success

        assertEquals(123L, result.snapshot.fetchedAt)
        assertEquals(2, result.snapshot.rooms.size)
        assertEquals("南校区_1号宿舍_101", result.snapshot.rooms[0].roomName)
        assertEquals(100.5, result.snapshot.rooms[0].usedKwh, 0.0)
        assertEquals(12.25, result.snapshot.rooms[0].remainingKwh, 0.0)
        assertTrue(result.snapshot.rooms[0].isPoweredOn)
        assertFalse(result.snapshot.rooms[1].isPoweredOn)
    }

    @Test
    fun parsesSingleRoomWithoutRoomlist() {
        val raw = room("东校区_3号宿舍_303", "36.2", "45.8", "1").toString()

        val result = ElectricityParser.parse(raw, 456L) as ElectricityParseResult.Success

        assertEquals(1, result.snapshot.rooms.size)
        assertEquals("东校区_3号宿舍_303", result.snapshot.rooms[0].roomName)
        assertEquals(36.2, result.snapshot.rooms[0].usedKwh, 0.0)
        assertEquals(45.8, result.snapshot.rooms[0].remainingKwh, 0.0)
        assertTrue(result.snapshot.rooms[0].isPoweredOn)
    }

    @Test
    fun emptyRoomlistMeansNotBound() {
        val raw = """{"result_":"true","body":{"result":"0","roomlist":[]}}"""

        assertEquals(ElectricityParseResult.NotBound, ElectricityParser.parse(raw))
    }

    @Test
    fun parsesEscapedJsonStringBody() {
        val body = JSONObject().put(
            "roomlist",
            JSONArray().put(room("西校区_5号宿舍_505", "20", "8.5", "1")),
        ).toString()
        val raw = JSONObject().put("body", body).put("result_", "true").toString()

        val result = ElectricityParser.parse(raw, 789L) as ElectricityParseResult.Success

        assertEquals("西校区_5号宿舍_505", result.snapshot.rooms.single().roomName)
        assertEquals(8.5, result.snapshot.rooms.single().remainingKwh, 0.0)
    }

    @Test
    fun distinguishesMalformedJsonFromUnrecognizedStructure() {
        assertEquals(ElectricityParseResult.Malformed, ElectricityParser.parse("{not-json"))
        assertEquals(
            ElectricityParseResult.Malformed,
            ElectricityParser.parse(JSONObject().put("body", "{").toString()),
        )
        assertEquals(
            ElectricityParseResult.Malformed,
            ElectricityParser.parse(JSONObject().put("body", "{")),
        )
        assertEquals(ElectricityParseResult.Unrecognized, ElectricityParser.parse("[]"))
        assertEquals(
            ElectricityParseResult.Unrecognized,
            ElectricityParser.parse("""{"body":{"unexpected":true}}"""),
        )
    }

    @Test
    fun recognizesAccountWithNoRoomMessage() {
        val raw = """{"body":{"result":"1","message":"学号查不到房间"}}"""
        val emptyBody = JSONObject().put("body", "").put("message_", "学号查不到房间").toString()

        assertEquals(ElectricityParseResult.RoomNotFound, ElectricityParser.parse(raw))
        assertEquals(ElectricityParseResult.RoomNotFound, ElectricityParser.parse(emptyBody))
    }

    @Test
    fun treatsEveryStatusOtherThanOneAsPowerOff() {
        val raw = JSONObject().put("roomfullname", "东校区_3号宿舍_303").put(
            "detaillist",
            JSONArray().put(JSONObject().put("use", 1).put("odd", 2).put("status", 1.5)),
        ).toString()

        val result = ElectricityParser.parse(raw) as ElectricityParseResult.Success

        assertFalse(result.snapshot.rooms.single().isPoweredOn)
    }

    @Test
    fun rejectsInvalidRoomNumbersAsUnrecognized() {
        val raw = room("东校区_3号宿舍_303", "unknown", "10", "1").toString()

        assertEquals(ElectricityParseResult.Unrecognized, ElectricityParser.parse(raw))
    }

    private fun room(name: String, used: String, remaining: String, status: String): JSONObject =
        JSONObject().put(
            "roomfullname",
            name,
        ).put(
            "detaillist",
            JSONArray().put(
                JSONObject()
                    .put("use", used)
                    .put("odd", remaining)
                    .put("status", status),
            ),
        )
}
