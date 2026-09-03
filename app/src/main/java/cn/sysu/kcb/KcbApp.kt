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
            runCatching { container.settings.ensureFriendPeriodHeight() }
            val snap = runCatching { container.settings.snapshot() }.getOrNull()
            val enabled = snap?.webdavAutoSync ?: true
            val wifiOnly = snap?.webdavWifiOnly ?: true
            WebDavSyncWorker.schedule(this@KcbApp, enabled, wifiOnly)
            delay(2_500)
            val vacuumed = runCatching { container.timetable.compactStorage() }.getOrDefault(false)
            if (vacuumed) delay(1_500)
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
