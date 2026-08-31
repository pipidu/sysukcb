package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.repo.TimetableRepository
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.SemesterRange
import cn.sysu.kcb.domain.WeekMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class WakeUpImportService(
    private val repo: TimetableRepository,
    private val json: Json,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun import(raw: String, semesterHint: String, themeColor: Long): WakeUpImportResult = withContext(Dispatchers.IO) {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isBlank()) throw ImportFailedException("请粘贴 WakeUp 分享口令，或选择备份 / CSV 文件")
        val payload = if (looksLikeShareCode(text)) fetchShare(text) else text
        val parsed = parse(payload)
        if (parsed.courses.isEmpty()) throw ImportFailedException("没有解析到课程")
        val semester = semesterHint.ifBlank { SemesterRange.guessCurrent() }
        val existing = repo.listSemesters().firstOrNull { it.acadYearSemester == semester }
        val start = parsed.startDate
        val fromCourses = parsed.courses.maxOf { highestWeek(it.weeksMask) }
        val maxWeek = maxOf(parsed.maxWeek, fromCourses, 1)
        val startMillis = start?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: existing?.startMillis ?: 0L
        val endMillis = start?.plusWeeks(maxWeek.toLong())?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: existing?.endMillis ?: 0L
        val entity = SemesterEntity(
            acadYearSemester = semester,
            acadYear = semester.substringBefore("-"),
            acadSemester = semester.substringAfter("-").toIntOrNull() ?: 1,
            startMillis = startMillis,
            endMillis = endMillis,
            isCurrent = existing?.isCurrent ?: false,
        )
        val weeks = buildWeeks(semester, start, maxWeek)
        val periods = parsed.periods.map { it.copy(acadYearSemester = semester, id = 0) }
        val courses = parsed.courses.map { course ->
            course.copy(
                id = 0,
                acadYearSemester = semester,
                source = "shared",
                color = CourseColors.of(course.courseName, themeColor),
            )
        }
        repo.replaceCoursesKeepExams(entity, weeks, periods, courses)
        WakeUpImportResult(semester = semester, count = courses.size, tableName = parsed.tableName)
    }

    private fun looksLikeShareCode(text: String): Boolean {
        if (text.contains('{') || text.contains("课程名称")) return false
        val compact = text.replace("\n", " ")
        return compact.contains("wakeup", ignoreCase = true) ||
            compact.contains("WakeUp") ||
            compact.contains("课程表」") ||
            compact.matches(Regex("^[A-Za-z0-9_-]{6,64}$"))
    }

    private fun fetchShare(raw: String): String {
        val key = extractShareKey(raw) ?: throw ImportFailedException("无法识别分享口令，请改用 WakeUp 导出的备份文件")
        val urls = listOf(
            "https://i.wakeup.fun/share_schedule/get?id=$key",
            "https://i.wakeup.fun/share_schedule/get?key=$key",
        )
        var lastHint = ""
        for (url in urls) {
            val response = runCatching {
                http.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "sysukcb/android")
                        .header("Accept", "application/json,text/plain,*/*")
                        .get()
                        .build(),
                ).execute().use { it.code to it.body?.string().orEmpty() }
            }.getOrNull() ?: continue
            lastHint = response.second.take(80)
            if (response.first !in 200..299) continue
            val body = response.second
            if (body.isBlank()) continue
            extractSharePayload(body)?.let { return it }
            if (looksLikeBackup(body) || looksLikeCsv(body)) return body
        }
        throw ImportFailedException(
            if (lastHint.contains("sign", ignoreCase = true) || lastHint.contains("403")) {
                "新版分享口令无法直接拉取，请在 WakeUp 里「导出为备份」后再导入文件"
            } else {
                "分享口令无效或已过期，请改用 WakeUp 备份文件"
            },
        )
    }

    private fun extractShareKey(raw: String): String? {
        val text = raw.trim()
        Regex("(?:key|id|code)=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { return it }
        Regex("share_schedule/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)?.let { return it }
        Regex("wakeup://[^\\s]*[?&](?:id|key)=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { return it }
        if (text.matches(Regex("^[A-Za-z0-9_-]{6,64}$"))) return text
        return null
    }

    private fun extractSharePayload(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return if (looksLikeBackup(body)) body else null
        val data = obj["data"]
        when (data) {
            is JsonPrimitive -> data.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            is JsonObject -> {
                data.str("data", "content", "file", "schedule").takeIf { it.isNotBlank() }?.let { return it }
                if (data.containsKey("courseName") || data.containsKey("startNode")) return data.toString()
            }
            is JsonArray -> return data.toString()
            else -> {}
        }
        obj.str("data", "content", "file").takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun parse(text: String): Parsed {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (looksLikeCsv(trimmed)) return parseCsv(trimmed)
        parseBackup(trimmed)?.let { return it }
        parseCourseArray(trimmed)?.let { return it }
        throw ImportFailedException("不是 WakeUp 备份、CSV 或分享数据")
    }

    private fun looksLikeBackup(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.size >= 3 && lines.any { it.startsWith("{") || it.startsWith("[") }
    }

    private fun looksLikeCsv(text: String): Boolean {
        val header = text.lineSequence().firstOrNull().orEmpty()
        return header.contains("课程名称") && header.contains("星期")
    }

    private fun parseBackup(text: String): Parsed? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val jsonLines = if (lines.first().matches(Regex("\\d+"))) lines.drop(1) else lines
        val wrapped = runCatching {
            json.parseToJsonElement("[${jsonLines.joinToString(",")}]").jsonArray
        }.getOrNull() ?: return null
        var tableName = ""
        var startDate: LocalDate? = null
        var maxWeek = 0
        var nodes = 0
        val infos = mutableMapOf<Int, Pair<String, String>>()
        val classes = mutableListOf<JsonObject>()
        val times = mutableListOf<JsonObject>()
        for (el in wrapped) {
            when (el) {
                is JsonObject -> {
                    if (el.containsKey("tableName") || el.containsKey("startDate") || el.containsKey("maxWeek")) {
                        tableName = el.str("tableName", "name").ifBlank { tableName }
                        startDate = parseDate(el.str("startDate")) ?: startDate
                        maxWeek = el.int("maxWeek").takeIf { it > 0 } ?: maxWeek
                        nodes = el.int("nodes").takeIf { it > 0 } ?: nodes
                    }
                }
                is JsonArray -> {
                    val first = el.firstOrNull() as? JsonObject ?: continue
                    when {
                        first.containsKey("startNode") && first.containsKey("day") ->
                            classes += el.mapNotNull { it as? JsonObject }
                        first.containsKey("courseName") ->
                            el.mapNotNull { it as? JsonObject }.forEach { info ->
                                val id = info.int("id")
                                infos[id] = info.str("courseName") to info.str("note")
                            }
                        first.containsKey("startTime") && (first.containsKey("node") || first.containsKey("endTime")) ->
                            times += el.mapNotNull { it as? JsonObject }
                    }
                }
                else -> {}
            }
        }
        if (classes.isEmpty()) return null
        val courses = classes.mapNotNull { row -> toCourse(row, infos) }
        val periods = times.mapNotNull { row -> toPeriod(row) }
            .filter { nodes <= 0 || it.sectionNumber <= nodes }
            .sortedBy { it.sectionNumber }
        return Parsed(
            tableName = tableName,
            startDate = startDate,
            maxWeek = maxWeek,
            periods = periods,
            courses = courses,
        )
    }

    private fun parseCourseArray(text: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["courseList"] ?: root["courses"] ?: root["data"]) as? JsonArray
            else -> null
        } ?: return null
        val courses = array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            toCourse(o, emptyMap())
        }
        if (courses.isEmpty()) return null
        return Parsed(courses = courses)
    }

    private fun parseCsv(text: String): Parsed {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) throw ImportFailedException("CSV 里没有课程")
        val header = splitCsv(lines.first()).map { it.trim() }
        fun idx(vararg names: String) = names.firstNotNullOfOrNull { name ->
            header.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }
        } ?: -1
        val iName = idx("课程名称", "课程名", "name")
        val iDay = idx("星期", "星期几", "day")
        val iStart = idx("开始节数", "开始节", "startNode")
        val iEnd = idx("结束节数", "结束节", "endNode")
        val iTeacher = idx("老师", "教师", "teacher")
        val iPlace = idx("地点", "教室", "room")
        val iWeeks = idx("周数", "周次", "weeks")
        if (iName < 0 || iDay < 0 || iStart < 0 || iEnd < 0 || iWeeks < 0) {
            throw ImportFailedException("CSV 表头需要包含课程名称、星期、开始节数、结束节数、周数")
        }
        val courses = lines.drop(1).mapNotNull { line ->
            val cols = splitCsv(line)
            val name = cols.getOrNull(iName)?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val day = cols.getOrNull(iDay)?.filter { it.isDigit() }?.toIntOrNull() ?: return@mapNotNull null
            val start = cols.getOrNull(iStart)?.filter { it.isDigit() }?.toIntOrNull() ?: return@mapNotNull null
            val end = cols.getOrNull(iEnd)?.filter { it.isDigit() }?.toIntOrNull() ?: start
            val weeksRaw = cols.getOrNull(iWeeks).orEmpty()
            val mask = WeekMask.parse(weeksRaw, 1)
            if (day !in 1..7 || start < 1) return@mapNotNull null
            CourseEntity(
                acadYearSemester = "",
                source = "shared",
                courseName = name,
                teacher = cols.getOrNull(iTeacher)?.takeIf { it != "无" }.orEmpty(),
                place = cols.getOrNull(iPlace)?.takeIf { it != "无" }.orEmpty(),
                dayOfWeek = day,
                startPeriod = start,
                endPeriod = end.coerceAtLeast(start),
                startWeek = 1,
                weeksMask = mask,
                timeDetail = weeksRaw,
                color = 0L,
            )
        }
        return Parsed(courses = courses)
    }

    private fun toCourse(row: JsonObject, infos: Map<Int, Pair<String, String>>): CourseEntity? {
        val id = row.int("id")
        val name = infos[id]?.first.orEmpty().ifBlank { row.str("courseName", "name") }
        if (name.isBlank()) return null
        val day = row.int("day")
        val startNode = row.int("startNode", "startSection")
        val step = row.int("step").takeIf { it > 0 } ?: 1
        val endNode = row.int("endNode").takeIf { it > 0 } ?: (startNode + step - 1)
        val startWeek = row.int("startWeek").takeIf { it > 0 } ?: 1
        val endWeek = row.int("endWeek").takeIf { it > 0 } ?: startWeek
        if (day !in 1..7 || startNode < 1) return null
        val type = row.int("type")
        val mask = WeekMask.fromRange(startWeek, endWeek) { week ->
            when (type) {
                1 -> week % 2 == 1
                2 -> week % 2 == 0
                else -> true
            }
        }
        val note = infos[id]?.second.orEmpty().ifBlank { row.str("note") }
        val weeksLabel = WeekMask.describe(mask)
        return CourseEntity(
            acadYearSemester = "",
            source = "shared",
            courseName = name,
            teacher = row.str("teacher").takeIf { it != "无" }.orEmpty(),
            place = row.str("room", "position", "location").takeIf { it != "无" }.orEmpty(),
            dayOfWeek = day,
            startPeriod = startNode,
            endPeriod = endNode.coerceAtLeast(startNode),
            startWeek = startWeek,
            weeksMask = mask,
            timeDetail = weeksLabel,
            color = 0L,
            notes = note,
        )
    }

    private fun toPeriod(row: JsonObject): PeriodEntity? {
        val node = row.int("node", "sectionNumber")
        if (node < 1) return null
        val start = row.str("startTime")
        val end = row.str("endTime")
        return PeriodEntity(
            acadYearSemester = "",
            sectionNumber = node,
            minorName = "第${node}节",
            startTime = start,
            endTime = end,
            bigSection = "",
            bigSectionName = "",
        )
    }

    private fun buildWeeks(semester: String, start: LocalDate?, maxWeek: Int): List<WeekEntity> {
        val count = maxWeek.coerceIn(1, WeekMask.MAX_WEEK)
        val origin = start?.let { mondayOf(it) }
        return (1..count).map { week ->
            val from = origin?.plusWeeks((week - 1).toLong())
            WeekEntity(
                acadYearSemester = semester,
                weekly = week,
                weeklyName = "第${week}周",
                startDate = from?.toString(),
                endDate = from?.plusDays(6)?.toString(),
            )
        }
    }

    private fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun parseDate(raw: String): LocalDate? {
        val value = raw.trim().take(10)
        if (value.isBlank()) return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }

    private fun highestWeek(mask: Long): Int {
        var max = 0
        for (week in 1..WeekMask.MAX_WEEK) if (WeekMask.has(mask, week)) max = week
        return max
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                        buf.append('"')
                        i++
                    } else {
                        quoted = !quoted
                    }
                }
                c == ',' && !quoted -> {
                    out += buf.toString()
                    buf.clear()
                }
                else -> buf.append(c)
            }
            i++
        }
        out += buf.toString()
        return out
    }

    private fun JsonObject.str(vararg keys: String): String {
        for (key in keys) {
            val value = this[key] ?: continue
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.let { return it }
                else -> {}
            }
        }
        return ""
    }

    private fun JsonObject.int(vararg keys: String): Int {
        for (key in keys) {
            val value = this[key] ?: continue
            when (value) {
                is JsonPrimitive -> {
                    value.intOrNull?.let { return it }
                    value.contentOrNull?.toIntOrNull()?.let { return it }
                }
                else -> {}
            }
        }
        return 0
    }

    private data class Parsed(
        val tableName: String = "",
        val startDate: LocalDate? = null,
        val maxWeek: Int = 0,
        val periods: List<PeriodEntity> = emptyList(),
        val courses: List<CourseEntity> = emptyList(),
    )
}

data class WakeUpImportResult(
    val semester: String,
    val count: Int,
    val tableName: String,
)
