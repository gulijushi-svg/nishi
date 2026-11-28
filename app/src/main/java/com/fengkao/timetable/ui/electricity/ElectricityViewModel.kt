package com.fengkao.timetable.ui.electricity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.electricity.ElectricityAccount
import com.fengkao.timetable.data.electricity.ElectricityBindOutcome
import com.fengkao.timetable.data.electricity.ElectricityResult
import com.fengkao.timetable.data.electricity.ElectricityRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ElectricityUiState(
    val rooms: List<ElectricityRoom> = emptyList(),
    val fetchedAt: Long = 0L,
    val threshold: Int = 15,
    val busy: Boolean = false,
    val recoveringAccount: Boolean = false,
    val needsAccount: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
    val offline: Boolean = false,
    val account: String = "",
)

class ElectricityViewModel(application: Application) : AndroidViewModel(application) {
    private val container get() = (getApplication() as TimetableApp).container
    private val _state = MutableStateFlow(ElectricityUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, recoveringAccount = false, message = null, error = false) }
        viewModelScope.launch {
            try {
                val settings = container.settings.first()
                var account = ElectricityAccount.normalize(settings.electricityAccount)
                if (account.isBlank()) {
                    account = ElectricityAccount.normalize(settings.account)
                }
                _state.update { it.copy(threshold = settings.electricityThreshold) }
                if (account.isBlank()) {
                    _state.update { it.copy(recoveringAccount = true) }
                    account = ElectricityAccount.normalize(container.gradeRepository.fetchStudentIdWithSession().orEmpty())
                }
                if (account.isNotBlank()) {
                    container.settings.setElectricityAccount(account)
                }
                _state.update { it.copy(account = account) }
                if (account.isBlank()) {
                    _state.update {
                        it.copy(
                            account = "",
                            busy = false,
                            recoveringAccount = false,
                            needsAccount = true,
                            message = "教务系统未登录，请单独填写电费学号",
                            error = false,
                        )
                    }
                    return@launch
                }
                when (val result = container.electricityRepository.fetch(account)) {
                    is ElectricityResult.Success -> {
                        _state.update {
                            it.copy(
                                rooms = result.snapshot.rooms,
                                fetchedAt = result.snapshot.fetchedAt,
                                busy = false,
                                recoveringAccount = false,
                                needsAccount = false,
                                offline = false,
                                message = null,
                                error = false,
                            )
                        }
                    }
                    ElectricityResult.NotBound -> updateFailure(
                        "未在完美校园绑定宿舍，请先在完美校园 App 里绑定房间",
                        clearRooms = true,
                    )
                    ElectricityResult.RoomNotFound -> updateFailure(
                        "没有查到当前教务账号绑定的房间",
                        clearRooms = true,
                    )
                    ElectricityResult.Unavailable -> updateFailure(
                        null,
                        clearRooms = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateFailure(null, clearRooms = false)
            }
        }
    }

    fun saveAccount(value: String) {
        val account = ElectricityAccount.normalize(value.filter(Char::isDigit))
        if (account.isBlank()) {
            _state.update { it.copy(message = "请输入正确的学号", error = true) }
            return
        }
        viewModelScope.launch {
            container.settings.setElectricityAccount(account)
            _state.update { it.copy(needsAccount = false, message = null, error = false) }
            refresh()
        }
    }

    fun setThreshold(value: Int) {
        val threshold = value.coerceIn(1, 100)
        _state.update { it.copy(threshold = threshold, message = null, error = false) }
        viewModelScope.launch { container.settings.setElectricityThreshold(threshold) }
    }

    fun unbind(room: ElectricityRoom) {
        if (room.roomVerify.isBlank()) {
            _state.update { it.copy(message = "没有找到这个房间的解绑标识，请刷新后重试", error = true) }
            return
        }
        val account = _state.value.account
        if (account.isBlank()) {
            _state.update { it.copy(message = "请先在电费页补录学号后再解绑", error = true) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, error = false) }
        viewModelScope.launch {
            when (val outcome = container.electricityRepository.unbindRoom(account, room.roomVerify)) {
                is ElectricityBindOutcome.Success -> {
                    _state.update {
                        it.copy(
                            rooms = it.rooms.filterNot { item -> item.roomVerify == room.roomVerify },
                            busy = false,
                            message = "已解绑 ${room.roomName}",
                            error = false,
                        )
                    }
                }
                is ElectricityBindOutcome.Failed -> {
                    _state.update { it.copy(busy = false, message = outcome.message, error = true) }
                }
                ElectricityBindOutcome.Unavailable -> {
                    _state.update { it.copy(busy = false, message = "解绑接口暂时不可用，请稍后再试", error = true) }
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val settings = container.settings.first()
            val cached = withContext(Dispatchers.IO) { container.electricityStore.load() }
            _state.update {
                it.copy(
                    rooms = cached?.rooms.orEmpty(),
                    fetchedAt = cached?.fetchedAt ?: 0L,
                    threshold = settings.electricityThreshold,
                    offline = cached != null,
                )
            }
            refresh()
        }
    }

    private fun updateFailure(message: String?, clearRooms: Boolean) {
        _state.update {
            it.copy(
                rooms = if (clearRooms) emptyList() else it.rooms,
                fetchedAt = if (clearRooms) 0L else it.fetchedAt,
                busy = false,
                recoveringAccount = false,
                offline = !clearRooms && it.rooms.isNotEmpty(),
                message = message,
                error = true,
            )
        }
    }
}
