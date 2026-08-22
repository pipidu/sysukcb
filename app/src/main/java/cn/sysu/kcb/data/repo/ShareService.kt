package cn.sysu.kcb.data.repo

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cn.sysu.kcb.data.local.CourseEntity
import cn.sysu.kcb.data.local.ExamEntity
import cn.sysu.kcb.data.local.PeriodEntity
import cn.sysu.kcb.data.local.SemesterEntity
import cn.sysu.kcb.data.local.WeekEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class SharePack(
    val format: String = "sysukcb",
    val version: Int = 1,
    val exportedAt: String = Instant.now().toString(),
    val semesters: List<SemesterEntity> = emptyList(),
    val weeks: List<WeekEntity> = emptyList(),
    val periods: List<PeriodEntity> = emptyList(),
    val courses: List<CourseEntity> = emptyList(),
    val exams: List<ExamEntity> = emptyList(),
)

class ShareService(
    private val context: Context,
    private val repo: TimetableRepository,
    private val json: Json,
) {
    suspend fun exportSemester(semester: String): File {
        val pack = SharePack(
            semesters = repo.listSemesters().filter { it.acadYearSemester == semester },
            weeks = repo.listWeeks(semester).map { it.copy(id = 0) },
            periods = repo.listPeriods(semester).map { it.copy(id = 0) },
            courses = repo.listCourses(semester).map { it.copy(id = 0) },
            exams = repo.listExams(semester).map { it.copy(id = 0) },
        )
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "中大课表-$semester.sysukcb.json")
        file.writeText(json.encodeToString(SharePack.serializer(), pack))
        return file
    }

    fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "中大课表")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享课表").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun importJson(text: String): String {
        val pack = json.decodeFromString(SharePack.serializer(), text)
        if (pack.format != "sysukcb") {
            error("不是中大课表导出文件")
        }
        val groupedCourses = pack.courses.groupBy { it.acadYearSemester }
        val groupedWeeks = pack.weeks.groupBy { it.acadYearSemester }
        val groupedPeriods = pack.periods.groupBy { it.acadYearSemester }
        val groupedExams = pack.exams.groupBy { it.acadYearSemester }
        val semesters = pack.semesters.ifEmpty {
            groupedCourses.keys.map {
                SemesterEntity(it, it, 0, 0, 0, false)
            }
        }
        for (semester in semesters) {
            repo.replaceSemesterPack(
                semester = semester.copy(isCurrent = false),
                weeks = groupedWeeks[semester.acadYearSemester].orEmpty(),
                periods = groupedPeriods[semester.acadYearSemester].orEmpty(),
                courses = groupedCourses[semester.acadYearSemester].orEmpty().map {
                    it.copy(source = if (it.source == "imported") "shared" else it.source)
                },
                exams = groupedExams[semester.acadYearSemester].orEmpty(),
            )
        }
        return semesters.firstOrNull()?.acadYearSemester.orEmpty()
    }
}
