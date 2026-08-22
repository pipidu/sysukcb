package cn.sysu.kcb.domain

import java.time.LocalDate

object SemesterRange {
    fun ordinal(sem: String): Int? {
        val parts = sem.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val term = parts[1].toIntOrNull() ?: return null
        if (term !in 1..2) return null
        return year * 2 + (term - 1)
    }

    fun fromOrdinal(ord: Int): String {
        val year = ord / 2
        val term = ord % 2 + 1
        return "$year-$term"
    }

    fun span(anchor: String, before: Int = 8, after: Int = 8): List<String> {
        val center = ordinal(anchor) ?: return listOf(anchor)
        return (center - before..center + after).map { fromOrdinal(it) }.asReversed()
    }

    fun window(anchor: String, yearRadius: Int = 4): List<String> = span(anchor, yearRadius * 2, yearRadius * 2)

    fun guessCurrent(today: LocalDate = LocalDate.now()): String {
        val month = today.monthValue
        return when {
            month >= 8 -> "${today.year}-1"
            month == 1 -> "${today.year - 1}-1"
            else -> "${today.year - 1}-2"
        }
    }
}
