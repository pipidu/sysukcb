package cn.sysu.kcb.ui.me

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.sysu.kcb.ui.AppViewModel
import cn.sysu.kcb.ui.theme.PresetThemeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(viewModel: AppViewModel, onLogin: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showColor by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()?.let { viewModel.importJson(it) }
        }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Scaffold(topBar = { TopAppBar(title = { Text("我的", fontWeight = FontWeight.SemiBold) }) }) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            ListItem(
                headlineContent = { Text(if (loggedIn) "已登录教务系统" else "未登录") },
                supportingContent = { Text(if (loggedIn) "可重新导入最新课表和考试" else "通过 WebView 登录后自动导入") },
                trailingContent = {
                    Button(onClick = onLogin) { Text(if (loggedIn) "重新登录" else "登录") }
                },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.importFromJwxt() }, enabled = !importing && loggedIn) {
                    Text(if (importing) "导入中…" else "从教务导入")
                }
                if (loggedIn) OutlinedButton(onClick = { viewModel.logout() }) { Text("退出登录") }
            }
            Spacer(Modifier.height(8.dp))
            ListItem(
                headlineContent = { Text("导出课表") },
                supportingContent = { Text("生成 JSON 文件，分享给同学用本 App 导入") },
                trailingContent = {
                    OutlinedButton(
                        onClick = { viewModel.exportSemester(settings.selectedSemester) },
                        enabled = settings.selectedSemester.isNotBlank(),
                    ) { Text("分享") }
                },
            )
            ListItem(
                headlineContent = { Text("从文件导入") },
                supportingContent = { Text("打开他人分享的 .sysukcb.json") },
                trailingContent = {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("选择")
                    }
                },
            )
            ListItem(
                headlineContent = { Text("主题色") },
                supportingContent = { Text("默认中大红，可随时更换") },
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
            if (settings.reminderEnabled) {
                Text("提前 ${settings.reminderMinutes} 分钟", modifier = Modifier.padding(horizontal = 16.dp))
                Slider(
                    value = settings.reminderMinutes.toFloat(),
                    onValueChange = { viewModel.setReminderMinutes(it.toInt().coerceIn(5, 60)) },
                    valueRange = 5f..60f,
                    steps = 10,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ListItem(
                headlineContent = { Text("考试提醒") },
                trailingContent = {
                    Switch(
                        checked = settings.examReminderEnabled,
                        onCheckedChange = { viewModel.setExamReminderEnabled(it) },
                    )
                },
            )
            if (settings.examReminderEnabled) {
                Text("提前 ${settings.examReminderMinutes} 分钟", modifier = Modifier.padding(horizontal = 16.dp))
                Slider(
                    value = settings.examReminderMinutes.toFloat(),
                    onValueChange = { viewModel.setExamReminderMinutes(it.toInt().coerceIn(15, 180)) },
                    valueRange = 15f..180f,
                    steps = 10,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("清空本地数据", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "数据仅保存在本机。HAR 抓包文件不会被应用读取或上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
}

@Composable
private fun ColorPickerDialog(
    current: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val hsv = remember {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(current.toInt(), arr)
        arr
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    var hex by remember {
        mutableStateOf("%06X".format(current and 0xFFFFFF))
    }
    val live = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PresetThemeColors.forEach { (color, name) ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(
                                    width = if (current == color) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { onPick(color) },
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(live),
                )
                LabelSlider("色相", h, 0f, 360f) { h = it }
                LabelSlider("饱和", s, 0f, 1f) { s = it }
                LabelSlider("明度", v, 0.15f, 1f) { v = it }
                TextField(
                    value = hex,
                    onValueChange = {
                        hex = it.removePrefix("#").take(6)
                        if (hex.length == 6) {
                            hex.toLongOrNull(16)?.let { v16 ->
                                val arr = FloatArray(3)
                                android.graphics.Color.colorToHSV((0xFF000000 or v16).toInt(), arr)
                                h = arr[0]; s = arr[1]; v = arr[2]
                            }
                        }
                    },
                    label = { Text("十六进制") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(0xFF000000L or (live.toArgb().toLong() and 0xFFFFFF)) }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LabelSlider(label: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label ${"%.0f".format(if (to > 2f) value else value * 100)}")
        Slider(value = value, onValueChange = onChange, valueRange = from..to)
    }
}
