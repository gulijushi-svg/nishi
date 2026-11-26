package com.fengkao.timetable.data.xuexitong

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 全合成通知样本：覆盖待办合并、长期考试与课表异动，不包含真实账号数据。 */
class NoticeExtractFixtureTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.parse("2026-10-23T15:45:00+08:00[Asia/Shanghai]")
        .toInstant().toEpochMilli()

    private fun load(): List<XuexitongNotice> {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("xuexitong-notices.json")) {
            "缺少测试资源 xuexitong-notices.json"
        }.bufferedReader().readText()
        val array = JSONArray(text)
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            val title = o.optString("title")
            val content = o.optString("content")
            XuexitongNotice(
                id = o.optString("id"),
                title = title,
                content = content,
                sender = o.optString("sender"),
                timeText = o.optString("timeText"),
                timeMillis = runCatching {
                    ZonedDateTime.parse(
                        o.optString("timeText").replace(' ', 'T') + "+08:00[Asia/Shanghai]"
                    ).toInstant().toEpochMilli()
                }.getOrDefault(0L),
                category = NoticeFormat.classify(title, content),
                attachmentKind = o.optString("attachmentKind"),
                attachmentName = o.optString("attachmentName"),
                attachmentUrl = o.optString("attachmentUrl"),
            )
        }
    }

    @Test
    fun `13条通知压成6件作业考试任务`() {
        val notices = load()
        assertEquals(13, notices.size)
        val tasks = NoticeExtract.buildTasks(notices, now)
        assertEquals(6, tasks.size)
        assertEquals(4, tasks.count { it.kind == TaskKind.HOMEWORK })
    }

    @Test
    fun `当前仍可做的作业是4件`() {
        val tasks = NoticeExtract.buildTasks(load(), now)
        val open = tasks.filter {
            it.kind == TaskKind.HOMEWORK &&
                (it.status == TaskStatus.OPEN || it.status == TaskStatus.URGENT) &&
                !it.longWindow
        }
        assertEquals(
            listOf("第一章作业", "上机实验作业2", "第五章作业", "第二章作业"),
            open.map { it.title },
        )
    }

    @Test
    fun `两门自主考试被识别为长窗口`() {
        val tasks = NoticeExtract.buildTasks(load(), now)
        val long = tasks.filter { it.longWindow && it.status != TaskStatus.CLOSED }
        assertEquals(setOf("安全与急救", "实验室安全"), long.map { it.title }.toSet())
    }

    @Test
    fun `重复提醒合并后不再重复出现`() {
        val tasks = NoticeExtract.buildTasks(load(), now)
        val titles = tasks.filter { it.kind == TaskKind.HOMEWORK }.map { it.title }
        assertEquals("作业标题不应重复", titles.size, titles.toSet().size)
        assertTrue("应存在被合并的多条提醒", tasks.any { it.reminderCount >= 2 })
    }

    @Test
    fun `合成公告里能抽出5条课表异动`() {
        val changes = NoticeExtract.buildChanges(load())
        assertEquals(5, changes.size)
        assertEquals(2, changes.count { it.applicable })
        assertEquals(1, changes.count { it.kind == ChangeKind.SWAP })
        assertEquals(1, changes.count { it.kind == ChangeKind.CANCEL })
    }

    @Test
    fun `抽出的课表异动与合成样本一致`() {
        val changes = NoticeExtract.buildChanges(load()).associateBy { it.noticeId }

        val db = changes["SYN-CH-001"]!!
        assertEquals(ChangeKind.MOVE, db.kind)
        assertEquals("数据库原理", db.courseName)
        assertEquals("2026-09-23", db.toDate.toString())
        assertEquals(3, db.toStartPeriod)
        assertEquals(5, db.toEndPeriod)
        assertEquals("示例楼A101", db.place)

        val se = changes["SYN-CH-002"]!!
        assertEquals(ChangeKind.MOVE, se.kind)
        assertEquals("软件工程", se.courseName)
        assertEquals("2026-10-17", se.toDate.toString())

        val swap = changes["SYN-CH-003"]!!
        assertEquals(ChangeKind.SWAP, swap.kind)
        assertTrue(!swap.applicable)

        val cancel = changes["SYN-CH-004"]!!
        assertEquals(ChangeKind.CANCEL, cancel.kind)
        assertTrue(!cancel.applicable)
    }
}
