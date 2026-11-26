package com.fengkao.timetable.data.electricity

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val SCHOOL_CODE_ZZNU = "510"
const val DEFAULT_ELECTRICITY_BASE_URL = "https://xqh5.17wanxiao.com"

class ElectricityApi(
    private val client: OkHttpClient = defaultElectricityClient(),
    private val baseUrl: String = DEFAULT_ELECTRICITY_BASE_URL,
) {
    fun fetch(account: String): String {
        val param = JSONObject()
            .put("cmd", "getbindroom")
            .put("account", account)
            .toString()
        val form = FormBody.Builder()
            .add("param", param)
            .add("customercode", SCHOOL_CODE_ZZNU)
            .add("method", "getbindroom")
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + SERVLET_PATH)
            .header("Accept", "application/json")
            .post(form)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("宿舍电费接口暂时不可用")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("宿舍电费接口返回为空")
        }
    }

    private companion object {
        const val SERVLET_PATH = "/smartWaterAndElectricityService/SWAEServlet"
    }
}

private fun defaultElectricityClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()
