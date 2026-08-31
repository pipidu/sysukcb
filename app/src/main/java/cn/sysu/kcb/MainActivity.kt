package cn.sysu.kcb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.KcbRoot
import cn.sysu.kcb.ui.theme.KcbTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleImportIntent(intent)
        KcbApp.instance.container.alarms.ensureChannels()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            KcbTheme(themeColor = settings.themeColor, themeMode = settings.themeMode) {
                KcbRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (notificationPermissionRequested) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermissionRequested = true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    private fun handleImportIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                intent.clipData?.getItemAt(0)?.uri ?: if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        } ?: return
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()?.let { viewModel.importJson(it) }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private var notificationPermissionRequested = false
    }
}
