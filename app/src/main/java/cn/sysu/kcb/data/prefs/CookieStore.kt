package cn.sysu.kcb.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CookieStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kcb_cookies",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("kcb_cookies_plain", Context.MODE_PRIVATE)
    }

    fun cookieHeader(): String = prefs.getString(KEY, "").orEmpty()

    fun save(cookieHeader: String) {
        prefs.edit().putString(KEY, cookieHeader).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }

    fun hasSession(): Boolean {
        val value = cookieHeader()
        return value.contains("LYSESSIONID") && value.contains("user=")
    }

    fun syncFromWebView() {
        val fromWeb = CookieManager.getInstance().getCookie(JWXT_ORIGIN).orEmpty()
        if (fromWeb.isNotBlank()) save(fromWeb)
    }

    companion object {
        private const val KEY = "jwxt_cookie"
        const val JWXT_ORIGIN = "https://jwxt.sysu.edu.cn"
        const val LOGIN_URL =
            "https://jwxt.sysu.edu.cn/jwxt/api/sso/cas/login?pattern=student-login"
    }
}
