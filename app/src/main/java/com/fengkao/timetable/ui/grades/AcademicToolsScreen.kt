package com.fengkao.timetable.ui.grades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fengkao.timetable.data.grades.AcademicNotice
import com.fengkao.timetable.data.grades.BlockCourse
import com.fengkao.timetable.data.grades.ExamRecord
import com.fengkao.timetable.data.grades.SelectedCourse
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.ui.profile.SettingsHint
import com.fengkao.timetable.ui.profile.SettingsItem
import com.fengkao.timetable.ui.profile.SettingsSection
import com.fengkao.timetable.ui.profile.SettingsValue
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid

/** 教务工具箱首页只做入口，具体内容在独立页面展示。 */
@Composable
internal fun AcademicToolsScreen(
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onOverview: () -> Unit,
    onExams: () -> Unit,
    onNotices: () -> Unit,
    onSelected: () -> Unit,
    onBlocks: () -> Unit,
    onExport: () -> Unit,
) {
    if (!loggedIn) {
        SettingsSection("学习工具") {
            Text(
                "请先在“我的 → 校园服务账户 → 教务系统账户”登录教务系统。登录后课表、成绩、考试、通知和选课信息都使用同一个会话。",
                color = InkMid,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(16.dp),
            )
            TextButton(
                onClick = onLogin,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            ) {
                Text("前往教务登录")
            }
        }
        return
    }
    SettingsSection("学业信息") {
        SettingsItem("学业生涯", Icons.Outlined.School, "GPA、学分与通过课程", divider = true) { onOverview() }
        SettingsItem("考试信息", Icons.Outlined.Event, "考试时间、地点、座位号", divider = true) { onExams() }
        SettingsItem("通知消息", Icons.Outlined.Notifications, "停补换课与教务通知") { onNotices() }
    }
    SettingsSection("选课") {
        SettingsItem("已选课程", Icons.Outlined.Assignment, "查看已选课程", divider = true) { onSelected() }
        SettingsItem("板块课", Icons.Outlined.ShoppingCart, "板块 1 / 2 / 3 与选课") { onBlocks() }
    }
    SettingsSection("导出") {
        SettingsItem("导出 PDF", Icons.Outlined.Description, "课程表与学业成绩单") { onExport() }
    }
    SettingsHint("所有页面共用当前教务登录会话；选课结果以教务系统为准。")
}

@Composable
internal fun AcademicOverviewPage(state: AcademicToolsState) {
    ToolStatus(state)
    state.overview?.let { overview ->
        SettingsSection("学业生涯概览") {
            SettingsValue("GPA", overview.gpa?.let { "%.2f".format(it) } ?: "--")
            SettingsValue("要求学分", overview.requiredCredits?.let { "%.1f".format(it) } ?: "--")
            SettingsValue("获得学分", overview.earnedCredits?.let { "%.1f".format(it) } ?: "--")
            SettingsValue("通过 / 未通过", "${overview.passedCourses ?: "--"} / ${overview.failedCourses ?: "--"}")
        }
        SettingsHint("统计来自教务学业生涯页面；如果学校页面改版，部分字段可能显示 --。")
    }
    if (state.overview == null && state.loading == null) SettingsHint("还没有读取到学业生涯数据")
}

@Composable
internal fun AcademicExamsPage(state: AcademicToolsState) {
    ToolStatus(state)
    if (state.exams.isNotEmpty()) ExamCard(state.exams) else if (state.loading == null) SettingsHint("还没有考试安排")
}

@Composable
internal fun AcademicNoticesPage(state: AcademicToolsState) {
    ToolStatus(state)
    if (state.notices.isNotEmpty()) NoticeCard(state.notices) else if (state.loading == null) SettingsHint("还没有通知消息")
}

@Composable
internal fun AcademicSelectedPage(state: AcademicToolsState) {
    ToolStatus(state)
    if (state.selected.isNotEmpty()) SelectedCard(state.selected)
    else if (state.loading == null) SettingsHint("还没有已选课程")
}

@Composable
internal fun AcademicBlocksPage(
    state: AcademicToolsState,
    account: String,
    onLoad: (Int) -> Unit,
    onSelect: (BlockCourse) -> Unit,
) {
    ToolStatus(state)
    SettingsSection("选择板块") {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { block ->
                OutlinedButton(onClick = { onLoad(block) }, modifier = Modifier.weight(1f)) {
                    Text("板块 $block", color = BrandGreen, fontSize = 12.sp)
                }
            }
        }
    }
    if (state.blocks.isNotEmpty()) BlockCard(state.blocks, account, onSelect)
    else if (state.loading == null) SettingsHint("选择板块后读取可选课程；当前可能不在选课时间。")
}

@Composable
internal fun AcademicExportPage(
    state: AcademicToolsState,
    schedule: CachedSchedule?,
    onExportSchedule: () -> Unit,
    onExportGrades: () -> Unit,
) {
    ToolStatus(state)
    SettingsSection("导出 PDF") {
        SettingsItem("课程表 PDF", Icons.Outlined.CalendarMonth, "保存到下载/泥石", enabled = schedule != null, divider = true) {
            onExportSchedule()
        }
        SettingsItem("学业成绩 PDF", Icons.Outlined.Description, "保存到下载/泥石") { onExportGrades() }
    }
    state.pdfPath?.let { SettingsHint("已保存：$it") }
}

@Composable
private fun ToolStatus(state: AcademicToolsState) {
    state.loading?.let {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        SettingsHint("正在读取$it…")
    }
    state.message?.let { SettingsHint(it, error = state.error) }
}

@Composable
private fun ExamCard(exams: List<ExamRecord>) {
    SettingsSection("考试安排") {
        exams.forEach { exam ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(exam.title, color = InkDark, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("${exam.time}  ${exam.place}  ${exam.seat}", color = InkMid, fontSize = 12.sp)
                Text(listOf(exam.examName, exam.teacher, "${exam.credit} 学分").filter { it.isNotBlank() }.joinToString(" · "),
                    color = InkGray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun NoticeCard(notices: List<AcademicNotice>) {
    SettingsSection("通知消息") {
        notices.take(20).forEach { notice ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(notice.title.ifBlank { notice.type.ifBlank { "教务通知" } }, color = InkDark, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(notice.content, color = InkMid, fontSize = 12.sp, lineHeight = 18.sp)
                Text(notice.time, color = InkGray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SelectedCard(courses: List<SelectedCourse>) {
    SettingsSection("已选课程") {
        courses.forEach { course ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(course.title, color = InkDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(listOf(course.teacher, course.credit + " 学分", course.place).filter { it.isNotBlank() }.joinToString(" · "),
                        color = InkGray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun BlockCard(courses: List<BlockCourse>, account: String, onSelect: (BlockCourse) -> Unit) {
    SettingsSection("板块课") {
        courses.take(30).forEach { course ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(course.title, color = InkDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(listOf(course.teacher, course.credit + " 学分", "${course.selected}/${course.capacity}",
                        course.place, course.time).filter { it.isNotBlank() }.joinToString(" · "),
                        color = InkGray, fontSize = 11.sp)
                }
                OutlinedButton(onClick = { onSelect(course) }) { Text("选课", color = BrandGreen, fontSize = 12.sp) }
            }
        }
    }
}
