package cn.sysu.kcb.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

const val GITHUB_PAGE_URL = "https://github.com/pipidu/sysukcb"

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

    suspend fun fetchLatest(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/pipidu/sysukcb/releases/latest")
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
