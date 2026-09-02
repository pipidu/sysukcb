package cn.sysu.kcb.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.sysu.kcb.data.school.School

class CookieStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    fun cookieHeader(): String = prefs.getString(KEY, "").orEmpty()

    fun save(cookieHeader: String) {
        prefs.edit().putString(KEY, cookieHeader).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        wipeBrowser()
    }

    fun wipeBrowser(webView: WebView? = null, onDone: (() -> Unit)? = null) {
        webView?.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            clearFormData()
            clearSslPreferences()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        val manager = CookieManager.getInstance()
        manager.removeAllCookies {
            manager.flush()
            onDone?.invoke()
        }
        if (onDone == null) manager.flush()
    }

    fun hasSession(school: School): Boolean = school.hasSession(cookieHeader())

    fun hasAnySession(): Boolean {
        val value = cookieHeader()
        return School.All.any { it.hasSession(value) }
    }

    fun syncFromWebView(school: School) {
        val manager = CookieManager.getInstance()
        manager.flush()
        val merged = linkedMapOf<String, String>()
        for (origin in school.cookieOrigins) {
            val header = manager.getCookie(origin).orEmpty()
            if (header.isBlank()) continue
            for (part in header.split(";")) {
                val kv = part.trim()
                val name = kv.substringBefore("=")
                if (name.isNotBlank()) merged[name] = kv
            }
        }
        if (merged.isNotEmpty()) save(merged.values.joinToString("; "))
    }

    companion object {
        private const val KEY = "jwxt_cookie"
        const val JWXT_ORIGIN = "https://jwxt.sysu.edu.cn"
        const val LOGIN_URL =
            "https://jwxt.sysu.edu.cn/jwxt/api/sso/cas/login?pattern=student-login"

        private fun openPrefs(context: Context): SharedPreferences = runCatching {
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
    }
}
