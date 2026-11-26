package com.fengkao.timetable.ui.wash

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fengkao.timetable.data.wash.WashDevice
import com.fengkao.timetable.data.wash.WashOrderItem
import com.fengkao.timetable.data.wash.WashSku
import com.fengkao.timetable.ui.navigation.LocalPageActive
import com.fengkao.timetable.ui.service.rememberDeviceQrScanner
import com.fengkao.timetable.ui.theme.*
import com.fengkao.timetable.ui.water.*import kotlinx.coroutines.delay

@Composable
fun WashScreen(model: WashViewModel, embedded: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val active = LocalPageActive.current
    LifecycleResumeEffect(active) {
        if (active) model.enter()
        onPauseOrDispose { }
    }
    LifeTheme(ServiceKind.Laundry) {
        if (state.detailLoading || state.detail != null) {
            WashDetailPage(model, state, embedded)
        } else {
            WashMainPage(model, state, embedded)
        }
    }
}

@Composable
private fun WashMainPage(model: WashViewModel, state: WashUiState, embedded: Boolean) {
    LifePage(embedded = embedded) {
        if (!embedded) LifeHeader("洗衣", "扫码使用校园自助洗衣机", Icons.Outlined.LocalLaundryService, ServiceKind.Laundry)
        if (!state.loggedIn) {
            WashLogin(state, model, showHeading = true)
        } else {
            WashServiceSwitch(state, model)
            if (state.showOrders) {
                WashOrders(state, model)
            } else {
                WashHome(state, model)
            }
        }
        ServiceMessage(
            message = state.message,
            error = state.error,
            retry = state.loggedIn && state.error,
            busy = state.busy,
            onRetry = {
                model.clearMessage()
                if (state.showOrders) model.refreshOrders() else model.refreshDevices()
            },
        )
    }
}

@Composable
private fun WashServiceSwitch(state: WashUiState, model: WashViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(4.dp),
    ) {
        listOf(false to "附近设备", true to "历史订单").forEach { (orders, label) ->
            val selected = state.showOrders == orders
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) ServiceKind.Laundry.soft else Color.Transparent)
                    .clickable { model.selectService(orders) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (orders) Icons.AutoMirrored.Outlined.ListAlt else Icons.Outlined.LocalLaundryService,
                    null,
                    Modifier.size(18.dp),
                    tint = if (selected) ServiceKind.Laundry.ink else InkGray,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    color = if (selected) ServiceKind.Laundry.ink else InkMid,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun WashHome(state: WashUiState, model: WashViewModel) {
    val requestScan = rememberDeviceQrScanner(
        onCode = model::scanResult,
        onError = model::message,
        onPermissionDenied = { model.message("未开启相机权限，可手动输入设备编号") },
    )
    var showManual by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }

    LifeCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(ServiceKind.Laundry.soft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.LocalLaundryService, null, Modifier.size(34.dp), tint = ServiceKind.Laundry.ink)
            }
            Column(Modifier.weight(1f)) {
                Text("自助洗衣", color = InkMid, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.devices.firstOrNull()?.orgName?.takeIf { it.isNotBlank() } ?: "扫码或选择常用洗衣机",
                    color = InkDark, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { requestScan() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ServiceKind.Laundry.action,
                contentColor = Color.White,
            ),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.busy) "正在识别设备…" else "扫码使用洗衣机", fontWeight = FontWeight.SemiBold)
        }
        TextButton(
            onClick = { showManual = true },
            enabled = !state.busy,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("模拟器无法扫码？手动输入设备编号", color = InkMid, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("扫码后在设备详情里选择洗衣模式并支付；洗衣机由校园洗衣平台控制。", color = InkMid, fontSize = 12.sp)
    }

    if (showManual) {
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text("手动输入设备编号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("输入洗衣机二维码里的 NQT 或设备编号。", color = InkGray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        singleLine = true,
                        label = { Text("设备编号 / NQT") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManual = false
                        model.scanResult(manualCode.trim())
                        manualCode = ""
                    },
                    enabled = manualCode.isNotBlank(),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false }) { Text("取消") }
            },
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("最近使用", color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { model.refreshDevices() }, enabled = !state.loadingDevices && !state.busy) {
            if (state.loadingDevices) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("刷新", fontSize = 13.sp)
        }
    }
    if (state.loadingDevices && state.devices.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ServiceKind.Laundry.action)
        }
    } else if (state.devices.isEmpty()) {
        LifeCard {
            Text("还没有最近使用的洗衣机", color = InkDark, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("扫描机身二维码即可查看设备并开始洗衣。", color = InkMid, fontSize = 13.sp)
        }
    } else {
        val now = rememberClock(LocalPageActive.current)
        state.devices.forEach { device ->
            WashDeviceCard(device, state.running[device.goodsId], now, state.busy) {
                model.openDevice(device.goodsId)
            }
        }
    }
}

@Composable
private fun WashDeviceCard(
    device: WashDevice,
    running: WashRunningTime?,
    now: Long,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val free = device.workStatus == 10 && running == null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = free && !busy, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(ServiceKind.Laundry.soft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.LocalLaundryService, null, Modifier.size(28.dp), tint = ServiceKind.Laundry.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    device.goodsName?.takeIf { it.isNotBlank() } ?: "洗衣机 ${device.goodsId}",
                    color = InkDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    device.orgName?.takeIf { it.isNotBlank() } ?: "校园洗衣房",
                    color = InkGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (running != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            ((running.finishAtMillis - now).coerceAtLeast(0L).toFloat() /
                                (running.totalSeconds * 1000f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = ServiceKind.Laundry.action,
                        trackColor = ServiceKind.Laundry.soft,
                    )
                }
            }
            Text(
                when {
                    running != null -> formatRemaining(running.finishAtMillis - now)
                    free -> "空闲"
                    else -> "使用中"
                },
                color = if (free) BrandGreen else if (running != null) ServiceKind.Laundry.ink else InkGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun WashOrders(state: WashUiState, model: WashViewModel) {
    Text("历史订单", color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (state.ordersLoading && state.orders.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ServiceKind.Laundry.action)
        }
    } else if (state.orders.isEmpty()) {
        LifeCard {
            Text("暂无洗衣订单", color = InkDark, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("完成一次扫码洗衣后，订单会出现在这里。", color = InkMid, fontSize = 13.sp)
        }
    } else {
        state.orders.forEach { WashOrderCard(it) }
        if (state.orderHasMore) {
            TextButton(
                onClick = model::loadMoreOrders,
                enabled = !state.ordersLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.ordersLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("加载更多")
            }
        }
    }
}

@Composable
private fun WashOrderCard(item: WashOrderItem) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.createTime.orEmpty(), color = InkGray, fontSize = 12.sp)
                Text(orderStatus(item.orderStatus), color = orderStatusColor(item.orderStatus), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                item.machineName?.takeIf { it.isNotBlank() } ?: item.shopName.orEmpty().ifBlank { "校园洗衣机" },
                color = InkDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.machineFunctionName.orEmpty().ifBlank { "自助洗衣" }, color = InkMid, fontSize = 13.sp)
                Text("￥${item.payPrice.orEmpty().ifBlank { item.markPrice.orEmpty() }}", color = InkDark, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun WashDetailPage(model: WashViewModel, state: WashUiState, embedded: Boolean) {
    val activity = LocalActivity.current
    LifePage(embedded = embedded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::closeDetail) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回洗衣机列表", tint = InkDark)
            }
            Text("洗衣机详情", color = InkDark, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (state.detailLoading) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ServiceKind.Laundry.action)
            }
        } else {
            val detail = state.detail
            if (detail != null) {
                LifeCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(ServiceKind.Laundry.soft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.LocalLaundryService, null, Modifier.size(30.dp), tint = ServiceKind.Laundry.ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(detail.name.orEmpty().ifBlank { "洗衣机 ${detail.goodsId}" }, color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(detail.orgName.orEmpty().ifBlank { "校园洗衣房" }, color = InkGray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                val skuAddons = state.skus.filter { it.isAddon }
                val modes = state.skus.filterNot { it.isAddon }
                if (state.addons.isNotEmpty()) {
                    Text("选配洗涤用品", color = InkDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    state.addons.groupBy { it.name }
                        .toList()
                        .sortedBy { it.first }
                        .forEach { (group, variants) ->
                            val sorted = variants.sortedBy { it.copyCount }
                            val index = (state.addonQuantities[group] ?: 0)
                                .coerceIn(0, (sorted.size - 1).coerceAtLeast(0))
                            val current = sorted[index]
                            WashOfficialAddonCard(
                                addon = current,
                                checked = group in state.selectedAddonKeys,
                                quantity = index + 1,
                                maxQuantity = sorted.size,
                                enabled = state.payingSkuId == null && !state.busy,
                                onToggle = { model.toggleAddonKey(group) },
                                onMinus = { model.setAddonQuantity(group, index - 1) },
                                onPlus = { model.setAddonQuantity(group, index + 1) },
                            )
                        }
                } else if (skuAddons.isNotEmpty()) {
                    Text("选配洗涤用品", color = InkDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    skuAddons.forEach { sku ->
                        WashAddonCard(
                            sku = sku,
                            checked = sku.skuId in state.selectedAddonIds,
                            enabled = state.payingSkuId == null && !state.busy,
                            onToggle = { model.toggleAddon(sku.skuId) },
                        )
                    }
                } else {
                    Text(
                        "当前洗衣房接口未返回消毒液 / 洗衣液加购项",
                        color = InkGray,
                        fontSize = 11.sp,
                    )
                }
                Text("选择洗衣模式", color = InkDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (modes.isEmpty()) {
                    LifeCard { Text("该设备暂未配置可用的洗衣模式", color = InkMid, fontSize = 14.sp) }
                } else {
                    modes.forEach { sku ->
                        WashSkuCard(sku, state.payingSkuId, state.busy) { model.pay(activity, sku) }
                    }
                }
                ServiceMessage(state.message, state.error)
                Text("支付完成后，请按洗衣机面板提示放置衣物并启动。", color = InkGray, fontSize = 12.sp)
            } else {
                LifeCard { Text("设备信息加载失败，请返回重试", color = InkMid, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun WashAddonCard(
    sku: WashSku,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) ServiceKind.Laundry.soft else CardWhite,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) onToggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = ServiceKind.Laundry.action),
            )
            Column(Modifier.weight(1f)) {
                Text(sku.name.orEmpty().ifBlank { "洗涤用品" }, color = InkDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sku.feature.orEmpty().ifBlank { "可选加购" }, color = InkGray, fontSize = 11.sp)
            }
            Text("￥${sku.price.orEmpty()}", color = InkDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WashOfficialAddonCard(
    addon: com.fengkao.timetable.data.wash.WashAddon,
    checked: Boolean,
    quantity: Int,
    maxQuantity: Int,
    enabled: Boolean,
    onToggle: () -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) ServiceKind.Laundry.soft else CardWhite,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { if (enabled) onToggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = ServiceKind.Laundry.action),
            )
            Column(Modifier.weight(1f)) {
                Text(addon.name.ifBlank { "洗护用品" }, color = InkDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    addon.description.ifBlank { addon.feature }.ifBlank { "官方洗护用品" },
                    color = InkGray,
                    fontSize = 11.sp,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "￥${addon.price.ifBlank { "--" }}",
                        color = InkDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    AddonQuantityButton(
                        icon = Icons.Outlined.Remove,
                        enabled = enabled && checked && quantity > 1,
                        onClick = onMinus,
                    )
                    Text(
                        "$quantity",
                        color = InkDark,
                        fontSize = 14.sp,
                        modifier = Modifier.width(26.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    AddonQuantityButton(
                        icon = Icons.Outlined.Add,
                        enabled = enabled && checked && quantity < maxQuantity,
                        onClick = onPlus,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonQuantityButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 不用 IconButton：Material3 默认 48dp 最小触控区会把卡片撑成大块空白。
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) CardWhite else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (enabled) InkDark else InkGray)
    }
}

@Composable
private fun WashSkuCard(sku: WashSku, payingSkuId: Int?, busy: Boolean, onClick: () -> Unit) {
    val paying = payingSkuId == sku.skuId
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = payingSkuId == null && !busy, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(sku.name.orEmpty().ifBlank { "洗衣模式" }, color = InkDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(sku.feature.orEmpty().ifBlank { sku.unit.orEmpty() }, color = InkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("￥${sku.price.orEmpty()}", color = InkDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (paying) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ServiceKind.Laundry.action)
        }
    }
}

@Composable
fun WashLogin(
    state: WashUiState,
    model: WashViewModel,
    showHeading: Boolean = true,
    submitLabel: String = "登录洗衣账户",
) {
    LifeCard {
        if (showHeading) {
            Text("登录洗衣账户", color = InkDark, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("使用手机号与短信验证码登录，首次登录会自动注册。", color = InkGray, fontSize = 12.sp)
            Spacer(Modifier.height(22.dp))
        }
        OutlinedTextField(
            value = state.phone,
            onValueChange = model::phone,
            label = { Text("手机号") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.code,
                onValueChange = model::code,
                label = { Text("短信验证码") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = model::sendSms,
                enabled = !state.busy && state.validPhone && state.smsSeconds == 0,
                modifier = Modifier.width(112.dp),
            ) {
                Text(if (state.smsSeconds > 0) "${state.smsSeconds}s 后重发" else "获取验证码", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = model::login,
            enabled = state.canSubmitCode,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ServiceKind.Laundry.action, contentColor = Color.White),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text(submitLabel, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Text("登录凭据使用 Android Keystore 加密保存在本机", color = InkGray, fontSize = 12.sp)
    }
}

@Composable
private fun rememberClock(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        now = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun formatRemaining(millis: Long): String {
    val seconds = ((millis.coerceAtLeast(0L) + 999L) / 1000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun orderStatus(value: Int): String = when (value) {
    0 -> "待支付"
    1 -> "已超时"
    2 -> "已支付"
    3 -> "已完成"
    else -> "订单 $value"
}

@Composable
private fun orderStatusColor(value: Int): Color = when (value) {
    3 -> BrandGreen
    2 -> Color(0xFF2F6F9F)
    0 -> Color(0xFFB26A00)
    else -> InkGray
}
