package cn.sysu.kcb.data.repo

import androidx.room.withTransaction
import cn.sysu.kcb.data.local.AppDatabase
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.CoursePatchEntity
import cn.sysu.kcb.data.local.DayMoveEntity
import cn.sysu.kcb.data.local.DaySuspensionEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.ExamWeekEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.local.StickyNoteEntity
import cn.sysu.kcb.data.local.WeekdayEntity
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.DefaultPeriods
import cn.sysu.kcb.domain.ScheduleAdjustments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class TimetableRepository(private val db: AppDatabase) {
    val semesters: Flow<List<SemesterEntity>> = db.semesterDao().observeAll()
    val populatedCourseSemesters: Flow<List<String>> = db.courseDao().observePopulatedSemesters()
    val populatedExamSemesters: Flow<List<String>> = db.examDao().observePopulatedSemesters()

    fun courses(semester: String): Flow<List<CourseEntity>> = db.courseDao().observe(semester)
    fun exams(semester: String): Flow<List<ExamEntity>> = db.examDao().observe(semester)
    fun examWeeks(semester: String): Flow<List<ExamWeekEntity>> = db.examWeekDao().observe(semester)
    fun allExamWeeks(): Flow<List<ExamWeekEntity>> = db.examWeekDao().observeAll()
    fun weeks(semester: String): Flow<List<WeekEntity>> = db.weekDao().observe(semester)
    fun periods(semester: String): Flow<List<PeriodEntity>> = db.periodDao().observe(semester)
    fun allExams(): Flow<List<ExamEntity>> = db.examDao().observeAll()

    fun timetableState(semester: String): Flow<TimetableSnapshot> {
        val base = combine(
            db.courseDao().observe(semester),
            db.weekDao().observe(semester),
            db.periodDao().observe(semester),
            db.semesterDao().observe(semester),
            db.stickyNoteDao().observe(semester),
        ) { courses, weeks, periods, semesterRow, notes ->
            TimetableSnapshot(
                semester = semesterRow,
                courses = courses,
                weeks = weeks,
                periods = periods.ifEmpty { defaultPeriods(semester) },
                notes = notes,
            )
        }
        val adjustments = combine(
            db.daySuspensionDao().observe(semester),
            db.dayMoveDao().observe(semester),
            db.coursePatchDao().observe(semester),
        ) { suspensions, moves, patches ->
            ScheduleAdjustments(suspensions, moves, patches)
        }
        return combine(base, adjustments) { snap, adjust ->
            snap.copy(suspensions = adjust.suspensions, moves = adjust.moves, patches = adjust.patches)
        }
    }

    suspend fun listCourses(semester: String) = db.courseDao().list(semester)
    suspend fun listWeeks(semester: String) = db.weekDao().list(semester)
    suspend fun listPeriods(semester: String): List<PeriodEntity> {
        val stored = db.periodDao().list(semester)
        return stored.ifEmpty { defaultPeriods(semester) }
    }
    suspend fun listExams(semester: String) = db.examDao().list(semester)
    suspend fun listAllExams() = db.examDao().listAll()
    suspend fun listAllCourses() = db.courseDao().listAll()
    suspend fun listAllWeeks() = db.weekDao().listAll()
    suspend fun listAllPeriods() = db.periodDao().listAll()
    suspend fun listExamWeeks(semester: String) = db.examWeekDao().list(semester)
    suspend fun listAllExamWeeks() = db.examWeekDao().listAll()
    suspend fun listNotes(semester: String) = db.stickyNoteDao().list(semester)
    suspend fun listAllNotes() = db.stickyNoteDao().listAll()
    suspend fun listSemesters() = db.semesterDao().list()
    suspend fun getCourse(id: Long) = db.courseDao().get(id)
    suspend fun currentSemester() = db.semesterDao().current()

    suspend fun upsertSemester(item: SemesterEntity) = db.semesterDao().upsert(item)

    suspend fun ensureSemester(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) return
        if (db.semesterDao().get(trimmed) != null) return
        val parts = trimmed.split("-")
        val year = parts.getOrNull(0).orEmpty()
        val term = parts.getOrNull(1)?.toIntOrNull() ?: 1
        upsertSemester(
            SemesterEntity(
                acadYearSemester = trimmed,
                acadYear = year,
                acadSemester = term,
                startMillis = 0L,
                endMillis = 0L,
                isCurrent = false,
            ),
        )
    }
    suspend fun clearCurrentFlag() = db.semesterDao().clearCurrentFlag()
    suspend fun replaceWeeks(semester: String, items: List<WeekEntity>) {
        if (items.isEmpty()) return
        db.withTransaction {
            db.weekDao().deleteSemester(semester)
            db.weekDao().upsertAll(items)
        }
    }
    suspend fun replacePeriods(semester: String, items: List<PeriodEntity>) {
        if (items.isEmpty()) return
        db.withTransaction {
            db.periodDao().deleteSemester(semester)
            db.periodDao().upsertAll(items)
        }
    }
    suspend fun replaceWeekdays(items: List<WeekdayEntity>) {
        if (items.isNotEmpty()) db.weekdayDao().upsertAll(items)
    }

    suspend fun replaceImportedCourses(semester: String, imported: List<CourseEntity>) {
        db.withTransaction {
            db.courseDao().deleteImported(semester)
            if (imported.isNotEmpty()) db.courseDao().insertAll(imported.map { it.copy(id = 0) })
        }
    }

    suspend fun replaceCoursesKeepExams(
        semester: SemesterEntity,
        weeks: List<WeekEntity>,
        periods: List<PeriodEntity>,
        courses: List<CourseEntity>,
    ) {
        db.withTransaction {
            db.semesterDao().upsert(semester)
            if (weeks.isNotEmpty()) {
                db.weekDao().deleteSemester(semester.acadYearSemester)
                db.weekDao().upsertAll(weeks)
            }
            if (periods.isNotEmpty()) {
                db.periodDao().deleteSemester(semester.acadYearSemester)
                db.periodDao().upsertAll(periods)
            }
            db.courseDao().deleteSemester(semester.acadYearSemester)
            if (courses.isNotEmpty()) db.courseDao().insertAll(courses.map { it.copy(id = 0) })
        }
    }

    suspend fun replaceExams(semester: String, items: List<ExamEntity>) {
        db.withTransaction {
            db.examDao().deleteSemester(semester)
            if (items.isNotEmpty()) db.examDao().upsertAll(items)
        }
    }

    suspend fun replaceExamWeeks(semester: String, items: List<ExamWeekEntity>) {
        db.withTransaction {
            db.examWeekDao().deleteSemester(semester)
            if (items.isNotEmpty()) db.examWeekDao().upsertAll(items)
        }
    }

    suspend fun compactStorage(): Boolean {
        val raw = db.rawImportDao().count()
        val extra = db.courseDao().countExtraJson() + db.examDao().countExtraJson()
        if (raw > 0) db.rawImportDao().clear()
        if (extra > 0) {
            db.courseDao().clearExtraJson()
            db.examDao().clearExtraJson()
        }
        val dirty = raw > 0 || extra > 0
        if (dirty) {
            runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        }
        return dirty
    }

    suspend fun addCourse(item: CourseEntity): Long = db.courseDao().insert(item)
    suspend fun updateCourse(item: CourseEntity) = db.courseDao().update(item)
    suspend fun deleteCourse(item: CourseEntity) = db.courseDao().delete(item)

    suspend fun saveNote(item: StickyNoteEntity): Long {
        return if (item.id == 0L) db.stickyNoteDao().insert(item) else {
            db.stickyNoteDao().update(item)
            item.id
        }
    }

    suspend fun deleteNote(item: StickyNoteEntity) = db.stickyNoteDao().delete(item)

    suspend fun replaceNotes(semester: String, items: List<StickyNoteEntity>) {
        db.withTransaction {
            db.stickyNoteDao().deleteSemester(semester)
            if (items.isNotEmpty()) db.stickyNoteDao().insertAll(items.map { it.copy(id = 0) })
        }
    }

    suspend fun recolorToTheme(fromTheme: Long, toTheme: Long) {
        if (fromTheme == toTheme) return
        val all = db.courseDao().listAll()
        val next = all.mapNotNull { course ->
            val color = CourseColors.remap(course.color, fromTheme, toTheme, course.courseName)
            if (color != course.color) course.copy(color = color) else null
        }
        if (next.isNotEmpty()) db.courseDao().updateAll(next)
    }

    suspend fun <T> withTransaction(block: suspend () -> T): T = db.withTransaction(block)

    suspend fun replaceSemesterPack(
        semester: SemesterEntity,
        weeks: List<WeekEntity>,
        periods: List<PeriodEntity>,
        courses: List<CourseEntity>,
        exams: List<ExamEntity>,
        examWeeks: List<ExamWeekEntity> = emptyList(),
    ) {
        val weeksForExams = examWeeks.ifEmpty {
            exams.map { it.examWeekId.orEmpty() to it.examWeekName }
                .filter { it.first.isNotBlank() || it.second.isNotBlank() }
                .distinctBy { it.first.ifBlank { it.second } }
                .map { (id, name) ->
                    ExamWeekEntity(
                        acadYearSemester = semester.acadYearSemester,
                        examWeekId = id.ifBlank { name },
                        examWeekName = name.ifBlank { "考试" },
                    )
                }
        }
        db.withTransaction {
            db.semesterDao().upsert(semester)
            db.weekDao().deleteSemester(semester.acadYearSemester)
            if (weeks.isNotEmpty()) db.weekDao().upsertAll(weeks)
            db.periodDao().deleteSemester(semester.acadYearSemester)
            if (periods.isNotEmpty()) db.periodDao().upsertAll(periods)
            db.courseDao().deleteSemester(semester.acadYearSemester)
            if (courses.isNotEmpty()) db.courseDao().insertAll(courses.map { it.copy(id = 0) })
            db.examDao().deleteSemester(semester.acadYearSemester)
            if (exams.isNotEmpty()) db.examDao().upsertAll(exams)
            db.examWeekDao().deleteSemester(semester.acadYearSemester)
            if (weeksForExams.isNotEmpty()) db.examWeekDao().upsertAll(weeksForExams)
        }
    }

    suspend fun adjustments(semester: String) = ScheduleAdjustments(
        suspensions = db.daySuspensionDao().list(semester),
        moves = db.dayMoveDao().list(semester),
        patches = db.coursePatchDao().list(semester),
    )

    suspend fun listAllSuspensions() = db.daySuspensionDao().listAll()
    suspend fun listAllMoves() = db.dayMoveDao().listAll()
    suspend fun listAllPatches() = db.coursePatchDao().listAll()

    suspend fun replaceSuspensions(semester: String, weekNo: Int, days: Set<Int>) {
        db.withTransaction {
            db.daySuspensionDao().deleteWeek(semester, weekNo)
            val rows = days.filter { it in 1..7 }.map {
                DaySuspensionEntity(acadYearSemester = semester, weekNo = weekNo, dayOfWeek = it)
            }
            if (rows.isNotEmpty()) db.daySuspensionDao().insertAll(rows)
        }
    }

    suspend fun saveMove(item: DayMoveEntity) {
        if (item.fromWeek == item.toWeek && item.fromDay == item.toDay) {
            db.dayMoveDao().deleteOne(item.acadYearSemester, item.fromWeek, item.fromDay)
        } else {
            db.dayMoveDao().insert(item.copy(id = 0))
        }
    }

    suspend fun deleteMove(semester: String, fromWeek: Int, fromDay: Int) {
        db.dayMoveDao().deleteOne(semester, fromWeek, fromDay)
    }

    suspend fun savePatch(item: CoursePatchEntity) {
        val empty = item.courseName == null && item.teacher == null && item.place == null &&
            item.dayOfWeek == null && item.startPeriod == null && item.endPeriod == null && item.notes == null
        if (empty) {
            db.coursePatchDao().deleteOne(item.acadYearSemester, item.courseKey, item.weekNo)
        } else {
            db.coursePatchDao().insert(item.copy(id = 0))
        }
    }

    suspend fun deletePatch(semester: String, courseKey: String, weekNo: Int) {
        db.coursePatchDao().deleteOne(semester, courseKey, weekNo)
    }

    suspend fun replaceAdjustments(
        semester: String,
        suspensions: List<DaySuspensionEntity>,
        moves: List<DayMoveEntity>,
        patches: List<CoursePatchEntity>,
    ) {
        db.withTransaction {
            db.daySuspensionDao().deleteSemester(semester)
            db.dayMoveDao().deleteSemester(semester)
            db.coursePatchDao().deleteSemester(semester)
            if (suspensions.isNotEmpty()) {
                db.daySuspensionDao().insertAll(suspensions.map { it.copy(id = 0, acadYearSemester = semester) })
            }
            if (moves.isNotEmpty()) {
                db.dayMoveDao().insertAll(moves.map { it.copy(id = 0, acadYearSemester = semester) })
            }
            if (patches.isNotEmpty()) {
                db.coursePatchDao().insertAll(patches.map { it.copy(id = 0, acadYearSemester = semester) })
            }
        }
    }

    suspend fun clearAll() {
        db.courseDao().clear()
        db.examDao().clear()
        db.examWeekDao().clear()
        db.stickyNoteDao().clear()
        db.daySuspensionDao().clear()
        db.dayMoveDao().clear()
        db.coursePatchDao().clear()
        db.semesterDao().clear()
        db.rawImportDao().clear()
    }

    private fun defaultPeriods(semester: String) = DefaultPeriods.list.map {
        PeriodEntity(
            acadYearSemester = semester,
            sectionNumber = it.section,
            minorName = it.name,
            startTime = it.start,
            endTime = it.end,
            bigSection = it.big,
            bigSectionName = it.bigName,
        )
    }
}

data class TimetableSnapshot(
    val semester: SemesterEntity?,
    val courses: List<CourseEntity>,
    val weeks: List<WeekEntity>,
    val periods: List<PeriodEntity>,
    val notes: List<StickyNoteEntity> = emptyList(),
    val suspensions: List<DaySuspensionEntity> = emptyList(),
    val moves: List<DayMoveEntity> = emptyList(),
    val patches: List<CoursePatchEntity> = emptyList(),
)
