package com.fengkao.timetable.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget 刷新入口：读数据 → 渲染推送 → 调度下一分钟闹钟。
 * 任意线程可安全调用 [requestUpdateAll]。
 */
object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestUpdateAll(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { updateAll(app) } }
    }

    suspend fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val snap = WidgetData.load(context)
        pushAll(context, mgr, snap)
        WidgetAlarm.scheduleNext(context, snap)
    }

    private fun pushAll(
        context: Context,
        mgr: AppWidgetManager,
        snap: WidgetSnapshot,
    ) {
        for ((cls, defaultMedium) in listOf(
            Widget2x2Provider::class.java to false,
            Widget4x2Provider::class.java to true,
        )) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls)) ?: continue
            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val minWidth = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
                val maxWidth = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
                val size = WidgetSizing.resolve(minWidth, maxWidth,
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
                    context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    defaultMedium)
                mgr.updateAppWidget(id, WidgetRenderer.render(context, size.medium, snap, size.width, size.height))
            }
        }
    }
}
