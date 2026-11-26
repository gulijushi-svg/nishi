package com.fengkao.timetable.data

import android.content.Context
import com.fengkao.timetable.data.local.CourseChangeStore
import com.fengkao.timetable.data.local.ScheduleCache
import com.fengkao.timetable.data.local.SettingsStore
import com.fengkao.timetable.data.model.CachedSchedule

/**
 * 兼容层：旧版通过 Chaquopy 调 Python 教务 SDK 的直连同步已移除。
 *
 * 当前默认课表来源是学习通；教务系统只保留 WebView 网页导入。
 * 类保留是为了不破坏旧版数据/后台任务的二进制结构，实际同步由
 * [com.fengkao.timetable.ui.MainViewModel.importXuexitongSchedule] 完成。
 */
class ScheduleRepository(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") settings: SettingsStore,
    @Suppress("UNUSED_PARAMETER") cache: ScheduleCache,
    @Suppress("UNUSED_PARAMETER") changes: CourseChangeStore,
) {
    sealed interface SyncOutcome {
        data class Success(val schedule: CachedSchedule) : SyncOutcome
        data class Failure(val message: String) : SyncOutcome
    }

    suspend fun sync(
        requested: TermSpec? = null,
        onLog: (String) -> Unit = {},
    ): SyncOutcome {
        onLog("旧版教务直连同步已停用")
        return SyncOutcome.Failure("请使用学习通登录后同步课表")
    }
}
