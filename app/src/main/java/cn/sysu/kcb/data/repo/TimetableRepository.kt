package cn.sysu.kcb.data.repo

import androidx.room.withTransaction
import cn.sysu.kcb.data.local.AppDatabase
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.ExamWeekEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.RawImportEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.local.WeekdayEntity
import cn.sysu.kcb.domain.CourseColors
import cn.sysu.kcb.domain.DefaultPeriods
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class TimetableRepository(private val db: AppDatabase) {
    val semesters: Flow<List<SemesterEntity>> = db.semesterDao().observeAll()

    fun courses(semester: String): Flow<List<CourseEntity>> = db.courseDao().observe(semester)
    fun exams(semester: String): Flow<List<ExamEntity>> = db.examDao().observe(semester)
    fun examWeeks(semester: String): Flow<List<ExamWeekEntity>> = db.examWeekDao().observe(semester)
    fun allExamWeeks(): Flow<List<ExamWeekEntity>> = db.examWeekDao().observeAll()
    fun weeks(semester: String): Flow<List<WeekEntity>> = db.weekDao().observe(semester)
    fun periods(semester: String): Flow<List<PeriodEntity>> = db.periodDao().observe(semester)
    fun allExams(): Flow<List<ExamEntity>> = db.examDao().observeAll()

    fun timetableState(semester: String): Flow<TimetableSnapshot> =
        combine(
            db.courseDao().observe(semester),
            db.weekDao().observe(semester),
            db.periodDao().observe(semester),
            db.semesterDao().observeAll(),
        ) { courses, weeks, periods, semesters ->
            TimetableSnapshot(
                semester = semesters.firstOrNull { it.acadYearSemester == semester },
                courses = courses,
                weeks = weeks,
                periods = periods.ifEmpty { defaultPeriods(semester) },
            )
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
    suspend fun listSemesters() = db.semesterDao().list()
    suspend fun getCourse(id: Long) = db.courseDao().get(id)
    suspend fun currentSemester() = db.semesterDao().current()

    suspend fun upsertSemester(item: SemesterEntity) = db.semesterDao().upsert(item)
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
            imported.forEach { db.courseDao().insert(it.copy(id = 0)) }
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
            courses.forEach { db.courseDao().insert(it.copy(id = 0)) }
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

    suspend fun saveRaw(item: RawImportEntity) = db.rawImportDao().upsert(item)

    suspend fun addCourse(item: CourseEntity): Long = db.courseDao().insert(item)
    suspend fun updateCourse(item: CourseEntity) = db.courseDao().update(item)
    suspend fun deleteCourse(item: CourseEntity) = db.courseDao().delete(item)

    suspend fun recolorToTheme(fromTheme: Long, toTheme: Long) {
        if (fromTheme == toTheme) return
        val all = db.courseDao().listAll()
        db.withTransaction {
            for (course in all) {
                val next = CourseColors.remap(course.color, fromTheme, toTheme, course.courseName)
                if (next != course.color) db.courseDao().update(course.copy(color = next))
            }
        }
    }

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
            courses.forEach { db.courseDao().insert(it.copy(id = 0)) }
            db.examDao().deleteSemester(semester.acadYearSemester)
            if (exams.isNotEmpty()) db.examDao().upsertAll(exams)
            db.examWeekDao().deleteSemester(semester.acadYearSemester)
            if (weeksForExams.isNotEmpty()) db.examWeekDao().upsertAll(weeksForExams)
        }
    }

    suspend fun clearAll() {
        db.courseDao().clear()
        db.examDao().clear()
        db.examWeekDao().clear()
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
)
