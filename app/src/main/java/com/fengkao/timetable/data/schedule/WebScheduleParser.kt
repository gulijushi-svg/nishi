package com.fengkao.timetable.data.schedule

import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.data.model.CourseBlock
import com.fengkao.timetable.data.model.StudentInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析 Classy / 新版正方 WebView 导入返回的 JSON。
 *
 * 浏览器登录后由页面内 JS 请求：
 * `/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`
 * 返回的 `kbList` 中字段与「新正方」教务系统一致。这里只针对郑州师范学院，
 * 不引入多学校适配层。
 */
object WebScheduleParser {

    fun parse(raw: JSONObject): CachedSchedule? {
        val data = when (val value = raw.opt("data")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            null -> null
            else -> null
        } ?: return null


        val kbList = findKbList(data) ?: return null
        if (kbList.length() == 0) return null

        val xnm = raw.optString("xnm").toIntOrNull()
            ?: TermCalculator.autoTerm().xnm
        val term = termOf(raw.optString("xqm"))
        val xsxx = data.optJSONObject("xsxx") ?: findStudent(data)
        val className = xsxx?.optString("BJMC").orEmpty()
        val student = StudentInfo(
            name = xsxx?.optString("XM").orEmpty(),
            studentId = first(xsxx, "XH_ID", "XH", "xh_id"),
            className = className,
            department = first(xsxx, "JGMC", "YXMC", "department"),
            major = first(xsxx, "ZYMCM", "ZYMC", "major"),
            grade = first(xsxx, "NJDM_ID", "NJMC", "grade"),
        )

        val blocks = mutableListOf<CourseBlock>()
        for (i in 0 until kbList.length()) {
            val item = kbList.optJSONObject(i) ?: continue
            val name = first(item, "kcmc", "kcm", "courseName")
            if (name.isBlank()) continue
            val day = item.optString("xqj").toIntOrNull()
                ?: dayOf(item.optString("xqjmc"))
                ?: continue
            val sectionText = first(item, "jcs", "jc", "classSessions")
            val sections = parseSections(sectionText)
            if (sections.isEmpty()) continue
            val weekText = first(item, "zcd", "kkzc", "zc", "classWeek")
            val weeks = parseWeeks(weekText)
            if (weeks.isEmpty()) continue
            val room = first(item, "cdmc", "classroomName", "jxcd")
            val teacher = first(item, "xm", "jsxm", "teacher")
            val campus = first(item, "xqmc", "campus")
            for ((start, end) in sections) {
                blocks += CourseBlock(
                    name = name,
                    place = room,
                    teacher = teacher,
                    campus = campus,
                    dayOfWeek = day.coerceIn(1, 7),
                    startPeriod = start,
                    endPeriod = end,
                    weeks = weeks,
                    weeksText = weekText,
                    className = className,
                )
            }
        }
        if (blocks.isEmpty()) return null

        // 同一门课同一格子可能被接口拆成多条，合并周次。
        val merged = linkedMapOf<String, CourseBlock>()
        for (block in blocks) {
            val key = listOf(
                block.name, block.dayOfWeek, block.startPeriod,
                block.endPeriod, block.place, block.teacher,
            ).joinToString("|")
            val old = merged[key]
            merged[key] = if (old == null) block else old.copy(
                weeks = (old.weeks + block.weeks).distinct().sorted(),
            )
        }
        return CachedSchedule(
            xnm = xnm,
            term = term,
            fetchedAt = System.currentTimeMillis(),
            student = student,
            courses = merged.values.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod })),
        )
    }

    private fun termOf(raw: String): Int = when (raw.trim()) {
        "3" -> 1
        "12" -> 2
        "16" -> 3
        else -> raw.toIntOrNull()?.let { if (it > 2) 1 else it } ?: 1
    }

    private fun dayOf(text: String): Int? {
        val chars = charArrayOf('一', '二', '三', '四', '五', '六', '日', '天')
        for (c in text) {
            val idx = chars.indexOf(c)
            if (idx >= 0) return if (idx >= 6) 7 else idx + 1
        }
        return null
    }

    /** "1-2" / "3-4,6-7" / "0102" / "第1-2节" → 多段节次。 */
    private fun parseSections(raw: String): List<Pair<Int, Int>> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        raw.split(",", "，", ";", "；").forEach { part ->
            val text = part.trim().removeSuffix("节").trim()
            if (text.isEmpty()) return@forEach
            if (text.contains("-")) {
                val nums = Regex("""\d+""").findAll(text).map { it.value.toIntOrNull() }.toList()
                if (nums.size >= 2 && nums[0] != null && nums[1] != null) {
                    val a = nums[0]!!.coerceIn(1, 24)
                    val b = nums[1]!!.coerceIn(a, 24)
                    out += a to b
                }
                return@forEach
            }
            val digits = text.filter(Char::isDigit)
            if (digits.isEmpty()) return@forEach
            if (digits.length >= 2 && digits.length % 2 == 0 && digits.startsWith("0")) {
                val nums = digits.chunked(2).mapNotNull { it.toIntOrNull() }
                var i = 0
                while (i + 1 < nums.size) {
                    out += nums[i].coerceIn(1, 24) to nums[i + 1].coerceIn(1, 24)
                    i += 2
                }
            } else {
                val n = digits.toIntOrNull() ?: return@forEach
                out += n.coerceIn(1, 24) to n.coerceIn(1, 24)
            }
        }
        return out
    }

    /** "1-16周" / "1-16周(单)" / "{第1-16周}" / "1-8,11-16" → 周次列表。 */
    private fun parseWeeks(raw: String): List<Int> {
        val text = raw.replace("{", "").replace("}", "").replace("第", "").trim()
        if (text.isBlank()) return (1..16).toList()
        val result = sortedSetOf<Int>()
        text.split(",", "，", ";", "；").forEach { part0 ->
            val part = part0.trim()
            if (part.isEmpty()) return@forEach
            val odd = part.contains("(单)") || part.contains("(单周)") || part.endsWith("单")
            val even = part.contains("(双)") || part.contains("(双周)") || part.endsWith("双")
            val numbers = Regex("""\d+""").findAll(part).map { it.value.toInt() }.toList()
            if (numbers.isEmpty()) return@forEach
            val start = numbers.first().coerceIn(1, 30)
            val end = (numbers.getOrNull(1) ?: start).coerceIn(start, 30)
            for (w in start..end) {
                if (odd && w % 2 == 0) continue
                if (even && w % 2 == 1) continue
                result += w
            }
        }
        return result.toList()
    }

    private fun findKbList(data: JSONObject, depth: Int = 0): JSONArray? {
        if (depth > 5) return null
        for (key in listOf("kbList", "kbxx", "tmp_list")) {
            data.optJSONArray(key)?.let { return it }
        }
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.opt(key)
            if (value is JSONObject) findKbList(value, depth + 1)?.let { return it }
            if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    (value.opt(i) as? JSONObject)?.let { findKbList(it, depth + 1)?.let { found -> return found } }
                }
            }
        }
        return null
    }

    private fun findStudent(data: JSONObject): JSONObject? {
        for (key in listOf("xsxx", "student", "studentInfo")) {
            data.optJSONObject(key)?.let { return it }
        }
        return null
    }

    private fun first(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }
}
