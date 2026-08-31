package cn.sysu.kcb.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        FriendPackEntity::class,
    ],
    version = 3,
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
    abstract fun friendPackDao(): FriendPackDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS friend_packs (
                        id TEXT NOT NULL PRIMARY KEY,
                        nickname TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        exportedAt TEXT NOT NULL,
                        syncedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sysu-kcb.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}
