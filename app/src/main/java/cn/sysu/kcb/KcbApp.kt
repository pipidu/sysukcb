package cn.sysu.kcb

import android.content.ComponentCallbacks2
import android.app.Application
import cn.sysu.kcb.data.AppContainer
import cn.sysu.kcb.data.AppStorage
import cn.sysu.kcb.data.remote.WebDavSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KcbApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        appScope.launch {
            runCatching { container.timetable.listSemesters() }
            runCatching { AppStorage.trimCaches(this@KcbApp) }
            val enabled = runCatching { container.settings.snapshot().webdavAutoSync }.getOrDefault(true)
            WebDavSyncWorker.schedule(this@KcbApp, enabled)
            delay(2_500)
            runCatching { container.timetable.compactStorage() }
            if (enabled) {
                runCatching { container.webdav.autoSync() }
            }
        }
    }

    fun trimCachesAsync() {
        appScope.launch { runCatching { AppStorage.trimCaches(this@KcbApp) } }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) trimCachesAsync()
    }

    companion object {
        lateinit var instance: KcbApp
            private set
    }
}
