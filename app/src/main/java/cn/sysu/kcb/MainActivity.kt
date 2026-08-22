package cn.sysu.kcb

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
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
            KcbTheme(themeColor = settings.themeColor) {
                KcbRoot(viewModel)
            }
        }
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
}
