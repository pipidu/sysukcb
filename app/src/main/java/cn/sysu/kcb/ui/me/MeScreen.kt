package cn.sysu.kcb.ui.me

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.data.prefs.SettingsRepository
import cn.sysu.kcb.data.prefs.UserSettings
import cn.sysu.kcb.data.remote.SessionStatus
import cn.sysu.kcb.data.school.School
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.UpdateCheckState
import cn.sysu.kcb.ui.theme.KcbTopBar
import cn.sysu.kcb.ui.theme.PresetThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeScreen(
    viewModel: AppViewModel,
    onLogin: () -> Unit,
    onAbout: () -> Unit,
    onWebDav: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    val sessionStatus by viewModel.sessionStatus.collectAsStateWithLifecycle()
    val checkingSession by viewModel.checkingSession.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showColor by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmAllImport by remember { mutableStateOf(false) }
    var showWakeUp by remember { mutableStateOf(false) }
    var wakeupCode by remember { mutableStateOf("") }
    var accountOpen by rememberSaveable { mutableStateOf(true) }
    var importOpen by rememberSaveable { mutableStateOf(true) }
    var appearanceOpen by rememberSaveable { mutableStateOf(true) }
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val canImport = !importing && !checkingSession && sessionStatus == SessionStatus.Valid
    LaunchedEffect(Unit) {
        viewModel.checkSession()
        viewModel.checkForUpdate(manual = false)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()?.let { viewModel.importJson(it) }
        }
    }
    val wakeupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()?.let { viewModel.importWakeUp(it) }
        }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(settings.reminderEnabled, settings.examReminderEnabled) {
        if (Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        if (!settings.reminderEnabled && !settings.examReminderEnabled) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            KcbTopBar {
                Text(
                    "我的",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            ExpandableCard(
                title = "账号",
                summary = accountSummary(settings, sessionStatus, checkingSession),
                expanded = accountOpen,
                onToggle = { accountOpen = !accountOpen },
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    School.All.forEach { school ->
                        FilterChip(
                            selected = settings.schoolId == school.id,
                            onClick = { viewModel.setSchool(school.id) },
                            enabled = !importing,
                            label = { Text(school.displayName) },
                        )
                    }
                }
                Text(
                    sessionLabel(sessionStatus, checkingSession),
                    color = when {
                        checkingSession -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        sessionStatus == SessionStatus.Valid -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogin) { Text(if (loggedIn) "重新登录" else "登录") }
                    if (loggedIn) OutlinedButton(onClick = { viewModel.logout() }) { Text("退出登录") }
                }
            }
            ExpandableCard(
                title = "导入导出",
                summary = "教务导入、文件、WakeUp",
                expanded = importOpen,
                onToggle = { importOpen = !importOpen },
            ) {
                Text(
                    "选中学期只导入课表页当前学年；全部导入会覆盖前后各 8 个学期。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.importFromJwxt() },
                        enabled = canImport,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (importing) "导入中…" else "导入选中学期", textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = { confirmAllImport = true },
                        enabled = canImport,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("全部导入", textAlign = TextAlign.Center)
                    }
                }
                ListItem(
                    headlineContent = { Text("导出课表") },
                    supportingContent = { Text("生成 JSON，分享给同学用本 App 导入") },
                    trailingContent = {
                        OutlinedButton(
                            onClick = { viewModel.exportSemester(settings.selectedSemester) },
                            enabled = settings.selectedSemester.isNotBlank(),
                        ) { Text("分享") }
                    },
                )
                ListItem(
                    headlineContent = { Text("从文件导入") },
                    supportingContent = { Text("本 App 的 JSON，或 WakeUp 备份 / CSV") },
                    trailingContent = {
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                            Text("选择")
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("从 WakeUp 导入") },
                    supportingContent = { Text("备份文件或分享口令，导入到当前学期，考试保留") },
                    trailingContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { wakeupPicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                enabled = !importing,
                            ) { Text("文件") }
                            OutlinedButton(
                                onClick = { showWakeUp = true },
                                enabled = !importing,
                            ) { Text("口令") }
                        }
                    },
                )
            }
            Card(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(onClick = onWebDav),
            ) {
                ListItem(
                    headlineContent = { Text("同步", fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            if (settings.webdavUrl.isBlank()) "用坚果云和好友互看课表"
                            else webdavSyncHint(settings.webdavLastSyncAt, settings.webdavLastMessage),
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    },
                )
            }
            ExpandableCard(
                title = "外观与提醒",
                summary = appearanceSummary(settings),
                expanded = appearanceOpen,
                onToggle = { appearanceOpen = !appearanceOpen },
            ) {
                Text("显示模式", style = MaterialTheme.typography.labelLarge)
                val modes = listOf(
                    SettingsRepository.THEME_MODE_SYSTEM to "跟随系统",
                    SettingsRepository.THEME_MODE_LIGHT to "浅色",
                    SettingsRepository.THEME_MODE_DARK to "深色",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (id, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == id,
                            onClick = { viewModel.setThemeMode(id) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }
                ListItem(
                    headlineContent = { Text("主题色") },
                    supportingContent = { Text(themeColorName(settings.themeColor)) },
                    trailingContent = {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(settings.themeColor))
                                .clickable { showColor = true },
                        )
                    },
                    modifier = Modifier.clickable { showColor = true },
                )
                ListItem(
                    headlineContent = { Text("上课提醒") },
                    trailingContent = {
                        Switch(
                            checked = settings.reminderEnabled,
                            onCheckedChange = {
                                if (it && Build.VERSION.SDK_INT >= 33) {
                                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                if (it && Build.VERSION.SDK_INT >= 31) {
                                    val am = context.getSystemService(AlarmManager::class.java)
                                    if (!am.canScheduleExactAlarms()) {
                                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        })
                                    }
                                }
                                viewModel.setReminderEnabled(it)
                            },
                        )
                    },
                )
                AnimatedVisibility(
                    visible = settings.reminderEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        Text("提前 ${settings.reminderMinutes} 分钟")
                        Slider(
                            value = settings.reminderMinutes.toFloat(),
                            onValueChange = { viewModel.setReminderMinutes(it.toInt().coerceIn(5, 60)) },
                            valueRange = 5f..60f,
                            steps = 10,
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("考试提醒") },
                    trailingContent = {
                        Switch(
                            checked = settings.examReminderEnabled,
                            onCheckedChange = {
                                if (it && Build.VERSION.SDK_INT >= 33) {
                                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                viewModel.setExamReminderEnabled(it)
                            },
                        )
                    },
                )
                AnimatedVisibility(
                    visible = settings.examReminderEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        Text("提前 ${settings.examReminderMinutes} 分钟")
                        Slider(
                            value = settings.examReminderMinutes.toFloat(),
                            onValueChange = { viewModel.setExamReminderMinutes(it.toInt().coerceIn(15, 180)) },
                            valueRange = 15f..180f,
                            steps = 10,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("清空本地数据", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "数据默认只保存在本机。WebDAV 同步走你自己的网盘，密码存在本机加密存储。HAR 抓包文件不会被应用读取或上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = {
                    val available = updateState as? UpdateCheckState.Available
                    Text(
                        if (available != null) "发现新版本 ${available.update.versionName}"
                        else "课程表D · GitHub",
                    )
                },
                leadingContent = {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                },
                trailingContent = {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                },
                modifier = Modifier.clickable { onAbout() },
            )
        }
    }
    if (showColor) {
        ColorPickerDialog(
            current = settings.themeColor,
            onDismiss = { showColor = false },
            onPick = {
                viewModel.setThemeColor(it)
                showColor = false
            },
        )
    }
    if (confirmAllImport) {
        AlertDialog(
            onDismissRequest = { confirmAllImport = false },
            title = { Text("全部导入？") },
            text = {
                Text("会覆盖当前学期前后各 8 个学期已导入的课表（手改过的导入课也会被替换），只保留你手动添加的课。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAllImport = false
                    viewModel.importAllYears()
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { confirmAllImport = false }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本地数据？") },
            text = { Text("课表、考试和手改记录都会删除，登录状态也会保留到你手动退出。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLocal()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    if (showWakeUp) {
        AlertDialog(
            onDismissRequest = { showWakeUp = false },
            title = { Text("WakeUp 分享口令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在 WakeUp 复制整段分享口令粘贴到这里。新版口令若无法拉取，请改用「导出为备份」再点文件导入。",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    OutlinedTextField(
                        value = wakeupCode,
                        onValueChange = { wakeupCode = it },
                        label = { Text("分享口令") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val code = wakeupCode
                        showWakeUp = false
                        viewModel.importWakeUp(code)
                    },
                    enabled = wakeupCode.isNotBlank() && !importing,
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showWakeUp = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!expanded && summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(
    current: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val rgb = remember(current) { rgbOf(current) }
    var r by remember { mutableFloatStateOf(rgb[0]) }
    var g by remember { mutableFloatStateOf(rgb[1]) }
    var b by remember { mutableFloatStateOf(rgb[2]) }
    var hex by remember { mutableStateOf(hexOf(r, g, b)) }
    fun setRgb(nr: Float, ng: Float, nb: Float) {
        r = nr.coerceIn(0f, 255f)
        g = ng.coerceIn(0f, 255f)
        b = nb.coerceIn(0f, 255f)
        hex = hexOf(r, g, b)
    }
    val live = Color(r / 255f, g / 255f, b / 255f)
    val livePacked = packedRgb(r, g, b)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PresetThemeColors.forEach { (color, name) ->
                        val selected = (color and 0xFFFFFFL) == livePacked
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(56.dp)
                                .clickable {
                                    val next = rgbOf(color)
                                    setRgb(next[0], next[1], next[2])
                                },
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    ),
                            )
                            Text(
                                name,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(live),
                )
                LabelSlider("R", r, 0f, 255f) { setRgb(it, g, b) }
                LabelSlider("G", g, 0f, 255f) { setRgb(r, it, b) }
                LabelSlider("B", b, 0f, 255f) { setRgb(r, g, it) }
                TextField(
                    value = hex,
                    onValueChange = {
                        hex = it.removePrefix("#").take(6)
                        if (hex.length == 6) {
                            hex.toLongOrNull(16)?.let { v16 ->
                                val next = rgbOf(0xFF000000L or v16)
                                r = next[0]; g = next[1]; b = next[2]
                            }
                        }
                    },
                    label = { Text("十六进制") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(0xFF000000L or livePacked) }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun rgbOf(color: Long): FloatArray {
    val n = (color and 0xFFFFFFL).toInt()
    return floatArrayOf(
        ((n shr 16) and 0xFF).toFloat(),
        ((n shr 8) and 0xFF).toFloat(),
        (n and 0xFF).toFloat(),
    )
}

private fun packedRgb(r: Float, g: Float, b: Float): Long =
    ((r.toInt() and 0xFF).toLong() shl 16) or
        ((g.toInt() and 0xFF).toLong() shl 8) or
        (b.toInt() and 0xFF).toLong()

private fun hexOf(r: Float, g: Float, b: Float): String =
    "%02X%02X%02X".format(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))

@Composable
private fun LabelSlider(label: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label ${"%.0f".format(if (to > 2f) value else value * 100)}")
        Slider(value = value, onValueChange = onChange, valueRange = from..to)
    }
}

private fun sessionLabel(status: SessionStatus, checking: Boolean): String = when {
    checking -> "正在检查登录…"
    status == SessionStatus.Valid -> "登录有效，可导入课表和考试"
    status == SessionStatus.Unreachable -> "无法检查登录，请稍后重试"
    else -> "未登录或已过期，请重新登录后再导入"
}

private fun accountSummary(settings: UserSettings, status: SessionStatus, checking: Boolean): String {
    val school = School.of(settings.schoolId).displayName
    return "$school · ${sessionLabel(status, checking)}"
}

private fun themeColorName(color: Long): String =
    PresetThemeColors.firstOrNull { (it.first and 0xFFFFFFL) == (color and 0xFFFFFFL) }?.second ?: "自定义"

private fun appearanceSummary(settings: UserSettings): String {
    val mode = when (settings.themeMode) {
        SettingsRepository.THEME_MODE_LIGHT -> "浅色"
        SettingsRepository.THEME_MODE_DARK -> "深色"
        else -> "跟随系统"
    }
    return "${themeColorName(settings.themeColor)} · $mode"
}
