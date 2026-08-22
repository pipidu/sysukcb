package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.prefs.CookieStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

fun nowT(): Long = System.currentTimeMillis() / 1000

interface JwxtApi {
    @GET("jwxt/api/login/status")
    suspend fun loginStatus(@Query("_t") t: Long = nowT()): JsonObject

    @GET("jwxt/base-info/acadyearterm/showNewAcadlist")
    suspend fun showNewAcadlist(@Query("_t") t: Long = nowT()): JsonObject

    @GET("jwxt/base-info/acadyearterm/findAcadyeartermNamesBox")
    suspend fun findAcadyeartermNamesBox(@Query("_t") t: Long = nowT()): JsonObject

    @GET("jwxt/base-info/school-calender/weekly")
    suspend fun weeklyList(
        @Query("academicYear") academicYear: String,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @GET("jwxt/base-info/school-calender")
    suspend fun schoolCalender(
        @Query("academicYear") academicYear: String,
        @Query("weekly") weekly: Int,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @GET("jwxt/base-info/AcadyeartermSet/minorName")
    suspend fun minorName(
        @Query("schoolYear") schoolYear: String,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @GET("jwxt/base-info/codedata/findcodedataNames")
    suspend fun weekdays(
        @Query("datableNumber") datableNumber: Int = 233,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @POST("jwxt/timetable-search/stuTimeTabPrint/studentQuery")
    suspend fun studentQuery(
        @Body body: JsonObject,
        @Header("menuId") menuId: String = "jwxsd_xskbcx",
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @GET("jwxt/timetable-search/classTableInfo/selectStudentClassTable")
    suspend fun selectStudentClassTable(
        @Query("academicYear") academicYear: String,
        @Query("weekly") weekly: Int,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @GET("jwxt/schedule/agg/commonScheduleExamTime/queryExamWeekName")
    suspend fun queryExamWeekName(
        @Query("yearTerm") yearTerm: String,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @POST("jwxt/examination-manage/classroomResource/queryStuEaxmInfo")
    suspend fun queryStuExamInfo(
        @Body body: JsonObject,
        @Header("menuId") menuId: String = "jwxsd_ksxxck",
        @Query("code") code: String = "jwxsd_ksxxck",
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @POST("jwxt/timetable-search/timeTabPrint/class/unknown")
    suspend fun classUnknown(
        @Body body: JsonObject,
        @Query("_t") t: Long = nowT(),
    ): JsonObject

    @POST("jwxt/schedule/agg/mediationApply/timetable/list")
    suspend fun mediationList(
        @Body body: JsonObject,
        @Query("_t") t: Long = nowT(),
    ): JsonObject
}

class SessionExpiredException(message: String = "登录已失效，请重新登录教务系统") : java.io.IOException(message)

class ImportFailedException(message: String) : Exception(message)

enum class SessionStatus { Valid, LoggedOut, Expired, Unreachable }

data class SessionCheckResult(val status: SessionStatus, val detail: String = "")

fun createJwxtJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

fun createJwxtApi(cookieStore: CookieStore, json: Json): JwxtApi {
    val interceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", CookieStore.JWXT_ORIGIN)
            .header("Referer", "https://jwxt.sysu.edu.cn/jwxt/mk/schedule-web/")
            .header("lastAccessTime", System.currentTimeMillis().toString())
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
        val cookie = cookieStore.cookieHeader()
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        if (original.header("menuId") == null) builder.header("menuId", "jwxsd_xskbcx")
        val response = chain.proceed(builder.build())
        if (response.code in listOf(301, 302, 303, 307, 308, 401, 403)) {
            response.close()
            throw SessionExpiredException()
        }
        val peek = response.peekBody(128).string()
        if (peek.contains("<html", ignoreCase = true) || peek.contains("cas.sysu.edu.cn")) {
            response.close()
            throw SessionExpiredException()
        }
        response
    }
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(interceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .followRedirects(false)
        .build()
    val contentType = "application/json".toMediaType()
    return Retrofit.Builder()
        .baseUrl("${CookieStore.JWXT_ORIGIN}/")
        .client(client)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
        .create(JwxtApi::class.java)
}
