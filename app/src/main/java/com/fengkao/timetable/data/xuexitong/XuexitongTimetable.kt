package com.fengkao.timetable.data.xuexitong

import com.fengkao.timetable.data.model.PeriodTime

/** 学习通课程表接口返回的课程与学校节次表。 */
data class XuexitongTimetable(
    val schoolYear: String = "",
    val semester: String = "",
    val userName: String = "",
    val className: String = "",
    val firstWeekDate: Long = 0L,
    val currentWeek: Int = 0,
    val maxWeek: Int = 20,
    val sectionTimes: List<PeriodTime> = emptyList(),
    val lessons: List<XuexitongLesson> = emptyList(),
)

data class XuexitongLesson(
    val lessonId: String = "",
    val name: String = "",
    val location: String = "",
    val teacher: String = "",
    val dayOfWeek: Int = 1,
    val beginNumber: Int = 1,
    val length: Int = 1,
    /** 全学期合并后的周次；单周接口返回当前周。 */
    val weeks: Set<Int> = emptySet(),
)

/** [1,2,3,5,8,9] → "1-3,5,8-9"；课表 UI 的 weeksText 使用。 */
fun compressWeeks(weeks: Collection<Int>): String {
    val values = weeks.filter { it > 0 }.distinct().sorted()
    if (values.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = values.first()
    var previous = start
    for (value in values.drop(1)) {
        if (value == previous + 1) {
            previous = value
            continue
        }
        parts += if (start == previous) "$start" else "$start-$previous"
        start = value
        previous = value
    }
    parts += if (start == previous) "$start" else "$start-$previous"
    return parts.joinToString(",")
}
