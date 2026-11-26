package com.fengkao.timetable.data.schedule

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WebScheduleParserTest {
    @Test fun parsesZfNewKbList() {
        val kb = JSONObject()
            .put("kcmc", "高等数学（上）")
            .put("cdmc", "明理楼 A-201")
            .put("xm", "陈立群")
            .put("xqj", "1")
            .put("jc", "1-2")
            .put("zcd", "1-16周")
            .put("xqmc", "东校区")
        val data = JSONObject()
            .put("xsxx", JSONObject()
                .put("XM", "演示同学")
                .put("XH_ID", "000000000000")
                .put("BJMC", "24计算机科学与技术1班"))
            .put("kbList", JSONArray().put(kb))
        val raw = JSONObject()
            .put("ok", true)
            .put("xnm", "2026")
            .put("xqm", "3")
            .put("data", data.toString())

        val schedule = WebScheduleParser.parse(raw)!!

        assertEquals(2026, schedule.xnm)
        assertEquals(1, schedule.term)
        assertEquals("000000000000", schedule.student.studentId)
        assertEquals(1, schedule.courses.size)
        assertEquals("高等数学（上）", schedule.courses[0].name)
        assertEquals(1, schedule.courses[0].startPeriod)
        assertEquals(2, schedule.courses[0].endPeriod)
        assertEquals((1..16).toList(), schedule.courses[0].weeks)
    }
}
