package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.DaySuspensionEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.prefs.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ClassAlarmPlanTest {
    private val origin = LocalDate.of(2026, 2, 16)
    private val weeks = TeachingWeek.buildWeeks("2026-2", 16, origin)
    private val startMillis = TeachingWeek.toEpochMillis(origin)
    private val week1Only = course("高数", day = 1, mask = WeekMask.bit(1))

    @Test
    fun noRemindersWhenDateIsAfterTheTerm() {
        val afterTerm = LocalDate.of(2026, 6, 8)
        assertEquals(null, TeachingWeek.resolveWeek(afterTerm, weeks, startMillis))
        assertTrue(
            coursesForClassRemindersOn(
                date = afterTerm,
                courses = listOf(week1Only),
                weeks = weeks,
                semesterStartMillis = startMillis,
                adjustments = ScheduleAdjustments(),
            ).isEmpty(),
        )
    }

    @Test
    fun noRemindersWhenDateIsBeforeTheTerm() {
        val beforeTerm = LocalDate.of(2026, 2, 9)
        assertEquals(null, TeachingWeek.resolveWeek(beforeTerm, weeks, startMillis))
        assertTrue(
            coursesForClassRemindersOn(
                date = beforeTerm,
                courses = listOf(week1Only),
                weeks = weeks,
                semesterStartMillis = startMillis,
                adjustments = ScheduleAdjustments(),
            ).isEmpty(),
        )
    }

    @Test
    fun weekMaskIsAppliedWhenTheTeachingWeekIsKnown() {
        val week1Monday = origin
        val week2Monday = origin.plusWeeks(1)
        val onWeek1 = coursesForClassRemindersOn(week1Monday, listOf(week1Only), weeks, startMillis, ScheduleAdjustments())
        val onWeek2 = coursesForClassRemindersOn(week2Monday, listOf(week1Only), weeks, startMillis, ScheduleAdjustments())
        assertEquals(listOf("高数"), onWeek1.map { it.courseName })
        assertTrue(onWeek2.isEmpty())
    }

    @Test
    fun suspensionsAreAppliedWhenTheTeachingWeekIsKnown() {
        val adjustments = ScheduleAdjustments(
            suspensions = listOf(
                DaySuspensionEntity(acadYearSemester = "2026-2", weekNo = 1, dayOfWeek = 1),
            ),
        )
        assertTrue(
            coursesForClassRemindersOn(origin, listOf(week1Only), weeks, startMillis, adjustments).isEmpty(),
        )
    }

    @Test
    fun shouldSkipRescheduleOnTheSameDayWhenFingerprintIsUnchanged() {
        assertFalse(shouldRefreshClassAlarms("2026-11-18", "abc", "2026-11-18", "abc"))
        assertTrue(shouldRefreshClassAlarms("2026-11-18", "abc", "2026-11-19", "abc"))
        assertTrue(shouldRefreshClassAlarms("2026-11-18", "abc", "2026-11-18", "def"))
        assertTrue(shouldRefreshClassAlarms(null, null, "2026-11-18", "abc"))
    }

    @Test
    fun fingerprintChangesWhenReminderSettingsOrCoursesChange() {
        val base = classAlarmFingerprint(
            settings = UserSettings(reminderEnabled = true, reminderMinutes = 15),
            semester = "2026-2",
            semesterStartMillis = startMillis,
            courses = listOf(week1Only),
            exams = emptyList(),
            periods = listOf(period()),
            weeks = weeks,
            adjustments = ScheduleAdjustments(),
        )
        val laterMinutes = classAlarmFingerprint(
            settings = UserSettings(reminderEnabled = true, reminderMinutes = 20),
            semester = "2026-2",
            semesterStartMillis = startMillis,
            courses = listOf(week1Only),
            exams = emptyList(),
            periods = listOf(period()),
            weeks = weeks,
            adjustments = ScheduleAdjustments(),
        )
        val extraCourse = classAlarmFingerprint(
            settings = UserSettings(reminderEnabled = true, reminderMinutes = 15),
            semester = "2026-2",
            semesterStartMillis = startMillis,
            courses = listOf(week1Only, course("英语", day = 2, mask = WeekMask.bit(1))),
            exams = emptyList(),
            periods = listOf(period()),
            weeks = weeks,
            adjustments = ScheduleAdjustments(),
        )
        val withExam = classAlarmFingerprint(
            settings = UserSettings(reminderEnabled = true, reminderMinutes = 15),
            semester = "2026-2",
            semesterStartMillis = startMillis,
            courses = listOf(week1Only),
            exams = listOf(
                ExamEntity(
                    acadYearSemester = "2026-2",
                    subjectName = "高数",
                    examDate = "2026-06-20",
                    startTime = "08:00",
                    endTime = "10:00",
                ),
            ),
            periods = listOf(period()),
            weeks = weeks,
            adjustments = ScheduleAdjustments(),
        )
        assertTrue(base != laterMinutes)
        assertTrue(base != extraCourse)
        assertTrue(base != withExam)
        assertEquals(
            base,
            classAlarmFingerprint(
                settings = UserSettings(reminderEnabled = true, reminderMinutes = 15),
                semester = "2026-2",
                semesterStartMillis = startMillis,
                courses = listOf(week1Only),
                exams = emptyList(),
                periods = listOf(period()),
                weeks = weeks,
                adjustments = ScheduleAdjustments(),
            ),
        )
    }

    @Test
    fun legacyCodesAreScannedOnlyOnce() {
        val first = alarmRequestCodesToCancel(setOf("401", "402"), legacyCleared = false)
        assertTrue(0 in first)
        assertTrue(399 in first)
        assertTrue(401 in first)
        val later = alarmRequestCodesToCancel(setOf("401", "402"), legacyCleared = true)
        assertEquals(setOf(401, 402), later)
    }


    @Test
    fun widgetRefreshIncludesUpcomingClassBoundariesAndMidnights() {
        val periods = listOf(period(1, "08:00", "08:45"), period(2, "08:55", "09:40"))
        val now = LocalDateTime.of(origin, LocalTime.of(7, 0))
        val times = widgetRefreshTimes(now, listOf(week1Only), periods, weeks, startMillis)
        assertEquals(
            listOf(
                LocalDateTime.of(origin, LocalTime.of(8, 0)),
                LocalDateTime.of(origin, LocalTime.of(9, 40)),
            ),
            times.filter { it.kind == ALARM_KIND_WIDGET }.map { it.at },
        )
        assertEquals(14, times.count { it.kind == ALARM_KIND_MIDNIGHT })
        assertEquals(origin.plusDays(1).atStartOfDay(), times.first { it.kind == ALARM_KIND_MIDNIGHT }.at)
        assertEquals(origin.plusDays(14).atStartOfDay(), times.last { it.kind == ALARM_KIND_MIDNIGHT }.at)
        assertTrue(times.all { it.at.isAfter(now) })
    }

    @Test
    fun widgetRefreshSkipsClassBoundariesThatAlreadyPassed() {
        val periods = listOf(period(1, "08:00", "08:45"), period(2, "08:55", "09:40"))
        val now = LocalDateTime.of(origin, LocalTime.of(8, 20))
        val widgetTimes = widgetRefreshTimes(now, listOf(week1Only), periods, weeks, startMillis)
            .filter { it.kind == ALARM_KIND_WIDGET }
            .map { it.at }
        assertEquals(listOf(LocalDateTime.of(origin, LocalTime.of(9, 40))), widgetTimes)
    }

    @Test
    fun widgetRefreshSkipsDaysWithoutATeachingWeek() {
        val periods = listOf(period(1, "08:00", "08:45"), period(2, "08:55", "09:40"))
        val afterTerm = LocalDateTime.of(2026, 6, 8, 7, 0)
        val times = widgetRefreshTimes(afterTerm, listOf(week1Only), periods, weeks, startMillis)
        assertTrue(times.none { it.kind == ALARM_KIND_WIDGET })
        assertEquals(14, times.count { it.kind == ALARM_KIND_MIDNIGHT })
    }

    @Test
    fun widgetRefreshRespectsSuspensions() {
        val periods = listOf(period(1, "08:00", "08:45"), period(2, "08:55", "09:40"))
        val adjustments = ScheduleAdjustments(
            suspensions = listOf(
                DaySuspensionEntity(acadYearSemester = "2026-2", weekNo = 1, dayOfWeek = 1),
            ),
        )
        val now = LocalDateTime.of(origin, LocalTime.of(7, 0))
        val times = widgetRefreshTimes(now, listOf(week1Only), periods, weeks, startMillis, adjustments)
        assertTrue(times.none { it.kind == ALARM_KIND_WIDGET && it.at.toLocalDate() == origin })
    }

    @Test
    fun midnightAlarmsExtendTheClassReminderWindow() {
        assertTrue(shouldExtendClassReminderWindow(ALARM_KIND_MIDNIGHT))
        assertFalse(shouldExtendClassReminderWindow(ALARM_KIND_WIDGET))
        assertFalse(shouldExtendClassReminderWindow(ALARM_KIND_REMINDER))
    }

    @Test
    fun parseClockTimeAcceptsCommonFormats() {
        assertEquals(LocalTime.of(8, 0), parseClockTime("08:00"))
        assertEquals(LocalTime.of(8, 0), parseClockTime("8:00"))
        assertEquals(LocalTime.of(8, 0), parseClockTime("08：00"))
        assertEquals(null, parseClockTime(""))
        assertEquals(null, parseClockTime("abc"))
    }

    private fun course(name: String, day: Int, mask: Long): CourseEntity = CourseEntity(
        acadYearSemester = "2026-2",
        source = "imported",
        courseName = name,
        dayOfWeek = day,
        startPeriod = 1,
        endPeriod = 2,
        weeksMask = mask,
        color = 1L,
    )

    private fun period(
        section: Int = 1,
        start: String = "08:00",
        end: String = "08:45",
    ): PeriodEntity = PeriodEntity(
        acadYearSemester = "2026-2",
        sectionNumber = section,
        minorName = section.toString(),
        startTime = start,
        endTime = end,
        bigSection = section.toString(),
        bigSectionName = "第${section}节",
    )
}
