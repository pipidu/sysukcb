package cn.sysu.kcb.data.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.sysu.kcb.KcbApp
import java.util.concurrent.TimeUnit

class WebDavSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val app = applicationContext as? KcbApp ?: return Result.success()
            app.container.webdav.autoSync(force = false)
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "webdav-auto-sync"
        private const val UNIQUE_WIFI = "webdav-auto-sync-wifi"
        private const val UNIQUE_ANY = "webdav-auto-sync-any"

        fun schedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(UNIQUE_NAME)
            if (!enabled) {
                manager.cancelUniqueWork(UNIQUE_WIFI)
                manager.cancelUniqueWork(UNIQUE_ANY)
                return
            }
            val keepName = if (wifiOnly) UNIQUE_WIFI else UNIQUE_ANY
            val cancelName = if (wifiOnly) UNIQUE_ANY else UNIQUE_WIFI
            manager.cancelUniqueWork(cancelName)
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = PeriodicWorkRequestBuilder<WebDavSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build(),
                )
                .build()
            manager.enqueueUniquePeriodicWork(
                keepName,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
