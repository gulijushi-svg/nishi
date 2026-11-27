package com.fengkao.timetable.data.electricity

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ElectricityArea(
    val id: Int,
    val name: String,
)

sealed interface ElectricityBindOutcome {
    data class Success(val roomName: String) : ElectricityBindOutcome
    data class Failed(val message: String) : ElectricityBindOutcome
    data object Unavailable : ElectricityBindOutcome
}

object ElectricityBindingParser {
    fun parseAreas(raw: String): List<ElectricityArea> {
        val root = unwrap(raw) ?: return emptyList()
        val array = root.optJSONArray("data") ?: root.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<ElectricityArea>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = int(item, "id") ?: int(item, "value") ?: continue
            val name = text(item, "name").ifBlank { text(item, "label") }
            if (name.isNotBlank()) result += ElectricityArea(id, name)
        }
        return result
    }

    fun parseBind(raw: String): ElectricityBindOutcome {
        val root = unwrap(raw) ?: return ElectricityBindOutcome.Unavailable
        val code = int(root, "result") ?: int(root, "code_")
        val message = text(root, "message").ifBlank { text(root, "msg") }
        if (code != null && code != 0) {
            return ElectricityBindOutcome.Failed(message.ifBlank { "绑定失败，请检查房间信息" })
        }
        val roomName = text(root, "roomfullname").ifBlank {
            root.optJSONArray("roomlist")?.optJSONObject(0)?.let { text(it, "roomfullname") }.orEmpty()
        }
        return ElectricityBindOutcome.Success(roomName.ifBlank { "房间" })
    }

    fun parseRoomByAccount(raw: String): ElectricityArea? {
        val root = unwrap(raw) ?: return null
        val data = root.optJSONObject("data") ?: root
        val id = int(data, "room_num") ?: int(data, "roomnum") ?: return null
        val name = text(data, "roomfullname").ifBlank { text(data, "roomnum") }
        return if (name.isBlank()) null else ElectricityArea(id, name)
    }

    private fun unwrap(raw: String): JSONObject? {
        var current: Any? = try {
            JSONTokener(raw).nextValue()
        } catch (_: Exception) {
            return null
        }
        repeat(3) {
            when (current) {
                is String -> current = try {
                    JSONTokener(current).nextValue()
                } catch (_: Exception) {
                    return null
                }
                is JSONObject -> {
                    val body = current.opt("body")
                    if (body is String && body.isNotBlank()) {
                        current = try {
                            JSONTokener(body).nextValue()
                        } catch (_: Exception) {
                            return null
                        }
                    } else if (body is JSONObject) {
                        current = body
                    } else {
                        return current
                    }
                }
                else -> return null
            }
        }
        return current as? JSONObject
    }

    private fun text(value: JSONObject, key: String): String {
        val raw = value.opt(key)
        if (raw == null || raw == JSONObject.NULL) return ""
        return raw.toString().trim().takeUnless { it == "null" }.orEmpty()
    }

    private fun int(value: JSONObject, key: String): Int? = when (val raw = value.opt(key)) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }
}
