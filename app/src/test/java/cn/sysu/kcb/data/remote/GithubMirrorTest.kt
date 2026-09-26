package cn.sysu.kcb.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubMirrorTest {
    private val asset = "https://github.com/pipidu/sysukcb/releases/download/v1.2.9/sysukcb-1.2.9.apk"

    @Test
    fun mirror2PrefixesGithubUrlAndWinsOverOldMirror() {
        assertEquals(
            "https://gh.4o.pw/$asset",
            mirroredGithubUrl(asset, useMirror = true, useMirror2 = true),
        )
    }

    @Test
    fun oldMirrorUsedWhenMirror2Off() {
        assertEquals(
            "https://gh-proxy.com/$asset",
            mirroredGithubUrl(asset, useMirror = true, useMirror2 = false),
        )
    }

    @Test
    fun directGithubWhenBothMirrorsOff() {
        assertEquals(asset, mirroredGithubUrl(asset, useMirror = false, useMirror2 = false))
    }

    @Test
    fun downloadListKeepsCosThenMirror2() {
        val update = AppUpdate(
            versionName = "1.2.9",
            versionCode = 81,
            htmlUrl = "https://github.com/pipidu/sysukcb/releases/tag/v1.2.9",
            apkUrl = asset,
            cosUrl = "https://cdn.example/sysukcb-1.2.9.apk",
            notes = "",
        )
        assertEquals(
            listOf(
                "https://cdn.example/sysukcb-1.2.9.apk",
                "https://gh.4o.pw/$asset",
            ),
            update.downloadUrls(useCos = true, useMirror = false, useMirror2 = true),
        )
    }
}
