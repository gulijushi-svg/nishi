package com.fengkao.timetable.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fengkao.timetable.TimetableApp
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.local.DEFAULT_BASE_URL
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.data.schedule.WebScheduleParser
import com.fengkao.timetable.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 郑州师范学院教务网页导入。
 *
 * 与 Classy 的思路一致：不把账号密码交给本地代理解析，而是在 WebView 中
 * 让用户直接完成学校的网页登录；登录态 Cookie 留在 WebView 内，随后在
 * 同源页面执行 JS 请求个人课表接口并解析 JSON。
 *
 * 本项目只保留郑州师范学院一个默认地址，不维护学校列表。
 */
class ScheduleWebImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TimetableApp).container
        setContent {
            TimetableTheme {
                WebImportScreen(container) {
                    setResult(Activity.RESULT_OK, Intent())
                    finish()
                }
            }
        }
    }
}

internal class NishiBridge(private val callback: (String) -> Unit) {
    @JavascriptInterface
    fun onResult(raw: String) {
        callback(raw)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebImportScreen(
    container: com.fengkao.timetable.AppContainer,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在打开郑州师范学院教务系统…") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<CachedSchedule?>(null) }

    fun handleResult(raw: String) {
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null) {
            status = "读取失败"
            error = "教务页面返回了无法识别的数据"
            return
        }
        if (!result.optBoolean("ok")) {
            status = "读取失败"
            val detail = result.optString("err")
            error = when {
                result.optString("kind") == "SESSION_EXPIRED" -> "登录状态已失效，请重新登录后再导入"
                result.optString("kind") == "NOT_ON_TIMETABLE" -> "请先打开个人课表页面，再点击读取课表"
                detail.contains("未找到学期") || detail.contains("请先登录") ->
                    "请先在网页中登录，并打开个人课表页面"
                else -> detail.ifBlank { "读取课表失败，请重试" }
            }
            return
        }
        val schedule = WebScheduleParser.parse(result)
        if (schedule == null || schedule.courses.isEmpty()) {
            error = "没有读取到课程，请确认已打开个人课表页并选择了正确学期"
            return
        }
        scope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    val existingAccount = container.settings.first().account.trim()
                    val id = schedule.student.studentId.trim()
                    val savedAccount = id.ifBlank { existingAccount }
                    container.settings.saveCredentials(savedAccount, null, DEFAULT_BASE_URL, false)
                    container.settings.setAutoSync(false)
                    container.settings.setLastSync(schedule.xnm, schedule.term, System.currentTimeMillis())
                    container.settings.setTermStartEpochDay(
                        TermCalculator.defaultTermStart(schedule.xnm, schedule.term).toEpochDay(),
                        TermSpec(schedule.xnm, schedule.term),
                    )
                    container.cache.save(schedule)
                    savedAccount
                }
                error = null
                status = "已读取 ${account.ifBlank { "教务网页" }} 的 ${schedule.courses.size} 个课程格子"
                imported = schedule
            } catch (e: Exception) {
                error = e.message ?: "课表保存失败，请重试"
            }
        }
    }

    val bridge = remember {
        NishiBridge { raw ->
            scope.launch(Dispatchers.Main) { handleResult(raw) }
        }
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            addJavascriptInterface(bridge, "NishiBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    val allowed = request.url.scheme == "https" && request.url.host == HOST
                    if (!allowed) {
                        loading = false
                        status = "已阻止离开教务系统的页面"
                        error = "为保护登录信息，仅允许访问学校教务页面"
                    }
                    return !allowed
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    if (url?.startsWith("https://jwgl.zznu.edu.cn/jwglxt/") == true &&
                        !url.contains("login_slogin")
                    ) {
                        scope.launch { container.academicSession.captureFromWebView(url) }
                    }
                    status = if (url?.contains("xskbcx") == true) {
                        "已进入个人课表页，点击“读取当前课表”"
                    } else {
                        "请在网页中登录，并打开“个人课表”页面"
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceError?,
                ) {
                    loading = false
                    error = "网页加载失败，请检查网络或学校教务地址"
                }
            }
            loadUrl(START_URL)
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                title = { Text("网页导入课表", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Surface(color = CardWhite, tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(status, color = InkMid, fontSize = 13.sp)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                error = null
                                webView.loadUrl(SCHEDULE_URL)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !loading,
                        ) { Text("打开课表页", fontSize = 13.sp) }
                        Button(
                            onClick = {
                                error = null
                                status = "正在读取当前课表…"
                                webView.evaluateJavascript(FETCH_JS) { }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !loading,
                        ) {
                            Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("读取当前课表", fontSize = 13.sp)
                        }
                    }
                    Text("登录信息只保留在网页 Cookie 中；网页导入不保存密码。", color = InkGray, fontSize = 11.sp)
                }
            }
        }
    }

    imported?.let { schedule ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("课表导入成功") },
            text = {
                Text(
                    "已识别 ${schedule.student.name.ifBlank { "当前学生" }} 的 ${schedule.courses.size} 个课程格子，" +
                        "学期：${schedule.xnm}-${schedule.xnm + 1} 学年第 ${schedule.term} 学期。"
                )
            },
            confirmButton = {
                TextButton(onClick = onDone) { Text("完成") }
            },
            containerColor = CardWhite,
        )
    }
}

private const val HOST = "jwgl.zznu.edu.cn"
private const val BASE_PATH = "/jwglxt"
private const val START_URL = "https://$HOST$BASE_PATH/xtgl/login_slogin.html"
private const val SCHEDULE_URL = "https://$HOST$BASE_PATH/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"

/**
 * Classy 的 zf_new 抓取逻辑精简版：只保留郑州师范学院的新正方路径，
 * 使用 WebView 中的登录 Cookie 请求 kbList，不引入学校列表或其它协议。
 */
private const val FETCH_JS = """(function(){
  function fail(kind, err){
    NishiBridge.onResult(JSON.stringify({ok:false,kind:kind,err:err||''}));
  }
  function ok(data, xnm, xqm){
    NishiBridge.onResult(JSON.stringify({ok:true,data:JSON.stringify(data),xnm:xnm,xqm:xqm}));
  }
  try {
    if (location.protocol !== 'https:' || location.host !== 'jwgl.zznu.edu.cn') {
      fail('NOT_ON_TIMETABLE', '请先打开学校教务页面');
      return;
    }
    var path = location.pathname;
    var onJwglxt = path.indexOf('/jwglxt/') >= 0;
    var onKbcx = path.indexOf('/kbcx/') >= 0;
    if (!onJwglxt && !onKbcx) {
      fail('NOT_ON_TIMETABLE', '请先打开个人课表页面');
      return;
    }
    var pathPrefix = onJwglxt ? '/jwglxt' : '';
    var search = location.search.substring(1);
    var params = {};
    search.split('&').forEach(function(p){
      if (!p) return;
      var kv = p.split('=');
      params[kv[0]] = decodeURIComponent(kv[1] || '');
    });
    var gnmkdm = params['gnmkdm'] || 'N2151';

    function pickTerm(){
      return new Promise(function(resolve, reject){
        if (params['xnm'] && params['xqm']) { resolve({xnm:params['xnm'],xqm:params['xqm']}); return; }
        var sel = document.querySelector('#xnm');
        var sel2 = document.querySelector('#xqm');
        if (sel && sel.value && sel2 && sel2.value) {
          resolve({xnm:String(sel.value).trim(), xqm:String(sel2.value).trim()});
          return;
        }
        var idxPath = pathPrefix + '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' + encodeURIComponent(gnmkdm) + '&layout=default';
        fetch(idxPath, {credentials:'include', headers:{'X-Requested-With':'XMLHttpRequest'}})
          .then(function(r){ return r.text(); })
          .then(function(html){
            function selectedTerm(id) {
              var doc = new DOMParser().parseFromString(html, 'text/html');
              var select = doc.getElementById(id);
              if (!select) throw new Error('未找到学期，请先在教务页面选择学期');
              var options = Array.prototype.filter.call(select.options, function(o){ return o.value.trim() !== ''; });
              var chosen = options.filter(function(o){ return o.hasAttribute('selected'); });
              if (chosen.length === 1) return chosen[0].value.trim();
              if (options.length === 1) return options[0].value.trim();
              throw new Error('无法确认默认学期，请先在教务页面选择学期后导入');
            }
            resolve({xnm:selectedTerm('xnm'), xqm:selectedTerm('xqm')});
          }).catch(function(e){ reject(e); });
      });
    }

    function req(url, method, body){
      return fetch(url, {
        method: method,
        credentials: 'include',
        headers: {
          'X-Requested-With': 'XMLHttpRequest',
          'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
        },
        body: body
      }).then(function(r){ return r.text(); });
    }

    pickTerm().then(function(t){
      var apiPath = pathPrefix + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + encodeURIComponent(gnmkdm);
      var body = 'xnm=' + encodeURIComponent(t.xnm) +
        '&xqm=' + encodeURIComponent(t.xqm) + '&kzlx=ck&xsdm=&kclbdm=&kclxdm=';
      return req(apiPath, 'POST', body).then(function(text){ return {t:t,text:text}; });
    }).then(function(o){
      var text = o.text, t = o.t;
      var looksLikeLogin = text.indexOf('login_slogin') >= 0 &&
        text.indexOf('csrftoken') >= 0 &&
        (text.indexOf('请输入密码') >= 0 || text.indexOf('name="mm"') >= 0 || text.indexOf('name="password"') >= 0);
      if (looksLikeLogin) { fail('SESSION_EXPIRED', '登录状态已失效'); return; }
      var data;
      try { data = JSON.parse(text); } catch(e){ fail('SESSION_EXPIRED', '接口返回非 JSON，可能已过期'); return; }
      function findKbList(obj, depth){
        if (depth > 5 || !obj || typeof obj !== 'object') return null;
        if (Array.isArray(obj)) {
          for (var i=0;i<obj.length;i++) { var r=findKbList(obj[i], depth+1); if (r) return r; }
          return null;
        }
        if (obj.kbList && Array.isArray(obj.kbList)) return obj.kbList;
        for (var k in obj) { var r=findKbList(obj[k], depth+1); if (r) return r; }
        return null;
      }
      var kbList = findKbList(data, 0);
      if (!kbList || kbList.length === 0) {
        ok({kbList:[]}, t.xnm, t.xqm);
        return;
      }
      var payload = Array.isArray(data) ? {kbList:data} : data;
      ok(payload, t.xnm, t.xqm);
    }).catch(function(e){ fail('FORMAT_ERROR', String(e)); });
  } catch(err) {
    fail('FORMAT_ERROR', String(err));
  }
})();"""
