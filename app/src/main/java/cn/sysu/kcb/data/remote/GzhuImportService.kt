package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.ExamWeekEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.prefs.CookieStore
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.repo.TimetableRepository
import cn.sysu.kcb.data.school.School
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.domain.WeekMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class GzhuImportService(
    private val client: GzhuClient,
    private val json: Json,
    private val cookies: CookieStore,
    private val repo: TimetableRepository,
    private val settings: SettingsRepository,
) : SchoolImporter {
    private val school = School.Gzhu

    override suspend fun isLoggedIn(): Boolean = checkSession().status == SessionStatus.Valid

    override suspend fun checkSession(): SessionCheckResult = withContext(Dispatchers.IO) {
        cookies.syncFromWebView(school)
        if (!cookies.hasSession(school)) return@withContext SessionCheckResult(SessionStatus.LoggedOut)
        runCatching {
            val html = client.get(
                GzhuClient.TIMETABLE_INDEX,
                query = mapOf(
                    "gnmkdm" to GzhuClient.GNMKDM_KB,
                    "layout" to "default",
                ),
                referer = "${school.apiOrigin}/jwglxt/xtgl/index_initMenu.html?jsdm=xs",
            )
            val logged = html.contains("id=\"xnm\"") ||
                html.contains("id='xnm'") ||
                html.contains("clickMenu(")
            if (!logged) throw SessionExpiredException()
            SessionCheckResult(SessionStatus.Valid)
        }.getOrElse { error ->
            when (error) {
                is SessionExpiredException -> SessionCheckResult(SessionStatus.Expired, error.message.orEmpty())
                else -> SessionCheckResult(SessionStatus.Unreachable, error.message.orEmpty())
            }
        }
    }

    override suspend fun importAllYears(
        onlyCurrent: Boolean,
        semesterOverride: String?,
        onProgress: suspend (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        cookies.syncFromWebView(school)
        if (!cookies.hasSession(school)) throw SessionExpiredException()
        onProgress("正在打开广大课表页…")
        val indexHtml = client.get(
            GzhuClient.TIMETABLE_INDEX,
            query = mapOf("gnmkdm" to GzhuClient.GNMKDM_KB, "layout" to "default"),
            referer = "${school.apiOrigin}/jwglxt/xtgl/index_initMenu.html?jsdm=xs",
        )
        saveRaw("xskbcxIndex", "", indexHtml)
        val csrf = parseCsrf(indexHtml)
        val currentXnm = parseSelected(indexHtml, "xnm")
            .ifBlank { SemesterRange.guessCurrent().substringBefore("-") }
        val currentXqm = parseSelected(indexHtml, "xqm").ifBlank {
            val term = SemesterRange.guessCurrent().substringAfter("-")
            if (term == "2") "12" else "3"
        }
        val jwxtCurrent = toAppSemester(currentXnm, currentXqm)
        val focus = semesterOverride?.ifBlank { null } ?: jwxtCurrent
        val listed = parseYearOptions(indexHtml).flatMap { xnm ->
            listOf(toAppSemester(xnm, "3"), toAppSemester(xnm, "12"))
        }
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

        repo.clearCurrentFlag()
        var importedCount = 0
        for ((index, sem) in targets.withIndex()) {
            onProgress("正在导入 $sem（${index + 1}/${targets.size}）")
            val ok = runCatching {
                importSemester(sem, csrf, isCurrent = sem == jwxtCurrent)
            }.isSuccess
            if (ok) importedCount++
        }
        if (importedCount == 0) throw ImportFailedException("没有成功导入任何学期，请重新登录后再试")
        val previous = settings.snapshot().selectedSemester
        settings.setSelectedSemester(previous.takeIf { it.isNotBlank() } ?: jwxtCurrent)
        onProgress("导入完成")
        jwxtCurrent
    }

    private suspend fun importSemester(semester: String, csrf: String, isCurrent: Boolean) {
        val (xnm, xqm) = toXnmXqm(semester)
        repo.upsertSemester(
            SemesterEntity(
                acadYearSemester = semester,
                acadYear = "$xnm-${xnm.toIntOrNull()?.plus(1) ?: xnm}",
                acadSemester = if (xqm == "12") 2 else 1,
                startMillis = 0L,
                endMillis = 0L,
                isCurrent = isCurrent,
            ),
        )
        val fields = linkedMapOf(
            "xnm" to xnm,
            "xqm" to xqm,
            "kzlx" to "ck",
            "xsdm" to "",
            "kclbdm" to "",
            "kclxdm" to "",
            "gnmkdm" to GzhuClient.GNMKDM_KB,
        )
        if (csrf.isNotBlank()) fields["csrftoken"] = csrf
        val raw = client.postForm(
            GzhuClient.TIMETABLE_DATA,
            fields = fields,
            query = mapOf("gnmkdm" to GzhuClient.GNMKDM_KB),
        )
        saveRaw("xskbcx_cxXsgrkb", semester, raw)
        val data = parseObject(raw) ?: throw ImportFailedException("课表返回不是 JSON")
        val courses = parseCourses(semester, data, settings.snapshot().themeColor)
        repo.replaceImportedCourses(semester, courses)

        val campusId = campusIdOf(data)
        val periods = runCatching { fetchPeriods(semester, xnm, xqm, campusId, csrf) }.getOrDefault(emptyList())
        if (periods.isNotEmpty()) repo.replacePeriods(semester, periods)
        else repo.replacePeriods(semester, GzhuDefaultPeriods.of(semester))

        repo.replaceWeeks(semester, weeksFromCourses(semester, courses))
        runCatching { importExams(semester, xnm, xqm, csrf) }
        if (isCurrent) {
            previousSemester(semester)?.let { prev ->
                val (pxnm, pxqm) = toXnmXqm(prev)
                runCatching { importExams(prev, pxnm, pxqm, csrf) }
            }
        }
    }

    private suspend fun fetchPeriods(
        semester: String,
        xnm: String,
        xqm: String,
        campusId: String,
        csrf: String,
    ): List<PeriodEntity> {
        val fields = linkedMapOf("xnm" to xnm, "xqm" to xqm, "xqh_id" to campusId)
        if (csrf.isNotBlank()) fields["csrftoken"] = csrf
        val raw = client.postForm(
            GzhuClient.PERIODS,
            fields = fields,
            query = mapOf("gnmkdm" to GzhuClient.GNMKDM_KB),
        )
        saveRaw("xskbcx_cxRjc", semester, raw)
        val arr = parseArray(raw) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val section = o.str("jcmc").toIntOrNull() ?: o.str("xsdj").toIntOrNull() ?: return@mapNotNull null
            if (section <= 0) return@mapNotNull null
            val big = o.str("xsdj").ifBlank { o.str("rsdm") }
            PeriodEntity(
                acadYearSemester = semester,
                sectionNumber = section,
                minorName = "第${section}节",
                startTime = o.str("qssj"),
                endTime = o.str("jssj"),
                bigSection = big,
                bigSectionName = o.str("rsdmc").ifBlank { "第${big}大节" },
            )
        }.distinctBy { it.sectionNumber }.sortedBy { it.sectionNumber }
    }

    private suspend fun importExams(semester: String, xnm: String, xqm: String, csrf: String) {
        val exams = fetchExams(xnm, xqm, csrf)
        if (exams.isEmpty()) return
        val mapped = exams.mapNotNull { parseExam(semester, it) }
        if (mapped.isEmpty()) return
        val weeks = mapped
            .map { it.examWeekId.orEmpty() to it.examWeekName }
            .filter { it.first.isNotBlank() || it.second.isNotBlank() }
            .distinctBy { it.first.ifBlank { it.second } }
            .ifEmpty { listOf("exam" to "考试") }
        repo.replaceExamWeeks(
            semester,
            weeks.map { (id, name) ->
                ExamWeekEntity(
                    acadYearSemester = semester,
                    examWeekId = id.ifBlank { name },
                    examWeekName = name.ifBlank { "考试" },
                )
            },
        )
        repo.replaceExams(
            semester,
            mapped.distinctBy { it.examIndex ?: "${it.subjectName}|${it.examDate}|${it.startTime}" },
        )
    }

    private suspend fun fetchExams(xnm: String, xqm: String, csrf: String): List<JsonObject> {
        val page = runCatching {
            client.get(
                GzhuClient.EXAM_PAGE,
                query = mapOf("gnmkdm" to GzhuClient.GNMKDM_EXAM, "layout" to "default"),
                referer = "${school.apiOrigin}/jwglxt/xtgl/index_initMenu.html?jsdm=xs",
            )
        }.getOrNull().orEmpty()
        val guid = parseExamWidgetGuid(page).ifBlank { GzhuClient.EXAM_WIDGET_GUID }
        if (guid.isBlank()) return emptyList()
        saveRaw("viewFunc_exam", "$xnm-$xqm", page.take(20_000))
        val referer = "${school.apiOrigin}${GzhuClient.EXAM_PAGE}?gnmkdm=${GzhuClient.GNMKDM_EXAM}&layout=default"
        val all = mutableListOf<JsonObject>()
        var pageNo = 1
        while (pageNo <= 20) {
            val fields = queryModelFields(xnm, xqm, csrf, pageNo)
            val raw = runCatching {
                client.postForm(
                    GzhuClient.EXAM_LIST,
                    fields = fields,
                    query = mapOf("func_widget_guid" to guid, "gnmkdm" to GzhuClient.GNMKDM_EXAM),
                    referer = referer,
                )
            }.getOrNull() ?: break
            if (pageNo == 1) saveRaw("funcData_cxFuncDataList", "$xnm-$xqm", raw)
            val chunk = itemsOf(raw)
            if (chunk.isEmpty()) break
            all += chunk
            val total = parseObject(raw)?.str("totalResult")?.toIntOrNull()
                ?: parseObject(raw)?.str("totalCount")?.toIntOrNull()
                ?: chunk.size
            if (all.size >= total || chunk.size < 500) break
            pageNo++
        }
        return all
    }

    private fun parseExamWidgetGuid(html: String): String {
        val patterns = listOf(
            Regex("""func_widget_guid\s*[:=]\s*['"]([A-Za-z0-9]+)['"]"""),
            Regex("""func_widget_guid=([A-Za-z0-9]+)"""),
        )
        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.get(1).orEmpty()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun queryModelFields(xnm: String, xqm: String, csrf: String, page: Int = 1) = linkedMapOf(
        "xnm" to xnm,
        "xqm" to xqm,
        "_search" to "false",
        "nd" to System.currentTimeMillis().toString(),
        "queryModel.showCount" to "500",
        "queryModel.currentPage" to page.toString(),
        "queryModel.sortName" to " ",
        "queryModel.sortOrder" to "asc",
    ).also { if (csrf.isNotBlank()) it["csrftoken"] = csrf }

    private fun parseExam(semester: String, o: JsonObject): ExamEntity? {
        val rawName = o.firstStr("sjbh", "kcmc", "kcm", "examSubjectName")
        val name = rawName.replace(Regex("""\([^)]*\)\s*$"""), "").trim().ifBlank { rawName.trim() }
        if (name.isBlank()) return null
        val (date, start, end) = parseExamTime(o.firstStr("kssj", "ksrq", "examDate"))
        val weekName = o.firstStr("ksmc", "ksxzmc", "ksxz", "examWeekName").ifBlank { "考试" }
        val weekId = o.firstStr("ksmcdmb_id", "ksid").ifBlank { weekName }
        val place = listOf(o.firstStr("ksxq", "xqmc"), o.firstStr("ksdd", "cdmc", "jsmc", "classroomNumber"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return ExamEntity(
            acadYearSemester = semester,
            examIndex = listOf(o.firstStr("jxbmc"), weekId, date, start)
                .filter { it.isNotBlank() }
                .joinToString("|")
                .ifBlank { null },
            subjectName = name,
            examDate = date,
            startTime = start,
            endTime = end,
            duration = o.firstStr("ksxs", "duration"),
            classroom = place,
            examMode = o.firstStr("ksfs", "ksfsmc", "examMode"),
            examStage = "",
            examWeekName = weekName,
            examWeekId = weekId,
            extraJson = "{}",
        )
    }

    private fun parseExamTime(raw: String): Triple<String, String, String> {
        val date = raw.substringBefore("(").trim()
        val span = raw.substringAfter("(", "").substringBefore(")").trim()
        if (span.isBlank()) {
            val parts = splitTimeRange(raw)
            return Triple(date.take(10), parts.first, parts.second)
        }
        val times = span.split("-", "–", "—").map { it.trim() }.filter { it.isNotBlank() }
        return Triple(date.take(10), times.getOrNull(0).orEmpty(), times.getOrNull(1).orEmpty())
    }

    private fun previousSemester(sem: String): String? {
        val parts = sem.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        return if (parts[1] == "2") "$year-1" else "${year - 1}-2"
    }

    private fun parseCourses(semester: String, data: JsonObject, themeColor: Long): List<CourseEntity> {
        val kbList = data["kbList"] as? JsonArray ?: JsonArray(emptyList())
        val result = mutableListOf<CourseEntity>()
        for (item in kbList) {
            val o = item as? JsonObject ?: continue
            val name = o.str("kcmc").trim()
            if (name.isBlank()) continue
            val day = o.str("xqj").toIntOrNull() ?: continue
            val (startPeriod, endPeriod) = parsePeriods(o.firstStr("jcs", "jc", "jcor"))
            if (startPeriod <= 0) continue
            val zcd = o.str("zcd").ifBlank { o.str("qsjsz") }
            val startWeek = Regex("""\d+""").find(zcd)?.value?.toIntOrNull() ?: 1
            val weeksMask = WeekMask.parse(zcd, startWeek)
            val place = listOf(o.str("xqmc"), o.str("cdmc")).filter { it.isNotBlank() }.joinToString(" ")
            result += CourseEntity(
                acadYearSemester = semester,
                source = "imported",
                locallyEdited = false,
                classesId = o.str("jxb_id").ifBlank { o.str("kch_id") }.ifBlank { null },
                sumClassesId = o.str("kch_id").ifBlank { null },
                courseName = name,
                teacher = o.str("xm"),
                place = place,
                dayOfWeek = day,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                startWeek = startWeek,
                weeksMask = weeksMask,
                timeDetail = listOf(zcd, o.firstStr("jcs", "jc")).filter { it.isNotBlank() }.joinToString(" "),
                color = CourseColors.of(name, themeColor),
                extraJson = "{}",
            )
        }
        return result.distinctBy { listOf(it.classesId, it.dayOfWeek, it.startPeriod, it.endPeriod, it.weeksMask) }
    }

    private fun weeksFromCourses(semester: String, courses: List<CourseEntity>): List<WeekEntity> {
        var maxWeek = 18
        for (course in courses) {
            for (week in 30 downTo 1) {
                if (WeekMask.has(course.weeksMask, week)) {
                    maxWeek = maxOf(maxWeek, week)
                    break
                }
            }
        }
        return (1..maxWeek).map { week ->
            WeekEntity(
                acadYearSemester = semester,
                weekly = week,
                weeklyName = "第${week}周",
                startDate = null,
                endDate = null,
            )
        }
    }

    private fun campusIdOf(data: JsonObject): String {
        val fromXs = (data["xsxx"] as? JsonObject)?.str("XQH_ID")
        if (!fromXs.isNullOrBlank()) return fromXs
        val kbList = data["kbList"] as? JsonArray ?: return ""
        return kbList.mapNotNull { (it as? JsonObject)?.str("xqh_id")?.trim(',') }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private suspend fun saveRaw(endpoint: String, semester: String, body: String) {
        // 不再把教务原文写入 raw_imports。
    }

    private fun parseObject(raw: String): JsonObject? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return el as? JsonObject
    }

    private fun parseArray(raw: String): JsonArray? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return el as? JsonArray
    }

    private fun itemsOf(raw: String): List<JsonObject> {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        return when (el) {
            is JsonArray -> el.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val items = el["items"] as? JsonArray ?: el["rows"] as? JsonArray
                items?.mapNotNull { it as? JsonObject }.orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun parseCsrf(html: String): String {
        return Regex("""id=["']csrftoken["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""name=["']csrftoken["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            .orEmpty()
    }

    private fun parseSelected(html: String, selectId: String): String {
        val select = Regex(
            """<select[^>]*id=["']$selectId["'][^>]*>[\s\S]*?</select>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value ?: return ""
        Regex("""<option[^>]*value=["']([^"']+)["'][^>]*selected""", RegexOption.IGNORE_CASE)
            .find(select)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it }
        Regex("""<option[^>]*selected[^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(select)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it }
        return ""
    }

    private fun parseYearOptions(html: String): List<String> {
        val select = Regex(
            """<select[^>]*id=["']xnm["'][^>]*>[\s\S]*?</select>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value ?: return emptyList()
        return Regex("""<option[^>]*value=["'](\d{4})["']""", RegexOption.IGNORE_CASE)
            .findAll(select)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun toAppSemester(xnm: String, xqm: String): String {
        val term = if (xqm == "12" || xqm == "16") 2 else 1
        return "$xnm-$term"
    }

    private fun toXnmXqm(sem: String): Pair<String, String> {
        val parts = sem.split("-")
        val year = parts.getOrNull(0).orEmpty()
        val xqm = if (parts.getOrNull(1) == "2") "12" else "3"
        return year to xqm
    }

    private fun parsePeriods(raw: String): Pair<Int, Int> {
        val periodPart = raw.substringBefore('{').substringBefore("第").replace("节", "").trim()
        val nums = Regex("""\d+""").findAll(periodPart)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..20 }
            .toList()
        if (nums.isEmpty()) return 0 to 0
        return nums.min() to nums.max()
    }

    private fun splitTimeRange(raw: String): Pair<String, String> {
        val parts = raw.split("-", "–", "—", "~").map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size >= 2) parts[0] to parts[1] else raw.trim() to ""
    }

    private fun JsonObject.str(key: String): String {
        val el = this[key] ?: return ""
        return when (el) {
            is JsonNull -> ""
            is JsonPrimitive -> el.contentOrNull.orEmpty()
            else -> el.toString()
        }
    }

    private fun JsonObject.firstStr(vararg keys: String): String {
        for (key in keys) {
            val value = str(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }
}

private object GzhuDefaultPeriods {
    data class Item(val section: Int, val start: String, val end: String, val big: String, val bigName: String)

    private val list = listOf(
        Item(1, "08:30", "09:15", "1", "上午"),
        Item(2, "09:20", "10:05", "1", "上午"),
        Item(3, "10:25", "11:10", "2", "上午"),
        Item(4, "11:15", "12:00", "2", "上午"),
        Item(5, "13:50", "14:35", "3", "下午"),
        Item(6, "14:40", "15:25", "3", "下午"),
        Item(7, "15:45", "16:30", "4", "下午"),
        Item(8, "16:35", "17:20", "4", "下午"),
        Item(9, "18:20", "19:05", "5", "晚上"),
        Item(10, "19:10", "19:55", "6", "晚上"),
        Item(11, "20:00", "20:45", "7", "晚上"),
    )

    fun of(semester: String) = list.map {
        PeriodEntity(
            acadYearSemester = semester,
            sectionNumber = it.section,
            minorName = "第${it.section}节",
            startTime = it.start,
            endTime = it.end,
            bigSection = it.big,
            bigSectionName = it.bigName,
        )
    }
}
