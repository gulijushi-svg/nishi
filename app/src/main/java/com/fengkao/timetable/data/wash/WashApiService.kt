package com.fengkao.timetable.data.wash

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * 胖乖生活用户侧接口。签名、版本号与渠道由 [WashSigningInterceptor] 统一附加，
 * 因此这里只声明业务表单字段。
 */
interface WashApiService {
    @FormUrlEncoded
    @POST("goods/latestUsed")
    suspend fun latestUsed(
        @Field("categoryCode") categoryCode: String,
        @Field("token") token: String,
    ): WashResponse<List<WashDevice>>

    @FormUrlEncoded
    @POST("goods/normal/details")
    suspend fun goodsDetails(
        @Field("goodsId") goodsId: Int,
        @Field("token") token: String,
    ): WashResponse<WashGoodsDetail>

    @FormUrlEncoded
    @POST("goods/normal/skus")
    suspend fun goodsSkus(
        @Field("goodsId") goodsId: Int,
        @Field("token") token: String,
    ): WashResponse<List<WashSku>>

    @FormUrlEncoded
    @POST("goods/scan/v2")
    suspend fun goodsScan(
        @Field("NQT") nqt: String,
        @Field("token") token: String,
    ): WashResponse<WashGoodsScan>

    /** 店铺商品列表：用于读取「洗护用品」等加购商品。 */
    @FormUrlEncoded
    @POST("appointNew/near/newMachines")
    suspend fun shopGoods(
        @Field("goodsPage") goodsPage: String = "1",
        @Field("pageSize") pageSize: String = "100",
        @Field("shopId") shopId: String,
        @Field("machineTypeId") machineTypeId: String = "04",
        @Field("orgId") orgId: String = "0",
        @Field("token") token: String,
    ): WashResponse<com.google.gson.JsonElement>

    @FormUrlEncoded
    @POST("trade/create")
    suspend fun createTrade(
        @Field("promotions") promotions: String,
        @Field("items") items: String,
        @Field("token") token: String,
    ): WashResponse<WashCreateTrade>

    @FormUrlEncoded
    @POST("pay/unify/prePay")
    suspend fun prePay(
        @Field("orderNo") orderNo: String,
        @Field("payType") payType: Int,
        @Field("token") token: String,
    ): WashResponse<WashPrePay>

    @FormUrlEncoded
    @POST("common/sms/sendCode")
    suspend fun sendSmsCode(
        @Field("phone") phone: String,
        @Field("template") template: String = "reg",
    ): WashResponse<Any>

    @FormUrlEncoded
    @POST("user/reg")
    suspend fun userReg(
        @Field("phone") phone: String,
        @Field("verify") verify: String,
        @Field("channel") channel: String = "android_app",
    ): WashResponse<WashUserReg>

    @FormUrlEncoded
    @POST("user/info")
    suspend fun userInfo(
        @Field("token") token: String,
    ): WashResponse<WashUserInfo>

    @FormUrlEncoded
    @POST("order/list")
    suspend fun orderList(
        @Field("page") page: Int,
        @Field("pageSize") pageSize: Int,
        @Field("orderStatus") orderStatus: String,
        @Field("token") token: String,
    ): WashResponse<WashOrderPage>

    @FormUrlEncoded
    @POST("order/detail")
    suspend fun orderDetail(
        @Field("orderId") orderId: String,
        @Field("token") token: String,
    ): WashResponse<WashOrderDetail>

    @FormUrlEncoded
    @POST("message/index/list")
    suspend fun messageList(
        @Field("subtypeId") subtypeId: Int,
        @Field("token") token: String,
    ): WashResponse<List<WashMessageRow>>
}
