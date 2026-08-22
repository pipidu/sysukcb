package cn.sysu.kcb

import android.app.Application
import cn.sysu.kcb.data.AppContainer

class KcbApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: KcbApp
            private set
    }
}
