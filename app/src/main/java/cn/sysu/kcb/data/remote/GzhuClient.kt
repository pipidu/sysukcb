package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.school.School
import okhttp3.FormBody
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
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        builder.header(
            "Referer",
            referer ?: "${school.apiOrigin}$TIMETABLE_INDEX?gnmkdm=$GNMKDM_KB&layout=default",
        )
        val cookie = cookies.cookieHeader()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return builder
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val location = response.header("Location").orEmpty()
            if (response.code in 301..308 || response.code in listOf(401, 403)) {
                if (isLoginRedirect(location) || response.code in listOf(401, 403)) {
                    throw SessionExpiredException()
                }
                if (response.code in 301..308) throw SessionExpiredException()
            }
            val body = response.body?.string().orEmpty()
            if (looksLikeLoginPage(body)) throw SessionExpiredException()
            return body
        }
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
        const val EXAM_FALLBACK = "/jwglxt/kwgl/kscx_cxXsksxxIndex.html"

        fun isLoginRedirect(location: String): Boolean {
            val lower = location.lowercase()
            return lower.contains("login_slogin") ||
                lower.contains("newcas.gzhu.edu.cn") ||
                lower.contains("/cas/login")
        }

        fun looksLikeLoginPage(body: String): Boolean {
            if (body.contains("clickMenu(")) return false
            return body.contains("login_slogin") ||
                body.contains("name=\"yhm\"") ||
                body.contains("id=\"yhm\"")
        }
    }
}
