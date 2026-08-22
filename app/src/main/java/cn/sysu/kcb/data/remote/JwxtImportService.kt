package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.RawImportEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.local.WeekdayEntity
import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.repo.TimetableRepository
import cn.sysu.kcb.domain.CourseColors
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
) {
    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        cookies.syncFromWebView()
        if (!cookies.hasSession()) return@withContext false
        runCatching {
            val body = api.loginStatus()
            requireOk(body)
            true
        }.getOrDefault(false)
    }

    suspend fun importAll(semesterOverride: String? = null): String = withContext(Dispatchers.IO) {
        cookies.syncFromWebView()
        if (!cookies.hasSession()) throw SessionExpiredException()
        val status = api.loginStatus()
        saveRaw("login/status", semesterOverride.orEmpty(), status)
        requireOk(status)

        val current = api.showNewAcadlist()
        saveRaw("showNewAcadlist", "", current)
        requireOk(current)
        val currentObj = dataObject(current)
        val currentSem = semesterOverride
            ?: currentObj.str("acadYearSemester").ifBlank { error("无法读取当前学年学期") }

        val semester = SemesterEntity(
            acadYearSemester = currentSem,
            acadYear = currentObj.str("acadYear").ifBlank { currentSem },
            acadSemester = currentObj.int("acadSemester"),
            startMillis = currentObj.long("acadStartdate"),
            endMillis = currentObj.long("acadEnddate"),
            isCurrent = semesterOverride == null || semesterOverride == currentObj.str("acadYearSemester"),
        )
        repo.upsertSemester(semester)

        val box = api.findAcadyeartermNamesBox()
        saveRaw("findAcadyeartermNamesBox", currentSem, box)
        requireOk(box)
        dataArray(box).forEach { item ->
            val obj = item.jsonObject
            val id = obj.str("acadYearSemester")
            if (id.isNotBlank() && id != currentSem) {
                repo.upsertSemester(
                    SemesterEntity(
                        acadYearSemester = id,
                        acadYear = obj.str("acadYear").ifBlank { id },
                        acadSemester = obj.str("acadYear").toIntOrNull() ?: 0,
                        startMillis = 0,
                        endMillis = 0,
                        isCurrent = false,
                    ),
                )
            }
        }

        val weekdays = api.weekdays()
        saveRaw("findcodedataNames", currentSem, weekdays)
        requireOk(weekdays)
        repo.replaceWeekdays(
            dataArray(weekdays).map {
                val o = it.jsonObject
                WeekdayEntity(o.str("dataNumber"), o.str("dataName"))
            },
        )

        val periodsResp = api.minorName(currentSem)
        saveRaw("minorName", currentSem, periodsResp)
        requireOk(periodsResp)
        repo.replacePeriods(
            currentSem,
            dataArray(periodsResp).map {
                val o = it.jsonObject
                PeriodEntity(
                    acadYearSemester = currentSem,
                    sectionNumber = o.int("sectionNumber"),
                    minorName = o.str("minorName"),
                    startTime = o.str("startTime"),
                    endTime = o.str("endTime"),
                    bigSection = o.str("bigSection"),
                    bigSectionName = o.str("bigSectionName"),
                )
            },
        )

        val weeklyResp = api.weeklyList(currentSem)
        saveRaw("school-calender/weekly", currentSem, weeklyResp)
        requireOk(weeklyResp)
        val weeklyData = dataObject(weeklyResp)
        val weeklyList = weeklyData["weeklyList"]?.jsonArray.orEmpty()
        val nowWeekly = weeklyData.str("nowWeekly").toIntOrNull()
            ?: weeklyData.int("nowTimeWeekly").takeIf { it > 0 }
            ?: 1
        val weeks = mutableListOf<WeekEntity>()
        for (item in weeklyList) {
            val o = item.jsonObject
            val weekly = o.int("weekly")
            val cal = runCatching { api.schoolCalender(currentSem, weekly) }.getOrNull()
            if (cal != null) {
                saveRaw("school-calender/$weekly", currentSem, cal)
            }
            val range = cal?.let { runCatching { dataObject(it) }.getOrNull() }
            weeks += WeekEntity(
                acadYearSemester = currentSem,
                weekly = weekly,
                weeklyName = o.str("weeklyName").ifBlank { "第${weekly}周" },
                startDate = range?.str("startTime"),
                endDate = range?.str("endTime"),
            )
        }
        repo.replaceWeeks(currentSem, weeks)

        val queryBody = buildJsonObject {
            put("acadYear", currentSem)
            put("submitFlag", "1")
            put("nothroughCourseFlag", "1")
        }
        val table = api.studentQuery(queryBody)
        saveRaw("studentQuery", currentSem, table)
        requireOk(table)
        val imported = parseCourses(currentSem, dataObject(table))
        repo.replaceImportedCourses(currentSem, imported)

        runCatching {
            val weeklyTable = api.selectStudentClassTable(currentSem, nowWeekly)
            saveRaw("selectStudentClassTable", currentSem, weeklyTable)
        }

        val unknownBody = buildJsonObject {
            put("pageNo", 1)
            put("pageSize", 10)
            put("total", true)
            put("param", buildJsonObject { put("schoolSemester", currentSem) })
        }
        saveRaw("class/unknown", currentSem, api.classUnknown(unknownBody))

        val mediationBody = buildJsonObject {
            put("pageNo", 1)
            put("pageSize", 10)
            put("total", true)
            put("param", buildJsonObject { put("yearTerm", currentSem) })
        }
        saveRaw("mediationApply/timetable/list", currentSem, api.mediationList(mediationBody))

        importExams(currentSem)
        previousSemester(currentSem)?.let { prev ->
            runCatching { importExams(prev) }
        }

        settings.setSelectedSemester(currentSem)
        currentSem
    }

    private suspend fun importExams(semester: String) {
        val weeksResp = api.queryExamWeekName(semester)
        saveRaw("queryExamWeekName", semester, weeksResp)
        requireOk(weeksResp)
        val exams = mutableListOf<ExamEntity>()
        for (item in dataArray(weeksResp)) {
            val week = item.jsonObject
            val body = buildJsonObject {
                put("acadYear", semester)
                put("examWeekId", week.str("examWeekId"))
                put("examWeekName", week.str("examWeekName"))
                put("examDate", "")
            }
            val examResp = api.queryStuExamInfo(body)
            saveRaw("queryStuEaxmInfo/${week.str("examWeekId")}", semester, examResp)
            requireOk(examResp)
            exams += parseExams(semester, week.str("examWeekId"), examResp)
        }
        repo.replaceExams(semester, exams)
    }

    private fun parseCourses(semester: String, data: JsonObject): List<CourseEntity> {
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
                    color = CourseColors.of(name),
                    extraJson = o.toString(),
                )
            }
        }
        return result.distinctBy { listOf(it.classesId, it.dayOfWeek, it.startPeriod, it.weeksMask) }
    }

    private fun parseExams(semester: String, weekId: String, resp: JsonObject): List<ExamEntity> {
        val dataEl = resp["data"] ?: return emptyList()
        val blocks = when (dataEl) {
            is JsonArray -> dataEl
            is JsonObject -> {
                val timetable = dataEl["timetable"]
                if (timetable is JsonObject) {
                    timetable.values.filterIsInstance<JsonArray>().flatten()
                } else emptyList()
            }
            else -> emptyList()
        }
        return blocks.mapNotNull { el ->
            if (el !is JsonObject) return@mapNotNull null
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
                examWeekId = weekId,
                weekly = el.int("weekly"),
                dayOfWeek = el.str("week").toIntOrNull() ?: el.int("week"),
                startPeriod = el.int("startClassTimes"),
                endPeriod = el.int("endClassTimes"),
                extraJson = el.toString(),
            )
        }
    }

    private suspend fun saveRaw(endpoint: String, semester: String, body: JsonObject) {
        repo.saveRaw(
            RawImportEntity(
                key = "$endpoint|$semester",
                acadYearSemester = semester,
                endpoint = endpoint,
                json = json.encodeToString(JsonObject.serializer(), body),
                fetchedAt = System.currentTimeMillis(),
            ),
        )
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

    private fun JsonArray.flatten(): List<JsonElement> = flatMap { el ->
        when (el) {
            is JsonArray -> el
            JsonNull -> emptyList()
            else -> listOf(el)
        }
    }
}
