package cn.sysu.kcb.data.remote

import android.util.Base64
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
import cn.sysu.kcb.domain.TeachingWeek
import cn.sysu.kcb.domain.WeekMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.max
import kotlin.math.min
import java.time.LocalDate

class BnuzhImportService(
    private val client: BnuzhClient,
    private val json: Json,
    private val cookies: CookieStore,
    private val repo: TimetableRepository,
    private val settings: SettingsRepository,
) : SchoolImporter {
    private val school = School.Bnuzh

    override suspend fun isLoggedIn(): Boolean = checkSession().status == SessionStatus.Valid

    override suspend fun checkSession(): SessionCheckResult = withContext(Dispatchers.IO) {
        cookies.syncFromWebView(school)
        if (!cookies.hasSession(school)) return@withContext SessionCheckResult(SessionStatus.LoggedOut)
        runCatching {
            val raw = client.postForm(BnuzhClient.YEAR_TERM, referer = "${school.apiOrigin}${BnuzhClient.HOMES}")
            val xn = parseObject(raw)?.str("xn").orEmpty()
            if (xn.isBlank()) throw SessionExpiredException()
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
        onProgress("正在打开北师珠课表页…")
        val token = warmupTimetable()
        val currentRaw = client.postForm(
            BnuzhClient.YEAR_TERM,
            referer = timetableReferer(),
        )
        val currentObj = parseObject(currentRaw) ?: throw ImportFailedException("无法读取当前学年学期")
        val currentXn = currentObj.str("xn").ifBlank { SemesterRange.guessCurrent().substringBefore("-") }
        val currentXq = currentObj.str("xqM").ifBlank { currentObj.str("xq") }.ifBlank {
            if (SemesterRange.guessCurrent().endsWith("-2")) "1" else "0"
        }
        val jwxtCurrent = toAppSemester(currentXn, currentXq)
        val focus = semesterOverride?.ifBlank { null } ?: jwxtCurrent
        val listed = runCatching { fetchTermDropList() }.getOrDefault(emptyList())
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
                importSemester(sem, token, isCurrent = sem == jwxtCurrent, currentObj = currentObj)
            }.isSuccess
            if (ok) importedCount++
        }
        if (importedCount == 0) throw ImportFailedException("没有成功导入任何学期，请重新登录后再试")
        val previous = settings.snapshot().selectedSemester
        settings.setSelectedSemester(previous.takeIf { it.isNotBlank() } ?: jwxtCurrent)
        onProgress("导入完成")
        jwxtCurrent
    }

    private fun warmupTimetable(): String {
        client.get(
            BnuzhClient.TIMETABLE_PAGE,
            query = mapOf("menucode" to BnuzhClient.MENU_TIMETABLE),
            referer = "${school.apiOrigin}${BnuzhClient.HOMES}",
        )
        fetchToken("xkjg.wdkb.jsp", timetableReferer())
        client.get(BnuzhClient.TIMETABLE_MY, referer = timetableReferer())
        val token = fetchToken("xkjg.wdkb.my.jsp", "${school.apiOrigin}${BnuzhClient.TIMETABLE_MY}")
        if (token.isBlank()) throw ImportFailedException("课表页令牌为空，请重新登录后再试")
        return token
    }

    private fun fetchToken(menuCode: String, referer: String): String {
        val raw = client.postForm(
            BnuzhClient.SET_TOKEN,
            fields = mapOf("menucode" to menuCode),
            referer = referer,
        )
        return raw.replace(Regex("\\s+"), "")
    }

    private fun timetableReferer(): String =
        "${school.apiOrigin}${BnuzhClient.TIMETABLE_PAGE}?menucode=${BnuzhClient.MENU_TIMETABLE}"

    private fun examReferer(): String =
        "${school.apiOrigin}${BnuzhClient.EXAM_PAGE}?menucode=${BnuzhClient.MENU_EXAM}"

    private fun fetchTermDropList(): List<String> {
        val raw = client.postForm(
            BnuzhClient.DROP_LISTS,
            fields = dropListFields(BnuzhClient.COMBO_TERM),
            referer = timetableReferer(),
        )
        return parseDropList(raw).mapNotNull { item ->
            val parts = item.code.split(",")
            val xn = parts.getOrNull(0).orEmpty()
            val xq = parts.getOrNull(1).orEmpty()
            if (xn.isBlank()) null else toAppSemester(xn, xq)
        }
    }

    private suspend fun importSemester(
        semester: String,
        token: String,
        isCurrent: Boolean,
        currentObj: JsonObject,
    ) {
        val (xn, xq) = toXnXq(semester)
        repo.upsertSemester(
            SemesterEntity(
                acadYearSemester = semester,
                acadYear = "$xn-${xn.toIntOrNull()?.plus(1) ?: xn}",
                acadSemester = if (xq == "1") 2 else 1,
                startMillis = 0L,
                endMillis = 0L,
                isCurrent = isCurrent,
            ),
        )
        val html = fetchTimetableHtml(xn, xq, token)
        val courses = BnuzhParse.courses(semester, html, settings.snapshot().themeColor)
        repo.replaceImportedCourses(semester, courses)
        repo.replacePeriods(semester, BnuzhDefaultPeriods.of(semester))
        val origin = originOf(semester, html.takeIf { isCurrent }, currentObj.takeIf { isCurrent })
        val weeks = weeksFromCourses(semester, courses, origin)
        repo.replaceWeeks(semester, weeks)
        val startMillis = origin?.let { TeachingWeek.toEpochMillis(TeachingWeek.mondayOf(it)) } ?: 0L
        val endMillis = TeachingWeek.weekStartOf(weeks.maxByOrNull { it.weekly })
            ?.plusDays(6)
            ?.let { TeachingWeek.toEpochMillis(it) } ?: 0L
        repo.upsertSemester(
            SemesterEntity(
                acadYearSemester = semester,
                acadYear = "$xn-${xn.toIntOrNull()?.plus(1) ?: xn}",
                acadSemester = if (xq == "1") 2 else 1,
                startMillis = startMillis,
                endMillis = endMillis,
                isCurrent = isCurrent,
            ),
        )
        runCatching { importExams(semester, xn, xq) }
        if (isCurrent) {
            previousSemester(semester)?.let { prev ->
                val (pxn, pxq) = toXnXq(prev)
                runCatching { importExams(prev, pxn, pxq) }
            }
        }
    }

    private fun fetchTimetableHtml(xn: String, xq: String, token: String): String {
        val params = Base64.encodeToString(
            "xn=$xn&xq=$xq".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val query = mapOf("params" to params, "t" to token)
        val primary = runCatching {
            client.get(BnuzhClient.TIMETABLE_DATA, query, timetableReferer())
        }.getOrNull()
        if (primary != null && looksLikeTimetable(primary)) return primary
        return client.get(BnuzhClient.TIMETABLE_DATA_FALLBACK, query, timetableReferer())
    }

    private fun looksLikeTimetable(html: String): Boolean =
        html.contains("课程名") || html.contains("上课时间") || html.contains("<tbody", ignoreCase = true)

    private suspend fun importExams(semester: String, xn: String, xq: String) {
        val page = runCatching {
            client.get(
                BnuzhClient.EXAM_PAGE,
                query = mapOf("menucode" to BnuzhClient.MENU_EXAM),
                referer = "${school.apiOrigin}${BnuzhClient.HOMES}",
            )
        }.getOrNull().orEmpty()
        if (page.contains("没有访问权限")) return
        val tableId = BnuzhParse.examTableId(page).ifBlank { BnuzhClient.EXAM_TABLE_ID }
        val batches = runCatching { fetchExamBatches() }.getOrDefault(emptyList())
            .filter { it.xn == xn && it.xq == xq }
        if (batches.isEmpty()) return
        val mapped = mutableListOf<ExamEntity>()
        for (batch in batches) {
            val html = runCatching {
                client.postForm(
                    BnuzhClient.EXAM_TABLE,
                    fields = mapOf(
                        "xh" to "",
                        "xn" to batch.xn,
                        "xq" to batch.xq,
                        "kslc" to batch.kslc,
                        "xnxqkslc" to batch.code,
                        "menucode_current" to BnuzhClient.MENU_EXAM,
                    ),
                    query = mapOf("tableId" to tableId),
                    referer = examReferer(),
                    ajax = false,
                )
            }.getOrNull() ?: continue
            if (html.contains("没有访问权限")) continue
            mapped += BnuzhParse.exams(semester, html, batch)
        }
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

    private fun fetchExamBatches(): List<BnuzhExamBatch> {
        val raw = client.postForm(
            BnuzhClient.DROP_LISTS,
            fields = dropListFields(BnuzhClient.COMBO_EXAM),
            referer = examReferer(),
        )
        return parseDropList(raw).mapNotNull { item ->
            val parts = item.code.split(",")
            val xn = parts.getOrNull(0).orEmpty()
            val xq = parts.getOrNull(1).orEmpty()
            val kslc = parts.getOrNull(2).orEmpty()
            if (xn.isBlank() || xq.isBlank() || kslc.isBlank()) null
            else BnuzhExamBatch(item.code, xn, xq, kslc, item.name.ifBlank { "考试" })
        }
    }

    private fun dropListFields(comboBoxName: String) = linkedMapOf(
        "comboBoxName" to comboBoxName,
        "paramValue" to "",
        "isYXB" to "0",
        "isCDDW" to "0",
        "isPYCC" to "0",
        "isPYCCNJ" to "0",
        "zyPyccFiled" to "",
        "isKBLB" to "0",
        "isXQ" to "0",
        "isBJ" to "0",
        "isSXJD" to "0",
        "isDJKSLB" to "0",
    )

    private fun parseDropList(raw: String): List<BnuzhDropItem> {
        val arr = parseArray(raw) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val code = o.str("code")
            if (code.isBlank()) null else BnuzhDropItem(code, o.str("name"))
        }
    }

    private fun weeksFromCourses(semester: String, courses: List<CourseEntity>, origin: LocalDate?): List<WeekEntity> {
        var maxWeek = 18
        for (course in courses) {
            for (week in 30 downTo 1) {
                if (WeekMask.has(course.weeksMask, week)) {
                    maxWeek = maxOf(maxWeek, week)
                    break
                }
            }
        }
        return TeachingWeek.buildWeeks(semester, maxWeek, origin)
    }

    private fun originOf(semester: String, html: String?, currentObj: JsonObject?): LocalDate? {
        currentObj?.let { obj ->
            listOf("ksrq", "qsrq", "xqksrq", "kkksrq", "startTime", "startDate").forEach { key ->
                TeachingWeek.parseLocalDate(obj.str(key))?.let { return TeachingWeek.mondayOf(it) }
            }
        }
        html?.let {
            TeachingWeek.originFromCurrentWeek(TeachingWeek.parseCurrentWeekLabel(it))?.let { origin ->
                return origin
            }
        }
        return TeachingWeek.guessTermStart(semester)
    }

    private fun previousSemester(sem: String): String? {
        val parts = sem.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        return if (parts[1] == "2") "$year-1" else "${year - 1}-2"
    }

    private fun toAppSemester(xn: String, xq: String): String {
        val term = if (xq == "1") 2 else 1
        return "$xn-$term"
    }

    private fun toXnXq(sem: String): Pair<String, String> {
        val parts = sem.split("-")
        val year = parts.getOrNull(0).orEmpty()
        val xq = if (parts.getOrNull(1) == "2") "1" else "0"
        return year to xq
    }

    private fun parseObject(raw: String): JsonObject? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return el as? JsonObject
    }

    private fun parseArray(raw: String): JsonArray? {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return el as? JsonArray
    }

    private fun JsonObject.str(key: String): String {
        val el = this[key] ?: return ""
        return when (el) {
            is JsonNull -> ""
            is JsonPrimitive -> el.contentOrNull.orEmpty()
            else -> el.toString()
        }
    }
}

internal data class BnuzhDropItem(val code: String, val name: String)

internal data class BnuzhExamBatch(
    val code: String,
    val xn: String,
    val xq: String,
    val kslc: String,
    val name: String,
)

internal object BnuzhParse {
    private val slotRegex = Regex(
        """(\d+(?:\s*[-–—~]\s*\d+)?)周?\s*([一二三四五六日天])\s*\[\s*(\d+)?\s*(?:[-–—~]\s*(\d+))?\s*\]\s*([^,]*)""",
    )
    private val codeNameRegex = Regex("""^\[([^\]]+)]\s*(.+)$""")
    private val examTimeRegex = Regex(
        """(\d{4}-\d{2}-\d{2})\s*(?:\(([^)]*)\))?\s*(\d{1,2}:\d{2})\s*[-–—~]\s*(\d{1,2}:\d{2})""",
    )

    fun examTableId(html: String): String =
        Regex("""DataTable\.jsp\?tableId=(\d+)""").find(html)?.groupValues?.get(1).orEmpty()

    fun courses(semester: String, html: String, themeColor: Long): List<CourseEntity> {
        val rows = tableRows(html)
        if (rows.isEmpty()) throw ImportFailedException("课表页没有表格")
        val header = rows.firstOrNull { row ->
            val joined = row.joined()
            "课程名" in joined || "上课时间" in joined
        }
        val nameIdx = header.indexOf("课程", 0)
        val teacherIdx = header.indexOf("教师", 4)
        val timeIdx = header.indexOf("上课时间", 5)
        val result = mutableListOf<CourseEntity>()
        for (row in rows) {
            val joined = row.joined()
            if ("课程名" in joined && ("上课时间" in joined || "任课教师" in joined)) continue
            val nameCell = row.cell(nameIdx).ifBlank { row.cells.getOrNull(0)?.text.orEmpty() }
            val (code, name) = splitCodeName(nameCell)
            if (name.isBlank() || "课程名" in name) continue
            val teacher = row.cell(teacherIdx)
            val timePlace = row.cell(timeIdx)
            val classId = row.named("skbjdm").ifBlank {
                val kcdm = row.named("kcdm").ifBlank { code }
                kcdm
            }
            val kcdm = row.named("kcdm").ifBlank { code }
            for (slot in parseSlots(timePlace)) {
                result += CourseEntity(
                    acadYearSemester = semester,
                    source = "imported",
                    locallyEdited = false,
                    classesId = classId.ifBlank { null },
                    sumClassesId = kcdm.ifBlank { null },
                    courseName = name,
                    teacher = teacher,
                    place = slot.place,
                    dayOfWeek = slot.day,
                    startPeriod = slot.startPeriod,
                    endPeriod = slot.endPeriod,
                    startWeek = slot.startWeek,
                    weeksMask = slot.weeksMask,
                    timeDetail = slot.raw,
                    color = CourseColors.of(name, themeColor),
                    extraJson = "{}",
                )
            }
        }
        return mergeCourses(result)
    }

    fun exams(semester: String, html: String, batch: BnuzhExamBatch): List<ExamEntity> {
        val rows = tableRows(html)
        val header = rows.firstOrNull { row ->
            val joined = row.joined()
            "考试时间" in joined || ("序号" in joined && "课程" in joined)
        }
        val nameIdx = header.indexOf("课程", 2)
        val weekIdx = header.indexOf("轮次", 1)
        val modeIdx = header.indexOf("考核", 5)
        val timeIdx = header.indexOf("考试时间", 6)
        val placeIdx = header.indexOf("地点", 7)
        val result = mutableListOf<ExamEntity>()
        for (row in rows) {
            val joined = row.cells.joinToString { it.text }
            if ("考试时间" in joined && ("序号" in joined || "课程" in joined)) continue
            val rawName = row.cell(nameIdx)
            val (_, name) = splitCodeName(rawName)
            if (name.isBlank() || name == "课程") continue
            val timeRaw = row.cell(timeIdx)
            val parsed = parseExamTime(timeRaw)
            val weekName = row.cell(weekIdx).ifBlank { batch.name }.ifBlank { "考试" }
            val weekId = batch.code.ifBlank { weekName }
            val place = row.cell(placeIdx)
            result += ExamEntity(
                acadYearSemester = semester,
                examIndex = listOf(weekId, parsed.date, parsed.start, name)
                    .filter { it.isNotBlank() }
                    .joinToString("|")
                    .ifBlank { null },
                subjectName = name,
                examDate = parsed.date,
                startTime = parsed.start,
                endTime = parsed.end,
                classroom = place,
                examMode = row.cell(modeIdx),
                examWeekName = weekName,
                examWeekId = weekId,
                weekly = parsed.weekly,
                dayOfWeek = parsed.dayOfWeek,
                extraJson = "{}",
            )
        }
        return result
    }

    private fun mergeCourses(items: List<CourseEntity>): List<CourseEntity> {
        val merged = linkedMapOf<String, CourseEntity>()
        for (item in items) {
            val key = listOf(
                item.courseName,
                item.dayOfWeek,
                item.startPeriod,
                item.endPeriod,
                item.place,
            ).joinToString("|")
            val old = merged[key]
            merged[key] = if (old == null) {
                item
            } else {
                old.copy(
                    weeksMask = old.weeksMask or item.weeksMask,
                    startWeek = min(old.startWeek, item.startWeek),
                    timeDetail = listOf(old.timeDetail, item.timeDetail)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("；"),
                    teacher = old.teacher.ifBlank { item.teacher },
                )
            }
        }
        return merged.values.toList()
    }

    private fun parseSlots(raw: String): List<BnuzhSlot> {
        if (raw.isBlank()) return emptyList()
        return slotRegex.findAll(raw).mapNotNull { match ->
            val weekRaw = match.groupValues[1].replace(" ", "")
            val day = weekdayOf(match.groupValues[2]) ?: return@mapNotNull null
            val start = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues[4].toIntOrNull() ?: start
            if (start !in 1..20) return@mapNotNull null
            val place = stripCapacity(match.groupValues[5].trim())
            val startWeek = Regex("""\d+""").find(weekRaw)?.value?.toIntOrNull() ?: 1
            BnuzhSlot(
                day = day,
                startPeriod = min(start, end),
                endPeriod = max(start, end),
                startWeek = startWeek,
                weeksMask = WeekMask.parse(weekRaw, startWeek),
                place = place,
                raw = match.value.trim().trimEnd(','),
            )
        }.toList()
    }

    private fun parseExamTime(raw: String): BnuzhExamTime {
        val match = examTimeRegex.find(raw.replace("：", ":"))
        if (match == null) {
            val date = raw.take(10)
            return BnuzhExamTime(date, "", "", 0, 0)
        }
        val extra = match.groupValues[2]
        val weekly = Regex("""(\d+)\s*周""").find(extra)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val day = weekdayNameOf(extra)
        return BnuzhExamTime(
            date = match.groupValues[1],
            start = match.groupValues[3],
            end = match.groupValues[4],
            weekly = weekly,
            dayOfWeek = day,
        )
    }

    private fun splitCodeName(raw: String): Pair<String, String> {
        val match = codeNameRegex.find(raw.trim()) ?: return "" to raw.trim()
        return match.groupValues[1] to match.groupValues[2].trim()
    }

    private fun stripCapacity(place: String): String =
        place.replace(Regex("""\(\d+\)\s*$"""), "").trim()

    private fun weekdayOf(ch: String): Int? = when (ch) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> null
    }

    private fun weekdayNameOf(raw: String): Int {
        val map = listOf("星期一" to 1, "星期二" to 2, "星期三" to 3, "星期四" to 4, "星期五" to 5, "星期六" to 6, "星期日" to 7, "星期天" to 7)
        return map.firstOrNull { it.first in raw }?.second ?: 0
    }

    private fun tableRows(html: String): List<BnuzhRow> {
        val cleaned = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        return Regex("""<tr[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { BnuzhRow(parseCells(it.groupValues[1])) }
            .filter { it.cells.isNotEmpty() }
            .toList()
    }

    private fun parseCells(rowHtml: String): List<BnuzhCell> {
        return Regex("""<td([^>]*)>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
            .findAll(rowHtml)
            .map { match ->
                val attrs = match.groupValues[1]
                val name = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1).orEmpty()
                BnuzhCell(name = name, text = htmlText(match.groupValues[2]))
            }
            .toList()
    }

    private fun htmlText(raw: String): String {
        var s = raw.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ", ignoreCase = true)
            .replace("&ensp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun BnuzhRow?.indexOf(keyword: String, fallback: Int): Int {
        val idx = this?.cells?.indexOfFirst { keyword in it.text } ?: -1
        return if (idx >= 0) idx else fallback
    }

    private fun BnuzhRow.joined(): String = cells.joinToString { it.text }

    private fun BnuzhRow.cell(index: Int): String =
        if (index >= 0) cells.getOrNull(index)?.text.orEmpty() else ""

    private fun BnuzhRow.named(name: String): String =
        cells.firstOrNull { it.name.equals(name, ignoreCase = true) }?.text.orEmpty()
}

private data class BnuzhRow(val cells: List<BnuzhCell>)
private data class BnuzhCell(val name: String, val text: String)
private data class BnuzhSlot(
    val day: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val weeksMask: Long,
    val place: String,
    val raw: String,
)
private data class BnuzhExamTime(
    val date: String,
    val start: String,
    val end: String,
    val weekly: Int,
    val dayOfWeek: Int,
)

private object BnuzhDefaultPeriods {
    data class Item(val section: Int, val start: String, val end: String, val big: String, val bigName: String)

    private val list = listOf(
        Item(1, "08:00", "08:45", "1", "上午"),
        Item(2, "08:55", "09:40", "1", "上午"),
        Item(3, "10:00", "10:45", "2", "上午"),
        Item(4, "10:55", "11:40", "2", "上午"),
        Item(5, "13:30", "14:15", "3", "下午"),
        Item(6, "14:25", "15:10", "3", "下午"),
        Item(7, "15:30", "16:15", "4", "下午"),
        Item(8, "16:25", "17:10", "4", "下午"),
        Item(9, "18:00", "18:45", "5", "晚上"),
        Item(10, "18:55", "19:40", "5", "晚上"),
        Item(11, "19:50", "20:35", "6", "晚上"),
        Item(12, "20:45", "21:30", "6", "晚上"),
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
