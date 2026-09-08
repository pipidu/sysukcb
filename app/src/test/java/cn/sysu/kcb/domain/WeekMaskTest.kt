package cn.sysu.kcb.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekMaskTest {
    @Test
    fun mixedOddThenAllWeeks() {
        assertEquals(
            weeks(1, 3) + (4..17).toList(),
            parseWeeks("1-3周(单), 4-17周"),
        )
        assertEquals(
            weeks(1, 3) + (4..17).toList(),
            parseWeeks("1-3周（单），4-17周"),
        )
    }

    @Test
    fun wholeRangeOddOrEven() {
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), parseWeeks("1-16周(单)"))
        assertEquals(listOf(4, 6), parseWeeks("4-6周(双)"))
    }

    @Test
    fun mixedEvenThenOdd() {
        assertEquals(
            listOf(2, 4, 6, 8, 9, 11, 13, 15),
            parseWeeks("1-8周(双),9-16周(单)"),
        )
    }

    @Test
    fun commaRangesStayIntact() {
        assertEquals(
            listOf(1, 2, 5, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18),
            parseWeeks("1-2周,5周,9-18周"),
        )
        assertEquals(
            listOf(2, 3, 7, 8, 9, 10, 11, 12, 15, 16),
            parseWeeks("2-3周,7-12周,15-16周"),
        )
    }

    @Test
    fun plainRange() {
        assertEquals((1..16).toList(), parseWeeks("1-16周"))
        assertEquals((1..16).toList(), parseWeeks("第1-16周"))
    }

    private fun parseWeeks(raw: String): List<Int> {
        val mask = WeekMask.parse(raw, 1)
        return (1..30).filter { WeekMask.has(mask, it) }
    }

    private fun weeks(vararg values: Int): List<Int> = values.toList()
}
