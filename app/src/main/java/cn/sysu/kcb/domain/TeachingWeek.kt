package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.WeekEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 教学周按周一到周日计算。周条目的结束日如果写成下一周周一，
 * 用闭区间会把周一算进上一周，表头日期和「本周」会对不上。
 */
object TeachingWeek {
    fun parseLocalDate(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() || text.equals("null", ignoreCase = true) || text == "0") return null
        val numeric = text.substringBefore('.').filter { it.isDigit() || it == '-' }
        if (text.all { it.isDigit() || it == '.' } || (numeric.startsWith("-") && numeric.drop(1).all { it.isDigit() })) {
            text.substringBefore('.').toLongOrNull()?.let { parseMillis(it)?.let { date -> return date } }
        }
        Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""")
            .find(text.replace("T", " "))
            ?.let { match ->
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val day = match.groupValues[3].toInt()
                return runCatching { LocalDate.of(year, month, day) }.getOrNull()
            }
        return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }

    fun parseMillis(value: Long): LocalDate? {
        if (value <= 0L) return null
        if (value in 19900101L..21001231L) {
            val text = value.toString().padStart(8, '0')
            return runCatching {
                LocalDate.of(
                    text.substring(0, 4).toInt(),
                    text.substring(4, 6).toInt(),
                    text.substring(6, 8).toInt(),
                )
            }.getOrNull()
        }
        val millis = when {
            value in 1_000_000_000L..9_999_999_999L -> value * 1000L
            value >= 10_000_000_000L -> value
            else -> return null
        }
        return runCatching {
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
    }

    fun toEpochMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    fun weekStartOf(week: WeekEntity?): LocalDate? =
        parseLocalDate(week?.startDate)?.let { mondayOf(it) }

    fun originMonday(weeks: List<WeekEntity>, semesterStartMillis: Long): Pair<Int, LocalDate>? {
        val dated = weeks.mapNotNull { item ->
            val start = weekStartOf(item) ?: return@mapNotNull null
            if (item.weekly <= 0) return@mapNotNull null
            item.weekly to start
        }
        dated.minByOrNull { it.first }?.let { return it }
        parseMillis(semesterStartMillis)?.let { return 1 to mondayOf(it) }
        val semester = weeks.firstOrNull { it.acadYearSemester.isNotBlank() }?.acadYearSemester
        guessTermStart(semester)?.let { return 1 to it }
        return null
    }

    fun resolveWeek(date: LocalDate, weeks: List<WeekEntity>, semesterStartMillis: Long = 0): Int? {
        val windows = weeks.mapNotNull { item ->
            val start = weekStartOf(item) ?: return@mapNotNull null
            if (item.weekly <= 0) return@mapNotNull null
            item.weekly to start
        }
        for ((weekly, start) in windows.sortedByDescending { it.second }) {
            if (!date.isBefore(start) && date.isBefore(start.plusWeeks(1))) return weekly
        }
        val origin = originMonday(weeks, semesterStartMillis) ?: return null
        if (date.isBefore(origin.second)) return null
        val week = origin.first + (ChronoUnit.DAYS.between(origin.second, date) / 7).toInt()
        val max = weeks.maxOfOrNull { it.weekly }?.takeIf { it > 0 } ?: WeekMask.MAX_WEEK
        val min = weeks.map { it.weekly }.filter { it > 0 }.minOrNull() ?: 1
        return week.takeIf { it in min..max }
    }

    fun resolveWeekStart(
        selectedWeek: Int,
        week: WeekEntity?,
        weeks: List<WeekEntity>,
        semesterStartMillis: Long,
    ): LocalDate? {
        weekStartOf(week)?.let { return it }
        val origin = originMonday(weeks, semesterStartMillis) ?: return null
        return origin.second.plusWeeks((selectedWeek - origin.first).toLong())
    }

    fun buildWeeks(semester: String, maxWeek: Int, origin: LocalDate?): List<WeekEntity> {
        val count = maxWeek.coerceIn(1, WeekMask.MAX_WEEK)
        val monday = origin?.let { mondayOf(it) }
        return (1..count).map { week ->
            val from = monday?.plusWeeks((week - 1).toLong())
            WeekEntity(
                acadYearSemester = semester,
                weekly = week,
                weeklyName = "第${week}周",
                startDate = from?.toString(),
                endDate = from?.plusDays(6)?.toString(),
            )
        }
    }

    fun guessTermStart(semester: String?): LocalDate? {
        val parts = semester?.split("-").orEmpty()
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val term = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val anchor = when (term) {
            1 -> LocalDate.of(year, 9, 1)
            2 -> LocalDate.of(year + 1, 2, 16)
            else -> return null
        }
        return mondayOf(anchor)
    }

    fun originFromCurrentWeek(currentWeek: Int?, today: LocalDate = LocalDate.now()): LocalDate? {
        if (currentWeek == null || currentWeek !in 1..WeekMask.MAX_WEEK) return null
        return mondayOf(today).minusWeeks((currentWeek - 1).toLong())
    }

    fun parseCurrentWeekLabel(text: String): Int? {
        val patterns = listOf(
            Regex("""id\s*=\s*["']dqzc["'][^>]*>\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""当前教学周[^\d]{0,20}(\d+)"""),
            Regex("""本学期第\s*(\d+)\s*周"""),
            Regex("""nowzc["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""dqzc["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""currentWeek["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val week = pattern.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (week in 1..WeekMask.MAX_WEEK) return week
        }
        return null
    }
}
