package com.fengkao.timetable.data.grades

import android.content.Context
import com.fengkao.timetable.data.TermCalculator
import com.fengkao.timetable.data.TermSpec
import com.fengkao.timetable.data.model.CachedSchedule
import com.fengkao.timetable.data.schedule.WebScheduleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

/**
 * 郑州师范学院教务直连登录 + 成绩查询。
 *
 * 登录协议参考 zfn_api：login_getPublicKey → login_slogin；成绩接口参考
 * `cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005`。
 * 仅保存成绩，不持久化教务密码。
 */
class GradeRepository(
    private val context: Context,
    baseClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val sessionStore: AcademicSessionStore? = null,
) {

    sealed interface Outcome {
        data class Success(
            val snapshot: GradeSnapshot? = null,
            val schedule: CachedSchedule? = null,
        ) : Outcome
        data class Failure(val message: String) : Outcome
    }

    @Volatile private var sessionReady = false

    private val cookieJar = MemoryCookieJar()
    private val sharedClient = baseClient
    private val client = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .build()

    @Volatile
    private var lastGradeEndpoint: String? = null

    fun lastConfirmedGradeEndpoint(): String? = lastGradeEndpoint

    fun hasSession(): Boolean = sessionReady

    /** 进程重启后内存 Cookie 会丢失；用本机保存的加密凭据静默恢复会话。 */
    suspend fun ensureSession(account: String, password: String): ApiResult<Unit> {
        if (sessionReady) return ApiResult.Ok(Unit)
        if (account.isBlank() || password.isBlank()) {
            return ApiResult.Err("请先在“我的 → 校园服务账户 → 教务系统账户”登录教务系统")
        }
        val outcome = loginAndFetch(account, password, includeSchedule = false)
        return if (sessionReady) ApiResult.Ok(Unit)
        else ApiResult.Err((outcome as? Outcome.Failure)?.message ?: "教务会话恢复失败")
    }

    suspend fun loginAndFetch(
        account: String,
        password: String,
        includeSchedule: Boolean = false,
    ): Outcome = withContext(Dispatchers.IO) {
        if (account.isBlank() || password.isBlank()) return@withContext Outcome.Failure("请输入学号和密码")
        try {
            val base = "https://jwgl.zznu.edu.cn/jwglxt/"
            val loginPage = client.newCall(
                Request.Builder().url(base + "xtgl/login_slogin.html").get().build()
            ).execute().use { it.body?.string().orEmpty() }
            val csrf = inputValue(loginPage, "csrftoken")
                ?: return@withContext Outcome.Failure("教务登录页缺少 csrftoken")
            val mmsfjm = inputValue(loginPage, "mmsfjm")
            val encrypted = if (mmsfjm == "0") {
                password
            } else {
                val keyJson = client.newCall(
                    Request.Builder().url(base + "xtgl/login_getPublicKey.html").get().build()
                ).execute().use { it.body?.string().orEmpty() }
                val key = runCatching { JSONObject(keyJson) }.getOrNull()
                    ?: return@withContext Outcome.Failure("获取教务公钥失败")
                encryptPassword(
                    password,
                    key.optString("modulus"),
                    key.optString("exponent"),
                )
            }
            val formBuilder = FormBody.Builder()
                .add("csrftoken", csrf)
                .add("language", "zh_CN")
                .add("ydType", "")
                .add("yhm", account.trim())
                .add("mm", encrypted)
            if (!mmsfjm.isNullOrBlank()) formBuilder.add("mmsfjm", mmsfjm)
            val loginResp = client.newCall(
                Request.Builder()
                    .url(base + "xtgl/login_slogin.html")
                    .header("Referer", base + "xtgl/login_slogin.html")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(formBuilder.build())
                    .build()
            ).execute()
            val loginBody = loginResp.body?.string().orEmpty()
            val loginUrl = loginResp.request.url.toString()
            val tip = Regex("""<p[^>]*id=["']tips["'][^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                .find(loginBody)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            val explicitError = tip?.contains("用户名或密码") == true ||
                tip?.contains("验证码") == true ||
                tip?.contains("密码") == true
            // 正确登录后不同学校可能落在 index_initMenu / index / 首页；不把
            // “页面里没有某个固定字符串”当成登录失败，后续成绩请求才是最终验证。
            if (explicitError) {
                return@withContext Outcome.Failure(
                    when {
                        tip?.contains("用户名或密码") == true -> "用户名或密码不正确"
                        tip?.contains("验证码") == true -> "需要验证码，请改用网页登录导入"
                        !tip.isNullOrBlank() -> tip
                        else -> "教务登录失败，请检查账号密码"
                    }
                )
            }
            if (loginBody.contains("login_slogin") && loginBody.contains("csrftoken") &&
                !loginBody.contains("index_initMenu") && !loginUrl.contains("index_initMenu")) {
                return@withContext Outcome.Failure(
                    tip?.takeIf { it.isNotBlank() }
                        ?: "教务登录失败：登录页没有返回有效会话，请检查账号密码或学校登录方式"
                )
            }
            sessionReady = true
            sessionStore?.saveCookieHeader(cookieJar.header("jwgl.zznu.edu.cn"))
            val term = TermCalculator.autoTerm()
            val xqm = when (term.term) { 1 -> "3"; 2 -> "12"; else -> "3" }
            var schedule: CachedSchedule? = null
            if (includeSchedule) {
                val scheduleForm = FormBody.Builder()
                    .add("xnm", term.xnm.toString())
                    .add("xqm", xqm)
                    .build()
                val scheduleResp = client.newCall(
                    Request.Builder()
                        .url(base + "kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151")
                        .header("Referer", base + "kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .post(scheduleForm)
                        .build()
                ).execute()
                val scheduleBody = scheduleResp.body?.string().orEmpty()
                if (scheduleBody.contains("login_slogin") || scheduleBody.contains("用户登录")) {
                    return@withContext Outcome.Failure("教务会话已失效，请重新登录")
                }
                val scheduleJson = runCatching { JSONObject(scheduleBody) }.getOrNull()
                if (scheduleJson != null) {
                    val wrappedSchedule = JSONObject()
                        .put("xnm", term.xnm)
                        .put("xqm", xqm)
                        .put("data", scheduleJson.toString())
                    schedule = WebScheduleParser.parse(wrappedSchedule)
                }
                if (schedule == null) return@withContext Outcome.Failure("没有读取到课表，请稍后重试")
            }
            val form = FormBody.Builder()
                .add("xnm", term.xnm.toString())
                .add("xqm", xqm)
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "100")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()
            val gradeResp = client.newCall(
                Request.Builder()
                    .url(base + "cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005")
                    .header("Referer", base + "cjcx/cjcx_cxXsgrcj.html?gnmkdm=N305005")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build()
            ).execute()
            val gradeBody = gradeResp.body?.string().orEmpty()
            val snapshot = if (gradeBody.contains("login_slogin") || gradeBody.contains("用户登录")) {
                null
            } else {
                val gradeJson = runCatching { JSONObject(gradeBody) }.getOrNull()
                if (gradeJson == null) null else {
                    val wrapped = JSONObject()
                        .put("xnm", term.xnm)
                        .put("xqm", xqm)
                        .put("data", gradeJson.toString())
                    GradeParser.parse(wrapped)
                }
            }
            if (snapshot == null && schedule == null) {
                return@withContext Outcome.Failure("当前学期暂无成绩，或成绩尚未公布")
            }
            Outcome.Success(snapshot = snapshot, schedule = schedule)
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: "登录或读取成绩失败")
        }
    }

    sealed interface ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>
        data class Err(val message: String) : ApiResult<Nothing>
    }

    private val baseUrl = "https://jwgl.zznu.edu.cn/jwglxt/"
    private val headers = mapOf(
        "Referer" to baseUrl + "xtgl/index_initMenu.html",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun sessionError(): ApiResult.Err =
        if (sessionReady) ApiResult.Err("读取失败") else ApiResult.Err("请先在“我的 → 校园服务账户 → 教务系统账户”登录教务系统")

    private fun get(url: String): String = client.newCall(
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
    ).execute().use { it.body?.string().orEmpty() }

    private fun post(url: String, form: FormBody): String = client.newCall(
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(form).build()
    ).execute().use { it.body?.string().orEmpty() }

    private fun loginPage(body: String): Boolean =
        body.contains("login_slogin") || body.contains("用户登录")

    /** 考试信息。 */
    suspend fun fetchExams(): ApiResult<List<ExamRecord>> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        val term = TermCalculator.autoTerm()
        val xqm = if (term.term == 1) "3" else "12"
        val body = runCatching {
            post(
                baseUrl + "kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105",
                FormBody.Builder()
                    .add("xnm", term.xnm.toString())
                    .add("xqm", xqm)
                    .add("_search", "false")
                    .add("nd", System.currentTimeMillis().toString())
                    .add("queryModel.showCount", "100")
                    .add("queryModel.currentPage", "1")
                    .add("queryModel.sortName", "")
                    .add("queryModel.sortOrder", "asc")
                    .add("time", "0")
                    .build(),
            )
        }.getOrElse { return@withContext ApiResult.Err(it.message ?: "读取考试信息失败") }
        if (loginPage(body)) {
            sessionReady = false
            return@withContext ApiResult.Err("教务会话已失效，请重新登录")
        }
        runCatching {
            val items = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray()
            ApiResult.Ok((0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                ExamRecord(
                    title = o.optString("kcmc"),
                    time = o.optString("kssj"),
                    place = o.optString("cdmc"),
                    seat = o.optString("zwh"),
                    teacher = o.optString("jsxx"),
                    examName = o.optString("ksmc"),
                    credit = o.optString("xf"),
                )
            })
        }.getOrElse { ApiResult.Err(it.message ?: "考试信息解析失败") }
    }

    /** 教务通知 / 停补换课消息。 */
    suspend fun fetchNotifications(): ApiResult<List<AcademicNotice>> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        val body = runCatching {
            post(
                baseUrl + "xtgl/index_cxDbsy.html?doType=query",
                FormBody.Builder()
                    .add("sfyy", "0")
                    .add("flag", "1")
                    .add("_search", "false")
                    .add("nd", System.currentTimeMillis().toString())
                    .add("queryModel.showCount", "1000")
                    .add("queryModel.currentPage", "1")
                    .add("queryModel.sortName", "cjsj")
                    .add("queryModel.sortOrder", "desc")
                    .add("time", "0")
                    .build(),
            )
        }.getOrElse { return@withContext ApiResult.Err(it.message ?: "读取通知失败") }
        if (loginPage(body)) {
            sessionReady = false
            return@withContext ApiResult.Err("教务会话已失效，请重新登录")
        }
        runCatching {
            val items = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray()
            ApiResult.Ok((0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                val raw = o.optString("xxnr")
                val split = raw.split(":", limit = 2)
                AcademicNotice(
                    title = o.optString("xxbt").ifBlank { o.optString("bt") },
                    type = if (split.size == 2) split[0] else "",
                    content = if (split.size == 2) split[1] else raw,
                    time = o.optString("cjsj"),
                )
            })
        }.getOrElse { ApiResult.Err(it.message ?: "通知解析失败") }
    }

    /** 已选课程。 */
    suspend fun fetchSelectedCourses(): ApiResult<List<SelectedCourse>> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        val term = TermCalculator.autoTerm()
        val xqm = if (term.term == 1) "3" else "12"
        val body = runCatching {
            post(
                baseUrl + "xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm=N253512",
                FormBody.Builder()
                    .add("xkxnm", term.xnm.toString())
                    .add("xkxqm", xqm)
                    .build(),
            )
        }.getOrElse { return@withContext ApiResult.Err(it.message ?: "读取已选课程失败") }
        if (loginPage(body)) {
            sessionReady = false
            return@withContext ApiResult.Err("教务会话已失效，请重新登录")
        }
        runCatching {
            val array = org.json.JSONArray(body)
            ApiResult.Ok((0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SelectedCourse(
                    courseId = o.optString("kch"),
                    classId = o.optString("jxb_id"),
                    doId = o.optString("do_jxb_id"),
                    title = o.optString("kcmc"),
                    teacher = o.optString("jsxx"),
                    credit = o.optString("xf"),
                    category = o.optString("kklxmc"),
                    place = o.optString("jxdd"),
                    time = o.optString("sksj"),
                )
            })
        }.getOrElse { ApiResult.Err(it.message ?: "已选课程解析失败") }
    }

    /** 板块课列表。 */
    suspend fun fetchBlockCourses(block: Int): ApiResult<List<BlockCourse>> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        if (block !in 1..3) return@withContext ApiResult.Err("板块只能是 1、2、3")
        runCatching {
            val head = get(baseUrl + "xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default")
            if (loginPage(head)) {
                sessionReady = false
                return@withContext ApiResult.Err("教务会话已失效，请重新登录")
            }
            val hidden = parseHidden(head)
            val tabs = Regex("""<a[^>]*role=["']tab["'][^>]*onclick=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(head).mapNotNull { m ->
                    val quoted = Regex("""['"]([^'"]+)['"]""").findAll(m.groupValues[1])
                        .map { it.groupValues[1] }.toList()
                    if (quoted.size >= 2) quoted[0] to quoted[1] else null
                }.toList()
            if (tabs.size < 3) return@withContext ApiResult.Err("没有找到选课板块，可能当前不在选课时间")
            val kklxdm = tabs[block - 1].first
            val xkkzId = tabs[block - 1].second
            val display = post(
                baseUrl + "xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512",
                FormBody.Builder().add("xkkz_id", xkkzId).add("xszxzt", "1").add("kspage", "0").build(),
            )
            hidden.putAll(parseHidden(display))
            val term = TermCalculator.autoTerm()
            val xqm = if (term.term == 1) "3" else "12"
            fun field(key: String) = hidden[key].orEmpty()
            val kchBody = post(
                baseUrl + "xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512",
                FormBody.Builder()
                    .add("bklx_id", field("bklx_id")).add("xqh_id", field("xqh_id"))
                    .add("zyfx_id", field("zyfx_id")).add("njdm_id", field("njdm_id"))
                    .add("bh_id", field("bh_id")).add("xbm", field("xbm"))
                    .add("xslbdm", field("xslbdm")).add("ccdm", field("ccdm"))
                    .add("xsbj", field("xsbj")).add("xkxnm", term.xnm.toString())
                    .add("xkxqm", xqm).add("kklxdm", kklxdm)
                    .add("kkbk", field("kkbk")).add("rwlx", field("rwlx"))
                    .add("kspage", "1").add("jspage", "10").build(),
            )
            val kchJson = JSONObject(kchBody).optJSONArray("tmpList") ?: org.json.JSONArray()
            if (kchJson.length() == 0) return@withContext ApiResult.Ok(emptyList())
            val first = kchJson.getJSONObject(0)
            val bkkBody = post(
                baseUrl + "xsxk/zzxkyzb_cxJxbWithKchZzxkYzb.html?gnmkdm=N253512",
                FormBody.Builder()
                    .add("bklx_id", field("bklx_id")).add("xkxnm", term.xnm.toString())
                    .add("xkxqm", xqm).add("xkkz_id", xkkzId)
                    .add("xqh_id", field("xqh_id")).add("zyfx_id", field("zyfx_id"))
                    .add("njdm_id", field("njdm_id")).add("bh_id", field("bh_id"))
                    .add("xbm", field("xbm")).add("xslbdm", field("xslbdm"))
                    .add("ccdm", field("ccdm")).add("xsbj", field("xsbj"))
                    .add("kklxdm", kklxdm).add("kch_id", first.optString("kch_id"))
                    .add("kkbk", field("kkbk")).add("rwlx", field("rwlx"))
                    .add("zyh_id", field("zyh_id")).build(),
            )
            val bkkJson = org.json.JSONArray(bkkBody)
            ApiResult.Ok((0 until minOf(kchJson.length(), bkkJson.length())).map { i ->
                val a = kchJson.getJSONObject(i)
                val b = bkkJson.getJSONObject(i)
                BlockCourse(
                    courseId = a.optString("kch_id"), classId = b.optString("jxb_id"),
                    doId = b.optString("do_jxb_id"), title = a.optString("kcmc"),
                    teacher = a.optString("jsxx"), credit = a.optString("xf"),
                    capacity = b.optString("jxbrl"), selected = b.optString("yxzrs"),
                    place = b.optString("jxdd"), time = b.optString("sksj"), kklxdm = kklxdm,
                )
            })
        }.getOrElse { ApiResult.Err(it.message ?: "读取板块课失败") }
    }

    /** 选课。 */
    suspend fun selectCourse(course: BlockCourse, studentId: String): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        val term = TermCalculator.autoTerm()
        val xqm = if (term.term == 1) "3" else "12"
        val body = runCatching {
            post(
                baseUrl + "xsxk/zzxkyzb_xkBcZyZzxkYzb.html?gnmkdm=N253512",
                FormBody.Builder()
                    .add("jxb_ids", course.doId).add("kch_id", course.courseId)
                    .add("qz", "0").add("xkxnm", term.xnm.toString()).add("xkxqm", xqm)
                    .add("njdm_id", studentId.take(2)).add("zyh_id", studentId.drop(2).take(4))
                    .add("kklxdm", course.kklxdm).build(),
            )
        }.getOrElse { return@withContext ApiResult.Err(it.message ?: "选课失败") }
        if (loginPage(body)) {
            sessionReady = false
            return@withContext ApiResult.Err("教务会话已失效，请重新登录")
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        val flag = json?.optString("flag") ?: json?.optString("status") ?: body
        if (flag.contains("1") || body.contains("成功")) ApiResult.Ok("选课成功")
        else ApiResult.Err(json?.optString("msg").orEmpty().ifBlank { "选课失败：$body" })
    }

    /** 退课。 */
    suspend fun dropCourse(course: SelectedCourse): ApiResult<String> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        val term = TermCalculator.autoTerm()
        val xqm = if (term.term == 1) "3" else "12"
        val body = runCatching {
            post(
                baseUrl + "xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=N253512",
                FormBody.Builder()
                    .add("jxb_ids", course.doId).add("kch_id", course.courseId)
                    .add("xkxnm", term.xnm.toString()).add("xkxqm", xqm).build(),
            )
        }.getOrElse { return@withContext ApiResult.Err(it.message ?: "退课失败") }
        if (loginPage(body)) {
            sessionReady = false
            return@withContext ApiResult.Err("教务会话已失效，请重新登录")
        }
        if (body.contains("1") || body.contains("成功")) ApiResult.Ok("退课成功")
        else ApiResult.Err("退课失败：$body")
    }

    /** 学业生涯概览（GPA、学分统计）。 */
    suspend fun fetchAcademiaOverview(): ApiResult<AcademicOverview> = withContext(Dispatchers.IO) {
        if (!sessionReady) return@withContext sessionError()
        val body = runCatching {
            get(baseUrl + "xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default")
        }.getOrElse { return@withContext ApiResult.Err(it.message ?: "读取学业数据失败") }
        if (loginPage(body)) {
            sessionReady = false
            return@withContext ApiResult.Err("教务会话已失效，请重新登录")
        }
        val text = body.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
        val nums = Regex("""\d+(?:\.\d+)?""").findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        val gpa = Regex("""(?:GPA|平均学分绩点)[^0-9]{0,20}(\d+(?:\.\d+)?)""").find(text)
            ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val required = Regex("""要求学分[^0-9]{0,10}(\d+(?:\.\d+)?)""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val earned = Regex("""获得学分[^0-9]{0,10}(\d+(?:\.\d+)?)""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val passed = Regex("""通过\s*(\d+)\s*门""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val failed = Regex("""未通过\s*(\d+)\s*门""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ApiResult.Ok(
            AcademicOverview(
                gpa = gpa ?: nums.getOrNull(0),
                requiredCredits = required,
                earnedCredits = earned,
                passedCourses = passed,
                failedCourses = failed,
                rawText = text.take(300),
            )
        )
    }

    private fun parseHidden(html: String): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        Regex("""<input[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val name = Regex("""name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.getOrNull(1) ?: return@forEach
            val value = Regex("""value\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()
            result[name] = value
        }
        return result
    }

    /** 已登录教务会话下重新读取成绩，不要求用户再次输入密码。 */
    suspend fun fetchGradesWithSession(): Outcome = fetchGrades(TermCalculator.autoTerm())

    suspend fun fetchGrades(term: TermSpec): Outcome = withContext(Dispatchers.IO) {
        val cookie = if (sessionReady) {
            cookieJar.header("jwgl.zznu.edu.cn").ifBlank { sessionStore?.cookieHeader().orEmpty() }
        } else {
            sessionStore?.cookieHeader().orEmpty()
        }
        if (cookie.isBlank()) return@withContext Outcome.Failure("请先登录教务系统")
        val xqm = when (term.term) { 1 -> "3"; 2 -> "12"; else -> "3" }
        try {
            val endpoint = discoverGradeEndpoint(cookie)
            lastGradeEndpoint = endpoint.queryUrl
            val form = FormBody.Builder()
                .add("xnm", term.xnm.toString())
                .add("xqm", xqm)
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "100")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()
            val page = sharedGet(endpoint.pageUrl, cookie)
            if (loginPage(page)) throw SessionExpiredException()
            val body = sharedClient.newCall(
                Request.Builder()
                    .url(endpoint.queryUrl)
                    .header("Cookie", cookie)
                    .header("Referer", endpoint.pageUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            if (loginPage(body)) throw SessionExpiredException()
            val htmlSnapshot = if (body.contains('<')) {
                GradeParser.parseHtml(body, term.xnm, term.term)
            } else {
                null
            }
            if (htmlSnapshot != null) return@withContext Outcome.Success(snapshot = htmlSnapshot)
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json != null) {
                val wrapped = JSONObject()
                    .put("xnm", term.xnm)
                    .put("xqm", xqm)
                    .put("data", json.toString())
                val snapshot = GradeParser.parse(wrapped)
                if (snapshot != null) return@withContext Outcome.Success(snapshot = snapshot)
            }
            val document = Jsoup.parse(body, endpoint.pageUrl)
            val hasTermSelectors = document.select("#xnm, #xqm").isNotEmpty()
            val hasEmptyNotice = document.body().text().let {
                it.contains("暂无成绩") || it.contains("没有成绩") || it.contains("未录入")
            }
            if (hasTermSelectors || hasEmptyNotice || body.isBlank()) {
                return@withContext Outcome.Success(
                    snapshot = GradeSnapshot(xnm = term.xnm, term = term.term, fetchedAt = System.currentTimeMillis())
                )
            }
            Outcome.Failure("成绩页面结构暂未适配，请使用网页导入反馈")
        } catch (_: SessionExpiredException) {
            invalidateSession()
            Outcome.Failure("教务登录状态已失效，请重新登录")
        } catch (_: java.io.IOException) {
            Outcome.Failure("网络连接失败，请稍后重试")
        } catch (_: Exception) {
            Outcome.Failure("成绩读取失败，请稍后重试")
        }
    }

    private data class GradeEndpoint(val pageUrl: String, val queryUrl: String)

    private fun discoverGradeEndpoint(cookie: String): GradeEndpoint {
        val menu = sharedGet(baseUrl + "xtgl/index_initMenu.html", cookie)
        if (loginPage(menu)) throw SessionExpiredException()
        val document = Jsoup.parse(menu, baseUrl)
        val href = document.select("a[href]").firstOrNull { anchor ->
            val text = anchor.text()
            val url = anchor.absUrl("href")
            text.contains("成绩查询") ||
                ((url.contains("xscjcx", ignoreCase = true) || url.contains("cjcx", ignoreCase = true)) &&
                    url.contains("grcj", ignoreCase = true))
        }?.absUrl("href")
            ?.takeIf { runCatching { it.toHttpUrl().host == "jwgl.zznu.edu.cn" }.getOrDefault(false) }
        val pageHttp = href?.toHttpUrl()
            ?: (baseUrl + "cjcx/cjcx_cxXsgrcj.html?gnmkdm=N305005").toHttpUrl()
        val pageUrl = pageHttp.toString()
        val fragmentIndex = pageUrl.indexOf('#')
        val fragment = if (fragmentIndex >= 0) pageUrl.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) pageUrl.substring(0, fragmentIndex) else pageUrl
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val parameters = if (queryIndex >= 0) {
            withoutFragment.substring(queryIndex + 1)
                .split('&')
                .filterNot { it.substringBefore('=').equals("doType", ignoreCase = true) }
                .toMutableList()
        } else {
            mutableListOf()
        }
        parameters += "doType=query"
        if (pageHttp.queryParameter("gnmkdm").isNullOrBlank()) {
            parameters += "gnmkdm=N305005"
        }
        val queryUrl = "$base?${parameters.joinToString("&")}$fragment"
        return GradeEndpoint(pageUrl, queryUrl)
    }

    private fun sharedGet(url: String, cookie: String): String = sharedClient.newCall(
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
    ).execute().use { response ->
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        response.body?.string().orEmpty()
    }

    private class SessionExpiredException : Exception()

    private suspend fun invalidateSession() {
        sessionReady = false
        cookieJar.clear()
        sessionStore?.clear()
    }

    /** 老版本缺少学号时，从已登录的个人信息页补抓。 */
    suspend fun fetchStudentIdWithSession(): String? = withContext(Dispatchers.IO) {
        val cookie = if (sessionReady) {
            cookieJar.header("jwgl.zznu.edu.cn").ifBlank { sessionStore?.cookieHeader().orEmpty() }
        } else {
            sessionStore?.cookieHeader().orEmpty()
        }
        if (cookie.isBlank()) return@withContext null
        val body = runCatching { sharedGet(baseUrl + "xsxxxggl/xsxxwh_cxCkDgxsxx.html?gnmkdm=N100801", cookie) }
            .getOrNull() ?: return@withContext null
        if (loginPage(body)) {
            invalidateSession()
            return@withContext null
        }
        val jsonId = runCatching { JSONObject(body) }.getOrNull()?.let { json ->
            listOf("xh", "xs_id", "studentId", "student_id").firstOrNull { json.optString(it).isNotBlank() }
                ?.let(json::optString)
        }
        if (!jsonId.isNullOrBlank()) return@withContext jsonId.trim()
        val document = Jsoup.parse(body, baseUrl)
        document.select("label").firstOrNull { it.text().trim().trimEnd('：', ':') == "学号" }
            ?.parent()?.select("p, span, div")
            ?.map { it.text().trim() }
            ?.firstOrNull { it.matches(Regex("\\d{4,32}")) }
    }

    fun clearSession() {
        sessionReady = false
        cookieJar.clear()
    }

    private fun inputValue(html: String, name: String): String? {
        val tag = Regex("""<input[^>]*name=["']$name["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return null
        val value = Regex(
            """value\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        ).find(tag) ?: return null
        return value.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun encryptPassword(password: String, modulusB64: String, exponentB64: String): String {
        val modulus = java.math.BigInteger(1, Base64.getDecoder().decode(modulusB64))
        val exponent = java.math.BigInteger(1, Base64.getDecoder().decode(exponentB64))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val bucket = store.getOrPut(url.host) { mutableListOf() }
            // 合并而不是覆盖：登录过程中 JSESSIONID 和 route 可能分两次下发。
            for (cookie in cookies) {
                bucket.removeAll { it.name == cookie.name && it.path == cookie.path }
                bucket += cookie
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()

        fun header(host: String): String = store[host].orEmpty().joinToString("; ") { "${it.name}=${it.value}" }

        fun clear() = store.clear()
    }
}
