package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.CourseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedCourseMergeTest {
    @Test
    fun uneditedIncomingIsKeptWhenNothingWasLocallyEdited() {
        val incoming = listOf(imported("高数", classesId = "c1", day = 1, start = 1, id = 0))
        assertEquals(incoming, incomingImportedToInsert(incoming, retainedEdited = emptyList()))
    }

    @Test
    fun editedPlaceIsNotDuplicatedOnReimport() {
        val retained = imported("高数", classesId = "c1", day = 1, start = 1, id = 11, place = "东堂A")
        val incoming = imported("高数", classesId = "c1", day = 1, start = 1, id = 0, place = "东堂B")
        val toInsert = incomingImportedToInsert(listOf(incoming), listOf(retained))
        assertTrue(toInsert.isEmpty())
    }

    @Test
    fun otherImportedCoursesAreStillReplaced() {
        val retained = imported("高数", classesId = "c1", day = 1, start = 1, id = 11, place = "东堂A")
        val incomingEdited = imported("高数", classesId = "c1", day = 1, start = 1, id = 0, place = "东堂B")
        val incomingNew = imported("英语", classesId = "c2", day = 2, start = 3, id = 0)
        val toInsert = incomingImportedToInsert(listOf(incomingEdited, incomingNew), listOf(retained))
        assertEquals(listOf("英语"), toInsert.map { it.courseName })
    }

    @Test
    fun movedSessionStillMatchesTheSameClassId() {
        val retained = imported("高数", classesId = "c1", day = 2, start = 3, id = 11)
        val incoming = imported("高数", classesId = "c1", day = 1, start = 1, id = 0)
        val toInsert = incomingImportedToInsert(listOf(incoming), listOf(retained))
        assertTrue(toInsert.isEmpty())
    }

    @Test
    fun multiSessionClassOnlySkipsTheMatchingSession() {
        val retainedMonday = imported("高数", classesId = "c1", day = 1, start = 1, id = 11, place = "改过的教室")
        val incomingMonday = imported("高数", classesId = "c1", day = 1, start = 1, id = 0, place = "原教室")
        val incomingWednesday = imported("高数", classesId = "c1", day = 3, start = 5, id = 0, place = "原教室")
        val toInsert = incomingImportedToInsert(
            listOf(incomingMonday, incomingWednesday),
            listOf(retainedMonday),
        )
        assertEquals(listOf(3), toInsert.map { it.dayOfWeek })
    }

    @Test
    fun coursesWithoutClassIdMatchByNameDayAndPeriod() {
        val retained = imported("高数", classesId = null, day = 1, start = 1, id = 11, place = "东堂A")
        val incoming = imported("高数", classesId = null, day = 1, start = 1, id = 0, place = "东堂B")
        val other = imported("英语", classesId = null, day = 2, start = 1, id = 0)
        val toInsert = incomingImportedToInsert(listOf(incoming, other), listOf(retained))
        assertEquals(listOf("英语"), toInsert.map { it.courseName })
    }

    @Test
    fun cancelledCourseKeepsTheLocallyEditedCopyAndInsertsNothingForIt() {
        val retained = imported("高数", classesId = "c1", day = 1, start = 1, id = 11, place = "东堂A")
        val remaining = imported("英语", classesId = "c2", day = 2, start = 3, id = 0)
        val toInsert = incomingImportedToInsert(listOf(remaining), listOf(retained))
        assertEquals(listOf("英语"), toInsert.map { it.courseName })
    }

    private fun imported(
        name: String,
        classesId: String?,
        day: Int,
        start: Int,
        id: Long,
        place: String = "",
    ): CourseEntity = CourseEntity(
        id = id,
        acadYearSemester = "2026-1",
        source = "imported",
        locallyEdited = id != 0L,
        classesId = classesId,
        courseName = name,
        place = place,
        dayOfWeek = day,
        startPeriod = start,
        endPeriod = start + 1,
        weeksMask = WeekMask.bit(1),
        color = 1L,
    )
}
