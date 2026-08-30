package cn.sysu.kcb.data.school

data class School(
    val id: String,
    val displayName: String,
    val shortName: String,
    val loginUrl: String,
    val apiOrigin: String,
    val cookieOrigins: List<String>,
    val sessionTokens: List<String>,
) {
    fun isLanding(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
        return when (id) {
            ID_SYSU -> isSysuLanding(path)
            ID_GZHU -> isGzhuLanding(path)
            else -> false
        }
    }

    fun hasSession(cookieHeader: String): Boolean {
        if (cookieHeader.isBlank()) return false
        return sessionTokens.all { cookieHeader.contains(it) }
    }

    private fun isSysuLanding(path: String): Boolean {
        val origin = apiOrigin.lowercase()
        if (!path.startsWith(origin)) return false
        if (path.contains("/esc-sso") || path.contains("/api/sso/")) return false
        return path == origin ||
            path == "$origin/jwxt" ||
            path.startsWith("$origin/jwxt/mk")
    }

    private fun isGzhuLanding(path: String): Boolean {
        if (path.contains("login_slogin") || path.contains("/sso/") || path.contains("ticketlogin")) return false
        if (path.contains("newcas.gzhu.edu.cn") || path.contains("newmy.gzhu.edu.cn")) return false
        if (!path.contains("jwxt.gzhu.edu.cn")) return false
        return path.contains("/jwglxt/xtgl/index") ||
            path.contains("/jwglxt/kbcx/xskbcx") ||
            path.endsWith("/jwglxt")
    }

    companion object {
        const val ID_SYSU = "sysu"
        const val ID_GZHU = "gzhu"

        val Sysu = School(
            id = ID_SYSU,
            displayName = "中山大学",
            shortName = "中大",
            loginUrl = "https://jwxt.sysu.edu.cn/jwxt/api/sso/cas/login?pattern=student-login",
            apiOrigin = "https://jwxt.sysu.edu.cn",
            cookieOrigins = listOf("https://jwxt.sysu.edu.cn"),
            sessionTokens = listOf("LYSESSIONID", "user="),
        )

        val Gzhu = School(
            id = ID_GZHU,
            displayName = "广州大学",
            shortName = "广大",
            loginUrl = "https://newcas.gzhu.edu.cn/cas/login?service=http%3A%2F%2Fjwxt.gzhu.edu.cn%2Fsso%2Fdriot4login",
            apiOrigin = "https://jwxt.gzhu.edu.cn",
            cookieOrigins = listOf(
                "https://jwxt.gzhu.edu.cn",
                "http://jwxt.gzhu.edu.cn",
                "https://newcas.gzhu.edu.cn",
            ),
            sessionTokens = listOf("JSESSIONID"),
        )

        val All = listOf(Sysu, Gzhu)

        fun of(id: String): School = All.find { it.id == id } ?: Sysu
    }
}
