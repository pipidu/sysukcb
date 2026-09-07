package cn.sysu.kcb.data.remote

import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.school.School

class ImportRouter(
    private val settings: SettingsRepository,
    private val sysu: JwxtImportService,
    private val gzhu: GzhuImportService,
    private val bnuzh: BnuzhImportService,
) : SchoolImporter {
    override suspend fun isLoggedIn(): Boolean = pick().isLoggedIn()

    override suspend fun checkSession(): SessionCheckResult = pick().checkSession()

    override suspend fun importAllYears(
        onlyCurrent: Boolean,
        semesterOverride: String?,
        onProgress: suspend (String) -> Unit,
    ): String = pick().importAllYears(onlyCurrent, semesterOverride, onProgress)

    private suspend fun pick(): SchoolImporter = when (settings.snapshot().schoolId) {
        School.ID_GZHU -> gzhu
        School.ID_BNUZH -> bnuzh
        else -> sysu
    }
}
