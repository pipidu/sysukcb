package cn.sysu.kcb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import cn.sysu.kcb.ui.friends.FriendScreen
import cn.sysu.kcb.ui.login.LoginScreen
import cn.sysu.kcb.ui.me.AboutScreen
import cn.sysu.kcb.ui.me.MeScreen
import cn.sysu.kcb.ui.me.WebDavScreen
import cn.sysu.kcb.ui.theme.KcbBottomBarHeight
import cn.sysu.kcb.ui.theme.KcbMotion
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
    val navEntry by nav.currentBackStackEntryAsState()
    val onHome = navEntry?.destination?.route.orEmpty().let { it.isEmpty() || it == "home" }
    fun goHome() {
        if (!nav.popBackStack("home", false)) {
            nav.navigate("home") { launchSingleTop = true }
        }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = "home",
            enterTransition = { KcbMotion.fadeSlideIn() },
            exitTransition = { fadeOut(tween(KcbMotion.fast)) },
            popEnterTransition = { KcbMotion.fadeSlideIn(forward = false) },
            popExitTransition = { KcbMotion.fadeSlideOut(forward = false) },
        ) {
            composable("home") {
                HomeTabs(
                    viewModel = viewModel,
                    onLogin = {
                        viewModel.prepareFreshLogin()
                        nav.navigate("login")
                    },
                    onAbout = { nav.navigate("about") },
                    onWebDav = { nav.navigate("webdav") },
                    onEdit = { id -> nav.navigate("edit/$id") },
                    onAdd = { day, period, semester ->
                        nav.navigate("add?day=$day&period=$period&semester=$semester")
                    },
                )
            }
            composable("about") {
                AboutScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
            }
            composable("webdav") {
                WebDavScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
            }
            composable("login") {
                LoginScreen(
                    schoolId = settings.schoolId,
                    onClose = { goHome() },
                    onLoggedIn = {
                        goHome()
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
        AnimatedVisibility(
            visible = importing,
            enter = KcbMotion.overlayEnter,
            exit = KcbMotion.overlayExit,
        ) {
            ImportOverlay(progress = progress)
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(
                    if (onHome) Modifier.padding(bottom = KcbBottomBarHeight)
                    else Modifier.navigationBarsPadding(),
                ),
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
            Text("正在导入课表和考试", style = MaterialTheme.typography.titleMedium)
            AnimatedContent(
                targetState = progress.ifBlank { "请稍候，不要离开此页面" },
                label = "importProgress",
            ) { text ->
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
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
    onAbout: () -> Unit,
    onWebDav: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: (Int, Int, String) -> Unit,
) {
    val tabNav = rememberNavController()
    val current by tabNav.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "timetable"
    val openTimetableAt by viewModel.openTimetableAt.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val hasUpdate = updateState is UpdateCheckState.Available
    var meOpenedForUpdate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasUpdate) {
        if (!hasUpdate) meOpenedForUpdate = false
    }
    LaunchedEffect(route, hasUpdate) {
        if (hasUpdate && route == "me") meOpenedForUpdate = true
    }
    LaunchedEffect(openTimetableAt) {
        if (openTimetableAt == 0L) return@LaunchedEffect
        tabNav.navigate("timetable") {
            popUpTo("timetable") { inclusive = false }
            launchSingleTop = true
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(KcbBottomBarHeight),
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
                    selected = route == "friends",
                    onClick = { tabNav.navigate("friends") { popUpTo("timetable"); launchSingleTop = true } },
                    icon = { Icon(Icons.Outlined.Groups, contentDescription = "好友", modifier = Modifier.size(20.dp)) },
                    label = { Text("好友", fontSize = 11.sp, lineHeight = 12.sp) },
                )
                NavigationBarItem(
                    selected = route == "me",
                    onClick = { tabNav.navigate("me") { popUpTo("timetable"); launchSingleTop = true } },
                    icon = {
                        BadgedBox(badge = { if (hasUpdate && !meOpenedForUpdate) Badge() }) {
                            Icon(Icons.Outlined.Person, contentDescription = "我的", modifier = Modifier.size(20.dp))
                        }
                    },
                    label = { Text("我的", fontSize = 11.sp, lineHeight = 12.sp) },
                )
            }
        },
    ) { inner ->
        NavHost(
            navController = tabNav,
            startDestination = "timetable",
            modifier = Modifier.padding(inner),
            enterTransition = { fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = { fadeOut(tween(160)) },
        ) {
            composable("timetable") {
                TimetableScreen(viewModel = viewModel, onEdit = onEdit, onAdd = onAdd, onLogin = onLogin)
            }
            composable("exam") { ExamScreen(viewModel) }
            composable("friends") { FriendScreen(viewModel, onSetupSync = onWebDav) }
            composable("me") {
                MeScreen(
                    viewModel = viewModel,
                    onLogin = onLogin,
                    onAbout = onAbout,
                    onWebDav = onWebDav,
                    showAboutUpdateBadge = hasUpdate && meOpenedForUpdate,
                )
            }
        }
    }
}
