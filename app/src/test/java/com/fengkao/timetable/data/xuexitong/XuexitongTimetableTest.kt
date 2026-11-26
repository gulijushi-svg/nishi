package com.fengkao.timetable.data.xuexitong

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class XuexitongTimetableTest {
    private lateinit var server: MockWebServer
    private lateinit var api: XuexitongApi

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        api = XuexitongApi(
            passportBase = server.url("/").toString().trimEnd('/'),
            noticeBase = server.url("/").toString().trimEnd('/'),
            scheduleBase = server.url("/").toString().trimEnd('/'),
            cookieJar = XuexitongCookieJar(),
            client = OkHttpClient(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun parsesCurriculumLessonsAndSectionTimes() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":1,"data":{"curriculum":{"schoolYear":"2026-2027","semester":"1","userName":"张同学","className":"计算机1班","firstWeekDate":1788105600000,"currentWeek":2,"maxWeek":18,"lessonTimeConfigArray":["08:10-08:55","09:05-09:50"]},"lessonArray":[{"lessonId":"L1","name":"高等数学","location":"A201","teacherName":"李老师","dayOfWeek":1,"beginNumber":1,"length":2,"weeks":"2"}]}}"""
            )
        )
        val timetable = api.fetchSchedule(2)
        assertEquals("2026-2027", timetable.schoolYear)
        assertEquals("张同学", timetable.userName)
        assertEquals(18, timetable.maxWeek)
        assertEquals(2, timetable.sectionTimes.size)
        assertEquals("高等数学", timetable.lessons.single().name)
        assertEquals(1, timetable.lessons.single().dayOfWeek)
        assertEquals(setOf(2), timetable.lessons.single().weeks)
        assertTrue(server.takeRequest().body.readUtf8().contains("week=2"))
    }
}
