package com.fengkao.timetable.data.grades

import com.fengkao.timetable.data.TermSpec
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradeParserTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun parsesZfnGradeResponse() {
        val item = JSONObject()
            .put("kch_id", "DEMO-CS101")
            .put("kcmc", "程序设计基础")
            .put("jsxm", "示例教师")
            .put("jxbmc", "测试班 1")
            .put("xf", "3.0")
            .put("kclbmc", "专业必修")
            .put("kcxzmc", "必修")
            .put("cj", "92")
            .put("jd", "4.2")
            .put("ksxz", "考试")
            .put("kkbmmc", "示例学院")
        val data = JSONObject().put("items", JSONArray().put(item))
        val raw = JSONObject()
            .put("xnm", "2026")
            .put("xqm", "3")
            .put("data", data.toString())

        val snapshot = GradeParser.parse(raw)!!
        val record = snapshot.records.single()

        assertEquals(2026, snapshot.xnm)
        assertEquals(1, snapshot.term)
        assertEquals("程序设计基础", record.title)
        assertNear(92.0, record.score)
        assertNear(4.2, record.gradePoint)
        assertNear(4.2, snapshot.gpa())
        assertNear(92.0, snapshot.weightedAverage())
        assertNear(3.0, snapshot.totalCredits())
        assertFalse(snapshot.usedFallbackGradePoint)
    }

    @Test fun emptyItemsReturnAnEmptySnapshot() {
        val raw = JSONObject()
            .put("xnm", 2026)
            .put("xqm", 12)
            .put("data", JSONObject().put("items", JSONArray()))

        val snapshot = GradeParser.parse(raw)!!

        assertEquals(2026, snapshot.xnm)
        assertEquals(2, snapshot.term)
        assertTrue(snapshot.records.isEmpty())
        assertEquals(0, snapshot.totalCount())
        assertNull(snapshot.weightedAverage())
        assertNull(snapshot.gpa())
    }

    @Test fun officialZeroIsDistinctFromMissingAndPreventsFallback() {
        val items = JSONArray()
            .put(JSONObject().put("kcmc", "零绩点样例").put("xf", 2).put("cj", 55).put("jd", 0))
            .put(JSONObject().put("kcmc", "缺失绩点样例").put("xf", 2).put("cj", 60))

        val snapshot = GradeParser.parse(JSONObject().put("items", items))!!

        assertNear(0.0, snapshot.records[0].gradePoint)
        assertNull(snapshot.records[1].gradePoint)
        assertFalse(snapshot.usedFallbackGradePoint)
        assertNear(0.0, snapshot.gpa())
        assertNear(57.5, snapshot.weightedAverage())
        assertEquals(1, snapshot.passedCount())
    }

    @Test fun htmlMapsAliasesNestedCellsAndUnpublishedGrades() {
        val snapshot = GradeParser.parseHtml(fixture("zfn_grades.html"), 2026, 1)!!
        val first = snapshot.records[0]

        assertEquals(4, snapshot.records.size)
        assertEquals("DEMO-01", first.courseId)
        assertEquals("示例课程 A", first.title)
        assertEquals("示例教师", first.teacher)
        assertEquals("测试班 1", first.className)
        assertNear(3.0, first.credit)
        assertEquals("专业基础", first.category)
        assertEquals("必 修", first.nature)
        assertEquals("92", first.grade)
        assertNear(92.0, first.score)
        assertNear(4.2, first.gradePoint)
        assertEquals("正常考试", first.gradeNature)
        assertEquals("示例学院", first.college)

        assertNear(0.0, snapshot.records[1].gradePoint)
        assertEquals("补考", snapshot.records[1].gradeNature)
        assertEquals("", snapshot.records[2].grade)
        assertNull(snapshot.records[2].score)
        assertNull(snapshot.records[2].gradePoint)
        assertEquals("缓考", snapshot.records[2].gradeNature)
        assertEquals("未录入", snapshot.records[3].grade)
        assertNull(snapshot.records[3].score)
        assertFalse(snapshot.usedFallbackGradePoint)
        assertEquals(1, snapshot.passedCount())
    }

    @Test fun htmlFallbackUsesFormulaAndNeverConvertsLetterGrades() {
        val snapshot = GradeParser.parseHtml(fixture("fallback_grades.html"), 2026, 2)!!

        assertTrue(snapshot.usedFallbackGradePoint)
        assertEquals(6, snapshot.records.size)
        val expected = listOf(0.0, 0.0, 1.0, 4.5, 5.0)
        expected.forEachIndexed { index, gradePoint ->
            assertNear(gradePoint, snapshot.records[index].gradePoint)
        }
        assertNull(snapshot.records.last().gradePoint)
        assertNear(72.8, snapshot.weightedAverage())
        assertNear(2.1, snapshot.gpa())
    }

    @Test fun letterGradesWithoutOfficialPointsAreNotInvented() {
        val snapshot = GradeParser.parseHtml(fixture("letter_grades.html"), 2026, 1)!!

        assertFalse(snapshot.usedFallbackGradePoint)
        assertTrue(snapshot.records.all { it.gradePoint == null })
        assertTrue(snapshot.records.all { it.score == null })
        assertNull(snapshot.weightedAverage())
        assertNull(snapshot.gpa())
        assertEquals(5, snapshot.passedCount())
        assertEquals(6, snapshot.totalCount())
    }

    @Test fun htmlTableWithoutCourseRowsReturnsAnEmptySnapshot() {
        val snapshot = GradeParser.parseHtml(fixture("empty_grades.html"), 2026, 1)!!

        assertTrue(snapshot.records.isEmpty())
        assertFalse(snapshot.usedFallbackGradePoint)
        assertNull(GradeParser.parseHtml("<html><body>登录页面</body></html>", 2026, 1))
    }

    @Test fun weightedAverageGpaAndPassCountsUseOnlyEligibleRecords() {
        val snapshot = GradeSnapshot(
            records = listOf(
                GradeRecord(title = "数值优秀", credit = 3.0, grade = "90", score = 90.0, gradePoint = 4.0),
                GradeRecord(title = "数值及格", credit = 2.0, grade = "60", score = 60.0, gradePoint = 1.0),
                GradeRecord(title = "数值不及格", credit = 1.0, grade = "55", score = 55.0, gradePoint = 0.0),
                GradeRecord(title = "等级优秀", credit = 1.0, grade = "优秀"),
                GradeRecord(title = "未公布", credit = 4.0),
                GradeRecord(title = "缓考", credit = 1.0, grade = "缓考"),
                GradeRecord(title = "零学分", credit = 0.0, grade = "99", score = 99.0, gradePoint = 4.0),
            )
        )

        assertNear(445.0 / 6.0, snapshot.weightedAverage())
        assertNear(14.0 / 6.0, snapshot.gpa())
        assertNear(12.0, snapshot.totalCredits())
        assertEquals(4, snapshot.passedCount())
        assertEquals(7, snapshot.totalCount())
    }

    @Test fun jsonWithoutAnyOfficialPointAlsoUsesFallback() {
        val items = JSONArray()
            .put(JSONObject().put("kcmc", "JSON 回算样例").put("xf", 1).put("cj", "85.5"))

        val snapshot = GradeParser.parse(JSONObject().put("items", items))!!

        assertTrue(snapshot.usedFallbackGradePoint)
        assertNear(3.55, snapshot.records.single().gradePoint)
    }

    @Test fun storeKeepsSemestersAndLatestSeparately() {
        val store = GradeStore(folder.root)
        val current = snapshot(2026, 1, "最新学期", 200L)
        val previous = snapshot(2025, 2, "旧学期", 100L)

        assertTrue(store.save(current))
        assertFalse(store.save(previous))
        assertEquals(current, store.loadLatest())
        assertEquals(current, store.load())
        assertEquals(previous, store.load(TermSpec(2025, 2)))
        assertEquals(listOf(TermSpec(2025, 2), TermSpec(2026, 1)), store.terms())
        assertTrue(File(folder.root, "grades/2026_1.json").isFile)
        assertTrue(File(folder.root, "grades/2025_2.json").isFile)
        assertTrue(folder.root.walkTopDown().none { it.isFile && it.name.endsWith(".tmp") })
        assertEquals(current, GradeStore(folder.root).loadLatest())

        store.clear()
        assertNull(store.loadLatest())
        assertTrue(store.terms().isEmpty())
        assertFalse(File(folder.root, "grades").exists())
    }

    @Test fun storeMigratesLegacySingleFileBySemester() {
        val legacy = snapshot(2025, 2, "旧版缓存", 100L)
        File(folder.root, "grades_cache.json").writeText(
            Json.encodeToString(GradeSnapshot.serializer(), legacy),
            Charsets.UTF_8,
        )
        val store = GradeStore(folder.root)

        assertEquals(legacy, store.load(TermSpec(2025, 2)))
        assertEquals(legacy, store.loadLatest())
        assertEquals(listOf(TermSpec(2025, 2)), store.terms())
        assertTrue(File(folder.root, "grades/2025_2.json").isFile)
    }

    private fun snapshot(xnm: Int, term: Int, title: String, fetchedAt: Long) = GradeSnapshot(
        xnm = xnm,
        term = term,
        fetchedAt = fetchedAt,
        records = listOf(
            GradeRecord(title = title, credit = 1.0, grade = "0", score = 0.0, gradePoint = 0.0)
        ),
    )

    private fun fixture(name: String): String = checkNotNull(
        javaClass.getResourceAsStream("/grades/$name")
    ) { "Missing grade fixture: $name" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun assertNear(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, 0.0001)
    }
}
