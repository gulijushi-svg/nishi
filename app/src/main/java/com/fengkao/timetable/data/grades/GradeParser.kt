package com.fengkao.timetable.data.grades

import com.fengkao.timetable.data.TermCalculator
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * 解析 zfn_api 的 `get_grade` 对应接口，以及教务网页中的成绩表格。
 */
object GradeParser {

    fun parse(raw: JSONObject): GradeSnapshot? {
        val data = when (val value = raw.opt("data")) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull() ?: return null
            null -> raw
            else -> raw
        }
        val items = findItems(data) ?: findItems(raw) ?: return null
        val xnm = first(raw, "xnm").toIntOrNull()
            ?: first(data, "xnm").toIntOrNull()
            ?: TermCalculator.autoTerm().xnm
        val term = termOf(first(raw, "xqm", "term").ifBlank { first(data, "xqm", "term") })
        val records = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = first(item, "kcmc", "courseName", "title", "KCMC")
                if (title.isBlank()) continue
                val grade = first(item, "cj", "grade", "CJ", "score")
                val score = listOf("cj", "grade", "score", "CJ").firstNotNullOfOrNull { key ->
                    numberOrNull(first(item, key))
                }
                add(
                    GradeRecord(
                        courseId = first(item, "kch_id", "kch", "course_id", "KCH", "KCH_ID"),
                        title = title,
                        teacher = first(item, "jsxm", "jsxx", "teacher", "JSXM"),
                        className = first(item, "jxbmc", "bjmc", "class_name", "JXBMC"),
                        credit = numberOrNull(first(item, "xf", "credit", "XF")) ?: 0.0,
                        category = first(item, "kclbmc", "kklxmc", "category", "KCLBMC"),
                        nature = first(item, "kcxzmc", "kcxz", "nature", "KCXZMC"),
                        grade = grade,
                        score = score,
                        gradePoint = numberOrNull(first(item, "jd", "gradePoint", "grade_point", "JD")),
                        gradeNature = first(item, "ksxz", "grade_nature", "KSXZ"),
                        college = first(item, "kkbmmc", "college", "KKBMMC"),
                    )
                )
            }
        }
        val student = data.optJSONObject("xsxx")
            ?: data.optJSONObject("student")
            ?: items.optJSONObject(0)
        return GradeSnapshot(
            xnm = xnm,
            term = term,
            fetchedAt = System.currentTimeMillis(),
            studentName = first(student, "xm", "studentName", "name", "XM"),
            records = records,
        ).withFallbackGradePoints()
    }

    /** 解析真实 HTML 表格；找不到成绩表时返回 null，有表头但无课程时返回空快照。 */
    fun parseHtml(html: String, xnm: Int, term: Int): GradeSnapshot? {
        if (html.isBlank()) return null
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val table = document.select("table")
            .mapNotNull { candidate -> parseTable(candidate) }
            .maxWithOrNull(
                compareBy<ParsedGradeTable>({ it.records.size }, { it.header.columns.size })
            ) ?: return null
        return GradeSnapshot(
            xnm = xnm,
            term = termOf(term.toString()),
            fetchedAt = System.currentTimeMillis(),
            records = table.records,
        ).withFallbackGradePoints()
    }

    private fun parseTable(table: Element): ParsedGradeTable? {
        val header = parseHeader(table) ?: return null
        val rows = tableRows(table)
        val records = buildList {
            for (row in rows.drop(header.firstDataRow)) {
                val values = mutableMapOf<GradeColumn, String>()
                var columnIndex = 0
                for (cell in directCells(row)) {
                    header.columns[columnIndex]?.let { column ->
                        val value = cleanText(cell.text())
                        if (value.isNotBlank() || column == GradeColumn.GRADE) {
                            values[column] = value
                        }
                    }
                    columnIndex += span(cell, "colspan")
                }
                val title = values[GradeColumn.TITLE].orEmpty()
                if (title.isBlank() || isSummaryRow(title)) continue
                val grade = values[GradeColumn.GRADE].orEmpty()
                add(
                    GradeRecord(
                        courseId = values[GradeColumn.COURSE_ID].orEmpty(),
                        title = title,
                        teacher = values[GradeColumn.TEACHER].orEmpty(),
                        className = values[GradeColumn.CLASS_NAME].orEmpty(),
                        credit = numberOrNull(values[GradeColumn.CREDIT].orEmpty()) ?: 0.0,
                        category = values[GradeColumn.CATEGORY].orEmpty(),
                        nature = values[GradeColumn.NATURE].orEmpty(),
                        grade = grade,
                        score = numberOrNull(grade),
                        gradePoint = numberOrNull(values[GradeColumn.GRADE_POINT].orEmpty()),
                        gradeNature = values[GradeColumn.GRADE_NATURE].orEmpty(),
                        college = values[GradeColumn.COLLEGE].orEmpty(),
                    )
                )
            }
        }
        return ParsedGradeTable(header, records)
    }

    private fun parseHeader(table: Element): HeaderInfo? {
        val rows = tableRows(table)
        val grid = mutableListOf<MutableList<HeaderMarker?>>()
        var started = false
        var lastHeaderRow = -1

        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex > 5) break
            val cells = directCells(row)
            if (cells.isEmpty()) continue
            val columns = cells.map(::columnOf)
            val headerLike = cells.any { it.tagName().equals("th", ignoreCase = true) } ||
                columns.any { it != null }
            if (!headerLike) {
                if (started) break
                continue
            }
            if (started && gridHasRequiredColumns(grid) && columns.none { it != null }) break

            started = true
            lastHeaderRow = rowIndex
            ensureGridCell(grid, rowIndex, 0)
            var columnIndex = 0
            for ((cellIndex, cell) in cells.withIndex()) {
                while (
                    columnIndex < grid[rowIndex].size &&
                    grid[rowIndex][columnIndex]?.grouped == false
                ) {
                    columnIndex++
                }
                val rowSpan = span(cell, "rowspan")
                val colSpan = span(cell, "colspan")
                for (r in rowIndex until rowIndex + rowSpan) {
                    for (c in columnIndex until columnIndex + colSpan) {
                        ensureGridCell(grid, r, c)
                        val existing = grid[r][c]
                        if (existing == null || existing.grouped) {
                            grid[r][c] = HeaderMarker(columns[cellIndex], grouped = colSpan > 1)
                        }
                    }
                }
                columnIndex += colSpan
            }
        }

        if (!gridHasRequiredColumns(grid)) return null
        val mapped = linkedMapOf<Int, GradeColumn>()
        for (row in grid.asReversed()) {
            row.forEachIndexed { index, marker ->
                val column = marker?.column ?: return@forEachIndexed
                if (index !in mapped) mapped[index] = column
            }
        }
        return HeaderInfo(mapped, lastHeaderRow + 1)
    }

    private fun gridHasRequiredColumns(grid: List<List<HeaderMarker?>>): Boolean {
        val columns = grid.asSequence().flatten().mapNotNull { it?.column }.toSet()
        return GradeColumn.TITLE in columns && GradeColumn.GRADE in columns
    }

    private fun ensureGridCell(grid: MutableList<MutableList<HeaderMarker?>>, row: Int, column: Int) {
        while (grid.size <= row) grid.add(mutableListOf())
        while (grid[row].size <= column) grid[row].add(null)
    }

    private fun columnOf(header: Element): GradeColumn? {
        val text = compactHeader(header.text())
        return when {
            text.startsWith("课程名称") || text.startsWith("课程名") ||
                text in setOf("课程", "科目名称", "名称", "kcmc", "coursename") -> GradeColumn.TITLE
            text in setOf("课程编号", "课程代码", "编号", "kch", "kch_id", "courseid") -> GradeColumn.COURSE_ID
            text in setOf("学分", "课程学分", "xf", "credit") -> GradeColumn.CREDIT
            text in setOf("考试性质", "考试方式", "考核方式", "成绩性质", "ksxz", "gradenature") ->
                GradeColumn.GRADE_NATURE
            text in setOf("绩点", "学分绩点", "课程绩点", "jd", "gradepoint") -> GradeColumn.GRADE_POINT
            text in setOf("成绩", "课程成绩", "分数", "百分制成绩", "cj", "grade", "score") -> GradeColumn.GRADE
            text.startsWith("课程性质") || text.startsWith("必修/选修") ||
                text in setOf("课程属性", "性质", "必修选修", "kcxzmc", "kcxz", "nature") -> GradeColumn.NATURE
            text in setOf("课程类别", "课程分类", "课程类型", "类别", "kclbmc", "kklxmc", "category") ->
                GradeColumn.CATEGORY
            text in setOf("任课教师", "授课教师", "教师姓名", "教师", "jsxm", "teacher") -> GradeColumn.TEACHER
            text in setOf("教学班", "班级名称", "班级", "jxbmc", "bjmc", "classname") -> GradeColumn.CLASS_NAME
            text in setOf("开课学院", "开课院系", "学院", "kkbmmc", "college") -> GradeColumn.COLLEGE
            else -> null
        }
    }

    private fun tableRows(table: Element): List<Element> = table.select("tr").filter { row ->
        row.parents().firstOrNull { it.tagName() == "table" } == table
    }

    private fun directCells(row: Element): List<Element> = row.children().filter {
        it.tagName().equals("th", ignoreCase = true) || it.tagName().equals("td", ignoreCase = true)
    }

    private fun span(cell: Element, attribute: String): Int =
        cell.attr(attribute).toIntOrNull()?.coerceIn(1, 50) ?: 1

    private fun isSummaryRow(title: String): Boolean {
        val text = compactHeader(title)
        return text in setOf("合计", "总计", "平均", "加权平均", "学分合计", "绩点合计", "课程名称", "课程名")
    }

    private fun GradeSnapshot.withFallbackGradePoints(): GradeSnapshot {
        if (records.any { it.gradePoint != null }) return this
        val numericScores = records.mapNotNull { record ->
            val score = record.score?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
            if (isLetterGradeRecord(record)) null else score
        }
        if (numericScores.isEmpty()) return this
        return copy(
            records = records.map { record ->
                val score = record.score
                if (score != null && score in 0.0..100.0 && !isLetterGradeRecord(record)) {
                    val gradePoint = if (score < 60.0) 0.0 else (score - 50.0) / 10.0
                    record.copy(gradePoint = gradePoint)
                } else {
                    record
                }
            },
            usedFallbackGradePoint = true,
        )
    }

    private fun isLetterGradeRecord(record: GradeRecord): Boolean {
        val nature = cleanText(record.gradeNature)
        return listOf("等级制", "五级制", "两级制", "等级考核").any { it in nature }
    }

    private fun findItems(data: JSONObject): JSONArray? {
        for (key in listOf("items", "rows", "data")) {
            data.optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun termOf(raw: String): Int = when (cleanText(raw)) {
        "3", "1" -> 1
        "12", "2" -> 2
        "16", "3" -> 3
        else -> cleanText(raw).toIntOrNull()?.let { if (it > 2) 1 else it } ?: 1
    }

    private fun numberOrNull(value: String): Double? {
        val text = cleanText(value).replace(Regex("\\s+"), "")
        if (text in setOf("", "-", "--", "—", "null", "none", "NaN".lowercase())) return null
        return text.removeSuffix("%").removeSuffix("分")
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }
    }

    private fun first(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (key in keys) {
            val value = stringValue(obj.opt(key))
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        val actualKeys = obj.keys().asSequence().toList()
        for (key in keys) {
            val actualKey = actualKeys.firstOrNull { it.equals(key, ignoreCase = true) } ?: continue
            val value = stringValue(obj.opt(actualKey))
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return ""
    }

    private fun stringValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        else -> cleanText(value.toString())
    }

    private fun cleanText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace("\u200B", "")
        .replace("\uFEFF", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun compactHeader(value: String): String = cleanText(value)
        .replace(Regex("[\\s:：()（）\\[\\]【】*]"), "")
        .lowercase()

    private enum class GradeColumn {
        COURSE_ID, TITLE, TEACHER, CLASS_NAME, CREDIT, CATEGORY, NATURE,
        GRADE, GRADE_POINT, GRADE_NATURE, COLLEGE,
    }

    private data class HeaderMarker(val column: GradeColumn?, val grouped: Boolean = false)
    private data class HeaderInfo(val columns: Map<Int, GradeColumn>, val firstDataRow: Int)
    private data class ParsedGradeTable(val header: HeaderInfo, val records: List<GradeRecord>)
}
