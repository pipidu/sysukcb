package cn.sysu.kcb.data.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.MainActivity
import cn.sysu.kcb.R
import java.io.File

class ApkUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val received = inputData.getLong(KEY_RECEIVED, 0L)
        val total = inputData.getLong(KEY_TOTAL, 0L)
        return foreground(received, total, ongoing = true)
    }

    override suspend fun doWork(): Result {
        val version = inputData.getString(KEY_VERSION).orEmpty()
        val destPath = inputData.getString(KEY_DEST).orEmpty()
        val urls = listOfNotNull(
            inputData.getString(KEY_URL)?.takeIf { it.isNotBlank() },
            inputData.getString(KEY_FALLBACK)?.takeIf { it.isNotBlank() },
        ).distinct()
        if (version.isBlank() || destPath.isBlank() || urls.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "下载地址无效"))
        }
        val dest = File(destPath)
        val updates = (applicationContext as? KcbApp)?.container?.updates
            ?: GithubUpdateService(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        setForeground(foreground(0L, 0L, ongoing = true))
        var lastUi = 0L
        var lastError: String? = null
        for (url in urls) {
            val ok = runCatching {
                updates.downloadApk(url, dest) { received, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi < 400L && received != total) return@downloadApk
                    lastUi = now
                    setProgressAsync(
                        workDataOf(
                            KEY_RECEIVED to received,
                            KEY_TOTAL to total,
                            KEY_VERSION to version,
                        ),
                    )
                    setForegroundAsync(foreground(received, total, ongoing = true))
                }
            }
            if (ok.isSuccess && dest.isApkZip()) {
                notifyReady(version, dest)
                return Result.success(
                    workDataOf(
                        KEY_DEST to dest.absolutePath,
                        KEY_VERSION to version,
                    ),
                )
            }
            lastError = ok.exceptionOrNull()?.message ?: "下载失败"
        }
        return Result.failure(workDataOf(KEY_ERROR to (lastError ?: "下载失败")))
    }

    private fun foreground(received: Long, total: Long, ongoing: Boolean): ForegroundInfo {
        ensureChannel()
        val percent = if (total > 0L) ((received * 100L) / total).toInt().coerceIn(0, 100) else 0
        val text = if (total > 0L) {
            applicationContext.getString(R.string.update_download_progress, percent)
        } else {
            applicationContext.getString(R.string.update_downloading)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(applicationContext.getString(R.string.update_download_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setSilent(true)
            .setProgress(if (total > 0L) 100 else 0, percent, total <= 0L)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFY_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFY_PROGRESS, notification)
        }
    }

    private fun notifyReady(version: String, dest: File) {
        ensureChannel()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFY_PROGRESS)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(applicationContext.getString(R.string.update_ready_title))
            .setContentText(applicationContext.getString(R.string.update_ready_body, version))
            .setAutoCancel(true)
            .setContentIntent(installIntent(version))
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFY_READY, notification)
        dest.setLastModified(System.currentTimeMillis())
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun installIntent(version: String): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_INSTALL
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_VERSION, version)
        }
        return PendingIntent.getActivity(
            applicationContext,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.channel_update),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.channel_update)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val UNIQUE_NAME = "kcb-apk-update"
        const val ACTION_INSTALL = "cn.sysu.kcb.action.INSTALL_UPDATE"
        const val EXTRA_VERSION = "version"
        const val KEY_URL = "url"
        const val KEY_FALLBACK = "fallback"
        const val KEY_DEST = "dest"
        const val KEY_VERSION = "version"
        const val KEY_RECEIVED = "received"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFY_PROGRESS = 71001
        private const val NOTIFY_READY = 71002

        fun enqueue(context: Context, version: String, dest: File, urls: List<String>) {
            val primary = urls.getOrNull(0).orEmpty()
            val fallback = urls.getOrNull(1).orEmpty()
            val request = OneTimeWorkRequestBuilder<ApkUpdateWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URL to primary,
                        KEY_FALLBACK to fallback,
                        KEY_DEST to dest.absolutePath,
                        KEY_VERSION to version,
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
