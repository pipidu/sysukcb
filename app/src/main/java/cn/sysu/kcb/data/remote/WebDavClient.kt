package cn.sysu.kcb.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class WebDavClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    suspend fun upload(fileUrl: String, user: String, password: String, body: String) = withContext(Dispatchers.IO) {
        val url = normalizeFileUrl(fileUrl)
        ensureParents(url, user, password)
        val response = execute(
            Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password, java.nio.charset.StandardCharsets.UTF_8))
                .header("User-Agent", UA)
                .put(body.toRequestBody(JSON))
                .build(),
        )
        if (response.code !in 200..204 && response.code != 201) {
            throw ImportFailedException(errorMessage("上传", response.code, response.body))
        }
    }

    suspend fun download(fileUrl: String, user: String, password: String): String = withContext(Dispatchers.IO) {
        val url = normalizeFileUrl(fileUrl)
        val response = execute(
            Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, password, java.nio.charset.StandardCharsets.UTF_8))
                .header("User-Agent", UA)
                .get()
                .build(),
        )
        when (response.code) {
            in 200..299 -> response.body
            404, 409 -> throw ImportFailedException("云端还没有课表文件，请先上传")
            else -> throw ImportFailedException(errorMessage("下载", response.code, response.body))
        }
    }

    suspend fun listJsonFiles(fileUrl: String, user: String, password: String): List<String> = withContext(Dispatchers.IO) {
        val dir = directoryOf(normalizeFileUrl(fileUrl))
        val response = execute(
            Request.Builder()
                .url(dir)
                .header("Authorization", Credentials.basic(user, password, java.nio.charset.StandardCharsets.UTF_8))
                .header("User-Agent", UA)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
                .build(),
        )
        if (response.code !in 200..299 && response.code != 207) {
            throw ImportFailedException(errorMessage("列出好友课表", response.code, response.body))
        }
        parseJsonHrefs(response.body, dir)
    }

    private fun parseJsonHrefs(xml: String, dir: HttpUrl): List<String> {
        val names = linkedSetOf<String>()
        val dirPath = dir.encodedPath.trimEnd('/')
        for (match in HREF_REGEX.findAll(xml)) {
            val raw = match.groupValues[1].trim()
            if (raw.isBlank()) continue
            val decoded = runCatching {
                java.net.URLDecoder.decode(raw.replace("&amp;", "&"), Charsets.UTF_8.name())
            }.getOrDefault(raw)
            val name = if ("://" in decoded) {
                runCatching { decoded.toHttpUrl().pathSegments.lastOrNull().orEmpty() }.getOrDefault("")
            } else {
                val path = decoded.trimEnd('/')
                if (path.equals(dirPath, ignoreCase = true) || path.isBlank()) continue
                path.substringAfterLast('/')
            }
            if (name.isBlank() || !name.endsWith(".json", ignoreCase = true)) continue
            names += name
        }
        return names.toList()
    }

    private fun ensureParents(fileUrl: HttpUrl, user: String, password: String) {
        val segments = fileUrl.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2) return
        var path = ""
        for (i in 0 until segments.lastIndex) {
            path += "/" + segments[i]
            val dir = fileUrl.newBuilder().encodedPath(path).query(null).fragment(null).build()
            val response = execute(
                Request.Builder()
                    .url(dir)
                    .header("Authorization", Credentials.basic(user, password, java.nio.charset.StandardCharsets.UTF_8))
                    .header("User-Agent", UA)
                    .method("MKCOL", ByteArray(0).toRequestBody(null))
                    .build(),
            )
            if (response.code !in listOf(200, 201, 204, 301, 302, 405, 409)) {
                if (response.code in listOf(401, 403)) {
                    throw ImportFailedException(errorMessage("创建目录", response.code, response.body))
                }
            }
        }
    }

    private fun execute(request: Request): DavResponse {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return DavResponse(response.code, text)
        }
    }

    private fun errorMessage(action: String, code: Int, body: String): String = when (code) {
        401, 403 -> "WebDAV 用户名或密码错误"
            404 -> "${action}失败：路径不存在"
            507 -> "网盘空间不足"
            else -> {
                val hint = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
                if (hint.isBlank()) "WebDAV ${action}失败（$code）" else "WebDAV ${action}失败（$code）：$hint"
            }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val UA = "sysukcb/android"
        const val DEFAULT_FILE = "sysukcb.json"
        const val RESERVED_STEM = "sysukcb"
        private val HREF_REGEX = Regex("(?i)<[^<>]*:?href[^<>]*>([^<]+)</[^<>]*:?href>")
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>"""

        fun normalizeFileUrl(raw: String): HttpUrl {
            var value = raw.trim()
            if (value.isBlank()) throw ImportFailedException("请填写 WebDAV 地址")
            if (!value.contains("://")) value = "https://$value"
            if (value.endsWith("/")) value += DEFAULT_FILE
            val url = runCatching { value.toHttpUrl() }.getOrElse {
                throw ImportFailedException("WebDAV 地址无效")
            }
            val last = url.pathSegments.lastOrNull().orEmpty()
            if (last.isBlank() || !last.contains('.')) {
                return url.newBuilder().addPathSegment(DEFAULT_FILE).build()
            }
            return url
        }

        fun directoryOf(fileUrl: HttpUrl): HttpUrl {
            val path = fileUrl.encodedPath.trimEnd('/')
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            val encoded = if (parent.isBlank()) "/" else "$parent/"
            return fileUrl.newBuilder().encodedPath(encoded).query(null).fragment(null).build()
        }

        fun fileInDirectory(fileUrl: HttpUrl, filename: String): HttpUrl =
            directoryOf(fileUrl).newBuilder().addPathSegment(filename).build()

        fun sanitizeNickname(raw: String): String {
            val cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(32)
            if (cleaned.isBlank()) throw ImportFailedException("请填写昵称")
            if (cleaned.equals(RESERVED_STEM, ignoreCase = true) ||
                cleaned.equals(DEFAULT_FILE, ignoreCase = true)
            ) {
                throw ImportFailedException("昵称不能是 sysukcb")
            }
            return cleaned
        }

        fun nicknameFilename(nickname: String): String = "${sanitizeNickname(nickname)}.json"

        fun stemOf(filename: String): String = filename.removeSuffix(".json").removeSuffix(".JSON")
    }

    private data class DavResponse(val code: Int, val body: String)
}
