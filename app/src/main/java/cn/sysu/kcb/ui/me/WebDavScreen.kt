package cn.sysu.kcb.ui.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.data.remote.WebDavClient
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.theme.KcbTopBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WebDavScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val webdavBusy by viewModel.webdavBusy.collectAsStateWithLifecycle()
    val webdavHasPassword by viewModel.webdavHasPassword.collectAsStateWithLifecycle()
    var davUrl by remember { mutableStateOf("") }
    var davUser by remember { mutableStateOf("") }
    var davPassword by remember { mutableStateOf("") }
    var davNick by remember { mutableStateOf("") }
    var davAuto by remember { mutableStateOf(true) }
    var shareCode by remember { mutableStateOf<String?>(null) }
    var joinOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(settings.webdavUrl, settings.webdavUser, settings.webdavNickname, settings.webdavAutoSync) {
        davUrl = settings.webdavUrl.ifBlank { WebDavClient.DEFAULT_NUTSTORE_FILE_URL }
        davUser = settings.webdavUser
        davNick = settings.webdavNickname
        davAuto = settings.webdavAutoSync
    }

    Scaffold(
        topBar = {
            KcbTopBar {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text("WebDAV 同步", fontWeight = FontWeight.SemiBold)
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("坚果云（三步）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "1. 打开坚果云网页 → 账户信息 → 安全选项，关闭微信二次验证\n" +
                    "2. 第三方应用管理 → 生成「应用密码」，不要用登录密码\n" +
                    "3. 用户名填邮箱，密码粘贴应用密码；地址可留空，会自动用子文件夹 /dav/sysukcb/",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                "同一网盘账号、不同昵称上传后，会在「好友」里互看课表。也可以导出分享码发给好友，对方只需填分享码和自己的昵称。Nextcloud / 群晖把完整文件地址填进「地址」即可。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = davUrl,
                onValueChange = { davUrl = it },
                label = { Text("地址") },
                placeholder = { Text(WebDavClient.DEFAULT_NUTSTORE_FILE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = davUser,
                onValueChange = { davUser = it },
                label = { Text("用户名") },
                placeholder = { Text("坚果云邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = davPassword,
                onValueChange = { davPassword = it },
                label = { Text("应用密码") },
                placeholder = { Text(if (webdavHasPassword) "已保存，留空则不改" else "粘贴应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = davNick,
                onValueChange = { davNick = it },
                label = { Text("昵称") },
                placeholder = { Text("用来区分课表，不要叫 sysukcb") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text("自动同步") },
                supportingContent = {
                    Text(
                        if (settings.webdavWifiOnly) {
                            "连上 Wi‑Fi 后约每小时上传自己的课表，并拉取其他昵称"
                        } else {
                            "连网后约每小时上传自己的课表，并拉取其他昵称"
                        },
                    )
                },
                trailingContent = {
                    Switch(
                        checked = davAuto,
                        onCheckedChange = { checked ->
                            davAuto = checked
                            viewModel.saveWebDav(davUrl, davUser, davPassword, davNick, checked)
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("仅 Wi‑Fi 自动同步") },
                supportingContent = { Text("用流量时不自动同步；手动上传、下载和刷新好友仍可用") },
                trailingContent = {
                    Switch(
                        checked = settings.webdavWifiOnly,
                        onCheckedChange = { viewModel.setWebDavWifiOnly(it) },
                    )
                },
            )
            Text(
                webdavSyncHint(settings.webdavLastSyncAt, settings.webdavLastMessage),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.saveWebDav(davUrl, davUser, davPassword, davNick, davAuto) },
                    enabled = !webdavBusy,
                ) { Text("保存") }
                Button(
                    onClick = { viewModel.uploadWebDav(davUrl, davUser, davPassword, davNick, davAuto) },
                    enabled = !webdavBusy && !importing,
                ) { Text(if (webdavBusy) "同步中…" else "上传") }
                Button(
                    onClick = { viewModel.downloadWebDav(davUrl, davUser, davPassword, davNick, davAuto) },
                    enabled = !webdavBusy && !importing,
                ) { Text("下载") }
                Button(
                    onClick = { viewModel.syncFriendsWebDav(davUrl, davUser, davPassword, davNick, davAuto) },
                    enabled = !webdavBusy && !importing,
                ) { Text("同步好友") }
            }
            Text("分享码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "分享码里有网盘地址、用户名和应用密码，只发给信任的人。对方填分享码和自己的昵称就能进同一目录。",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching { viewModel.makeWebDavShareCode(davUrl, davUser, davPassword) }
                            .onSuccess { shareCode = it }
                            .onFailure { viewModel.showMessage(it.message ?: "无法生成分享码") }
                    },
                    enabled = davUser.isNotBlank() && (davPassword.isNotBlank() || webdavHasPassword),
                ) { Text("导出分享码") }
                OutlinedButton(onClick = { joinOpen = true }) { Text("用分享码加入") }
            }
        }
    }
    shareCode?.let { code ->
        ExportWebDavShareDialog(
            code = code,
            onCopy = {
                copyShareCode(context, code)
                viewModel.showMessage("已复制，只发给信任的人")
            },
            onDismiss = { shareCode = null },
        )
    }
    if (joinOpen) {
        JoinWebDavShareDialog(
            busy = webdavBusy,
            onJoin = { code, nick ->
                viewModel.joinWebDavShareCode(code, nick) { ok ->
                    if (ok) joinOpen = false
                }
            },
            onDismiss = { joinOpen = false },
        )
    }
}

internal fun webdavSyncHint(at: Long, message: String): String {
    if (at <= 0L) return message.ifBlank { "尚未同步" }
    val time = java.time.Instant.ofEpochMilli(at)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"))
    return if (message.isBlank()) "上次同步 $time" else "上次同步 $time · $message"
}

@Composable
internal fun ExportWebDavShareDialog(
    code: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV 分享码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "含网盘地址、用户名和应用密码，只发给信任的人。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun JoinWebDavShareDialog(
    busy: Boolean,
    onJoin: (code: String, nickname: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("用分享码加入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴好友给的分享码，再填你自己的昵称。不要和别人重复，也不要叫 sysukcb。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("分享码") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text("我的昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(code, nick) },
                enabled = !busy && code.isNotBlank() && nick.isNotBlank(),
            ) { Text(if (busy) "加入中…" else "加入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

private fun copyShareCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("kcb-webdav", code))
}
