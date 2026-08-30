package cn.sysu.kcb.data.remote

interface SchoolImporter {
    suspend fun isLoggedIn(): Boolean
    suspend fun checkSession(): SessionCheckResult
    suspend fun importAllYears(
        onlyCurrent: Boolean = false,
        semesterOverride: String? = null,
        onProgress: suspend (String) -> Unit = {},
    ): String
}
