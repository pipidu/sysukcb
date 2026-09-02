package cn.sysu.kcb.data.remote

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WebDavShareCode {
    const val PREFIX = "kcbdav1:"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Pack(
        val v: Int = 1,
        val url: String = "",
        val user: String = "",
        val password: String = "",
    )

    fun encode(url: String, user: String, password: String): String {
        val pack = Pack(
            url = url.trim().ifBlank { WebDavClient.DEFAULT_NUTSTORE_FILE_URL },
            user = user.trim(),
            password = password.trim().replace(Regex("\\s+"), ""),
        )
        if (pack.user.isBlank() || pack.password.isBlank()) {
            throw ImportFailedException("请先填写用户名和应用密码")
        }
        val bytes = json.encodeToString(Pack.serializer(), pack).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(raw: String): Triple<String, String, String> {
        val compact = raw.trim().replace(Regex("\\s+"), "")
        if (compact.isBlank()) throw ImportFailedException("请粘贴分享码")
        if (!compact.startsWith(PREFIX, ignoreCase = true)) {
            throw ImportFailedException("不是课程表D 的 WebDAV 分享码")
        }
        val body = compact.substring(PREFIX.length)
        val bytes = runCatching {
            Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrElse { throw ImportFailedException("分享码损坏") }
        if (bytes.isEmpty()) throw ImportFailedException("分享码损坏")
        val pack = runCatching {
            json.decodeFromString(Pack.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrElse { throw ImportFailedException("分享码损坏") }
        if (pack.user.isBlank() || pack.password.isBlank()) {
            throw ImportFailedException("分享码里没有账号信息")
        }
        return Triple(
            pack.url.trim().ifBlank { WebDavClient.DEFAULT_NUTSTORE_FILE_URL },
            pack.user.trim(),
            pack.password.trim(),
        )
    }
}
