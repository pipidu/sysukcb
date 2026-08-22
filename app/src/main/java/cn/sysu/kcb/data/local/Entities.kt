package cn.sysu.kcb.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val acadYearSemester: String,
    val acadYear: String,
    val acadSemester: Int,
    val startMillis: Long,
    val endMillis: Long,
    val isCurrent: Boolean,
)

@Serializable
@Entity(
    tableName = "weeks",
    indices = [Index(value = ["acadYearSemester", "weekly"], unique = true)],
)
data class WeekEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val acadYearSemester: String,
    val weekly: Int,
    val weeklyName: String,
    val startDate: String?,
    val endDate: String?,
)

@Serializable
@Entity(
    tableName = "periods",
    indices = [Index(value = ["acadYearSemester", "sectionNumber"], unique = true)],
)
data class PeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val acadYearSemester: String,
    val sectionNumber: Int,
    val minorName: String,
    val startTime: String,
    val endTime: String,
    val bigSection: String,
    val bigSectionName: String,
)

@Serializable
@Entity(
    tableName = "courses",
    indices = [
        Index("acadYearSemester"),
        Index("classesId"),
    ],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val acadYearSemester: String,
    val source: String,
    val locallyEdited: Boolean = false,
    val classesId: String? = null,
    val sumClassesId: String? = null,
    val courseName: String,
    val teacher: String = "",
    val place: String = "",
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int = 1,
    val weeksMask: Long,
    val timeDetail: String = "",
    val color: Long,
    val notes: String = "",
    val extraJson: String = "{}",
)

@Serializable
@Entity(tableName = "exams", indices = [Index("acadYearSemester")])
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val acadYearSemester: String,
    val examIndex: String? = null,
    val subjectName: String,
    val examDate: String,
    val startTime: String,
    val endTime: String,
    val duration: String = "",
    val classroom: String = "",
    val examMode: String = "",
    val examStage: String = "",
    val examWeekName: String = "",
    val examWeekId: String? = null,
    val weekly: Int = 0,
    val dayOfWeek: Int = 0,
    val startPeriod: Int = 0,
    val endPeriod: Int = 0,
    val extraJson: String = "{}",
)

@Entity(tableName = "raw_imports")
data class RawImportEntity(
    @PrimaryKey val key: String,
    val acadYearSemester: String,
    val endpoint: String,
    val json: String,
    val fetchedAt: Long,
)

@Entity(tableName = "weekdays")
data class WeekdayEntity(
    @PrimaryKey val dataNumber: String,
    val dataName: String,
)
