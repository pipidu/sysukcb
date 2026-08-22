package cn.sysu.kcb.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.sysu.kcb.KcbApp
import cn.sysu.kcb.data.prefs.CookieStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onClose: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val cookies = KcbApp.instance.container.cookies

    fun tryFinish(requireJwxtCheck: Boolean) {
        if (finished || checking) return
        checking = true
        cookies.syncFromWebView()
        scope.launch {
            val ok = runCatching { KcbApp.instance.container.importer.isLoggedIn() }
                .getOrDefault(!requireJwxtCheck && cookies.hasSession())
            if (ok) {
                finished = true
                onLoggedIn()
            } else {
                checking = false
            }
        }
    }

    fun maybeFinish(url: String) {
        val onJwxt = url.startsWith("https://jwxt.sysu.edu.cn") &&
            !url.contains("/esc-sso/") &&
            !url.contains("cas.sysu.edu.cn")
        if (!onJwxt) return
        tryFinish(requireJwxtCheck = true)
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("教务登录") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(onClick = { tryFinish(requireJwxtCheck = true) }, enabled = !finished && !checking) {
                        Text("开始导入")
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { tryFinish(requireJwxtCheck = false) },
                enabled = !finished && !checking,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(if (checking) "正在确认登录…" else "我已登录，导入课表") }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            AnimatedVisibility(visible = progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        cookies.wipeBrowser(this)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                cookies.syncFromWebView()
                                maybeFinish(url)
                            }
                        }
                        loadUrl(CookieStore.LOGIN_URL)
                        webView = this
                    }
                },
                update = { webView = it },
                onRelease = { view ->
                    view.stopLoading()
                    view.clearHistory()
                    view.destroy()
                    if (webView === view) webView = null
                },
            )
        }
    }
}
