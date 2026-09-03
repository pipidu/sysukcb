package cn.sysu.kcb.data

import android.content.Context
import java.io.File

object AppStorage {
    private val webViewCacheNames = setOf(
        "Cache",
        "HTTP Cache",
        "Code Cache",
        "GPUCache",
        "DawnCache",
        "DawnGraphiteCache",
        "DawnWebGPUCache",
        "GrShaderCache",
        "ShaderCache",
        "GPUPersistentCache",
        "Service Worker",
        "blob_storage",
    )

    fun trimCaches(context: Context) {
        val cache = context.cacheDir
        pruneDir(File(cache, "share"), maxAgeMs = 60 * 60 * 1000L)
        pruneUpdates(File(cache, "updates"))
        pruneDir(File(cache, "exports"), maxAgeMs = 0L)
        pruneDir(File(context.filesDir, "exports"), maxAgeMs = 0L)
        trimWebViewHttpCache(cache)
        trimWebViewHttpCache(File(cache, "WebView"))
        trimWebViewHttpCache(File(cache, "webview"))
        trimWebViewHttpCache(File(context.applicationInfo.dataDir, "app_webview"))
    }

    private fun pruneUpdates(dir: File) {
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            val part = file.name.endsWith(".part", ignoreCase = true)
            val stale = now - file.lastModified() > 24 * 60 * 60 * 1000L
            if (part || stale) runCatching { file.deleteRecursively() }
        }
    }

    private fun pruneDir(dir: File, maxAgeMs: Long) {
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (maxAgeMs <= 0L || now - file.lastModified() > maxAgeMs) {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    private fun trimWebViewHttpCache(root: File) {
        if (!root.exists()) return
        val targets = ArrayList<File>()
        root.walkTopDown().maxDepth(6).forEach { file ->
            if (file.isDirectory && file.name in webViewCacheNames) targets += file
        }
        targets.sortedByDescending { it.path.length }.forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }
}
