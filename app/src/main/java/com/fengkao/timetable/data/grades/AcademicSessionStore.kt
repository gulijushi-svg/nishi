package com.fengkao.timetable.data.grades

import android.content.Context
import android.webkit.CookieManager
import com.fengkao.timetable.data.local.Crypto
import com.fengkao.timetable.data.local.DEFAULT_BASE_URL
import com.fengkao.timetable.data.local.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WebView 与 OkHttp 共用的教务 Cookie；落盘部分使用 Android Keystore 加密。 */
class AcademicSessionStore(
    context: Context,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var memoryCookieHeader: String? = null

    suspend fun cookieHeader(): String? {
        memoryCookieHeader?.let { return it }
        val encrypted = settings.academicCookies() ?: return null
        val decrypted = runCatching { Crypto.decrypt(encrypted) }.getOrNull() ?: return null
        memoryCookieHeader = decrypted
        return decrypted
    }

    suspend fun saveCookieHeader(raw: String?) {
        val normalized = raw?.split(';')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.joinToString("; ")
            ?.takeIf(String::isNotEmpty)
        memoryCookieHeader = normalized
        if (normalized == null) {
            settings.clearAcademicCookies()
            return
        }
        val encrypted = runCatching { Crypto.encrypt(normalized) }.getOrNull() ?: return
        settings.saveAcademicCookies(encrypted)
    }

    suspend fun captureFromWebView(url: String = DEFAULT_BASE_URL) = withContext(Dispatchers.Main) {
        val raw = CookieManager.getInstance().getCookie(url)
        if (!raw.isNullOrBlank()) saveCookieHeader(raw)
    }

    suspend fun clear() {
        memoryCookieHeader = null
        settings.clearAcademicCookies()
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        }
    }
}
