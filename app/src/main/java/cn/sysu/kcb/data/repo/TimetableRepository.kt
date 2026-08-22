package cn.sysu.kcb.data.repo

import cn.sysu.kcb.data.local.AppDatabase
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.RawImportEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import cn.sysu.kcb.data.local.WeekdayEntity
import cn.sysu.kcb.domain.DefaultPeriods
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class TimetableRepository(private val db: AppDatabase) {
    val semesters: Flow<List<SemesterEntity>> = db.semesterDao().observeAll()

    fun courses(semester: String): Flow<List<CourseEntity>> = db.courseDao().observe(semester)
    fun exams(semester: String): Flow<List<ExamEntity>> = db.examDao().observe(semester)
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
    suspend fun listSemesters() = db.semesterDao().list()
    suspend fun getCourse(id: Long) = db.courseDao().get(id)
    suspend fun currentSemester() = db.semesterDao().current()

    suspend fun upsertSemester(item: SemesterEntity) = db.semesterDao().upsert(item)
    suspend fun replaceWeeks(semester: String, items: List<WeekEntity>) {
        db.weekDao().deleteSemester(semester)
        if (items.isNotEmpty()) db.weekDao().upsertAll(items)
    }
    suspend fun replacePeriods(semester: String, items: List<PeriodEntity>) {
        db.periodDao().deleteSemester(semester)
        if (items.isNotEmpty()) db.periodDao().upsertAll(items)
    }
    suspend fun replaceWeekdays(items: List<WeekdayEntity>) {
        if (items.isNotEmpty()) db.weekdayDao().upsertAll(items)
    }

    suspend fun replaceImportedCourses(semester: String, imported: List<CourseEntity>) {
        val existing = db.courseDao().list(semester)
        val editedIds = existing
            .filter { it.locallyEdited && !it.classesId.isNullOrBlank() }
            .map { it.classesId }
            .toSet()
        db.courseDao().deleteUneditedImported(semester)
        imported.filter { it.classesId == null || it.classesId !in editedIds }
            .forEach { db.courseDao().insert(it.copy(id = 0)) }
    }

    suspend fun replaceExams(semester: String, items: List<ExamEntity>) {
        db.examDao().deleteSemester(semester)
        if (items.isNotEmpty()) db.examDao().upsertAll(items)
    }

    suspend fun saveRaw(item: RawImportEntity) = db.rawImportDao().upsert(item)

    suspend fun addCourse(item: CourseEntity): Long = db.courseDao().insert(item)
    suspend fun updateCourse(item: CourseEntity) = db.courseDao().update(item)
    suspend fun deleteCourse(item: CourseEntity) = db.courseDao().delete(item)

    suspend fun replaceSemesterPack(
        semester: SemesterEntity,
        weeks: List<WeekEntity>,
        periods: List<PeriodEntity>,
        courses: List<CourseEntity>,
        exams: List<ExamEntity>,
    ) {
        db.semesterDao().upsert(semester)
        replaceWeeks(semester.acadYearSemester, weeks)
        replacePeriods(semester.acadYearSemester, periods)
        db.courseDao().deleteSemester(semester.acadYearSemester)
        courses.forEach { db.courseDao().insert(it.copy(id = 0)) }
        replaceExams(semester.acadYearSemester, exams)
    }

    suspend fun clearAll() {
        db.courseDao().clear()
        db.examDao().clear()
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
