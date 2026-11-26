package com.fengkao.timetable

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fengkao.timetable.data.ScheduleRepository
import com.fengkao.timetable.data.local.Crypto
import com.fengkao.timetable.data.local.ScheduleCache
import com.fengkao.timetable.data.local.CourseChangeStore
import com.fengkao.timetable.data.local.CourseNoteStore
import com.fengkao.timetable.data.local.SettingsStore
import com.fengkao.timetable.data.grades.GradeRepository
import com.fengkao.timetable.data.grades.GradeStore
import com.fengkao.timetable.data.grades.AcademicSessionStore
import com.fengkao.timetable.data.electricity.ElectricityApi
import com.fengkao.timetable.data.electricity.ElectricityAlertScheduler
import com.fengkao.timetable.data.electricity.ElectricityNotifier
import com.fengkao.timetable.data.electricity.ElectricityRepository
import com.fengkao.timetable.data.electricity.ElectricityStore
import com.fengkao.timetable.data.xuexitong.XuexitongRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Duration

/** 手工依赖容器（规模小，不引入 Hilt） */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val settings = SettingsStore(appContext)
    val cache = ScheduleCache(appContext)
    val changes = CourseChangeStore(appContext)
    val notes = CourseNoteStore(appContext)
    val grades = GradeStore(appContext)
    val electricityStore = ElectricityStore(appContext)
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    val electricityRepository = ElectricityRepository(
        appContext,
        api = ElectricityApi(httpClient),
        store = electricityStore,
    )
    val academicSession = AcademicSessionStore(appContext, settings)
    val gradeRepository = GradeRepository(appContext, httpClient, academicSession)
    val repository = ScheduleRepository(appContext, settings, cache, changes)
    val xuexitong = XuexitongRepository(appContext)
    // 展示本进程内最近的后台同步结果，不改变原任务或持久化结构。
    val backgroundSyncError = MutableStateFlow<String?>(null)
}

class TimetableApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 进程刚起来时把笔记提醒排上：闹钟被系统清掉（强停/重启）后也能恢复
        // 读的是几十条的小 JSON，仍放后台线程，避免拖慢冷启动首帧
        Thread { runCatching { com.fengkao.timetable.notify.NoteReminders.reschedule(this) } }.start()
        ElectricityNotifier.ensureChannel(this)
        Thread { runCatching { ElectricityAlertScheduler.ensure(this) } }.start()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 深浅模式切换：玻璃底图按新配置重绘（进程存活时立即生效）
        com.fengkao.timetable.widget.WidgetUpdater.requestUpdateAll(this)
    }
}

/** 周期性后台同步：登录态有效时自动拉取最新课表并更新本地缓存 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TimetableApp ?: return Result.failure()
        val container = app.container
        val s = runCatching { container.settings.first() }.getOrNull()
            ?: return Result.retry()
        if (!s.autoSync || s.account.isBlank() || s.passwordEnc.isBlank()) {
            return Result.success()
        }
        val password = runCatching { Crypto.decrypt(s.passwordEnc) }.getOrNull()
            ?: return Result.success()
        return when (val outcome = container.gradeRepository.loginAndFetch(s.account, password, includeSchedule = true)) {
            is com.fengkao.timetable.data.grades.GradeRepository.Outcome.Success -> {
                val schedule = outcome.schedule
                if (schedule == null) {
                    container.backgroundSyncError.value = "自动同步没有读取到课表"
                    Result.retry()
                } else {
                    container.cache.save(schedule)
                    outcome.snapshot?.let { container.grades.save(it) }
                    container.settings.setLastSync(schedule.xnm, schedule.term, System.currentTimeMillis())
                    container.backgroundSyncError.value = null
                    Result.success()
                }
            }
            is com.fengkao.timetable.data.grades.GradeRepository.Outcome.Failure -> {
                container.backgroundSyncError.value = outcome.message
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        }
    }
}

object SyncScheduler {
    const val WORK_NAME = "timetable_periodic_sync"
    const val PERIOD_MINUTES = 30L

    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofMinutes(PERIOD_MINUTES))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
