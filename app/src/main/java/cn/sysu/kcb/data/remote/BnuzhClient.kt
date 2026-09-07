package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.school.School
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class BnuzhClient(private val cookies: CookieStore) {
    private val school = School.Bnuzh

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .cookieJar(BnuzhStoreCookieJar(cookies))
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
        fields: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        referer: String? = null,
        ajax: Boolean = true,
    ): String {
        val url = path.toAbsolute().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = base(url.toString(), referer, ajax = ajax)
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
                if (ajax) "application/json, text/javascript, text/plain, */*; q=0.01"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .header("Origin", school.apiOrigin)
            .header("User-Agent", DESKTOP_UA)
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        builder.header("Referer", referer ?: "${school.apiOrigin}$HOMES")
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
                val body = decodeBody(response)
                if (response.code in listOf(401, 403) || looksLikeLoginPage(body)) {
                    throw SessionExpiredException()
                }
                if (response.code !in 200..299) {
                    throw ImportFailedException("北师珠教务接口 ${response.code}")
                }
                return body
            }
        }
        throw SessionExpiredException("登录跳转过多")
    }

    private fun decodeBody(response: okhttp3.Response): String {
        val rawBody = response.body ?: return ""
        val headerCharset = rawBody.contentType()?.charset()
        val bytes = rawBody.bytes()
        if (bytes.isEmpty()) return ""
        val latin = String(bytes, Charsets.ISO_8859_1)
        val meta = Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(latin)?.groupValues?.get(1)
        val charset = when {
            headerCharset != null -> headerCharset
            !meta.isNullOrBlank() -> runCatching { Charset.forName(meta) }.getOrNull()
            else -> null
        } ?: Charsets.UTF_8
        val text = String(bytes, charset)
        if (charset.name().equals("UTF-8", ignoreCase = true) && looksLikeMisdecodedGbk(text)) {
            val gbk = String(bytes, Charset.forName("GBK"))
            if (gbk.contains("学年") || gbk.contains("课程") || gbk.contains("考试")) return gbk
        }
        return text
    }

    private fun looksLikeMisdecodedGbk(text: String): Boolean {
        if (text.contains("学年") || text.contains("课程") || text.contains("考试")) return false
        return text.contains("ѧ") || text.contains("¿") || text.contains("γ")
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
        const val HOMES = "/frame/homes.html"
        const val TIMETABLE_PAGE = "/student/xkjg.wdkb.jsp"
        const val TIMETABLE_MY = "/student/xkjg.wdkb.my.jsp"
        const val SET_TOKEN = "/frame/menus/js/SetTokenkey.jsp"
        const val YEAR_TERM = "/jw/common/showYearTerm.action"
        const val DROP_LISTS = "/frame/droplist/getDropLists.action"
        const val TIMETABLE_DATA = "/wsxk/xkjg.ckdgxsxdkchj_data10319.jsp"
        const val TIMETABLE_DATA_FALLBACK = "/wsxk/xkjg.ckdgxsxdkchj_data.jsp"
        const val EXAM_PAGE = "/student/ksap.ksapb.html"
        const val EXAM_TABLE = "/taglib/DataTable.jsp"
        const val MENU_TIMETABLE = "JW130418"
        const val MENU_EXAM = "JW130603"
        const val EXAM_TABLE_ID = "2538"
        const val COMBO_TERM = "Ms_KBBP_FBXKJGXNXQ"
        const val COMBO_EXAM = "Ms_KSSW_FBXNXQKSLC"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun isJwxtHost(host: String): Boolean =
            host.equals("jwxt.bnuzh.edu.cn", ignoreCase = true)

        fun isLoginUrl(url: HttpUrl): Boolean {
            val path = url.encodedPath.lowercase()
            val host = url.host.lowercase()
            if (host.contains("cas.bnuzh.edu.cn") && path.contains("/cas/login")) return true
            return host.contains("jwxt.bnuzh.edu.cn") &&
                (path == "/caslogin" || path.endsWith("/caslogin"))
        }

        fun looksLikeLoginPage(body: String): Boolean {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
            if (body.contains("\"xn\"") && (body.contains("xqM") || body.contains("\"xq\""))) return false
            if (body.contains("[课程号]") || body.contains("上课时间") || body.contains("考试时间")) return false
            val hasUser = body.contains("name=\"username\"") ||
                body.contains("id=\"username\"") ||
                body.contains("name=\"yhm\"")
            val hasPassword = body.contains("type=\"password\"") || body.contains("name=\"password\"")
            return hasUser && hasPassword
        }
    }
}

private class BnuzhStoreCookieJar(private val cookies: CookieStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith("bnuzh.edu.cn")) return emptyList()
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
