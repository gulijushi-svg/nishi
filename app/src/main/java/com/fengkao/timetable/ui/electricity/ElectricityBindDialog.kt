package com.fengkao.timetable.ui.electricity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.electricity.ElectricityArea
import com.fengkao.timetable.data.electricity.ElectricityBindOutcome
import kotlinx.coroutines.launch

@Composable
fun ElectricityBindDialog(
    account: String,
    onDismiss: () -> Unit,
    onBound: () -> Unit,
) {
    val context = LocalContext.current
    val repository = (context.applicationContext as TimetableApp).container.electricityRepository
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var zones by remember { mutableStateOf<List<ElectricityArea>>(emptyList()) }
    var builds by remember { mutableStateOf<List<ElectricityArea>>(emptyList()) }
    var floors by remember { mutableStateOf<List<ElectricityArea>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<ElectricityArea>>(emptyList()) }
    var zone by remember { mutableStateOf<ElectricityArea?>(null) }
    var build by remember { mutableStateOf<ElectricityArea?>(null) }
    var floor by remember { mutableStateOf<ElectricityArea?>(null) }
    var room by remember { mutableStateOf<ElectricityArea?>(null) }
    var accountInput by rememberSaveable { mutableStateOf(account) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun fail(message: String) {
        busy = false
        error = message
    }

    LaunchedEffect(account) {
        if (account.isBlank()) {
            error = "请先在电费页补录学号后再绑定"
            return@LaunchedEffect
        }
        busy = true
        zones = repository.zones(accountInput)
        busy = false
        if (zones.isEmpty()) error = "没有获取到校区列表，请检查网络后重试"
    }

    fun choose(item: ElectricityArea) {
        when (step) {
            0 -> {
                zone = item
                step = 1
                busy = true
                error = null
                scope.launch {
                    builds = repository.builds(accountInput, item.id)
                    busy = false
                    if (builds.isEmpty()) fail("没有获取到楼栋列表")
                }
            }
            1 -> {
                build = item
                step = 2
                busy = true
                error = null
                scope.launch {
                    floors = repository.floors(accountInput, item.id)
                    busy = false
                    if (floors.isEmpty()) fail("没有获取到楼层列表")
                }
            }
            2 -> {
                floor = item
                step = 3
                busy = true
                error = null
                scope.launch {
                    rooms = repository.rooms(accountInput, item.id)
                    busy = false
                    if (rooms.isEmpty()) fail("没有获取到房间列表")
                }
            }
            3 -> {
                room = item
                step = 4
                error = null
            }
        }
    }

    fun bind() {
        if (accountInput.isBlank()) {
            error = "请先填写完美校园账号 / 学号"
            return
        }
        val z = zone ?: return
        val b = build ?: return
        val f = floor ?: return
        val r = room ?: return
        busy = true
        error = null
        scope.launch {
            when (val outcome = repository.bindRoom(accountInput, z.id, b.id, f.id, r.id)) {
                is ElectricityBindOutcome.Success -> {
                    busy = false
                    onBound()
                }
                is ElectricityBindOutcome.Failed -> {
                    if (outcome.message.contains("人员信息不存在")) {
                        fail("完美校园没有这个账号，请先在完美校园注册/绑定，或填写正确的电费账号")
                    } else {
                        fail(outcome.message)
                    }
                }
                ElectricityBindOutcome.Unavailable -> fail("绑定接口暂时不可用，请稍后再试")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (step == 4) "确认绑定" else "绑定房间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = accountInput,
                    onValueChange = { accountInput = it.filter(Char::isDigit).take(32) },
                    label = { Text("完美校园账号 / 学号") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(12.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                when (step) {
                    0 -> OptionList(zones, "选择校区", ::choose)
                    1 -> OptionList(builds, "选择楼栋", ::choose)
                    2 -> OptionList(floors, "选择楼层", ::choose)
                    3 -> OptionList(rooms, "选择房间", ::choose)
                    4 -> Text(
                        "${zone?.name.orEmpty()} · ${build?.name.orEmpty()} · " +
                            "${floor?.name.orEmpty()} · ${room?.name.orEmpty()}",
                    )
                }
            }
        },
        confirmButton = {
            if (step == 4) {
                TextButton(onClick = ::bind, enabled = !busy) { Text("绑定此房间") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = {
            if (step in 1..3) {
                TextButton(onClick = { step -= 1 }, enabled = !busy) { Text("返回") }
            }
        },
    )
}

@Composable
private fun OptionList(
    items: List<ElectricityArea>,
    title: String,
    onSelect: (ElectricityArea) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(items, key = { it.id }) { item ->
                TextButton(
                    onClick = { onSelect(item) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(item.name, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
