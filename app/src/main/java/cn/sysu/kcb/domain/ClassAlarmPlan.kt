package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.prefs.UserSettings
import java.time.LocalDate

fun coursesForClassRemindersOn(
    date: LocalDate,
    courses: List<CourseEntity>,
    weeks: List<WeekEntity>,
    semesterStartMillis: Long,
    adjustments: ScheduleAdjustments,
): List<CourseEntity> {
    val weekNo = TeachingWeek.resolveWeek(date, weeks, semesterStartMillis) ?: return emptyList()
    return activeCoursesOn(courses, weekNo, date.dayOfWeek.value, adjustments)
}

fun shouldRefreshClassAlarms(
    lastDate: String?,
    lastFingerprint: String?,
    today: String,
    fingerprint: String,
): Boolean = lastDate != today || lastFingerprint != fingerprint

fun classAlarmFingerprint(
    settings: UserSettings,
    semester: String,
    semesterStartMillis: Long,
    courses: List<CourseEntity>,
    exams: List<ExamEntity>,
    periods: List<PeriodEntity>,
    weeks: List<WeekEntity>,
    adjustments: ScheduleAdjustments,
): String = buildString {
    append(settings.reminderEnabled).append('|')
    append(settings.reminderMinutes).append('|')
    append(settings.reminderStyle).append('|')
    append(settings.examReminderEnabled).append('|')
    append(settings.examReminderMinutes).append('|')
    append(semester).append('|')
    append(semesterStartMillis)
    append("#c")
    courses.sortedWith(compareBy({ it.id }, { it.classesId }, { it.dayOfWeek }, { it.startPeriod })).forEach { course ->
        append('|')
        append(course.id).append(',')
        append(course.classesId.orEmpty()).append(',')
        append(course.courseName).append(',')
        append(course.place).append(',')
        append(course.dayOfWeek).append(',')
        append(course.startPeriod).append(',')
        append(course.endPeriod).append(',')
        append(course.weeksMask)
    }
    append("#e")
    exams.sortedWith(compareBy({ it.id }, { it.examDate }, { it.startTime })).forEach { exam ->
        append('|')
        append(exam.id).append(',')
        append(exam.examDate).append(',')
        append(exam.startTime)
    }
    append("#p")
    periods.sortedBy { it.sectionNumber }.forEach { period ->
        append('|')
        append(period.sectionNumber).append(',')
        append(period.startTime)
    }
    append("#w")
    weeks.sortedBy { it.weekly }.forEach { week ->
        append('|')
        append(week.weekly).append(',')
        append(week.startDate.orEmpty())
    }
    append("#s")
    adjustments.suspensions.sortedWith(compareBy({ it.weekNo }, { it.dayOfWeek }, { it.courseKey }, { it.id })).forEach { item ->
        append('|')
        append(item.weekNo).append(',')
        append(item.dayOfWeek).append(',')
        append(item.courseKey)
    }
    append("#m")
    adjustments.moves.sortedWith(compareBy({ it.fromWeek }, { it.fromDay }, { it.toWeek }, { it.toDay }, { it.id })).forEach { item ->
        append('|')
        append(item.fromWeek).append(',')
        append(item.fromDay).append(',')
        append(item.toWeek).append(',')
        append(item.toDay).append(',')
        append(item.copy)
    }
    append("#x")
    adjustments.patches.sortedWith(compareBy({ it.courseKey }, { it.weekNo }, { it.id })).forEach { item ->
        append('|')
        append(item.courseKey).append(',')
        append(item.weekNo).append(',')
        append(item.dayOfWeek).append(',')
        append(item.startPeriod).append(',')
        append(item.endPeriod)
    }
}

fun alarmRequestCodesToCancel(stored: Collection<String>, legacyCleared: Boolean, legacyLimit: Int = 400): Set<Int> {
    val codes = linkedSetOf<Int>()
    for (raw in stored) raw.toIntOrNull()?.let { codes += it }
    if (!legacyCleared) {
        for (code in 0 until legacyLimit) codes += code
    }
    return codes
}
