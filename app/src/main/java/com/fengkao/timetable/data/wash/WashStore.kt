package com.fengkao.timetable.data.wash

import android.content.Context
import com.fengkao.timetable.data.local.Crypto

/**
 * 洗衣账户的本地凭据。Token 与教务、喝水、学习通互相隔离，并使用
 * Android Keystore 的 AES-GCM 加密后写入独立 SharedPreferences。
 */
class WashStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("wash_account", Context.MODE_PRIVATE)

    fun token(): String {
        val stored = prefs.getString(KEY_TOKEN, "").orEmpty()
        if (stored.isBlank()) return ""
        return runCatching { Crypto.decrypt(stored) }.getOrElse {
            prefs.edit().remove(KEY_TOKEN).apply()
            ""
        }
    }

    fun phone(): String = prefs.getString(KEY_PHONE, "").orEmpty()

    fun saveSession(token: String, phone: String) {
        val encrypted = runCatching { Crypto.encrypt(token) }.getOrElse { "" }
        check(encrypted.isNotBlank()) { "登录凭据保存失败，请重试" }
        check(prefs.edit().putString(KEY_TOKEN, encrypted).putString(KEY_PHONE, phone).commit()) {
            "登录凭据保存失败，请重试"
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_PHONE).apply()
    }

    private companion object {
        const val KEY_TOKEN = "token_enc"
        const val KEY_PHONE = "phone"
    }
}
