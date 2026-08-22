package cn.sysu.kcb.ui.me

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sysu.kcb.BuildConfig

const val GITHUB_URL = "https://github.com/pipidu/sysukcb"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun openGithub() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
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
                    "中山大学本科教务课表客户端。课表与考试保存在本机，不经第三方服务器。",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text(GITHUB_URL) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "在浏览器打开")
                },
                modifier = Modifier.clickable { openGithub() },
            )
        }
    }
}
