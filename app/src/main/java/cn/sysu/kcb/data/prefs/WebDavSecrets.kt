package cn.sysu.kcb.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class WebDavSecrets(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    fun password(): String = prefs.getString(KEY, "").orEmpty()

    fun hasPassword(): Boolean = password().isNotBlank()

    fun save(password: String) {
        prefs.edit().putString(KEY, password.trim().replace(Regex("\\s+"), "")).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "password"

        private fun openPrefs(context: Context): SharedPreferences = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "kcb_webdav",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            context.getSharedPreferences("kcb_webdav_plain", Context.MODE_PRIVATE)
        }
    }
}
