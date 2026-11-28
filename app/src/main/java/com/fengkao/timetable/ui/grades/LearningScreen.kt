package com.fengkao.timetable.ui.grades

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fengkao.timetable.ui.MainViewModel
import com.fengkao.timetable.ui.collectMainPageState
import com.fengkao.timetable.ui.navigation.PageStack
import com.fengkao.timetable.ui.profile.SettingsPage
import com.fengkao.timetable.ui.profile.SettingsTheme
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.BrandGreenSoft
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray

internal enum class LearningPage(val title: String) {
    HOME("学习"),
    OVERVIEW("学业生涯"),
    EXAMS("考试信息"),
    NOTICES("通知消息"),
    SELECTED("已选课程"),
    BLOCKS("板块课"),
    EXPORT("导出 PDF"),
}

@Composable
fun LearningScreen(viewModel: MainViewModel, onNeedLogin: (() -> Unit)? = null) {
    val state = viewModel.collectMainPageState()
    val toolsModel: AcademicToolsViewModel = viewModel()
    val toolsState by toolsModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pageStateHolder = rememberSaveableStateHolder()
    var stack by rememberSaveable { mutableStateOf(listOf(LearningPage.HOME.name)) }
    fun go(next: LearningPage) {
        stack = if (stack.lastOrNull() == next.name) stack else stack + next.name
    }
    fun back() {
        stack = if (stack.size > 1) stack.dropLast(1) else listOf(LearningPage.HOME.name)
    }

    SettingsTheme {
        PageStack(stack, ::back) { key ->
            val page = LearningPage.valueOf(key)
            pageStateHolder.SaveableStateProvider(page.name) {
                SettingsPage(page.title, if (page == LearningPage.HOME) null else ::back) {
                    when (page) {
                        LearningPage.HOME -> {
                            GradeEntryCard {
                                context.startActivity(Intent(context, GradesActivity::class.java))
                            }
                            AcademicToolsScreen(
                                loggedIn = state.loggedIn || state.schedule?.courses.isNullOrEmpty() == false,
                                onLogin = { onNeedLogin?.invoke() ?: viewModel.setTab(3) },
                                onOverview = { go(LearningPage.OVERVIEW) },
                                onExams = { go(LearningPage.EXAMS) },
                                onNotices = { go(LearningPage.NOTICES) },
                                onSelected = { go(LearningPage.SELECTED) },
                                onBlocks = { go(LearningPage.BLOCKS) },
                                onExport = { go(LearningPage.EXPORT) },
                            )
                        }
                        LearningPage.OVERVIEW -> {
                            LaunchedEffect(Unit) { toolsModel.loadOverview() }
                            AcademicOverviewPage(toolsState)
                        }
                        LearningPage.EXAMS -> {
                            LaunchedEffect(Unit) { toolsModel.loadExams() }
                            AcademicExamsPage(toolsState)
                        }
                        LearningPage.NOTICES -> {
                            LaunchedEffect(Unit) { toolsModel.loadNotices() }
                            AcademicNoticesPage(toolsState)
                        }
                        LearningPage.SELECTED -> {
                            LaunchedEffect(Unit) { toolsModel.loadSelected() }
                            AcademicSelectedPage(toolsState)
                        }
                        LearningPage.BLOCKS -> AcademicBlocksPage(
                            state = toolsState,
                            account = state.settings.account,
                            onLoad = toolsModel::loadBlocks,
                            onSelect = { toolsModel.select(it, state.settings.account) },
                        )
                        LearningPage.EXPORT -> AcademicExportPage(
                            state = toolsState,
                            schedule = state.schedule,
                            onExportSchedule = { state.schedule?.let(toolsModel::exportSchedule) },
                            onExportGrades = toolsModel::exportGrades,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeEntryCard(onClick: () -> Unit) {
    Surface(
        color = CardWhite,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(BrandGreenSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.EmojiEvents, null, tint = BrandGreen, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                Text("成绩查询", color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("绩点、学分与加权均分", color = InkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = InkGray)
        }
    }
}
