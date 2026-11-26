package com.fengkao.timetable.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.local.DEFAULT_BASE_URL
import com.fengkao.timetable.data.model.*
import com.fengkao.timetable.data.wash.WashDevice
import com.fengkao.timetable.data.wash.WashGoodsDetail
import com.fengkao.timetable.data.wash.WashSku
import com.fengkao.timetable.data.water.WaterDevice
import com.fengkao.timetable.ui.*
import com.fengkao.timetable.ui.login.LoginScreen
import com.fengkao.timetable.ui.theme.TimetableTheme
import com.fengkao.timetable.ui.water.*
import com.fengkao.timetable.ui.wash.*
import com.fengkao.timetable.ui.welcome.WelcomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Only compiled into the offline .demo app. Uses the production screens with fictional state. */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val container = (application as TimetableApp).container
            val term = TermCalculator.autoTerm()
            val start = TermCalculator.today().minusWeeks(6).with(java.time.DayOfWeek.MONDAY)
            val courses = DemoFixtures.courses
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                container.settings.setAutoSync(false)
                container.settings.saveCredentials("DEMO-STUDENT", null, DEFAULT_BASE_URL, false)
                container.settings.completeWelcome()
                container.settings.setTermStartEpochDay(start.toEpochDay(), term)
                container.settings.setLastSync(term.xnm, term.term, now)
                container.settings.setTermWeeks(18)
                container.cache.save(CachedSchedule(term.xnm, term.term, now,
                    StudentInfo("演示同学", "DEMO-STUDENT", "24计算机科学与技术1班",
                        "电子信息与人工智能学院", "计算机科学与技术", "2026"), courses))
                container.changes.save(listOf(
                    CourseChange(ChangeType.CHANGED, "数据结构", "本周四 3–5 节 · 明科楼 512 → 周五 3–4 节", now),
                    CourseChange(ChangeType.ADDED, "中国传统文化", "周四 8–9 节 · 图书馆 报告厅", now),
                ))
                container.settings.setCourseMeta("数据结构", CourseMeta(CourseType.MAJOR, 6.0, true))
                container.settings.setCourseMeta("高等数学（上）", CourseMeta(CourseType.MAJOR, 6.0, true))
                container.settings.setCourseMeta("程序设计基础实验", CourseMeta(CourseType.EXP, 2.0))
                container.settings.setCourseMeta("大学体育（三）", CourseMeta(CourseType.PUB, 1.0))
                container.settings.setTuitionFee(5000)
                container.settings.setBusyBlocks(listOf(
                    BusyBlock(1, "班级例会", 9, 1280, 1350),
                    BusyBlock(3, "程序设计竞赛集训", 10, 1140, 1290),
                ))
                // 学习通通知样例：待办 / 课表异动 / 原文三层都有内容可看。
                // 直接写 store 而不改仓库：release 变体里不存在任何 demo 注入入口。
                val notices = DemoFixtures.notices(now)
                com.fengkao.timetable.data.xuexitong.XuexitongStore(this@DemoActivity).apply {
                    saveLogin("DEMO-STUDENT", "", "[]")
                    saveNotices(notices)
                    setLastFetchAt(now - 3 * 60_000L)
                    setBaselineDone()
                }
                // 课程笔记：作业 / 笔记 / 考点三类各一条，含两条提醒。
                // 先清空再写，否则每次启动都会多出一批新 UUID 的重复笔记。
                container.notes.all().forEach { container.notes.delete(it.id) }
                DemoFixtures.notes(TermCalculator.today(), now).forEach { container.notes.upsert(it) }
            }
            val provider = ViewModelProvider(this@DemoActivity)
            val main = provider[MainViewModel::class.java]
            val water = provider[WaterViewModel::class.java]
            val wash = provider[WashViewModel::class.java]
            main.state.first { it.bootstrapped }
            val screen = intent.getStringExtra("screen").orEmpty()
            setState(water, WaterUiState(loggedIn = screen != "water-login", configured = true, phone = "13800001234",
                devices = listOf(WaterDevice("DEMO-WATER-01", "示例饮水机 · 教学楼一层"), WaterDevice("DEMO-WATER-02", "示例饮水机 · 图书馆")),
                selectedId = "DEMO-WATER-01"))
            setState(wash, WashUiState(loggedIn = screen != "wash-login", phone = "13800001234",
                devices = listOf(
                    WashDevice(101, "洗衣机 01 · 滚筒", 1, "东校区 · 3号公寓洗衣房", 10),
                    WashDevice(102, "洗衣机 02 · 滚筒", 1, "东校区 · 3号公寓洗衣房", 20),
                )))
            // Optional screenshot states stay inside the permission-free demo build.
            when (intent.getStringExtra("serviceState")) {
                "single" -> {
                    setState(water, water.state.value.copy(devices = water.state.value.devices.take(1)))
                    setState(wash, wash.state.value.copy(devices = wash.state.value.devices.take(1)))
                }
                "empty" -> {
                    setState(water, water.state.value.copy(devices = emptyList(), selectedId = ""))
                    setState(wash, wash.state.value.copy(devices = emptyList()))
                }
                "busy" -> {
                    setState(water, water.state.value.copy(busy = true))
                    setState(wash, wash.state.value.copy(busy = true))
                }
                "error" -> {
                    setState(water, water.state.value.copy(refreshFailed = true, error = true, message = "设备更新失败，请重试"))
                    setState(wash, wash.state.value.copy(error = true, message = "设备更新失败，请重试"))
                }
                "started" -> setState(water, water.state.value.copy(startSeconds = 8))
                "detail" -> setState(wash, wash.state.value.copy(
                    detail = WashGoodsDetail(
                        goodsId = 101,
                        name = "洗衣机 01 · 滚筒",
                        orgId = 1,
                        orgName = "东校区 · 3号公寓洗衣房",
                    ),
                    skus = listOf(
                        WashSku(skuId = 1, name = "标准洗", feature = "约 35 分钟", price = "3.00", unit = "次"),
                        WashSku(skuId = 2, name = "大件洗", feature = "约 50 分钟", price = "5.00", unit = "次"),
                    ),
                ))
            }
            main.setTab(when (screen) {
                "water", "water-login", "wash", "wash-login", "electricity" -> 1
                "learning", "grades" -> 2
                "profile" -> 3
                else -> 0
            })
            com.fengkao.timetable.ui.service.ServiceSelection.index = when {
                screen.startsWith("wash") -> 1
                screen == "electricity" -> 2
                else -> 0
            }
            if (screen == "welcome") {
                container.settings.restartWelcome()
                container.settings.setWelcomeStep(intent.getIntExtra("step", 0))
                val settings = container.settings.first()
                setState(main, main.state.value.copy(settings = settings))
            }
            setContent {
                TimetableTheme {
                    when (screen) {
                        "welcome" -> WelcomeScreen(main)
                        "login" -> LoginScreen(main)
                        else -> MainScaffold(main)
                    }
                }
            }
        }
    }

    // Fixture injection lives exclusively in this build type; release contains no demo hooks.
    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(model: Any, value: T) {
        val field = model.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(model) as MutableStateFlow<T>).value = value
    }
}
