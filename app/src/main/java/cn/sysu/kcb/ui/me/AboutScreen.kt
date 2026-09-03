package cn.sysu.kcb.ui.me

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.BuildConfig
import cn.sysu.kcb.data.remote.GITHUB_PAGE_URL
import cn.sysu.kcb.ui.ApkDownloadState
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.UpdateCheckState
import cn.sysu.kcb.ui.theme.KcbTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val apkDownload by viewModel.apkDownload.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showUpdate by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val available = viewModel.updateState.value as? UpdateCheckState.Available
            ?: return@rememberLauncherForActivityResult
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        if (allowed) viewModel.downloadAndInstall(available.update)
        else viewModel.denyInstallPermission()
    }
    LaunchedEffect(Unit) {
        viewModel.checkForUpdate(manual = false)
    }
    LaunchedEffect(updateState) {
        if (updateState is UpdateCheckState.Available) showUpdate = true
    }
    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    fun startDownload() {
        val available = viewModel.updateState.value as? UpdateCheckState.Available ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            permissionLauncher.launch(viewModel.unknownSourcesIntent())
            return
        }
        viewModel.downloadAndInstall(available.update)
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
                    "中山大学、广州大学本科教务课表客户端。登录后可导入课表与考试；数据默认保存在本机，也可经 WebDAV 同步到自己的网盘。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = {
                    val download = apkDownload
                    Text(
                        when (val state = updateState) {
                            UpdateCheckState.Idle -> "应用内下载安装"
                            UpdateCheckState.Checking -> "正在检查…"
                            UpdateCheckState.UpToDate -> "已是最新版本"
                            is UpdateCheckState.Available -> if (
                                download !is ApkDownloadState.Progress &&
                                viewModel.hasCachedUpdateApk(state.update)
                            ) {
                                "已下载 ${state.update.versionName}，可直接安装"
                            } else {
                                "发现新版本 ${state.update.versionName}"
                            }
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
                headlineContent = { Text("镜像下载") },
                supportingContent = { Text("经 GitHub Releases 镜像拉取安装包，国内网络更稳") },
                trailingContent = {
                    Switch(
                        checked = settings.updateUseMirror,
                        onCheckedChange = { viewModel.setUpdateUseMirror(it) },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text("开源地址") },
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
        val apkReady = remember(update.versionName, apkDownload) {
            viewModel.hasCachedUpdateApk(update)
        }
        val downloading = apkDownload is ApkDownloadState.Progress || apkDownload is ApkDownloadState.Installing
        AlertDialog(
            onDismissRequest = {
                if (!downloading) {
                    showUpdate = false
                    viewModel.cancelApkDownload()
                }
            },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Column {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(update.notes.ifBlank { "下载安装包后按系统提示安装。" })
                    }
                    if (!apkReady) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("使用镜像下载", fontWeight = FontWeight.Medium)
                                Text(
                                    "国内访问 GitHub 较慢时可开",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                            Switch(
                                checked = settings.updateUseMirror,
                                onCheckedChange = { viewModel.setUpdateUseMirror(it) },
                                enabled = !downloading,
                            )
                        }
                    }
                    when (val dl = apkDownload) {
                        is ApkDownloadState.Progress -> {
                            Spacer(Modifier.height(12.dp))
                            if (dl.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (dl.received.toFloat() / dl.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${formatMb(dl.received)} / ${formatMb(dl.total)} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "正在下载…",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        ApkDownloadState.Installing -> {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("正在打开安装程序…", modifier = Modifier.padding(top = 6.dp))
                        }
                        is ApkDownloadState.Failed -> {
                            Spacer(Modifier.height(12.dp))
                            Text(dl.message, color = MaterialTheme.colorScheme.error)
                        }
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { startDownload() },
                    enabled = !downloading,
                ) {
                    Text(
                        when {
                            apkDownload is ApkDownloadState.Failed -> "重试"
                            apkReady -> "安装"
                            else -> "下载更新"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (downloading) viewModel.cancelApkDownload()
                        else {
                            showUpdate = false
                            viewModel.cancelApkDownload()
                        }
                    },
                ) { Text(if (downloading) "取消下载" else "稍后") }
            },
        )
    }
}

private fun formatMb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0)
