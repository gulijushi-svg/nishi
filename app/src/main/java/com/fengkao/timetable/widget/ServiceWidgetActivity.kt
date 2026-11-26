package com.fengkao.timetable.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fengkao.timetable.data.water.WaterStore
import com.fengkao.timetable.ui.theme.*
import com.fengkao.timetable.ui.water.*
import com.fengkao.timetable.widget.service.*
import kotlinx.coroutines.delay

/** 饮水组件的前台绑定/启动入口。 */
class ServiceWidgetActivity : ComponentActivity() {
    private val water: WaterViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val service = ServiceWidgetUpdater.service(this, id) ?: run { finish(); return }
        val binding = ServiceWidgetStore(this).binding(id)?.takeIf { it.service == service }
        val operation = runCatching { WidgetOperation.valueOf(intent.getStringExtra("operation").orEmpty()) }
            .getOrDefault(WidgetOperation.CHECK)
        enableEdgeToEdge()
        setContent {
            TimetableTheme {
                LifeTheme(ServiceKind.Drinking) {
                    var bind by rememberSaveable { mutableStateOf(intent.getBooleanExtra("bind", false) || binding == null) }
                    if (bind) WidgetBindingPage(id, water, ::finish)
                    else WidgetControlPage(id, binding!!, water, { bind = true }, ::finish)
                }
            }
        }
    }
}

@Composable
private fun WidgetBindingPage(id: Int, waterModel: WaterViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val water = remember { WaterStore(context) }
    val widget = remember { ServiceWidgetStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var showService by rememberSaveable { mutableStateOf(false) }
    var selectedDevice by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    LaunchedEffect(showService) { if (!showService) revision++ }
    if (showService) {
        BackHandler { showService = false }
        Column {
            TextButton(onClick = { showService = false }) { Text("返回组件绑定") }
            Box(Modifier.weight(1f)) { WaterScreen(waterModel) }
        }
        return
    }
    val devices = remember(revision) { water.devices().map { it.id to it.name } }
    val accounts = remember(revision) {
        if (water.token().isNotBlank()) listOf(water.phone()) else emptyList()
    }
    LifePage {
        DeviceManagementHeader("绑定喝水组件", close)
        Text("选择这个桌面组件使用的设备", color = InkMid, fontSize = 15.sp)
        if (accounts.isEmpty() || devices.isEmpty()) {
            LifeCard {
                Text(if (accounts.isEmpty()) "请先登录喝水账户" else "请先绑定一台设备", fontSize = 17.sp, color = InkDark)
                TextButton(onClick = { showService = true }) { Text("打开喝水页面") }
            }
        } else {
            LifeCard {
                Text("设备", fontWeight = FontWeight.SemiBold, color = InkDark)
                devices.forEach { (deviceId, name) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(deviceId == selectedDevice, onClick = { selectedDevice = deviceId })
                        TextButton(onClick = { selectedDevice = deviceId }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(name, fontSize = 15.sp, color = InkDark)
                                Text(deviceId, fontSize = 12.sp, color = InkGray)
                            }
                        }
                    }
                }
            }
            val phone = accounts.first()
            ServiceActionButton(
                "绑定到此组件",
                Icons.Outlined.Check,
                enabled = devices.any { it.first == selectedDevice } && phone in accounts,
                kind = ServiceKind.Drinking,
                onClick = {
                    runCatching {
                        widget.save(id, ServiceWidgetBinding(WidgetService.WATER, selectedDevice, phone))
                        ServiceWidgetUpdater.update(context, id)
                    }.onSuccess { close() }.onFailure { error = it.message ?: "绑定失败，请重试" }
                },
            )
            TextButton(onClick = { showService = true }) { Text("账户与设备设置") }
        }
        ServiceMessage(error, true)
    }
}

@Composable
private fun WidgetControlPage(
    id: Int,
    binding: ServiceWidgetBinding,
    water: WaterViewModel,
    rebind: () -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val ws by water.state.collectAsStateWithLifecycle()
    var launched by rememberSaveable { mutableStateOf(false) }
    val store = remember { ServiceWidgetStore(context) }
    fun execute() {
        if (launched) return
        launched = true
        store.status(id, "正在发送启动请求…")
        ServiceWidgetUpdater.update(context, id)
        water.startFromWidget(binding.accountPhone, binding.deviceId)
    }
    LaunchedEffect(Unit) { execute() }
    LaunchedEffect(launched, ws.busy, ws.message, ws.error) {
        if (launched && !ws.busy && ws.message != null) {
            store.status(id, ws.message.orEmpty())
            ServiceWidgetUpdater.update(context, id)
            if (!ws.error) { delay(900); close() }
        }
    }
    BackHandler(ws.busy) { /* 启动请求正在执行，等待结果写入组件。 */ }
    val name = ws.devices.firstOrNull { it.id == binding.deviceId }?.name
    LifePage {
        LifeHeader("喝水", "桌面组件", Icons.Outlined.WaterDrop, ServiceKind.Drinking)
        LifeCard {
            Text(name ?: binding.deviceId, color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            if (ws.busy || !launched) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在发送启动请求…", color = InkMid, fontSize = 15.sp)
                }
            } else ServiceMessage(ws.message ?: "操作已中断，请返回桌面确认状态", ws.error)
            if (!ws.busy) {
                TextButton(onClick = close) { Text("返回桌面") }
                TextButton(onClick = rebind) { Text("重新绑定组件") }
            }
        }
    }
}
