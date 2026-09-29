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
        StickyNoteEntity::class,
        DaySuspensionEntity::class,
        DayMoveEntity::class,
        CoursePatchEntity::class,
    ],
        version = 8,
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
    abstract fun stickyNoteDao(): StickyNoteDao
    abstract fun daySuspensionDao(): DaySuspensionDao
    abstract fun dayMoveDao(): DayMoveDao
    abstract fun coursePatchDao(): CoursePatchDao

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sticky_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        acadYearSemester TEXT NOT NULL,
                        content TEXT NOT NULL,
                        xFrac REAL NOT NULL,
                        yFrac REAL NOT NULL,
                        wFrac REAL NOT NULL,
                        hFrac REAL NOT NULL,
                        color INTEGER NOT NULL,
                        alpha REAL NOT NULL,
                        z INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sticky_notes_acadYearSemester ON sticky_notes (acadYearSemester)",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sticky_notes ADD COLUMN weeksMask INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sticky_notes ADD COLUMN fontSizeSp INTEGER NOT NULL DEFAULT 11",
                )
                db.execSQL(
                    "ALTER TABLE sticky_notes ADD COLUMN fontHighlight INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS day_suspensions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        acadYearSemester TEXT NOT NULL,
                        weekNo INTEGER NOT NULL,
                        dayOfWeek INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_day_suspensions_acadYearSemester_weekNo_dayOfWeek ON day_suspensions (acadYearSemester, weekNo, dayOfWeek)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS day_moves (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        acadYearSemester TEXT NOT NULL,
                        fromWeek INTEGER NOT NULL,
                        fromDay INTEGER NOT NULL,
                        toWeek INTEGER NOT NULL,
                        toDay INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_day_moves_acadYearSemester_fromWeek_fromDay ON day_moves (acadYearSemester, fromWeek, fromDay)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS course_patches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        acadYearSemester TEXT NOT NULL,
                        courseKey TEXT NOT NULL,
                        weekNo INTEGER NOT NULL,
                        courseName TEXT,
                        teacher TEXT,
                        place TEXT,
                        dayOfWeek INTEGER,
                        startPeriod INTEGER,
                        endPeriod INTEGER,
                        notes TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_course_patches_acadYearSemester_courseKey_weekNo ON course_patches (acadYearSemester, courseKey, weekNo)",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE day_moves ADD COLUMN copy INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sysu-kcb.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}
