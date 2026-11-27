package com.fengkao.timetable.ui.electricity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fengkao.timetable.data.electricity.ElectricityRoom
import com.fengkao.timetable.ui.theme.BrandGreen
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkDark
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricityScreen(model: ElectricityViewModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    var thresholdDialog by rememberSaveable { mutableStateOf(false) }
    var accountDialogDismissed by rememberSaveable { mutableStateOf(false) }
    var showBindDialog by rememberSaveable { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = state.busy && !state.recoveringAccount,
        onRefresh = model::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("宿舍剩余电量", color = InkDark, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.fetchedAt > 0L) "更新于 ${formatTime(state.fetchedAt)}" else "从完美校园读取",
                            color = InkGray,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { showBindDialog = true }, enabled = !state.busy) {
                        Text("绑定房间")
                    }
                    IconButton(onClick = model::refresh, enabled = !state.busy) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrandGreen)
                        } else {
                            Icon(Icons.Outlined.Refresh, "刷新电费", tint = BrandGreen)
                        }
                    }
                }
            }
            if (state.recoveringAccount) {
                item {
                    Surface(color = CardWhite, shape = RoundedCornerShape(18.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = BrandGreen)
                            Text("正在从已登录的教务个人信息中恢复学号…", color = InkMid, fontSize = 13.sp)
                        }
                    }
                }
            }
            state.message?.let { message ->
                item {
                    Surface(
                        color = if (state.error) MaterialTheme.colorScheme.errorContainer else CardWhite,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(message, color = InkDark, fontSize = 13.sp, lineHeight = 19.sp)
                            if (!state.busy) {
                                TextButton(onClick = model::refresh) { Text("重新查询") }
                            }
                        }
                    }
                }
            }
            if (state.needsAccount && accountDialogDismissed) {
                item {
                    Button(onClick = { accountDialogDismissed = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("补入学号")
                    }
                }
            }
            items(state.rooms, key = { it.roomName }) { room ->
                ElectricityRoomCard(room, state.threshold)
            }
            item {
                ThresholdCard(state.threshold) { thresholdDialog = true }
            }
            item {
                Text(
                    if (state.offline) "当前网络读取失败，显示最近一次缓存。" else "每天最多发送一次低电量提醒，可在系统设置中关闭通知。",
                    color = InkGray,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }

    if (showBindDialog) {
        ElectricityBindDialog(
            account = state.account,
            onDismiss = { showBindDialog = false },
            onBound = {
                showBindDialog = false
                model.refresh()
            },
        )
    }

    if (thresholdDialog) {
        var draft by remember { mutableFloatStateOf(state.threshold.toFloat()) }
        AlertDialog(
            onDismissRequest = { thresholdDialog = false },
            title = { Text("低电量提醒阈值") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("剩余电量低于 ${draft.toInt()} 度时提醒", color = InkDark)
                    Slider(
                        value = draft,
                        onValueChange = { draft = it },
                        valueRange = 1f..100f,
                        steps = 98,
                    )
                    Text("默认 15 度，可设置为 1–100 度。", color = InkGray, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.setThreshold(draft.toInt())
                    thresholdDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { thresholdDialog = false }) { Text("取消") } },
            containerColor = CardWhite,
        )
    }

    if (state.needsAccount && !accountDialogDismissed) {
        var account by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { accountDialogDismissed = true },
            title = { Text("补录教务学号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("未能从已登录的教务个人信息中恢复学号。补录后会保存到初始化使用的同一账户位置。", color = InkGray, fontSize = 12.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it.filter(Char::isDigit).take(32) },
                        label = { Text("学号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { model.saveAccount(account) },
                    enabled = account.length >= 4,
                ) { Text("保存并查询") }
            },
            dismissButton = { TextButton(onClick = { accountDialogDismissed = true }) { Text("稍后") } },
            containerColor = CardWhite,
        )
    }
}

@Composable
private fun ElectricityRoomCard(room: ElectricityRoom, threshold: Int) {
    val low = room.remainingKwh < threshold
    val accent = if (low) Color(0xFFD64B4B) else BrandGreen
    Surface(color = CardWhite, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(room.roomName, color = InkDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (low) {
                    Icon(Icons.Outlined.BatteryAlert, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("低电量", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatNumber(room.remainingKwh), color = accent, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("度", color = InkGray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 7.dp))
            }
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(InkGray.copy(alpha = 0.16f)),
            ) {
                Box(
                    Modifier.fillMaxWidth((room.remainingKwh / threshold).coerceIn(0.0, 1.0).toFloat())
                        .height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已用 ${formatNumber(room.usedKwh)} 度", color = InkGray, fontSize = 12.sp)
                Text(
                    if (room.isPoweredOn) "供电正常" else "当前未供电",
                    color = if (room.isPoweredOn) BrandGreen else InkGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ThresholdCard(threshold: Int, onClick: () -> Unit) {
    Surface(color = CardWhite, shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Notifications, null, tint = BrandGreen)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("低电量提醒", color = InkDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("阈值 $threshold 度 · 每天最多一次", color = InkGray, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onClick) { Text("设置") }
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.CHINA, "%.2f", value)

private fun formatTime(timestamp: Long): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}.getOrDefault("--")
