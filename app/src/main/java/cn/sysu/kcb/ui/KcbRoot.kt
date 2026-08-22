package cn.sysu.kcb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.sysu.kcb.ui.course.CourseEditScreen
import cn.sysu.kcb.ui.exam.ExamScreen
import cn.sysu.kcb.ui.login.LoginScreen
import cn.sysu.kcb.ui.me.MeScreen
import cn.sysu.kcb.ui.timetable.TimetableScreen

@Composable
fun KcbRoot(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val progress by viewModel.importProgress.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.consumeMessage()
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeTabs(
                    viewModel = viewModel,
                    onLogin = { nav.navigate("login") },
                    onEdit = { id -> nav.navigate("edit/$id") },
                    onAdd = { day, period, semester ->
                        nav.navigate("add?day=$day&period=$period&semester=$semester")
                    },
                )
            }
            composable("login") {
                LoginScreen(
                    onClose = { nav.popBackStack() },
                    onLoggedIn = {
                        nav.popBackStack()
                        viewModel.importAllYears()
                    },
                )
            }
            composable(
                "edit/{courseId}",
                arguments = listOf(navArgument("courseId") { type = NavType.LongType }),
            ) { entry ->
                CourseEditScreen(
                    viewModel = viewModel,
                    courseId = entry.arguments?.getLong("courseId"),
                    presetDay = 1,
                    presetPeriod = 1,
                    semester = settings.selectedSemester,
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                "add?day={day}&period={period}&semester={semester}",
                arguments = listOf(
                    navArgument("day") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("period") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("semester") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                CourseEditScreen(
                    viewModel = viewModel,
                    courseId = null,
                    presetDay = entry.arguments?.getInt("day") ?: 1,
                    presetPeriod = entry.arguments?.getInt("period") ?: 1,
                    semester = entry.arguments?.getString("semester").orEmpty().ifBlank { settings.selectedSemester },
                    onDone = { nav.popBackStack() },
                )
            }
        }
        if (importing) {
            ImportOverlay(progress = progress)
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        )
    }
}

@Composable
private fun ImportOverlay(progress: String) {
    BackHandler { }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("正在导入课表", style = MaterialTheme.typography.titleMedium)
            Text(
                progress.ifBlank { "请稍候，不要离开此页面" },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Text(
                "导入完成后会自动回到课表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomeTabs(
    viewModel: AppViewModel,
    onLogin: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: (Int, Int, String) -> Unit,
) {
    val tabNav = rememberNavController()
    val current by tabNav.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "timetable"
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(56.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = route == "timetable",
                    onClick = { tabNav.navigate("timetable") { popUpTo("timetable"); launchSingleTop = true } },
                    icon = { Icon(Icons.Outlined.DateRange, contentDescription = "课表", modifier = Modifier.size(20.dp)) },
                    label = { Text("课表", fontSize = 11.sp, lineHeight = 12.sp) },
                )
                NavigationBarItem(
                    selected = route == "exam",
                    onClick = { tabNav.navigate("exam") { popUpTo("timetable"); launchSingleTop = true } },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Assignment, contentDescription = "考试", modifier = Modifier.size(20.dp)) },
                    label = { Text("考试", fontSize = 11.sp, lineHeight = 12.sp) },
                )
                NavigationBarItem(
                    selected = route == "me",
                    onClick = { tabNav.navigate("me") { popUpTo("timetable"); launchSingleTop = true } },
                    icon = { Icon(Icons.Outlined.Person, contentDescription = "我的", modifier = Modifier.size(20.dp)) },
                    label = { Text("我的", fontSize = 11.sp, lineHeight = 12.sp) },
                )
            }
        },
    ) { inner ->
        NavHost(
            navController = tabNav,
            startDestination = "timetable",
            modifier = Modifier.padding(inner),
        ) {
            composable("timetable") {
                TimetableScreen(viewModel = viewModel, onEdit = onEdit, onAdd = onAdd, onLogin = onLogin)
            }
            composable("exam") { ExamScreen(viewModel) }
            composable("me") { MeScreen(viewModel = viewModel, onLogin = onLogin) }
        }
    }
}
