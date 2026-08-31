package cn.sysu.kcb.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semesters ORDER BY acadYearSemester DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters ORDER BY acadYearSemester DESC")
    suspend fun list(): List<SemesterEntity>

    @Query("SELECT * FROM semesters WHERE acadYearSemester = :id")
    suspend fun get(id: String): SemesterEntity?

    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    suspend fun current(): SemesterEntity?

    @Query("UPDATE semesters SET isCurrent = 0")
    suspend fun clearCurrentFlag()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SemesterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SemesterEntity>)

    @Query("DELETE FROM semesters")
    suspend fun clear()
}

@Dao
interface WeekDao {
    @Query("SELECT * FROM weeks WHERE acadYearSemester = :sem ORDER BY weekly")
    fun observe(sem: String): Flow<List<WeekEntity>>

    @Query("SELECT * FROM weeks WHERE acadYearSemester = :sem ORDER BY weekly")
    suspend fun list(sem: String): List<WeekEntity>

    @Query("SELECT * FROM weeks")
    suspend fun listAll(): List<WeekEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WeekEntity>)

    @Query("DELETE FROM weeks WHERE acadYearSemester = :sem")
    suspend fun deleteSemester(sem: String)
}

@Dao
interface PeriodDao {
    @Query("SELECT * FROM periods WHERE acadYearSemester = :sem ORDER BY sectionNumber")
    fun observe(sem: String): Flow<List<PeriodEntity>>

    @Query("SELECT * FROM periods WHERE acadYearSemester = :sem ORDER BY sectionNumber")
    suspend fun list(sem: String): List<PeriodEntity>

    @Query("SELECT * FROM periods")
    suspend fun listAll(): List<PeriodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PeriodEntity>)

    @Query("DELETE FROM periods WHERE acadYearSemester = :sem")
    suspend fun deleteSemester(sem: String)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE acadYearSemester = :sem ORDER BY dayOfWeek, startPeriod")
    fun observe(sem: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE acadYearSemester = :sem ORDER BY dayOfWeek, startPeriod")
    suspend fun list(sem: String): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun get(id: Long): CourseEntity?

    @Query("SELECT * FROM courses")
    suspend fun listAll(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CourseEntity): Long

    @Update
    suspend fun update(item: CourseEntity)

    @Delete
    suspend fun delete(item: CourseEntity)

    @Query("DELETE FROM courses WHERE acadYearSemester = :sem AND source = 'imported'")
    suspend fun deleteImported(sem: String)

    @Query("DELETE FROM courses WHERE acadYearSemester = :sem")
    suspend fun deleteSemester(sem: String)

    @Query("DELETE FROM courses")
    suspend fun clear()
}

@Dao
interface ExamDao {
    @Query("SELECT * FROM exams WHERE acadYearSemester = :sem ORDER BY examDate, startTime")
    fun observe(sem: String): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams WHERE acadYearSemester = :sem ORDER BY examDate, startTime")
    suspend fun list(sem: String): List<ExamEntity>

    @Query("SELECT * FROM exams ORDER BY examDate, startTime")
    fun observeAll(): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams")
    suspend fun listAll(): List<ExamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ExamEntity>)

    @Query("DELETE FROM exams WHERE acadYearSemester = :sem")
    suspend fun deleteSemester(sem: String)

    @Query("DELETE FROM exams")
    suspend fun clear()
}

@Dao
interface ExamWeekDao {
    @Query("SELECT * FROM exam_weeks WHERE acadYearSemester = :sem ORDER BY startDate, examWeekName")
    fun observe(sem: String): Flow<List<ExamWeekEntity>>

    @Query("SELECT * FROM exam_weeks ORDER BY acadYearSemester DESC, startDate")
    fun observeAll(): Flow<List<ExamWeekEntity>>

    @Query("SELECT * FROM exam_weeks WHERE acadYearSemester = :sem ORDER BY startDate, examWeekName")
    suspend fun list(sem: String): List<ExamWeekEntity>

    @Query("SELECT * FROM exam_weeks")
    suspend fun listAll(): List<ExamWeekEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ExamWeekEntity>)

    @Query("DELETE FROM exam_weeks WHERE acadYearSemester = :sem")
    suspend fun deleteSemester(sem: String)

    @Query("DELETE FROM exam_weeks")
    suspend fun clear()
}

@Dao
interface RawImportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RawImportEntity)

    @Query("SELECT * FROM raw_imports WHERE acadYearSemester = :sem")
    suspend fun list(sem: String): List<RawImportEntity>

    @Query("DELETE FROM raw_imports")
    suspend fun clear()
}

@Dao
interface WeekdayDao {
    @Query("SELECT * FROM weekdays ORDER BY dataNumber")
    suspend fun list(): List<WeekdayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WeekdayEntity>)
}
