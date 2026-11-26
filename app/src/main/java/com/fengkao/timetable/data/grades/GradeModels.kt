package com.fengkao.timetable.data.grades

import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class GradeRecord(
    val courseId: String = "",
    val title: String = "",
    val teacher: String = "",
    val className: String = "",
    val credit: Double = 0.0,
    val category: String = "",
    val nature: String = "",
    val grade: String = "",
    val score: Double? = null,
    val gradePoint: Double? = null,
    val gradeNature: String = "",
    val college: String = "",
)

@Serializable
data class GradeSnapshot(
    val xnm: Int = 0,
    val term: Int = 1,
    val fetchedAt: Long = 0L,
    val studentName: String = "",
    val records: List<GradeRecord> = emptyList(),
    val usedFallbackGradePoint: Boolean = false,
) {
    fun totalCredits(): Double = records.sumOf { it.credit }

    /** 只统计有数值成绩和正学分的课程；等级制及未公布成绩不参与均分。 */
    fun weightedAverage(): Double? {
        val scored = records.mapNotNull { record ->
            val score = record.score ?: return@mapNotNull null
            if (record.credit > 0) record.credit to score else null
        }
        val credits = scored.sumOf { it.first }
        if (credits <= 0) return null
        return scored.sumOf { it.first * it.second } / credits
    }

    /** 官方绩点为 0 也参与加权，缺失绩点则不参与。 */
    fun gpa(): Double? {
        val graded = records.mapNotNull { record ->
            val gradePoint = record.gradePoint ?: return@mapNotNull null
            if (record.credit > 0) record.credit to gradePoint else null
        }
        val credits = graded.sumOf { it.first }
        if (credits <= 0) return null
        return graded.sumOf { it.first * it.second } / credits
    }

    fun passedCount(): Int = records.count { record ->
        val numericScore = record.score ?: record.grade.toNumericScore()
        when {
            numericScore != null && numericScore >= 60 -> true
            normalizeGrade(record.grade) in PASSED_GRADES -> true
            else -> false
        }
    }

    fun totalCount(): Int = records.size

    fun termLabel(): String = if (xnm > 0) "$xnm-${xnm + 1} 学年第 $term 学期" else "当前学期"
}

private val PASSED_GRADES = setOf("优秀", "良好", "中等", "及格", "通过")

private fun normalizeGrade(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("\\s+"), "")
    .trim()

private fun String.toNumericScore(): Double? = normalizeGrade(this)
    .removeSuffix("分")
    .toDoubleOrNull()
    ?.takeIf { it.isFinite() }
