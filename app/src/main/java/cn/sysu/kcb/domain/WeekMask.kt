package cn.sysu.kcb.domain

import kotlin.math.max
import kotlin.math.min

object WeekMask {
    const val MAX_WEEK = 30

    fun bit(week: Int): Long =
        if (week in 1..62) 1L shl (week - 1) else 0L

    fun has(mask: Long, week: Int): Boolean = mask and bit(week) != 0L

    fun fromRange(start: Int, end: Int, predicate: (Int) -> Boolean = { true }): Long {
        var mask = 0L
        for (week in start..end) {
            if (predicate(week)) mask = mask or bit(week)
        }
        return mask
    }

    fun parse(timeDetail: String, startWeek: Int, maxWeek: Int = MAX_WEEK): Long {
        val raw = timeDetail.replace("/", "").trim()
        if (raw.isEmpty()) return bit(startWeek.coerceAtLeast(1))
        val odd = raw.contains("单")
        val even = raw.contains("双") && !odd
        val cleaned = raw
            .replace("每周", "")
            .replace("单周", "")
            .replace("双周", "")
            .replace("周", "")
            .trim()
        var mask = 0L
        if (cleaned.isEmpty()) {
            mask = fromRange(startWeek.coerceAtLeast(1), maxWeek)
        } else {
            for (part in cleaned.split(",", "，", "、", ";", "；")) {
                val token = part.trim()
                if (token.isEmpty()) continue
                val range = token.split("-", "–", "—", "~")
                if (range.size >= 2) {
                    val a = range[0].filter { it.isDigit() }.toIntOrNull() ?: continue
                    val b = range[1].filter { it.isDigit() }.toIntOrNull() ?: continue
                    mask = mask or fromRange(min(a, b), max(a, b))
                } else {
                    val n = token.filter { it.isDigit() }.toIntOrNull() ?: continue
                    mask = mask or bit(n)
                }
            }
        }
        if (odd) {
            var filtered = 0L
            for (w in 1..maxWeek) if (w % 2 == 1 && has(mask, w)) filtered = filtered or bit(w)
            mask = filtered
        } else if (even) {
            var filtered = 0L
            for (w in 1..maxWeek) if (w % 2 == 0 && has(mask, w)) filtered = filtered or bit(w)
            mask = filtered
        }
        if (mask == 0L) mask = bit(startWeek.coerceAtLeast(1))
        return mask
    }

    fun describe(mask: Long, maxWeek: Int = MAX_WEEK): String {
        val weeks = (1..maxWeek).filter { has(mask, it) }
        if (weeks.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = weeks.first()
        var prev = start
        for (w in weeks.drop(1) + listOf(-1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                parts += if (start == prev) "$start" else "$start-$prev"
                start = w
                prev = w
            }
        }
        return parts.joinToString(",") + "周"
    }
}

fun String.cleanJwxt(): String = trim().trimEnd('/').trim()

object CourseColors {
    val palette = listOf(
        0xFFC62828, 0xFFAD1457, 0xFF6A1B9A, 0xFF283593, 0xFF1565C0,
        0xFF00838F, 0xFF2E7D32, 0xFFEF6C00, 0xFF4E342E, 0xFF455A64,
        0xFF00897B, 0xFF5D4037,
    )

    fun of(name: String): Long {
        val index = (name.hashCode().toUInt() % palette.size.toUInt()).toInt()
        return palette[index]
    }
}

object DefaultPeriods {
    data class Period(val section: Int, val name: String, val start: String, val end: String, val big: String, val bigName: String)

    val list = listOf(
        Period(1, "第1节", "08:00", "08:45", "1", "第1大节"),
        Period(2, "第2节", "08:55", "09:40", "1", "第1大节"),
        Period(3, "第3节", "10:10", "10:55", "2", "第2大节"),
        Period(4, "第4节", "11:05", "11:50", "2", "第2大节"),
        Period(5, "第5节", "14:20", "15:05", "4", "第3大节"),
        Period(6, "第6节", "15:15", "16:00", "4", "第3大节"),
        Period(7, "第7节", "16:30", "17:15", "5", "第4大节"),
        Period(8, "第8节", "17:25", "18:10", "5", "第4大节"),
        Period(9, "第9节", "19:00", "19:45", "6", "第5大节"),
        Period(10, "第10节", "19:55", "20:40", "6", "第5大节"),
        Period(11, "第11节", "20:50", "21:35", "6", "第5大节"),
    )
}
