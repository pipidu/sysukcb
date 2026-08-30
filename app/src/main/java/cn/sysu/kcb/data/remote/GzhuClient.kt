package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.school.School
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class GzhuClient(private val cookies: CookieStore) {
    private val school = School.Gzhu

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(StoreCookieJar(cookies))
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    fun get(path: String, query: Map<String, String> = emptyMap(), referer: String? = null): String {
        val url = path.toAbsolute().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = base(url.toString(), referer, ajax = false).get().build()
        return execute(request)
    }

    fun postForm(
        path: String,
        fields: Map<String, String>,
        query: Map<String, String> = emptyMap(),
        referer: String? = null,
    ): String {
        val url = path.toAbsolute().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = base(url.toString(), referer, ajax = true)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .post(body)
            .build()
        return execute(request)
    }

    private fun base(url: String, referer: String?, ajax: Boolean): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header(
                "Accept",
                if (ajax) "application/json, text/javascript, */*; q=0.01"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .header("Origin", school.apiOrigin)
            .header("User-Agent", DESKTOP_UA)
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        builder.header(
            "Referer",
            referer ?: "${school.apiOrigin}$TIMETABLE_INDEX?gnmkdm=$GNMKDM_KB&layout=default",
        )
        return builder
    }

    private fun execute(request: Request): String {
        var current = request
        var hops = 0
        while (hops < 6) {
            hops++
            http.newCall(current).execute().use { response ->
                mergeSetCookie(response.headers("Set-Cookie"), response.request.url)
                if (response.code in 301..308) {
                    val location = response.header("Location").orEmpty()
                    val next = response.request.url.resolve(location)
                        ?: throw SessionExpiredException()
                    if (isLoginUrl(next)) throw SessionExpiredException()
                    if (!isJwxtHost(next.host)) throw SessionExpiredException()
                    current = current.newBuilder().url(next).build()
                    return@use
                }
                val body = response.body?.string().orEmpty()
                if (response.code in listOf(401, 403) || looksLikeLoginPage(body)) {
                    throw SessionExpiredException()
                }
                if (response.code !in 200..299) {
                    throw ImportFailedException("广大教务接口 ${response.code}")
                }
                return body
            }
        }
        throw SessionExpiredException("登录跳转过多")
    }

    private fun mergeSetCookie(setCookies: List<String>, url: HttpUrl) {
        if (setCookies.isEmpty()) return
        val merged = linkedMapOf<String, String>()
        for (part in cookies.cookieHeader().split(";")) {
            val kv = part.trim()
            val name = kv.substringBefore("=")
            if (name.isNotBlank()) merged[name] = kv
        }
        for (raw in setCookies) {
            val parsed = Cookie.parse(url, raw) ?: continue
            merged[parsed.name] = "${parsed.name}=${parsed.value}"
        }
        cookies.save(merged.values.joinToString("; "))
    }

    private fun String.toAbsolute() = when {
        startsWith("http") -> toHttpUrl()
        startsWith("/") -> "${school.apiOrigin}$this".toHttpUrl()
        else -> "${school.apiOrigin}/$this".toHttpUrl()
    }

    companion object {
        const val GNMKDM_KB = "N253508"
        const val GNMKDM_EXAM = "N35811031"
        const val TIMETABLE_INDEX = "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"
        const val TIMETABLE_DATA = "/jwglxt/kbcx/xskbcx_cxXsgrkb.html"
        const val PERIODS = "/jwglxt/kbcx/xskbcx_cxRjc.html"
        const val MENU = "/jwglxt/xtgl/index_initMenu.html"
        const val EXAM_PAGE = "/jwglxt/design/viewFunc_cxDesignFuncPageIndex.html"
        const val EXAM_LIST = "/jwglxt/design/funcData_cxFuncDataList.html"
        const val EXAM_WIDGET_GUID = "EC4C2CEBA4825066E0530100007FBC11"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun isJwxtHost(host: String): Boolean =
            host.equals("jwxt.gzhu.edu.cn", ignoreCase = true)

        fun isLoginUrl(url: HttpUrl): Boolean {
            val path = url.encodedPath.lowercase()
            val host = url.host.lowercase()
            return path.contains("login_slogin") ||
                host.contains("newcas.gzhu.edu.cn") ||
                path.contains("/cas/login")
        }

        fun looksLikeLoginPage(body: String): Boolean {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
            if (body.contains("id=\"xnm\"") || body.contains("id='xnm'")) return false
            if (body.contains("clickMenu(") || body.contains("\"kbList\"")) return false
            val hasUser = body.contains("name=\"yhm\"") || body.contains("id=\"yhm\"")
            val hasPassword = body.contains("type=\"password\"") || body.contains("name=\"mm\"")
            return hasUser && hasPassword
        }
    }
}

private class StoreCookieJar(private val cookies: CookieStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith("gzhu.edu.cn")) return emptyList()
        return cookies.cookieHeader().split(";").mapNotNull { part ->
            val kv = part.trim()
            if (kv.isEmpty() || "=" !in kv) return@mapNotNull null
            Cookie.parse(url, kv)
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) {
        if (cookieList.isEmpty()) return
        val merged = linkedMapOf<String, String>()
        for (part in cookies.cookieHeader().split(";")) {
            val kv = part.trim()
            val name = kv.substringBefore("=")
            if (name.isNotBlank()) merged[name] = kv
        }
        for (cookie in cookieList) {
            merged[cookie.name] = "${cookie.name}=${cookie.value}"
        }
        cookies.save(merged.values.joinToString("; "))
    }
}
