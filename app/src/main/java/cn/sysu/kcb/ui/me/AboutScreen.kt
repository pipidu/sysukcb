package cn.sysu.kcb.ui.me

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import cn.sysu.kcb.ui.theme.KcbTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.BuildConfig
import cn.sysu.kcb.data.remote.GITHUB_PAGE_URL
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.UpdateCheckState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.checkForUpdate(manual = false)
    }
    LaunchedEffect(updateState) {
        if (updateState is UpdateCheckState.Available) showUpdate = true
    }
    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    Scaffold(
        topBar = {
            KcbTopBar {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text("关于", fontWeight = FontWeight.SemiBold)
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text("课程表D", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "中山大学、广州大学本科教务课表客户端。课表与考试保存在本机，不经第三方服务器。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = {
                    Text(
                        when (val state = updateState) {
                            UpdateCheckState.Idle -> "从 GitHub Releases 获取"
                            UpdateCheckState.Checking -> "正在检查…"
                            UpdateCheckState.UpToDate -> "已是最新版本"
                            is UpdateCheckState.Available -> "发现新版本 ${state.update.versionName}"
                            is UpdateCheckState.Failed -> state.message
                        },
                    )
                },
                leadingContent = {
                    Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null)
                },
                trailingContent = {
                    if (updateState is UpdateCheckState.Checking) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.clickable { viewModel.checkForUpdate(manual = true) },
            )
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text(GITHUB_PAGE_URL) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "在浏览器打开")
                },
                modifier = Modifier.clickable { openUrl(GITHUB_PAGE_URL) },
            )
        }
    }
    val available = updateState as? UpdateCheckState.Available
    if (showUpdate && available != null) {
        val update = available.update
        AlertDialog(
            onDismissRequest = { showUpdate = false },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Text(
                    update.notes.ifBlank { "到 GitHub Releases 下载最新安装包。" },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdate = false
                        openUrl(update.apkUrl ?: update.htmlUrl)
                    },
                ) { Text("去更新") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdate = false }) { Text("稍后") }
            },
        )
    }
}
