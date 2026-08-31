package cn.sysu.kcb.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.remote.GzhuClient
import cn.sysu.kcb.data.school.School
import cn.sysu.kcb.ui.theme.KcbTopBar
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    schoolId: String,
    onClose: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    var school by remember(schoolId) { mutableStateOf(School.of(schoolId)) }
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var popupWebView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val cookies = KcbApp.instance.container.cookies

    fun tryFinish(requireJwxtCheck: Boolean) {
        if (finished || checking) return
        checking = true
        cookies.syncFromWebView(school)
        scope.launch {
            val checked = runCatching { KcbApp.instance.container.importer.isLoggedIn() }.getOrDefault(false)
            val ok = checked || (
                cookies.hasSession(school) &&
                    (!requireJwxtCheck || school.id == School.ID_GZHU)
                )
            if (ok) {
                finished = true
                onLoggedIn()
            } else {
                checking = false
            }
        }
    }

    fun maybeFinish(url: String) {
        if (!school.isLanding(url)) return
        tryFinish(requireJwxtCheck = true)
    }

    BackHandler {
        val popup = popupWebView
        when {
            popup != null && popup.canGoBack() -> popup.goBack()
            popup != null -> {
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.destroy()
                popupWebView = null
            }
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onClose()
        }
    }

    val gzhu = school.id == School.ID_GZHU
    Scaffold(
        topBar = {
            KcbTopBar {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "关闭")
                }
                Text("${school.shortName}教务登录", modifier = Modifier.weight(1f))
            }
        },
        bottomBar = {
            if (!gzhu) {
                Button(
                    onClick = { tryFinish(requireJwxtCheck = false) },
                    enabled = !finished && !checking,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (checking) "正在确认登录…" else "我已登录，导入课表和考试") }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val host = WebView(context)
                        lateinit var chrome: WebChromeClient
                        val client = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = shouldLeaveWebView(view, request.url.toString())

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                shouldLeaveWebView(view, url)

                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                cookies.syncFromWebView(school)
                                maybeFinish(url)
                            }
                        }
                        chrome = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                val parent = this@apply
                                val extra = WebView(parent.context).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    applyLoginSettings(desktopUa = schoolId == School.ID_GZHU)
                                    webViewClient = client
                                }
                                extra.webChromeClient = this
                                popupWebView?.let { old ->
                                    parent.removeView(old)
                                    old.destroy()
                                }
                                parent.addView(extra)
                                popupWebView = extra
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = extra
                                resultMsg.sendToTarget()
                                return true
                            }

                            override fun onCloseWindow(window: WebView?) {
                                val closing = window ?: popupWebView ?: return
                                (closing.parent as? ViewGroup)?.removeView(closing)
                                closing.destroy()
                                if (popupWebView === closing) popupWebView = null
                            }
                        }
                        host.apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            applyLoginSettings(desktopUa = schoolId == School.ID_GZHU)
                            webViewClient = client
                            webChromeClient = chrome
                        }
                        addView(host)
                        webView = host
                        cookies.wipeBrowser(host) {
                            host.post {
                                if (finished) return@post
                                scope.launch {
                                    val latest = School.of(
                                        KcbApp.instance.container.settings.snapshot().schoolId,
                                    )
                                    school = latest
                                    host.loadUrl(latest.loginUrl)
                                }
                            }
                        }
                    }
                },
                update = { frame ->
                    webView = frame.getChildAt(0) as? WebView ?: webView
                },
                onRelease = { view ->
                    val frame = view as? FrameLayout
                    val children = buildList {
                        if (frame != null) {
                            for (index in 0 until frame.childCount) {
                                (frame.getChildAt(index) as? WebView)?.let(::add)
                            }
                        }
                    }
                    children.forEach { child ->
                        child.stopLoading()
                        child.destroy()
                    }
                    if (webView in children) webView = null
                    popupWebView = null
                },
            )
            }
            if (gzhu) {
                Button(
                    onClick = { tryFinish(requireJwxtCheck = false) },
                    enabled = !finished && !checking,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(12.dp),
                ) { Text(if (checking) "正在确认…" else "我已登录") }
            }
        }
    }
}

private fun shouldLeaveWebView(view: WebView, url: String): Boolean {
    val lower = url.lowercase()
    if (lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("about:") ||
        lower.startsWith("javascript:") ||
        lower.startsWith("data:") ||
        lower.startsWith("blob:")
    ) {
        return false
    }
    runCatching {
        view.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    return true
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyLoginSettings(desktopUa: Boolean = false) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.mediaPlaybackRequiresUserGesture = false
    settings.userAgentString = if (desktopUa) {
        GzhuClient.DESKTOP_UA
    } else {
        settings.userAgentString.replace("; wv", "")
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}
