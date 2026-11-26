package com.fengkao.timetable.data.wash

/**
 * 「胖乖生活」洗衣服务的网络模型。
 *
 * 接口字段沿用原项目，读取时全部按可空处理：第三方接口在不同学校部署下会
 * 少返回字段，不能让一个缺失字段把整页数据打崩。
 */
data class WashResponse<T>(
    val code: Int = 1,
    val msg: String? = null,
    val data: T? = null,
)

data class WashDevice(
    val goodsId: Int = 0,
    val goodsName: String? = null,
    val orgId: Int = 0,
    val orgName: String? = null,
    /** 10 = 空闲；其余值按使用中处理。 */
    val workStatus: Int = 0,
    val shopId: String? = null,
)

data class WashMessageRow(
    val message: WashMessage? = null,
    val order: WashMessageOrder? = null,
)

data class WashMessageOrder(
    val orderNo: String? = null,
    val goodsCategory: String? = null,
    val shopName: String? = null,
    val skuName: String? = null,
    val goodsName: String? = null,
    val finishTime: String? = null,
)

data class WashMessage(
    val id: Int = 0,
    val content: String? = null,
    val createTime: String? = null,
    val read: Int = 0,
)

data class WashGoodsDetail(
    val goodsId: Int = 0,
    val name: String? = null,
    val categoryCode: String? = null,
    val orgId: Int = 0,
    val orgName: String? = null,
    val shopId: String? = null,
    val soldState: Int = 0,
    val payTypes: List<Int>? = null,
    val brandName: String? = null,
    val serviceTelephone: String? = null,
)

/** 官方 App 的「洗护用品」加购商品。 */
data class WashAddon(
    val goodsId: String = "",
    val skuId: String = "",
    val name: String = "",
    val price: String = "",
    val feature: String = "",
) {
    val key: String get() = "$goodsId:$skuId"
    val isConfigured: Boolean get() = goodsId.isNotBlank() && skuId.isNotBlank()
}

data class WashSku(
    val skuId: Int = 0,
    val name: String? = null,
    val feature: String? = null,
    val price: String? = null,
    val vipDiscountPrice: String? = null,
    val discountPrice: String? = null,
    val unit: String? = null,
    val stockState: Int = 0,
) {
    /** 洗衣模式之外的洗涤用品（洗衣液、消毒液等）。 */
    val isAddon: Boolean
        get() {
            val text = "${name.orEmpty()} ${feature.orEmpty()} ${unit.orEmpty()}"
            return listOf("消毒", "洗衣液", "洗衣粉", "柔顺", "除菌", "护理").any {
                text.contains(it, ignoreCase = true)
            }
        }
}

data class WashGoodsScan(
    val id: String? = null,
    val categoryCode: String? = null,
    val categoryName: String? = null,
    val type: String? = null,
)

data class WashCreateTrade(
    val tradeType: Int = 0,
    val tradeNo: String? = null,
    val orderId: String? = null,
)

data class WashPrePay(
    val exist: Boolean = false,
    val messageId: String? = null,
    val gateway: String? = null,
    val prepayParam: String? = null,
)

data class WashOrderPage(
    val total: Int = 0,
    val items: List<WashOrderItem>? = null,
)

data class WashOrderItem(
    val id: String? = null,
    val orderNo: String? = null,
    val orderType: Int = 0,
    val orderStatus: Int = 0,
    val createTime: String? = null,
    val markPrice: String? = null,
    val payPrice: String? = null,
    val machineType: Int = 0,
    val machineName: String? = null,
    val shopAutoId: Int = 0,
    val shopId: String? = null,
    val shopName: String? = null,
    val isBluetooth: Int = 0,
    val markMinutes: Int = 0,
    val machineFunctionName: String? = null,
    val parentTypeId: String? = null,
)

data class WashOrderDetail(
    val id: String? = null,
    val orderNo: String? = null,
    val orderStatus: Int = 0,
    val createTime: String? = null,
    val completeTime: String? = null,
    val finishTime: String? = null,
    val markPrice: String? = null,
    val payPrice: String? = null,
    val machineName: String? = null,
    val machineFunctionName: String? = null,
    val lineItems: List<WashOrderLineItem>? = null,
)

data class WashOrderLineItem(
    val goodsId: Int = 0,
    val skuId: Int = 0,
    val skuName: String? = null,
    val realPrice: String? = null,
)

data class WashUserReg(
    val token: String? = null,
)

data class WashUserInfo(
    val id: String? = null,
    val phone: String? = null,
    val balance: String? = null,
)
