package com.fengkao.timetable.data.electricity

import java.util.Base64
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

const val SCHOOL_CODE_ZZNU = "510"
const val DEFAULT_ELECTRICITY_BASE_URL = "https://xqh5.17wanxiao.com"

class ElectricityApi(
    private val client: OkHttpClient = defaultElectricityClient(),
    private val baseUrl: String = DEFAULT_ELECTRICITY_BASE_URL,
) {
    fun fetch(account: String): String =
        post("getbindroom", JSONObject().put("account", account), account)

    fun zones(account: String): String = post("getzonelist", JSONObject(), account)

    fun builds(account: String, zoneId: Int): String =
        post("getbuildlist", JSONObject().put("zoneid", zoneId), account)

    fun floors(account: String, buildId: Int): String =
        post("getfloorlist", JSONObject().put("buildid", buildId), account)

    fun rooms(account: String, floorId: Int): String =
        post("getroomnumlist", JSONObject().put("floorid", floorId), account)

    fun bindRoom(account: String, zoneId: Int, buildId: Int, floorId: Int, roomNum: Int): String =
        post(
            "bindroom",
            JSONObject()
                .put("zoneid", zoneId)
                .put("buildid", buildId)
                .put("floorid", floorId)
                .put("roomnum", roomNum),
            account,
        )

    fun unbindRoom(account: String, roomVerify: String): String =
        post("unbindroom", JSONObject().put("roomverify", roomVerify), account)

    fun roomByAccount(account: String, accountNum: String): String =
        post("getroombyaccountnum", JSONObject().put("account_num", accountNum), account)

    private fun post(method: String, param: JSONObject, account: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val randomStr = randomString(10)
        param.put("cmd", method)
        param.put("account", account)
        param.put("timestamp", timestamp)
        val key = requestKey(timestamp, randomStr)
        val plainEnvelope = JSONObject()
            .put("param", param.toString())
            .put("customercode", SCHOOL_CODE_ZZNU)
            .put("method", method)
            .put("command", "JBSWaterElecService")
            .toString()
        val form = FormBody.Builder()
            .add("encryptData", encrypt(plainEnvelope, key))
            .add("randomStr", randomStr)
            .add("timestamp", timestamp)
            .add("method", method)
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + SERVLET_PATH)
            .header("Accept", "application/json")
            .header("X-Requested-With", "com.newcapec.mobile.ncp")
            .header("Referer", "https://xqh5.17wanxiao.com/userwaterelecmini/index.html")
            .post(form)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("宿舍电费接口暂时不可用")
            val raw = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("宿舍电费接口返回为空")
            decryptEnvelope(raw)
        }
    }

    private fun decryptEnvelope(raw: String): String {
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return raw
        }
        val encrypted = root.optString("encryptData")
        if (encrypted.isBlank()) return raw
        val timestamp = root.optString("timestamp")
        val randomStr = root.optString("randomStr")
        val clear = try {
            decrypt(encrypted, requestKey(timestamp, randomStr))
        } catch (_: Exception) {
            return raw
        }
        val envelope = try {
            JSONObject(clear)
        } catch (_: Exception) {
            return clear
        }
        if (envelope.optString("result_") != "true") return clear
        return when (val body = envelope.opt("body")) {
            is String -> body
            is JSONObject -> body.toString()
            else -> clear
        }
    }

    private fun requestKey(timestamp: String, randomStr: String): String {
        var n = 0
        for (c in timestamp) {
            if (c.isDigit()) n = (10 * n + (c - '0')) % 8
        }
        val prefix = if (n % 2 == 1) timestamp.take(6) else timestamp.takeLast(6)
        return (prefix + randomStr).take(16)
    }

    private fun randomString(length: Int): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString(length) {
            repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }

    private fun encrypt(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return String(cipher.doFinal(Base64.getDecoder().decode(value)), Charsets.UTF_8)
    }

    private companion object {
        const val SERVLET_PATH = "/smartWaterAndElectricityService/SWAEEncryptServlet"
    }
}

private fun defaultElectricityClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()
