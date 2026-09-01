package cn.sysu.kcb.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class WebDavClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun upload(fileUrl: String, user: String, password: String, body: String) = withContext(Dispatchers.IO) {
        val url = normalizeFileUrl(fileUrl)
        ensureParents(url, user, password)
        var response = putFile(url, user, password, body)
        if (response.code == 404) {
            ensureParents(url, user, password)
            response = putFile(url, user, password, body)
        }
        if (response.code !in 200..204) {
            throw ImportFailedException(errorMessage("上传", response.code, response.body))
        }
    }

    suspend fun download(fileUrl: String, user: String, password: String): String = withContext(Dispatchers.IO) {
        val url = normalizeFileUrl(fileUrl)
        val response = execute(
            Request.Builder()
                .url(url)
                .header("Authorization", davAuth(user, password))
                .header("User-Agent", UA)
                .get()
                .build(),
        )
        when (response.code) {
            in 200..299 -> response.body.removePrefix("\uFEFF")
            404, 409 -> throw ImportFailedException("云端还没有课表文件，请先上传")
            else -> throw ImportFailedException(errorMessage("下载", response.code, response.body))
        }
    }

    suspend fun listJsonFiles(fileUrl: String, user: String, password: String): List<String> = withContext(Dispatchers.IO) {
        val dir = directoryOf(normalizeFileUrl(fileUrl))
        val response = execute(
            Request.Builder()
                .url(dir)
                .header("Authorization", davAuth(user, password))
                .header("User-Agent", UA)
                .header("Depth", "1")
                .header("Accept", "*/*")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
                .build(),
        )
        if (response.code !in 200..299) {
            throw ImportFailedException(errorMessage("列出好友课表", response.code, response.body))
        }
        parseJsonHrefs(response.body, dir)
    }

    private fun parseJsonHrefs(xml: String, dir: HttpUrl): List<String> {
        val names = linkedSetOf<String>()
        val dirPath = decodePath(dir.encodedPath).trimEnd('/')
        for (match in HREF_REGEX.findAll(xml)) {
            val raw = match.groupValues[1].trim()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            if (raw.isBlank()) continue
            val decoded = decodePath(raw)
            val name = hrefFileName(decoded, dirPath) ?: continue
            if (name.endsWith(".json", ignoreCase = true)) names += name
        }
        return names.toList()
    }

    private fun hrefFileName(decoded: String, dirPath: String): String? {
        val path = if ("://" in decoded) {
            val url = decoded.toHttpUrlOrNull() ?: return null
            "/" + url.pathSegments.filter { it.isNotEmpty() }.joinToString("/")
        } else {
            val raw = decoded.trim().trimEnd('/')
            if (raw.startsWith("/")) raw else "$dirPath/$raw"
        }
        if (path.isBlank() || path.equals(dirPath, ignoreCase = true)) return null
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (!parent.equals(dirPath, ignoreCase = true)) return null
        return path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private fun ensureParents(fileUrl: HttpUrl, user: String, password: String) {
        val segments = fileUrl.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2) return
        val builder = fileUrl.newBuilder().query(null).fragment(null).encodedPath("/")
        for (i in 0 until segments.lastIndex) {
            builder.addPathSegment(segments[i])
            val soFar = builder.build().pathSegments.filter { it.isNotEmpty() }
            if (soFar.size == 1 && soFar[0].equals("dav", ignoreCase = true)) continue
            val dir = builder.build().let { url ->
                url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
            }
            val response = execute(
                Request.Builder()
                    .url(dir)
                    .header("Authorization", davAuth(user, password))
                    .header("User-Agent", UA)
                    .method("MKCOL", ByteArray(0).toRequestBody(null))
                    .build(),
            )
            when (response.code) {
                401 -> throw ImportFailedException(errorMessage("创建目录", response.code, response.body))
                in MKCOL_OK -> Unit
                else -> throw ImportFailedException(errorMessage("创建目录", response.code, response.body))
            }
        }
    }

    private fun putFile(url: HttpUrl, user: String, password: String, body: String): DavResponse =
        execute(
            Request.Builder()
                .url(url)
                .header("Authorization", davAuth(user, password))
                .header("User-Agent", UA)
                .header("Overwrite", "T")
                .put(body.toByteArray(Charsets.UTF_8).toRequestBody(OCTET))
                .build(),
        )

    private fun execute(request: Request): DavResponse {
        var current = request
        repeat(5) {
            val (code, location, text) = http.newCall(current).execute().use { response ->
                Triple(response.code, response.header("Location"), response.body?.string().orEmpty())
            }
            if (code in listOf(301, 302, 307, 308) && !location.isNullOrBlank()) {
                val next = current.url.resolve(location) ?: return DavResponse(code, text)
                current = current.newBuilder().url(next).build()
            } else {
                return DavResponse(code, text)
            }
        }
        throw ImportFailedException("WebDAV 重定向过多")
    }

    private fun errorMessage(action: String, code: Int, body: String): String = when (code) {
        401 -> "WebDAV 用户名或密码错误。坚果云请用邮箱和应用密码，不要用登录密码"
        403 -> "网盘拒绝访问。坚果云请用应用密码，并在网页端「安全选项」关闭微信二次验证"
        404 -> "${action}失败：路径不存在。坚果云请把文件放在子文件夹，例如 /dav/sysukcb/sysukcb.json"
        405 -> "${action}失败：网盘不支持该操作"
        507 -> "网盘空间不足"
        else -> {
            val hint = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            if (hint.isBlank()) "WebDAV ${action}失败（$code）" else "WebDAV ${action}失败（$code）：$hint"
        }
    }

    private fun davAuth(user: String, password: String): String =
        Credentials.basic(
            user.trim(),
            password.trim().replace(Regex("\\s+"), ""),
            Charsets.UTF_8,
        )

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val UA = "sysukcb/android"
        const val DEFAULT_FILE = "sysukcb.json"
        const val RESERVED_STEM = "sysukcb"
        const val DEFAULT_NUTSTORE_FILE_URL = "https://dav.jianguoyun.com/dav/sysukcb/sysukcb.json"
        private val MKCOL_OK = setOf(200, 201, 204, 301, 302, 403, 405, 409)
        private val HREF_REGEX = Regex("(?is)<(?:[\\w-]+:)?href\\s*>(.*?)</(?:[\\w-]+:)?href\\s*>")
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>"""

        fun normalizeFileUrl(raw: String): HttpUrl {
            var value = raw.trim()
            if (value.isBlank()) throw ImportFailedException("请填写 WebDAV 地址")
            if (!value.contains("://")) value = "https://$value"
            if (value.endsWith("/")) value += DEFAULT_FILE
            var url = runCatching { value.toHttpUrl() }.getOrElse {
                throw ImportFailedException("WebDAV 地址无效")
            }
            val last = url.pathSegments.lastOrNull().orEmpty()
            if (last.isBlank() || !last.contains('.')) {
                url = url.newBuilder().addPathSegment(DEFAULT_FILE).build()
            }
            return nestNutstoreRootFile(url)
        }

        private fun nestNutstoreRootFile(url: HttpUrl): HttpUrl {
            if (!url.host.contains("jianguoyun", ignoreCase = true)) return url
            val segs = url.pathSegments.filter { it.isNotEmpty() }
            if (segs.size == 2 && segs[0].equals("dav", ignoreCase = true) && segs[1].contains('.')) {
                return url.newBuilder()
                    .query(null)
                    .fragment(null)
                    .encodedPath("/dav/$RESERVED_STEM/")
                    .addPathSegment(segs[1])
                    .build()
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
            var cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.endsWith(".json", ignoreCase = true)) {
                cleaned = cleaned.dropLast(5).trim()
            }
            cleaned = cleaned.take(32)
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

        private fun decodePath(value: String): String =
            runCatching {
                URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrDefault(value)
    }

    private data class DavResponse(val code: Int, val body: String)
}
