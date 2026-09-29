package cn.sysu.kcb.domain

import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.CoursePatchEntity
import cn.sysu.kcb.data.local.DayMoveEntity
import cn.sysu.kcb.data.local.DaySuspensionEntity

enum class CourseField {
    NAME,
    TEACHER,
    PLACE,
    TIME,
    NOTES,
}

data class ScheduleAdjustments(
    val suspensions: List<DaySuspensionEntity> = emptyList(),
    val moves: List<DayMoveEntity> = emptyList(),
    val patches: List<CoursePatchEntity> = emptyList(),
)

data class PlacedCourse(
    val course: CourseEntity,
    val muted: Boolean = false,
    val highlights: Set<CourseField> = emptySet(),
)

fun courseAdjustKey(course: CourseEntity): String = listOf(
    course.courseName.trim(),
    course.dayOfWeek.toString(),
    course.startPeriod.toString(),
    course.endPeriod.toString(),
    course.teacher.trim(),
    course.place.trim(),
).joinToString("|")

fun weekdayLabel(day: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day - 1) { "" }

fun placedCourses(
    courses: List<CourseEntity>,
    weekNo: Int,
    adjustments: ScheduleAdjustments,
): List<PlacedCourse> {
    if (weekNo !in 1..WeekMask.MAX_WEEK) return courses.map { PlacedCourse(it) }
    val out = mutableListOf<PlacedCourse>()
    for (course in courses) {
        if (!WeekMask.has(course.weeksMask, weekNo)) continue
        val homeMove = adjustments.moves.firstOrNull { it.fromWeek == weekNo && it.fromDay == course.dayOfWeek }
        val (edited, highlights) = applyPatch(course, patchFor(adjustments, course, weekNo))
        if (homeMove != null) {
            if (homeMove.toWeek == weekNo) {
                out += PlacedCourse(edited.copy(dayOfWeek = homeMove.toDay), muted = false, highlights)
            }
            continue
        }
        if (edited.dayOfWeek != course.dayOfWeek) {
            out += PlacedCourse(edited, muted = false, highlights)
            continue
        }
        val muted = isSuspended(adjustments, weekNo, course.dayOfWeek) ||
            hasArrival(courses, weekNo, course.dayOfWeek, adjustments)
        out += PlacedCourse(edited, muted, if (muted) emptySet() else highlights)
    }
    for (move in adjustments.moves) {
        if (move.toWeek != weekNo || move.fromWeek == weekNo) continue
        for (course in courses) {
            if (course.dayOfWeek != move.fromDay || !WeekMask.has(course.weeksMask, move.fromWeek)) continue
            val (edited, highlights) = applyPatch(course, patchFor(adjustments, course, move.fromWeek))
            out += PlacedCourse(edited.copy(dayOfWeek = move.toDay), muted = false, highlights)
        }
    }
    return out
}

fun activeCoursesOn(
    courses: List<CourseEntity>,
    weekNo: Int,
    dayOfWeek: Int,
    adjustments: ScheduleAdjustments,
): List<CourseEntity> = placedCourses(courses, weekNo, adjustments)
    .filter { !it.muted && it.course.dayOfWeek == dayOfWeek }
    .map { it.course }
    .sortedBy { it.startPeriod }

private fun isSuspended(adjustments: ScheduleAdjustments, weekNo: Int, day: Int): Boolean =
    adjustments.suspensions.any { it.weekNo == weekNo && it.dayOfWeek == day }

private fun hasArrival(
    courses: List<CourseEntity>,
    weekNo: Int,
    day: Int,
    adjustments: ScheduleAdjustments,
): Boolean {
    if (adjustments.moves.any { it.toWeek == weekNo && it.toDay == day && !(it.fromWeek == weekNo && it.fromDay == day) }) {
        return true
    }
    return courses.any { course ->
        if (course.dayOfWeek == day || !WeekMask.has(course.weeksMask, weekNo)) return@any false
        if (adjustments.moves.any { it.fromWeek == weekNo && it.fromDay == course.dayOfWeek }) return@any false
        val patch = patchFor(adjustments, course, weekNo) ?: return@any false
        val nextDay = patch.dayOfWeek ?: return@any false
        nextDay != course.dayOfWeek && nextDay == day
    }
}

private fun patchFor(adjustments: ScheduleAdjustments, course: CourseEntity, weekNo: Int): CoursePatchEntity? =
    adjustments.patches.firstOrNull { it.weekNo == weekNo && it.courseKey == courseAdjustKey(course) }

fun applyPatch(course: CourseEntity, patch: CoursePatchEntity?): Pair<CourseEntity, Set<CourseField>> {
    if (patch == null) return course to emptySet()
    val highlights = mutableSetOf<CourseField>()
    var next = course
    patch.courseName?.takeIf { it != course.courseName }?.let {
        next = next.copy(courseName = it)
        highlights += CourseField.NAME
    }
    patch.teacher?.takeIf { it != course.teacher }?.let {
        next = next.copy(teacher = it)
        highlights += CourseField.TEACHER
    }
    patch.place?.takeIf { it != course.place }?.let {
        next = next.copy(place = it)
        highlights += CourseField.PLACE
    }
    patch.notes?.takeIf { it != course.notes }?.let {
        next = next.copy(notes = it)
        highlights += CourseField.NOTES
    }
    val nextDay = patch.dayOfWeek ?: course.dayOfWeek
    val nextStart = patch.startPeriod ?: course.startPeriod
    val nextEnd = patch.endPeriod ?: course.endPeriod
    if (nextDay != course.dayOfWeek || nextStart != course.startPeriod || nextEnd != course.endPeriod) {
        next = next.copy(dayOfWeek = nextDay, startPeriod = nextStart, endPeriod = nextEnd)
        highlights += CourseField.TIME
    }
    return next to highlights
}
