package com.fengkao.timetable.data.wash

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class WashSessionExpired : IOException("洗衣账户登录已失效，请重新登录")
class WashApiException(message: String) : IOException(message)

/**
 * 洗衣服务仓库。把原项目的 Retrofit + 签名拦截器收拢成一个普通类，
 * 由 ViewModel 持有；项目其余部分仍然使用手工依赖容器。
 */
class WashRepository(context: Context) {
    private val store = WashStore(context)
    private val api: WashApiService

    init {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(WashSigningInterceptor { store.token() })
            .build()
        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .build()
            .create(WashApiService::class.java)
    }

    fun token(): String = store.token()
    fun phone(): String = store.phone()
    fun isLoggedIn(): Boolean = token().isNotBlank()

    fun saveSession(token: String, phone: String) = store.saveSession(token, phone)
    fun logout() = store.clear()

    suspend fun latestUsed(): List<WashDevice> = call {
        api.latestUsed("001", token())
    }.orEmpty()

    suspend fun goodsDetails(goodsId: Int): WashGoodsDetail = must(
        call { api.goodsDetails(goodsId, token()) },
        "未找到洗衣机信息",
    )

    suspend fun goodsSkus(goodsId: Int): List<WashSku> = call {
        api.goodsSkus(goodsId, token())
    }.orEmpty()

    /** 读取店铺里的洗护用品加购商品（官方 App 的「请添加洗护用品」）。 */
    suspend fun fetchAddons(shopId: String): List<WashAddon> {
        if (shopId.isBlank()) return emptyList()
        val root = call { api.shopGoods(shopId = shopId, token = token()) } ?: return emptyList()
        val objects = mutableListOf<JsonObject>()
        fun walk(element: JsonElement) {
            when {
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    objects += obj
                    obj.entrySet().forEach { walk(it.value) }
                }
                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
            }
        }
        walk(root)
        val keywords = listOf("除菌液", "洗衣液", "消毒液", "洗护", "洗衣粉", "柔顺")
        val result = mutableListOf<WashAddon>()
        for (obj in objects) {
            val name = obj.string("goodsName").ifBlank { obj.string("name") }
            if (name.isBlank() || keywords.none { name.contains(it) }) continue
            val goodsId = obj.string("goodsId").ifBlank { obj.string("id") }
            if (goodsId.isBlank()) continue
            var skuId = obj.string("skuId")
            var price = obj.string("price").ifBlank { obj.string("goodsPrice") }
            if (skuId.isBlank()) {
                val sku = runCatching { goodsSkus(goodsId.toInt()).firstOrNull() }.getOrNull()
                skuId = sku?.skuId?.toString().orEmpty()
                if (price.isBlank()) price = sku?.price.orEmpty()
            }
            if (skuId.isNotBlank()) {
                result += WashAddon(
                    goodsId = goodsId,
                    skuId = skuId,
                    name = name,
                    price = price,
                    feature = obj.string("feature").ifBlank { "官方洗护用品" },
                )
            }
        }
        return result.distinctBy { it.goodsId to it.skuId }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    suspend fun goodsScan(nqt: String): WashGoodsScan = must(
        call { api.goodsScan(nqt, token()) },
        "无效设备二维码",
    )

    suspend fun createTrade(promotions: List<Map<String, String>>, items: List<Map<String, String>>): WashCreateTrade =
        must(
            call { api.createTrade(Gson().toJson(promotions), Gson().toJson(items), token()) },
            "创建订单失败",
        )

    suspend fun prePay(orderNo: String, payType: Int = 13): WashPrePay = must(
        call { api.prePay(orderNo, payType, token()) },
        "获取支付参数失败",
    )

    suspend fun sendSmsCode(phone: String) {
        call { api.sendSmsCode(phone) }
    }

    suspend fun register(phone: String, code: String): String {
        val data = must(call { api.userReg(phone, code) }, "验证码登录失败")
        return data.token?.takeIf { it.isNotBlank() } ?: throw WashApiException("登录返回信息不完整")
    }

    suspend fun validateSession() {
        call { api.userInfo(token()) }
    }

    suspend fun orderList(page: Int, pageSize: Int = 20): WashOrderPage = must(
        call { api.orderList(page, pageSize, "", token()) },
        "订单列表为空",
    )

    suspend fun orderDetail(orderNo: String): WashOrderDetail = must(
        call { api.orderDetail(orderNo, token()) },
        "订单详情为空",
    )

    suspend fun messageList(): List<WashMessageRow> = call {
        api.messageList(5, token())
    }.orEmpty()

    private suspend fun <T> call(block: suspend () -> WashResponse<T>): T? {
        val response = try {
            block()
        } catch (e: WashSessionExpired) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) throw WashSessionExpired()
            throw IOException("洗衣服务连接失败（${e.code()}）", e)
        } catch (e: IOException) {
            throw IOException(e.message ?: "洗衣服务连接失败，请检查网络", e)
        }
        if (response.code == 2) throw WashSessionExpired()
        if (response.code != 0) {
            val message = response.msg?.takeIf { it.isNotBlank() } ?: "操作失败"
            if (message.contains("登录") || message.contains("token", true) ||
                message.contains("认证") || message.contains("过期")
            ) throw WashSessionExpired()
            throw WashApiException(message)
        }
        return response.data
    }

    private fun <T> must(value: T?, message: String): T =
        value ?: throw WashApiException(message)

    private companion object {
        const val BASE_URL = "https://userapi.qiekj.com/"
    }
}
