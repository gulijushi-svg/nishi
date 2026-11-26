package com.fengkao.timetable.ui.grades

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.local.Crypto
import com.fengkao.timetable.data.grades.GradeRepository
import com.fengkao.timetable.data.grades.GradeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GradeUiState(
    val snapshots: Map<TermSpec, GradeSnapshot> = emptyMap(),
    val selectedTerm: TermSpec = TermCalculator.autoTerm(),
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val offline: Boolean = false,
) {
    val snapshot: GradeSnapshot?
        get() = snapshots[selectedTerm]
}

class GradeViewModel(app: Application) : AndroidViewModel(app) {
    private val container get() = (getApplication() as TimetableApp).container
    private val store get() = container.grades
    private val repository get() = container.gradeRepository
    private val settings get() = container.settings
    private val academicSession get() = container.academicSession

    private val _state = MutableStateFlow(
        GradeUiState(loggedIn = repository.hasSession())
    )
    val state: StateFlow<GradeUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun reload() {
        load()
    }

    fun selectTerm(term: TermSpec) {
        if (_state.value.busy || term == _state.value.selectedTerm) return
        _state.update { it.copy(selectedTerm = term, message = null, error = false) }
        refresh(term)
    }

    fun refresh(term: TermSpec = _state.value.selectedTerm) {
        if (_state.value.busy) return
        _state.update { it.copy(selectedTerm = term, busy = true, message = null, error = false) }
        viewModelScope.launch {
            if (!repository.hasSession() && academicSession.cookieHeader().isNullOrBlank()) {
                val s = settings.first()
                val password = runCatching { Crypto.decrypt(s.passwordEnc) }.getOrNull().orEmpty()
                if (s.account.isNotBlank() && password.isNotBlank()) {
                    repository.ensureSession(s.account, password)
                }
            }
            when (val result = repository.fetchGrades(term)) {
                is GradeRepository.Outcome.Success -> {
                    val snapshot = result.snapshot
                    if (snapshot != null) {
                        withContext(Dispatchers.IO) { store.save(snapshot) }
                        _state.update {
                            it.copy(
                                snapshots = it.snapshots + (term to snapshot),
                                selectedTerm = term,
                                loggedIn = true,
                                busy = false,
                                offline = false,
                                message = null,
                                error = false,
                            )
                        }
                    } else {
                        _state.update { it.copy(busy = false, message = "没有读取到成绩", error = true) }
                    }
                }
                is GradeRepository.Outcome.Failure -> {
                    val hasCache = _state.value.snapshots[term] != null
                    val sessionMissing = result.message.contains("登录")
                    _state.update {
                        it.copy(
                            busy = false,
                            loggedIn = if (sessionMissing) false else it.loggedIn,
                            offline = hasCache,
                            message = if (hasCache) "刷新失败，已显示本地缓存" else result.message,
                            error = true,
                        )
                    }
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                store.terms().associateWith { term -> store.load(term) ?: store.loadLatest() }
                    .filterValues { it != null }
                    .mapNotNull { (term, snapshot) -> snapshot?.let { term to it } }
                    .toMap()
            }
            val selected = cached.keys.filter { term -> cached[term]?.records?.isNotEmpty() == true }
                .maxByOrNull(TermSpec::rank)
                ?: cached.keys.maxByOrNull(TermSpec::rank)
                ?: TermCalculator.autoTerm()
            _state.update {
                it.copy(
                    snapshots = cached,
                    selectedTerm = selected,
                    offline = cached.isNotEmpty(),
                    loggedIn = repository.hasSession(),
                )
            }
            refresh(selected)
        }
    }
}
