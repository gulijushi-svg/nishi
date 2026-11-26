package com.fengkao.timetable.data.wash

import android.content.Context
import com.google.gson.Gson
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

    /** 读取官方洗衣 SKU 页的「洗护用品」加购商品（goods/attachList/v3）。 */
    suspend fun fetchAddons(goodsId: Int): List<WashAddon> {
        if (goodsId <= 0) return emptyList()
        return call { api.attachList(goodsId.toString(), token()) }
            .orEmpty()
            .filter { it.isConfigured }
            .distinctBy { it.goodsId to it.skuId }
    }

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
