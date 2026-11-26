package com.fengkao.timetable.data.wash

import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest

/**
 * 洗衣接口签名。字段顺序和拼接方式必须与原客户端一致：
 * appSecret、channel、timestamp、token、version、请求路径。
 */
internal const val WASH_APP_SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
internal const val WASH_CHANNEL = "android_app"
internal const val WASH_VERSION = "1.60.3"

internal fun washSignature(path: String, token: String, timestamp: String): String {
    val raw = "appSecret=$WASH_APP_SECRET&channel=$WASH_CHANNEL&timestamp=$timestamp&token=$token&version=$WASH_VERSION&$path"
    val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

internal class WashSigningInterceptor(
    private val tokenProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val timestamp = System.currentTimeMillis().toString()
        val token = tokenProvider()
        val sign = washSignature(path, token, timestamp)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", token)
                .header("Version", WASH_VERSION)
                .header("channel", WASH_CHANNEL)
                .header("timestamp", timestamp)
                .header("sign", sign)
                .build()
        )
    }
}
