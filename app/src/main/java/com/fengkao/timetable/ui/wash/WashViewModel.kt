package com.fengkao.timetable.ui.wash

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alipay.sdk.app.PayTask
import com.fengkao.timetable.data.wash.WashApiException
import com.fengkao.timetable.data.wash.WashDevice
import com.fengkao.timetable.data.wash.WashGoodsDetail
import com.fengkao.timetable.data.wash.WashOrderItem
import com.fengkao.timetable.data.wash.WashRepository
import com.fengkao.timetable.data.wash.WashSessionExpired
import com.fengkao.timetable.data.wash.WashSku
import com.fengkao.timetable.data.wash.WashStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class WashRunningTime(
    val finishAtMillis: Long,
    val totalSeconds: Long,
)

data class WashUiState(
    val loggedIn: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val smsSentTo: String = "",
    val smsSeconds: Int = 0,
    val busy: Boolean = false,
    val loadingDevices: Boolean = false,
    val devices: List<WashDevice> = emptyList(),
    val running: Map<Int, WashRunningTime> = emptyMap(),
    val showOrders: Boolean = false,
    val orders: List<WashOrderItem> = emptyList(),
    val ordersLoading: Boolean = false,
    val orderPage: Int = 1,
    val orderHasMore: Boolean = true,
    val detail: WashGoodsDetail? = null,
    val skus: List<WashSku> = emptyList(),
    val addons: List<com.fengkao.timetable.data.wash.WashAddon> = emptyList(),
    val detailLoading: Boolean = false,
    val selectedAddonIds: Set<Int> = emptySet(),
    val selectedAddonKeys: Set<String> = emptySet(),
    val payingSkuId: Int? = null,
    val message: String? = null,
    val error: Boolean = false,
) {
    val validPhone: Boolean get() = phone.matches(Regex("^1\\d{10}$"))
    val canSubmitCode: Boolean
        get() = validPhone && code.length >= 4 && smsSentTo == phone && !busy
}

/**
 * 洗衣服务的页面状态机：短信登录、最近设备、扫码、确认价格与支付宝支付、
 * 历史订单都在这里完成。网络调用放在 IO 线程，UI 只读 StateFlow。
 */
class WashViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WashRepository(application)
    private val store = WashStore(application)
    private val demoMode = application.packageName.endsWith(".demo")
    private val _state = MutableStateFlow(
        WashUiState(loggedIn = repository.isLoggedIn(), phone = store.phone())
    )
    val state = _state.asStateFlow()

    private var smsJob: Job? = null

    fun phone(value: String) {
        _state.update {
            it.copy(
                phone = value.filter(Char::isDigit).take(11),
                code = "",
                smsSentTo = "",
                message = null,
            )
        }
    }

    fun code(value: String) {
        _state.update { it.copy(code = value.filter(Char::isDigit).take(6), message = null) }
    }

    fun message(text: String) {
        _state.update { it.copy(message = text, error = true) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = false) }
    }

    fun enter() {
        if (_state.value.busy) return
        // 没有本地 token 时保持当前状态：正式环境初始即为未登录；
        // Demo 构建会注入 loggedIn=true 的虚构状态，不能在这里被清掉。
        if (!repository.isLoggedIn()) return
        refreshDevices(showLoading = _state.value.devices.isEmpty())
    }

    fun sendSms() {
        if (demoMode) return message("演示模式不连接真实洗衣服务")
        val s = _state.value
        if (!s.validPhone || s.smsSeconds > 0) {
            message("请输入正确的手机号")
            return
        }
        _state.update { it.copy(busy = true, message = null, error = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.sendSmsCode(s.phone) }
                _state.update {
                    it.copy(
                        busy = false,
                        smsSentTo = s.phone,
                        smsSeconds = 60,
                        message = "验证码已发送，请查收短信",
                        error = false,
                    )
                }
                startSmsCountdown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, message = e.friendlyMessage(), error = true)
                }
            }
        }
    }

    private fun startSmsCountdown() {
        smsJob?.cancel()
        smsJob = viewModelScope.launch {
            while (_state.value.smsSeconds > 0) {
                delay(1_000)
                _state.update { it.copy(smsSeconds = (it.smsSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    fun login() {
        if (demoMode) return message("演示模式不连接真实洗衣服务")
        val s = _state.value
        if (!s.canSubmitCode) {
            message(if (s.smsSentTo != s.phone) "请先获取短信验证码" else "请输入验证码")
            return
        }
        _state.update { it.copy(busy = true, message = null, error = false) }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { repository.register(s.phone, s.code) }
                withContext(Dispatchers.IO) { repository.saveSession(token, s.phone) }
                _state.update {
                    it.copy(
                        loggedIn = true,
                        busy = false,
                        code = "",
                        smsSeconds = 0,
                        message = "登录成功",
                        error = false,
                    )
                }
                loadDevicesAndRunning()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.friendlyMessage(), error = true) }
            }
        }
    }

    fun logout() {
        smsJob?.cancel()
        repository.logout()
        _state.value = WashUiState(phone = "")
    }

    fun refreshDevices(showLoading: Boolean = true) {
        if (!repository.isLoggedIn()) return
        if (_state.value.busy && !showLoading) return
        _state.update { it.copy(loadingDevices = showLoading, message = null, error = false) }
        viewModelScope.launch {
            try {
                loadDevicesAndRunning()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionOrError(e)
            } finally {
                _state.update { it.copy(loadingDevices = false) }
            }
        }
    }

    private suspend fun loadDevicesAndRunning() {
        val devices = withContext(Dispatchers.IO) { repository.latestUsed() }
        val running = withContext(Dispatchers.IO) { loadRunningTimes() }
        _state.update {
            it.copy(
                loggedIn = true,
                devices = devices,
                running = running,
                message = null,
                error = false,
            )
        }
    }

    private suspend fun loadRunningTimes(): Map<Int, WashRunningTime> {
        val result = mutableMapOf<Int, WashRunningTime>()
        val messages = runCatching { repository.messageList() }.getOrDefault(emptyList())
        messages.asSequence()
            .mapNotNull { it.order }
            .take(8)
            .forEach { order ->
                val orderNo = order.orderNo?.takeIf { it.isNotBlank() } ?: return@forEach
                val detail = runCatching { repository.orderDetail(orderNo) }.getOrNull() ?: return@forEach
                val finishAt = parseDateTime(detail.completeTime)
                    ?.takeIf { it > System.currentTimeMillis() }
                    ?: return@forEach
                val goodsId = detail.lineItems?.firstOrNull()?.goodsId ?: return@forEach
                if (goodsId <= 0) return@forEach
                val createAt = parseDateTime(detail.createTime) ?: finishAt
                result[goodsId] = WashRunningTime(
                    finishAtMillis = finishAt,
                    totalSeconds = ((finishAt - createAt) / 1000L).coerceAtLeast(1L),
                )
            }
        return result
    }

    fun selectService(showOrders: Boolean) {
        _state.update { it.copy(showOrders = showOrders, message = null, error = false) }
        if (showOrders && _state.value.orders.isEmpty() && !_state.value.ordersLoading) {
            refreshOrders(reset = true)
        }
    }

    fun refreshOrders(reset: Boolean = true) {
        if (!repository.isLoggedIn()) return
        if (_state.value.ordersLoading) return
        val page = if (reset) 1 else _state.value.orderPage + 1
        _state.update { it.copy(ordersLoading = true, message = null, error = false) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.orderList(page) }
                val items = data.items.orEmpty()
                _state.update {
                    it.copy(
                        ordersLoading = false,
                        orders = if (reset) items else it.orders + items,
                        orderPage = page,
                        orderHasMore = items.size >= 20,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(ordersLoading = false) }
                handleSessionOrError(e)
            }
        }
    }

    fun loadMoreOrders() {
        if (_state.value.orderHasMore && !_state.value.ordersLoading) refreshOrders(reset = false)
    }

    fun openDevice(goodsId: Int) {
        if (goodsId <= 0 || !repository.isLoggedIn()) return
        _state.update {
            it.copy(
                detailLoading = true,
                detail = null,
                skus = emptyList(),
                addons = emptyList(),
                selectedAddonIds = emptySet(),
                selectedAddonKeys = emptySet(),
                message = null,
                error = false,
            )
        }
        viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { repository.goodsDetails(goodsId) }
                val skus = withContext(Dispatchers.IO) { repository.goodsSkus(goodsId) }
                val addons = withContext(Dispatchers.IO) {
                    runCatching {
                        repository.fetchAddons(detail.shopId ?: detail.orgId.toString())
                    }.getOrDefault(emptyList())
                }
                _state.update {
                    it.copy(detail = detail, skus = skus, addons = addons, detailLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(detailLoading = false) }
                handleSessionOrError(e)
            }
        }
    }

    fun closeDetail() {
        _state.update {
            it.copy(
                detail = null,
                skus = emptyList(),
                addons = emptyList(),
                detailLoading = false,
                selectedAddonIds = emptySet(),
                selectedAddonKeys = emptySet(),
                payingSkuId = null,
            )
        }
    }

    fun toggleAddon(skuId: Int) {
        _state.update {
            val selected = it.selectedAddonIds.toMutableSet()
            if (!selected.add(skuId)) selected.remove(skuId)
            it.copy(selectedAddonIds = selected)
        }
    }

    fun toggleAddonKey(key: String) {
        _state.update {
            val selected = it.selectedAddonKeys.toMutableSet()
            if (!selected.add(key)) selected.remove(key)
            it.copy(selectedAddonKeys = selected)
        }
    }

    fun scanResult(raw: String) {
        val nqt = runCatching { android.net.Uri.parse(raw).getQueryParameter("NQT") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: raw.takeIf { it.isNotBlank() }
        if (nqt.isNullOrBlank()) {
            message("无效二维码")
            return
        }
        if (!repository.isLoggedIn()) {
            message("请先登录洗衣账户")
            return
        }
        _state.update { it.copy(busy = true, message = null, error = false) }
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { repository.goodsScan(nqt).id }
                    ?.toIntOrNull()
                    ?: throw IOException("无效设备二维码")
                _state.update { it.copy(busy = false) }
                openDevice(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                handleSessionOrError(e)
            }
        }
    }

    fun pay(activity: Activity?, sku: WashSku) {
        if (!repository.isLoggedIn()) return message("请先登录洗衣账户")
        val detail = _state.value.detail ?: return message("设备信息尚未加载完成")
        if (activity == null) return message("当前页面无法发起支付")
        if (_state.value.busy || _state.value.payingSkuId != null) return
        _state.update { it.copy(payingSkuId = sku.skuId, message = null, error = false) }
        viewModelScope.launch {
            try {
                val promotions = listOf(
                    mapOf(
                        "assetId" to "0",
                        "oldPromotionId" to "3482",
                        "orgId" to detail.orgId.toString(),
                        "promotionId" to "0",
                        "promotionType" to "4",
                    )
                )
                val items = buildList {
                    add(
                        mapOf(
                            "amount" to "1",
                            "goodsId" to detail.goodsId.toString(),
                            "num" to "1",
                            "skuId" to sku.skuId.toString(),
                            "soldType" to "1",
                        )
                    )
                    // 洗衣液 / 消毒液等洗涤用品作为同一订单的附加项。
                    _state.value.skus
                        .filter { it.isAddon && it.skuId in _state.value.selectedAddonIds }
                        .forEach { addon ->
                            add(
                                mapOf(
                                    "amount" to "1",
                                    "goodsId" to detail.goodsId.toString(),
                                    "num" to "1",
                                    "skuId" to addon.skuId.toString(),
                                    "soldType" to "1",
                                )
                            )
                        }
                    _state.value.addons
                        .filter { it.isConfigured && it.key in _state.value.selectedAddonKeys }
                        .forEach { addon ->
                            add(
                                mapOf(
                                    "amount" to "1",
                                    "goodsId" to addon.goodsId,
                                    "num" to "1",
                                    "skuId" to addon.skuId,
                                    "soldType" to "1",
                                )
                            )
                        }
                }
                val trade = withContext(Dispatchers.IO) { repository.createTrade(promotions, items) }
                val orderId = trade.orderId?.takeIf { it.isNotBlank() }
                    ?: throw IOException("创建订单失败")
                val prepay = withContext(Dispatchers.IO) { repository.prePay(orderId) }
                val param = prepay.prepayParam?.takeIf { it.isNotBlank() }
                    ?: throw IOException("支付参数为空")
                val result = withContext(Dispatchers.IO) { PayTask(activity).payV2(param, true) }
                val status = result["resultStatus"]
                    ?: runCatching { JSONObject(result["result"].orEmpty()).optString("resultStatus") }.getOrNull()
                if (status == "9000") {
                    _state.update {
                        it.copy(
                            payingSkuId = null,
                            message = "支付成功，请按洗衣机面板提示开始洗衣",
                            error = false,
                        )
                    }
                    refreshDevices(showLoading = false)
                    refreshOrders(reset = true)
                } else {
                    _state.update {
                        it.copy(
                            payingSkuId = null,
                            message = if (status == "6001") "已取消支付" else "支付未完成（$status）",
                            error = true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(payingSkuId = null) }
                handleSessionOrError(e)
            }
        }
    }

    private fun handleSessionOrError(e: Exception) {
        if (e is WashSessionExpired) {
            repository.logout()
            _state.update {
                it.copy(
                    loggedIn = false,
                    detail = null,
                    skus = emptyList(),
                    message = "洗衣账户登录已失效，请重新登录",
                    error = true,
                )
            }
        } else {
            _state.update { it.copy(message = e.friendlyMessage(), error = true) }
        }
    }

    private fun Exception.friendlyMessage(): String = when (this) {
        is WashApiException -> message ?: "操作失败"
        is IOException -> message ?: "网络连接失败，请重试"
        else -> message ?: "操作失败，请重试"
    }

    private fun parseDateTime(raw: String?): Long? = runCatching {
        LocalDateTime.parse(raw.orEmpty().trim(), DATE_TIME)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private companion object {
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
