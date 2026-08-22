package cn.sysu.kcb.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SemesterEntity::class,
        WeekEntity::class,
        PeriodEntity::class,
        CourseEntity::class,
        ExamEntity::class,
        ExamWeekEntity::class,
        RawImportEntity::class,
        WeekdayEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun semesterDao(): SemesterDao
    abstract fun weekDao(): WeekDao
    abstract fun periodDao(): PeriodDao
    abstract fun courseDao(): CourseDao
    abstract fun examDao(): ExamDao
    abstract fun examWeekDao(): ExamWeekDao
    abstract fun rawImportDao(): RawImportDao
    abstract fun weekdayDao(): WeekdayDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sysu-kcb.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
