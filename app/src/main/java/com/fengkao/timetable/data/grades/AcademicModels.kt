package com.fengkao.timetable.data.grades

/** 教务工具箱使用的轻量数据模型，字段名称尽量保持与正方接口一致。 */
data class ExamRecord(
    val title: String,
    val time: String,
    val place: String,
    val seat: String,
    val teacher: String,
    val examName: String,
    val credit: String,
)

data class AcademicNotice(
    val title: String,
    val content: String,
    val type: String,
    val time: String,
)

data class SelectedCourse(
    val courseId: String,
    val classId: String,
    val doId: String,
    val title: String,
    val teacher: String,
    val credit: String,
    val category: String,
    val place: String,
    val time: String,
)

data class BlockCourse(
    val courseId: String,
    val classId: String,
    val doId: String,
    val title: String,
    val teacher: String,
    val credit: String,
    val capacity: String,
    val selected: String,
    val place: String,
    val time: String,
    val kklxdm: String,
)

data class AcademicOverview(
    val gpa: Double?,
    val requiredCredits: Double?,
    val earnedCredits: Double?,
    val passedCourses: Int?,
    val failedCourses: Int?,
    val rawText: String,
)
