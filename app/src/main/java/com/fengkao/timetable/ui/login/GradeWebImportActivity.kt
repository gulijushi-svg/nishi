package com.fengkao.timetable.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.grades.GradeRepository
import com.fengkao.timetable.ui.theme.CardWhite
import com.fengkao.timetable.ui.theme.InkGray
import com.fengkao.timetable.ui.theme.InkMid
import com.fengkao.timetable.ui.theme.TimetableTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GradeWebImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val year = intent.getIntExtra(EXTRA_YEAR, 0)
        val term = if (year > 0) {
            TermSpec(year, intent.getIntExtra(EXTRA_TERM, 1))
        } else {
            com.fengkao.timetable.data.TermCalculator.autoTerm()
        }
        setContent {
            TimetableTheme {
                GradeWebImportScreen(term) {
                    setResult(Activity.RESULT_OK, Intent())
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_YEAR = "year"
        const val EXTRA_TERM = "term"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeWebImportScreen(term: TermSpec, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as TimetableApp).container
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var hasCookie by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在打开郑州师范学院教务系统…") }
    var error by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    error = null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    val cookie = url?.let { CookieManager.getInstance().getCookie(it) }.orEmpty()
                    hasCookie = cookie.isNotBlank() &&
                        url.orEmpty().startsWith("https://jwgl.zznu.edu.cn/jwglxt/") &&
                        !url.orEmpty().contains("login_slogin")
                    if (hasCookie) {
                        status = "已获取教务登录状态，可以读取 ${term.label} 成绩"
                        scope.launch { container.academicSession.captureFromWebView(url.orEmpty()) }
                    } else {
                        status = "请在网页中登录教务系统"
                    }
                }
            }
            loadUrl(START_URL)
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }
    BackHandler { if (webView.canGoBack()) webView.goBack() else onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                title = { Text("网页读取成绩", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
            Surface(color = CardWhite, tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(status, color = InkMid, fontSize = 13.sp)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                error = null
                                webView.loadUrl(START_URL)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !loading && !importing,
                        ) { Text("重新登录", fontSize = 13.sp) }
                        Button(
                            onClick = {
                                error = null
                                importing = true
                                status = "正在读取 ${term.label} 成绩…"
                                scope.launch {
                                    when (val result = container.gradeRepository.fetchGrades(term)) {
                                        is GradeRepository.Outcome.Success -> {
                                            val snapshot = result.snapshot
                                            if (snapshot == null) {
                                                error = "没有读取到成绩，请确认教务页面当前学期有成绩"
                                                importing = false
                                            } else {
                                                withContext(Dispatchers.IO) { container.grades.save(snapshot) }
                                                onDone()
                                            }
                                        }
                                        is GradeRepository.Outcome.Failure -> {
                                            error = result.message
                                            importing = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = hasCookie && !loading && !importing,
                        ) {
                            if (importing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                            }
                            Text(" 读取成绩", fontSize = 13.sp)
                        }
                    }
                    Text(
                        "登录 Cookie 沿用教务网页会话，读取成绩后在本地解析并加密保存会话；不会保存或上传教务密码。",
                        color = InkGray,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private const val START_URL = "https://jwgl.zznu.edu.cn/jwglxt/xtgl/login_slogin.html"
