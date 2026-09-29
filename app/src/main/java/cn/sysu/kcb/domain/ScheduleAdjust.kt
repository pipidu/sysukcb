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
    val suspended = adjustments.suspensions.filter { it.weekNo == weekNo }.map { it.dayOfWeek }.toSet()
    val out = mutableListOf<PlacedCourse>()
    for (course in courses) {
        if (!WeekMask.has(course.weeksMask, weekNo)) continue
        out += placeOne(course, weekNo, weekNo, suspended, adjustments)
    }
    for (move in adjustments.moves) {
        if (move.toWeek != weekNo || move.fromWeek == weekNo) continue
        if (isSuspended(adjustments, move.fromWeek, move.fromDay)) continue
        for (course in courses) {
            if (course.dayOfWeek != move.fromDay || !WeekMask.has(course.weeksMask, move.fromWeek)) continue
            val (edited, highlights) = applyPatch(course, patchFor(adjustments, course, move.fromWeek))
            val copy = edited.copy(dayOfWeek = move.toDay)
            val muted = move.toDay in suspended
            out += PlacedCourse(copy, muted, if (muted) emptySet() else highlights)
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

private fun placeOne(
    course: CourseEntity,
    sourceWeek: Int,
    viewWeek: Int,
    suspendedOnView: Set<Int>,
    adjustments: ScheduleAdjustments,
): List<PlacedCourse> {
    val patch = patchFor(adjustments, course, sourceWeek)
    val (edited, highlights) = applyPatch(course, patch)
    val dayMove = adjustments.moves.firstOrNull { it.fromWeek == sourceWeek && it.fromDay == course.dayOfWeek }
    val sourceSuspended = isSuspended(adjustments, sourceWeek, course.dayOfWeek)
    if (dayMove != null && !sourceSuspended) {
        val cards = mutableListOf(PlacedCourse(course, muted = true))
        if (dayMove.toWeek == viewWeek) {
            val copy = edited.copy(dayOfWeek = dayMove.toDay)
            val muted = dayMove.toDay in suspendedOnView
            cards += PlacedCourse(copy, muted, if (muted) emptySet() else highlights)
        }
        return cards
    }
    if (dayMove != null && sourceSuspended) {
        return listOf(PlacedCourse(course, muted = true))
    }
    val relocated = edited.dayOfWeek != course.dayOfWeek ||
        edited.startPeriod != course.startPeriod ||
        edited.endPeriod != course.endPeriod
    if (relocated) {
        val muted = edited.dayOfWeek in suspendedOnView
        return listOf(
            PlacedCourse(course, muted = true),
            PlacedCourse(edited, muted, if (muted) emptySet() else highlights),
        )
    }
    val muted = course.dayOfWeek in suspendedOnView
    return listOf(PlacedCourse(edited, muted, if (muted) emptySet() else highlights))
}

private fun isSuspended(adjustments: ScheduleAdjustments, weekNo: Int, day: Int): Boolean =
    adjustments.suspensions.any { it.weekNo == weekNo && it.dayOfWeek == day }

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
