package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.ExamWeekEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.local.WeekdayEntity
import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.repo.TimetableRepository
import cn.sysu.kcb.data.school.School
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.domain.WeekMask
import cn.sysu.kcb.domain.cleanJwxt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class JwxtImportService(
    private val api: JwxtApi,
    private val json: Json,
    private val cookies: CookieStore,
    private val repo: TimetableRepository,
    private val settings: SettingsRepository,
) : SchoolImporter {
    override suspend fun isLoggedIn(): Boolean = checkSession().status == SessionStatus.Valid

    override suspend fun checkSession(): SessionCheckResult = withContext(Dispatchers.IO) {
        cookies.syncFromWebView(School.Sysu)
        if (!cookies.hasSession(School.Sysu)) return@withContext SessionCheckResult(SessionStatus.LoggedOut)
        runCatching {
            val body = api.showNewAcadlist()
            requireOk(body)
            SessionCheckResult(SessionStatus.Valid)
        }.getOrElse { error ->
            when (error) {
                is SessionExpiredException -> SessionCheckResult(SessionStatus.Expired, error.message.orEmpty())
                else -> SessionCheckResult(SessionStatus.Unreachable, error.message.orEmpty())
            }
        }
    }

    suspend fun importAll(semesterOverride: String? = null): String =
        importAllYears(onlyCurrent = semesterOverride != null, semesterOverride = semesterOverride)

    override suspend fun importAllYears(
        onlyCurrent: Boolean,
        semesterOverride: String?,
        onProgress: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        cookies.syncFromWebView(School.Sysu)
        if (!cookies.hasSession(School.Sysu)) throw SessionExpiredException()
        onProgress("正在验证登录…")
        val current = api.showNewAcadlist()
        saveRaw("showNewAcadlist", "", current)
        requireOk(current)
        val currentObj = dataObject(current)
        val jwxtCurrent = currentObj.str("acadYearSemester").ifBlank { error("无法读取当前学年学期") }
        val focus = semesterOverride?.ifBlank { null } ?: jwxtCurrent

        val listed = runCatching {
            val box = api.findAcadyeartermNamesBox()
            saveRaw("findAcadyeartermNamesBox", jwxtCurrent, box)
            dataArray(box).map { it.jsonObject.str("acadYearSemester") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        val generated = SemesterRange.span(jwxtCurrent, before = 8, after = 8)
        val jwxtOrd = SemesterRange.ordinal(jwxtCurrent)
        val extra = listed.filter { sem ->
            val ord = SemesterRange.ordinal(sem) ?: return@filter false
            jwxtOrd != null && kotlin.math.abs(ord - jwxtOrd) <= 8
        }
        val targets = if (onlyCurrent) {
            listOf(focus)
        } else {
            (generated + extra + focus).distinct().sortedByDescending { SemesterRange.ordinal(it) ?: 0 }
        }

        onProgress("正在读取节次和星期…")
        runCatching {
            val weekdays = api.weekdays()
            saveRaw("findcodedataNames", jwxtCurrent, weekdays)
            requireOk(weekdays)
            repo.replaceWeekdays(
                dataArray(weekdays).map {
                    val o = it.jsonObject
                    WeekdayEntity(o.str("dataNumber"), o.str("dataName"))
                },
            )
        }

        repo.clearCurrentFlag()
        var importedCount = 0
        for ((index, sem) in targets.withIndex()) {
            onProgress("正在导入 $sem（${index + 1}/${targets.size}）")
            val ok = runCatching {
                importSemester(
                    semester = sem,
                    currentMeta = if (sem == jwxtCurrent) currentObj else null,
                    isCurrent = sem == jwxtCurrent,
                )
            }.isSuccess
            if (ok) importedCount++
        }

        if (importedCount == 0) throw ImportFailedException("没有成功导入任何学期，请重新登录后再试")
        val previous = settings.snapshot().selectedSemester
        settings.setSelectedSemester(previous.takeIf { it.isNotBlank() } ?: jwxtCurrent)
        onProgress("导入完成")
        jwxtCurrent
    }

    private suspend fun importSemester(
        semester: String,
        currentMeta: JsonObject?,
        isCurrent: Boolean,
    ) {
        repo.upsertSemester(
            SemesterEntity(
                acadYearSemester = semester,
                acadYear = currentMeta?.str("acadYear").orEmpty().ifBlank { semester },
                acadSemester = currentMeta?.int("acadSemester")
                    ?: semester.substringAfter("-").toIntOrNull() ?: 0,
                startMillis = currentMeta?.long("acadStartdate") ?: 0L,
                endMillis = currentMeta?.long("acadEnddate") ?: 0L,
                isCurrent = isCurrent,
            ),
        )

        val periodsResp = api.minorName(semester)
        saveRaw("minorName", semester, periodsResp)
        if (runCatching { requireOk(periodsResp); true }.getOrDefault(false)) {
            repo.replacePeriods(
                semester,
                dataArray(periodsResp).map {
                    val o = it.jsonObject
                    PeriodEntity(
                        acadYearSemester = semester,
                        sectionNumber = o.int("sectionNumber"),
                        minorName = o.str("minorName"),
                        startTime = o.str("startTime"),
                        endTime = o.str("endTime"),
                        bigSection = o.str("bigSection"),
                        bigSectionName = o.str("bigSectionName"),
                    )
                },
            )
        }

        val weeklyResp = api.weeklyList(semester)
        saveRaw("school-calender/weekly", semester, weeklyResp)
        if (runCatching { requireOk(weeklyResp); true }.getOrDefault(false)) {
            val weeklyData = dataObject(weeklyResp)
            val weeklyList = weeklyData["weeklyList"]?.jsonArray.orEmpty()
            val nowWeekly = weeklyData.str("nowWeekly").toIntOrNull()
                ?: weeklyData.int("nowTimeWeekly").takeIf { it > 0 }
                ?: 1
            val anchor = runCatching { api.schoolCalender(semester, nowWeekly) }.getOrNull()
            if (anchor != null) saveRaw("school-calender/$nowWeekly", semester, anchor)
            val range = anchor?.let { runCatching { dataObject(it) }.getOrNull() }
            val anchorStart = range?.str("startTime")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            val anchorEnd = range?.str("endTime")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            val weeks = weeklyList.map { item ->
                val o = item.jsonObject
                val weekly = o.int("weekly")
                val offset = (weekly - nowWeekly).toLong()
                WeekEntity(
                    acadYearSemester = semester,
                    weekly = weekly,
                    weeklyName = o.str("weeklyName").ifBlank { "第${weekly}周" },
                    startDate = anchorStart?.plusWeeks(offset)?.toString(),
                    endDate = anchorEnd?.plusWeeks(offset)?.toString(),
                )
            }
            repo.replaceWeeks(semester, weeks)

            if (isCurrent) {
                runCatching {
                    val weeklyTable = api.selectStudentClassTable(semester, nowWeekly)
                    saveRaw("selectStudentClassTable", semester, weeklyTable)
                }
                runCatching {
                    val unknownBody = buildJsonObject {
                        put("pageNo", 1)
                        put("pageSize", 10)
                        put("total", true)
                        put("param", buildJsonObject { put("schoolSemester", semester) })
                    }
                    saveRaw("class/unknown", semester, api.classUnknown(unknownBody))
                }
                runCatching {
                    val mediationBody = buildJsonObject {
                        put("pageNo", 1)
                        put("pageSize", 10)
                        put("total", true)
                        put("param", buildJsonObject { put("yearTerm", semester) })
                    }
                    saveRaw("mediationApply/timetable/list", semester, api.mediationList(mediationBody))
                }
            }
        }

        val queryBody = buildJsonObject {
            put("acadYear", semester)
            put("submitFlag", "1")
            put("nothroughCourseFlag", "1")
        }
        val table = api.studentQuery(queryBody)
        saveRaw("studentQuery", semester, table)
        requireOk(table)
        repo.replaceImportedCourses(semester, parseCourses(semester, dataObject(table), settings.snapshot().themeColor))
        runCatching { importExams(semester) }
        if (isCurrent) {
            previousSemester(semester)?.let { prev ->
                runCatching { importExams(prev) }
            }
        }
    }

    private suspend fun importExams(semester: String) {
        val weeksResp = api.queryExamWeekName(semester)
        saveRaw("queryExamWeekName", semester, weeksResp)
        requireOk(weeksResp)
        val weekItems = dataArray(weeksResp).map { it.jsonObject }.filter { it.str("examWeekId").isNotBlank() }
        repo.replaceExamWeeks(
            semester,
            weekItems.map { week ->
                ExamWeekEntity(
                    acadYearSemester = semester,
                    examWeekId = week.str("examWeekId"),
                    examWeekName = week.str("examWeekName"),
                    startDate = week.str("startDate"),
                    endDate = week.str("endDate"),
                )
            },
        )
        val exams = mutableListOf<ExamEntity>()
        for (week in weekItems) {
            val body = buildJsonObject {
                put("acadYear", semester)
                put("examWeekId", week.str("examWeekId"))
                put("examWeekName", week.str("examWeekName"))
                put("examDate", "")
            }
            val examResp = runCatching { api.queryStuExamInfo(body) }.getOrNull() ?: continue
            saveRaw("queryStuEaxmInfo/${week.str("examWeekId")}", semester, examResp)
            if (runCatching { requireOk(examResp); true }.getOrDefault(false)) {
                exams += parseExams(semester, week.str("examWeekId"), examResp)
            }
        }
        repo.replaceExams(semester, exams.distinctBy { it.examIndex ?: "${it.subjectName}|${it.examDate}|${it.startTime}" })
    }

    private fun parseExams(semester: String, weekId: String, resp: JsonObject): List<ExamEntity> {
        return collectExamObjects(resp["data"]).mapNotNull { el ->
            val name = el.str("examSubjectName").ifBlank { return@mapNotNull null }
            ExamEntity(
                acadYearSemester = el.str("acadYear").ifBlank { semester },
                examIndex = el.str("index").ifBlank { null },
                subjectName = name,
                examDate = el.str("examDate").ifBlank { el.str("examDateStr") },
                startTime = el.str("startTime"),
                endTime = el.str("endTime"),
                duration = el.str("duration"),
                classroom = el.str("classroomNumber"),
                examMode = el.str("examMode"),
                examStage = el.str("examStage"),
                examWeekName = el.str("examWeekName"),
                examWeekId = weekId.ifBlank { el.str("examWeekId") },
                weekly = el.int("weekly"),
                dayOfWeek = el.str("week").toIntOrNull() ?: el.int("week"),
                startPeriod = el.int("startClassTimes"),
                endPeriod = el.int("endClassTimes"),
                extraJson = "{}",
            )
        }
    }

    private fun collectExamObjects(el: JsonElement?): List<JsonObject> {
        if (el == null || el is JsonNull) return emptyList()
        return when (el) {
            is JsonArray -> el.flatMap { collectExamObjects(it) }
            is JsonObject -> when {
                el.containsKey("examSubjectName") -> listOf(el)
                el.containsKey("timetable") -> collectExamObjects(el["timetable"])
                else -> el.values.flatMap { collectExamObjects(it) }
            }
            else -> emptyList()
        }
    }

    private fun parseCourses(semester: String, data: JsonObject, themeColor: Long): List<CourseEntity> {
        val timetable = data["timetable"]?.jsonObject ?: return emptyList()
        val result = mutableListOf<CourseEntity>()
        for ((_, value) in timetable) {
            if (value is JsonNull) continue
            val items = when (value) {
                is JsonArray -> value
                else -> continue
            }
            for (item in items) {
                val o = item.jsonObject
                val name = o.str("courseName").cleanJwxt()
                if (name.isBlank()) continue
                val startWeek = o.int("startWeek").takeIf { it > 0 } ?: 1
                result += CourseEntity(
                    acadYearSemester = semester,
                    source = "imported",
                    locallyEdited = false,
                    classesId = o.str("classesId").ifBlank { null },
                    sumClassesId = o.str("sumClassesID").ifBlank { null },
                    courseName = name,
                    teacher = o.str("teachingStaffName").cleanJwxt(),
                    place = o.str("classPlace").cleanJwxt(),
                    dayOfWeek = o.str("week").toIntOrNull() ?: o.int("week"),
                    startPeriod = o.int("startClassTimes"),
                    endPeriod = o.int("endClassTimes"),
                    startWeek = startWeek,
                    weeksMask = WeekMask.parse(o.str("timeDetail"), startWeek),
                    timeDetail = o.str("timeDetail").cleanJwxt(),
                    color = CourseColors.of(name, themeColor),
                    extraJson = "{}",
                )
            }
        }
        return result.distinctBy { listOf(it.classesId, it.dayOfWeek, it.startPeriod, it.weeksMask) }
    }

    private suspend fun saveRaw(endpoint: String, semester: String, body: JsonObject) {
        // 不再把教务原文写入 raw_imports。
    }

    private fun requireOk(body: JsonObject) {
        val code = body["code"]?.jsonPrimitive?.intOrNull
        if (code != null && code != 200) {
            val msg = body.str("message").ifBlank { body.str("msg") }
            if (code == 401 || code == 403) throw SessionExpiredException(msg.ifBlank { "登录已失效" })
            throw ImportFailedException(msg.ifBlank { "教务接口返回 $code" })
        }
    }

    private fun dataObject(body: JsonObject): JsonObject {
        val data = body["data"]
        return data as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun dataArray(body: JsonObject): JsonArray {
        val data = body["data"]
        return data as? JsonArray ?: JsonArray(emptyList())
    }

    private fun previousSemester(sem: String): String? {
        val parts = sem.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        return if (parts[1] == "2") "$year-1" else "${year - 1}-2"
    }

    private fun JsonObject.str(key: String): String {
        val el = this[key] ?: return ""
        return when (el) {
            is JsonNull -> ""
            is JsonPrimitive -> el.contentOrNull.orEmpty()
            else -> el.toString()
        }
    }

    private fun JsonObject.int(key: String): Int {
        val el = this[key] ?: return 0
        return when (el) {
            is JsonPrimitive -> el.intOrNull ?: el.contentOrNull?.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun JsonObject.long(key: String): Long {
        val el = this[key] ?: return 0L
        return when (el) {
            is JsonPrimitive -> el.longOrNull ?: el.contentOrNull?.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}
