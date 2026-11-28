package com.fengkao.timetable.data.electricity

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

object ElectricityParser {

    fun parse(raw: String, fetchedAt: Long = System.currentTimeMillis()): ElectricityParseResult =
        try {
            parseRoot(decodeDocument(raw), fetchedAt)
        } catch (_: MalformedJsonException) {
            ElectricityParseResult.Malformed
        }

    fun parse(raw: JSONObject, fetchedAt: Long = System.currentTimeMillis()): ElectricityParseResult =
        try {
            parseRoot(raw, fetchedAt)
        } catch (_: MalformedJsonException) {
            ElectricityParseResult.Malformed
        }

    private fun parseRoot(root: Any, fetchedAt: Long): ElectricityParseResult {
        var current = root
        var outerMessage = ""
        repeat(3) {
            if (current !is JSONObject) return ElectricityParseResult.Unrecognized
            messageOf(current).takeIf { it.isNotBlank() }?.let { outerMessage = it }
            if (!current.has("body")) return parsePayload(current, fetchedAt, outerMessage)
            current = when (val body = current.opt("body")) {
                is JSONObject -> body
                is String -> {
                    if (body.isBlank() && isRoomNotFound(outerMessage)) {
                        return ElectricityParseResult.RoomNotFound
                    }
                    decodeDocument(body)
                }
                else -> return unrecognizedOrNotFound(outerMessage)
            }
        }
        return ElectricityParseResult.Unrecognized
    }

    private fun parsePayload(
        payload: JSONObject,
        fetchedAt: Long,
        message: String,
    ): ElectricityParseResult {
        if (payload.has("roomlist")) {
            val list = payload.optJSONArray("roomlist")
                ?: return unrecognizedOrNotFound(message)
            if (list.length() == 0) return ElectricityParseResult.NotBound
            val rooms = ArrayList<ElectricityRoom>(list.length())
            for (index in 0 until list.length()) {
                val room = parseRoom(list.optJSONObject(index))
                    ?: return unrecognizedOrNotFound(message)
                rooms += room
            }
            return ElectricityParseResult.Success(
                ElectricitySnapshot(rooms = rooms, fetchedAt = fetchedAt),
            )
        }
        val room = parseRoom(payload) ?: return unrecognizedOrNotFound(message)
        return ElectricityParseResult.Success(
            ElectricitySnapshot(rooms = listOf(room), fetchedAt = fetchedAt),
        )
    }

    private fun parseRoom(room: JSONObject?): ElectricityRoom? {
        if (room == null) return null
        val name = text(room, "roomfullname")
        if (name.isBlank()) return null
        val detail = room.optJSONArray("detaillist")?.optJSONObject(0) ?: return null
        val used = number(detail, "use") ?: return null
        val remaining = number(detail, "odd") ?: return null
        val poweredOn = when (val status = detail.opt("status")) {
            is Number -> status.toDouble() == 1.0
            is String -> status.trim() == "1"
            else -> false
        }
        return ElectricityRoom(
            roomName = name,
            usedKwh = used,
            remainingKwh = remaining,
            isPoweredOn = poweredOn,
            roomVerify = text(room, "roomverify"),
        )
    }

    private fun decodeDocument(raw: String): Any {
        var text = raw
        repeat(4) {
            val value = try {
                decodeJson(text)
            } catch (_: JSONException) {
                throw MalformedJsonException()
            }
            if (value !is String) return value
            text = value
        }
        throw MalformedJsonException()
    }

    private fun decodeJson(raw: String): Any {
        val parser = JSONTokener(raw)
        val value = parser.nextValue()
        if (parser.nextClean() != '\u0000') throw JSONException("Unexpected trailing content")
        return value
    }

    private fun unrecognizedOrNotFound(message: String): ElectricityParseResult =
        if (isRoomNotFound(message)) ElectricityParseResult.RoomNotFound
        else ElectricityParseResult.Unrecognized

    private fun isRoomNotFound(message: String): Boolean = roomNotFoundPattern.containsMatchIn(message)

    private fun messageOf(value: JSONObject): String =
        firstText(value, "message_", "message", "msg")

    private fun firstText(value: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val text = text(value, key)
            if (text.isNotBlank()) return text
        }
        return ""
    }

    private fun text(value: JSONObject, key: String): String {
        val raw = value.opt(key)
        if (raw == null || raw == JSONObject.NULL) return ""
        return raw.toString().trim().takeUnless { it.isBlank() || it == "null" }.orEmpty()
    }

    private fun number(value: JSONObject, key: String): Double? =
        when (val raw = value.opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

    private class MalformedJsonException : Exception()

    private val roomNotFoundPattern = Regex(
        "(?:学号|账号|帐户|账户).{0,20}(?:查不到|找不到|查无|不存在|未找到|没有|未绑定|有误|错误|无效)" +
            "|(?:查不到|找不到|查无|不存在|未找到).{0,20}(?:学号|账号|帐户|账户)" +
            "|(?:未查询到|没有|查询不到).{0,12}(?:房间|宿舍)",
        RegexOption.DOT_MATCHES_ALL,
    )
}
