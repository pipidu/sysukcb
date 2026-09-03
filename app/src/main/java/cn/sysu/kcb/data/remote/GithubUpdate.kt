package cn.sysu.kcb.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

const val GITHUB_PAGE_URL = "https://github.com/pipidu/sysukcb"
private const val GITHUB_API_LATEST = "https://api.github.com/repos/pipidu/sysukcb/releases/latest"
const val GITHUB_RELEASE_MIRROR_PREFIX = "https://gh-proxy.com/"

fun mirroredGithubUrl(url: String, useMirror: Boolean): String {
    if (!useMirror) return url
    val trimmed = url.trim()
    if (trimmed.isBlank() || trimmed.startsWith(GITHUB_RELEASE_MIRROR_PREFIX)) return trimmed
    return GITHUB_RELEASE_MIRROR_PREFIX + trimmed
}

data class AppUpdate(
    val versionName: String,
    val versionCode: Int,
    val htmlUrl: String,
    val apkUrl: String?,
    val notes: String,
)

class GithubUpdateService(private val json: Json) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchLatest(useMirror: Boolean = false): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(mirroredGithubUrl(GITHUB_API_LATEST, useMirror))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "sysukcb-android")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                error("检查更新失败（${response.code}）")
            }
            val body = response.body?.string().orEmpty()
            json.decodeFromString(GithubReleaseDto.serializer(), body).toAppUpdate()
        }
    }

    suspend fun downloadApk(url: String, dest: File, onProgress: (received: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "sysukcb-android")
                .header("Accept", "application/octet-stream")
                .build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败（${response.code}）")
                val body = response.body ?: error("下载失败")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                dest.parentFile?.listFiles()?.forEach { child ->
                    if (child.name != dest.name && !child.name.endsWith(".part")) {
                        runCatching { child.delete() }
                    }
                }
                val tmp = File(dest.parentFile, "${dest.name}.part")
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            received += n
                            ensureActive()
                            onProgress(received, total)
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                if (!dest.isApkZip()) {
                    dest.delete()
                    error("下载的文件不是安装包，请切换镜像选项后重试")
                }
            }
        }
}

fun File.isApkZip(): Boolean {
    if (!isFile || length() < 4L) return false
    return inputStream().use { input ->
        val magic = ByteArray(2)
        val n = input.read(magic)
        n >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
    }
}

fun AppUpdate.isNewerThan(localCode: Int, localName: String): Boolean {
    if (versionCode > 0) return versionCode > localCode
    return compareVersionName(versionName, localName.substringBefore("-debug")) > 0
}

private fun compareVersionName(remote: String, local: String): Int {
    val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
    val b = local.split('.').map { it.toIntOrNull() ?: 0 }
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val da = a.getOrElse(i) { 0 }
        val db = b.getOrElse(i) { 0 }
        if (da != db) return da.compareTo(db)
    }
    return 0
}

@Serializable
private data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAssetDto> = emptyList(),
)

@Serializable
private data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

private fun GithubReleaseDto.toAppUpdate(): AppUpdate {
    val versionName = tagName.trim().removePrefix("v").removePrefix("V").ifBlank {
        name.orEmpty().substringAfterLast(' ').trim()
    }
    val versionCode = Regex("""versionCode\s*=\s*(\d+)""")
        .find(body.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: 0
    val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.browserDownloadUrl
    return AppUpdate(
        versionName = versionName.ifBlank { tagName },
        versionCode = versionCode,
        htmlUrl = htmlUrl,
        apkUrl = apk?.ifBlank { null },
        notes = body.orEmpty()
            .lineSequence()
            .filterNot { it.startsWith("versionCode=") || it.startsWith("versionName=") }
            .joinToString("\n")
            .trim(),
    )
}
