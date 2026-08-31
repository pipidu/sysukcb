package cn.sysu.kcb

import android.app.Application
import cn.sysu.kcb.data.AppContainer
import cn.sysu.kcb.data.remote.WebDavSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            val enabled = runCatching { container.settings.snapshot().webdavAutoSync }.getOrDefault(true)
            WebDavSyncWorker.schedule(this@KcbApp, enabled)
            if (enabled) {
                runCatching { container.webdav.autoSync() }
            }
        }
    }

    companion object {
        lateinit var instance: KcbApp
            private set
    }
}
