package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.CoursePatchEntity
import cn.sysu.kcb.data.local.DayMoveEntity
import cn.sysu.kcb.data.local.DaySuspensionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAdjustTest {
    @Test
    fun suspendedDayIsGrayAndDoesNotAlarm() {
        val course = course("高数", day = 1)
        val adjustments = ScheduleAdjustments(
            suspensions = listOf(DaySuspensionEntity(acadYearSemester = "2026-1", weekNo = 1, dayOfWeek = 1)),
        )
        val placed = placedCourses(listOf(course), 1, adjustments)
        assertEquals(1, placed.size)
        assertTrue(placed.single().muted)
        assertTrue(activeCoursesOn(listOf(course), 1, 1, adjustments).isEmpty())
    }

    @Test
    fun movedDayKeepsGrayOriginalAndAlarmsOnTarget() {
        val course = course("高数", day = 1)
        val adjustments = ScheduleAdjustments(
            moves = listOf(DayMoveEntity(acadYearSemester = "2026-1", fromWeek = 1, fromDay = 1, toWeek = 1, toDay = 3)),
        )
        val placed = placedCourses(listOf(course), 1, adjustments)
        assertEquals(listOf(1 to true, 3 to false), placed.map { it.course.dayOfWeek to it.muted })
        assertTrue(activeCoursesOn(listOf(course), 1, 1, adjustments).isEmpty())
        assertEquals("高数", activeCoursesOn(listOf(course), 1, 3, adjustments).single().courseName)
    }

    @Test
    fun suspendedSourceIsNotMoved() {
        val course = course("高数", day = 1)
        val adjustments = ScheduleAdjustments(
            suspensions = listOf(DaySuspensionEntity(acadYearSemester = "2026-1", weekNo = 1, dayOfWeek = 1)),
            moves = listOf(DayMoveEntity(acadYearSemester = "2026-1", fromWeek = 1, fromDay = 1, toWeek = 1, toDay = 3)),
        )
        val placed = placedCourses(listOf(course), 1, adjustments)
        assertEquals(listOf(1 to true), placed.map { it.course.dayOfWeek to it.muted })
        assertTrue(activeCoursesOn(listOf(course), 1, 3, adjustments).isEmpty())
    }

    @Test
    fun editedFieldsAreHighlightedAndOriginalStaysGray() {
        val course = course("高数", day = 1, teacher = "张")
        val adjustments = ScheduleAdjustments(
            patches = listOf(
                CoursePatchEntity(
                    acadYearSemester = "2026-1",
                    courseKey = courseAdjustKey(course),
                    weekNo = 1,
                    teacher = "李",
                    dayOfWeek = 2,
                ),
            ),
        )
        val placed = placedCourses(listOf(course), 1, adjustments)
        assertEquals(1, placed.first { it.muted }.course.dayOfWeek)
        val live = placed.first { !it.muted }
        assertEquals(2, live.course.dayOfWeek)
        assertEquals("李", live.course.teacher)
        assertEquals(setOf(CourseField.TEACHER, CourseField.TIME), live.highlights)
    }

    private fun course(name: String, day: Int, teacher: String = ""): CourseEntity = CourseEntity(
        acadYearSemester = "2026-1",
        source = "manual",
        courseName = name,
        teacher = teacher,
        dayOfWeek = day,
        startPeriod = 1,
        endPeriod = 2,
        weeksMask = WeekMask.bit(1),
        color = 1L,
    )
}
