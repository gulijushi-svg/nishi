package com.fengkao.timetable.ui.grades

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.local.Crypto
import com.fengkao.timetable.data.grades.AcademicNotice
import com.fengkao.timetable.data.grades.AcademicOverview
import com.fengkao.timetable.data.grades.AcademicPdfExporter
import com.fengkao.timetable.data.grades.BlockCourse
import com.fengkao.timetable.data.grades.ExamRecord
import com.fengkao.timetable.data.grades.GradeRepository
import com.fengkao.timetable.data.grades.SelectedCourse
import com.fengkao.timetable.data.model.CachedSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AcademicToolsState(
    val exams: List<ExamRecord> = emptyList(),
    val notices: List<AcademicNotice> = emptyList(),
    val selected: List<SelectedCourse> = emptyList(),
    val blocks: List<BlockCourse> = emptyList(),
    val overview: AcademicOverview? = null,
    val loading: String? = null,
    val message: String? = null,
    val error: Boolean = false,
    val pdfPath: String? = null,
)

class AcademicToolsViewModel(app: Application) : AndroidViewModel(app) {
    private val container get() = (getApplication() as TimetableApp).container
    private val repository get() = container.gradeRepository
    private val _state = MutableStateFlow(AcademicToolsState())
    val state: StateFlow<AcademicToolsState> = _state.asStateFlow()

    fun loadExams() = request("考试信息", { repository.fetchExams() }) {
        _state.update { state -> state.copy(exams = it) }
    }

    fun loadNotices() = request("通知消息", { repository.fetchNotifications() }) {
        _state.update { state -> state.copy(notices = it) }
    }

    fun loadSelected() = request("已选课程", { repository.fetchSelectedCourses() }) {
        _state.update { state -> state.copy(selected = it) }
    }

    fun loadBlocks(block: Int) = request("板块课 $block", { repository.fetchBlockCourses(block) }) {
        _state.update { state -> state.copy(blocks = it) }
    }

    fun loadOverview() = request("学业生涯", { repository.fetchAcademiaOverview() }) {
        _state.update { state -> state.copy(overview = it) }
    }

    fun select(course: BlockCourse, studentId: String) = request("选课", {
        repository.selectCourse(course, studentId)
    }) { message ->
        _state.update { it.copy(message = message) }
    }

    fun drop(course: SelectedCourse) = request("退课", { repository.dropCourse(course) }) { message ->
        _state.update {
            it.copy(
                selected = it.selected.filterNot { c -> c.doId == course.doId },
                message = message,
            )
        }
    }

    fun exportSchedule(schedule: CachedSchedule) {
        val path = runCatching {
            AcademicPdfExporter.schedule(getApplication(), schedule)
        }.getOrElse { fail(it.message ?: "导出课表失败"); return }
        _state.update { it.copy(pdfPath = path, message = "课表 PDF 已保存到下载目录") }
    }

    fun exportGrades() {
        val snapshot = container.grades.load() ?: run {
            fail("还没有成绩，无法导出")
            return
        }
        val path = runCatching {
            AcademicPdfExporter.grades(getApplication(), snapshot)
        }.getOrElse { fail(it.message ?: "导出成绩单失败"); return }
        _state.update { it.copy(pdfPath = path, message = "成绩单 PDF 已保存到下载目录") }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = null, message = message, error = true) }
    }

    private fun <T> request(
        label: String,
        block: suspend () -> GradeRepository.ApiResult<T>,
        onOk: (T) -> Unit,
    ) {
        if (_state.value.loading != null) return
        _state.update { it.copy(loading = label, message = null, error = false) }
        viewModelScope.launch {
            if (!repository.hasSession()) {
                val settings = container.settings.first()
                val password = runCatching { Crypto.decrypt(settings.passwordEnc) }.getOrNull().orEmpty()
                when (val restored = repository.ensureSession(settings.account, password)) {
                    is GradeRepository.ApiResult.Err -> {
                        fail(restored.message)
                        return@launch
                    }
                    is GradeRepository.ApiResult.Ok -> Unit
                }
            }
            when (val result = runCatching { block() }.getOrElse {
                fail(it.message ?: "读取失败")
                return@launch
            }) {
                is GradeRepository.ApiResult.Ok -> {
                    onOk(result.value)
                    _state.update { it.copy(loading = null) }
                }
                is GradeRepository.ApiResult.Err -> fail(result.message)
            }
        }
    }
}
