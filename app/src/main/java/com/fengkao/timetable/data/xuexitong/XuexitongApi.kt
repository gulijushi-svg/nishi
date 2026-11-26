package com.fengkao.timetable.data.xuexitong

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class XuexitongLoginResult(val ok: Boolean, val message: String)

class XuexitongSessionExpired(message: String = "学习通登录已失效，请重新登录") : IOException(message)

/**
 * 学习通（超星）只读接口：手机号/账号 + 密码登录，以及通知中心列表。
 *
 * 协议参考 DanMo661/Xuexitong-mcp（MIT）：登录走 passport2.chaoxing.com/fanyalogin，
 * 通知走 notice.chaoxing.com/pc/notice/getNoticeList，列表正文 content 即全文。
 */
class XuexitongApi(
    private val passportBase: String = "https://passport2.chaoxing.com",
    private val noticeBase: String = "https://notice.chaoxing.com",
    private val scheduleBase: String = "https://kb.chaoxing.com",
    private val cookieJar: XuexitongCookieJar,
    client: OkHttpClient? = null,
) {

    private val http: OkHttpClient = (client ?: OkHttpClient())
        .newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun login(account: String, password: String): XuexitongLoginResult {
        val form = FormBody.Builder()
            .add("fid", "-1")
            .add("uname", XuexitongCrypto.encrypt(account))
            .add("password", XuexitongCrypto.encrypt(password))
            .add("refer", "https%3A%2F%2Fi.chaoxing.com")
            .add("t", "true")
            .add("forbidotherlogin", "0")
            .add("validate", "")
            .add("doubleFactorLogin", "0")
            .add("independentId", "0")
            .build()
        val request = Request.Builder()
            .url(passportBase.trimEnd('/') + "/fanyalogin")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return XuexitongLoginResult(false, "登录服务暂时不可用（${response.code}）")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return XuexitongLoginResult(
                    false,
                    if (body.trimStart().startsWith("<")) "学习通触发了安全验证，请稍后重试或先在官方 App 登录一次"
                    else "登录响应异常，请检查网络",
                )
            val ok = json.optBoolean("status", false)
            val message = json.optString("msg2").ifBlank {
                if (ok) "登录成功" else "账号或密码错误"
            }
            return XuexitongLoginResult(ok, message)
        }
    }

    /** 读取最近的通知（默认最多两页），列表已按服务端顺序倒序返回。 */
    fun fetchNotices(maxPages: Int = 2): List<XuexitongNotice> {
        val result = mutableListOf<XuexitongNotice>()
        var lastId = ""
        var page = 0
        while (page < maxPages.coerceAtLeast(1)) {
            val form = FormBody.Builder()
                .add("type", "0")
                .add("notice_type", "0")
                .add("lastValue", lastId)
                .add("sort", "0")
                .add("folderUUID", "")
                .add("kw", "")
                .add("startTime", "")
                .add("endTime", "")
                .add("gKw", "")
                .add("gName", "")
                .add("year", "")
                .add("tag", "")
                .add("fidsCode", "")
                .add("filterSenderPuids", "")
                .add("filterTags", "")
                .build()
            val request = Request.Builder()
                .url(noticeBase.trimEnd('/') + "/pc/notice/getNoticeList")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://notice.chaoxing.com/pc/notice/myNotice")
                .post(form)
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.request.url.host.contains("passport") ||
                    body.trimStart().startsWith("<")
                ) {
                    throw XuexitongSessionExpired()
                }
                if (!response.isSuccessful) throw IOException("通知服务暂时不可用（${response.code}）")
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: throw IOException("通知接口返回异常")
                if (!json.optBoolean("status", false)) {
                    val message = json.optString("msg")
                    if (message.contains("登录") || message.contains("失效") || message.contains("未授权")) {
                        throw XuexitongSessionExpired()
                    }
                    throw IOException(message.ifBlank { "通知接口返回失败" })
                }
                val notices = json.optJSONObject("notices") ?: JSONObject()
                val list = notices.optJSONArray("list") ?: JSONArray()
                for (i in 0 until list.length()) {
                    list.optJSONObject(i)?.let { result += parseNotice(it) }
                }
                val next = notices.optString("lastGetId")
                val lastPage = notices.optBoolean("lastPage", false)
                page++
                if (lastPage || next.isBlank() || next == lastId || list.length() == 0) {
                    lastId = ""
                    page = maxPages
                } else {
                    lastId = next
                }
            }
        }
        return result.distinctBy { it.id }
    }

    /**
     * 学习通课程表。接口不传 week 只返回当前周；传 week 返回指定周。
     * 课程字段：name/location/teacherName/dayOfWeek(1..7)/beginNumber/length/weeks。
     */
    fun fetchSchedule(week: Int? = null): XuexitongTimetable {
        val form = FormBody.Builder().apply {
            if (week != null) add("week", week.toString())
        }.build()
        val request = Request.Builder()
            .url(scheduleBase.trimEnd('/') + "/pc/curriculum/getMyLessons")
            .header("User-Agent", USER_AGENT)
            .header("Referer", scheduleBase.trimEnd('/') + "/pc/curriculum/index")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.request.url.host.contains("passport") || body.trimStart().startsWith("<")) {
                throw XuexitongSessionExpired()
            }
            if (!response.isSuccessful) throw IOException("学习通课表服务暂时不可用（${response.code}）")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IOException("学习通课表接口返回异常")
            val result = json.optInt("result", -1)
            if (result != 1) {
                val message = json.optString("msg")
                if (message.contains("登录") || message.contains("失效") || message.contains("未授权")) {
                    throw XuexitongSessionExpired()
                }
                throw IOException(message.ifBlank { "学习通课表接口返回失败" })
            }
            val data = json.optJSONObject("data") ?: JSONObject()
            val curriculum = data.optJSONObject("curriculum") ?: JSONObject()
            val lessonArray = data.optJSONArray("lessonArray") ?: JSONArray()
            val lessons = buildList {
                for (i in 0 until lessonArray.length()) {
                    lessonArray.optJSONObject(i)?.let { item ->
                        val parsed = parseLesson(item, week)
                        if (parsed.name.isNotBlank()) add(parsed)
                    }
                }
            }
            return XuexitongTimetable(
                schoolYear = clean(curriculum.optString("schoolYear")),
                semester = clean(curriculum.optString("semester")),
                userName = clean(curriculum.optString("userName")),
                className = clean(curriculum.optString("className")).ifBlank {
                    clean(curriculum.optString("class_name"))
                },
                firstWeekDate = longValue(curriculum, "firstWeekDate").takeIf { it > 0 }
                    ?: longValue(curriculum, "firstWeekDateReal"),
                currentWeek = intValue(curriculum, "realCurrentWeek").takeIf { it > 0 }
                    ?: intValue(curriculum, "currentWeek"),
                maxWeek = intValue(curriculum, "maxWeek").takeIf { it > 0 } ?: 20,
                sectionTimes = parseSectionTimes(curriculum),
                lessons = lessons,
            )
        }
    }

    private fun parseLesson(item: JSONObject, fallbackWeek: Int?): XuexitongLesson {
        val begin = intValue(item, "beginNumber").takeIf { it > 0 } ?: 1
        val length = intValue(item, "length").takeIf { it > 0 } ?: 1
        val parsedWeeks = parseWeeks(clean(item.optString("weeks")))
            .ifEmpty { fallbackWeek?.let { setOf(it) } ?: emptySet() }
        return XuexitongLesson(
            lessonId = clean(item.optString("lessonId")).ifBlank { clean(item.optString("id")) },
            name = clean(item.optString("name")).ifBlank { clean(item.optString("courseName")) },
            location = clean(item.optString("location")),
            teacher = clean(item.optString("teacherName")),
            dayOfWeek = intValue(item, "dayOfWeek").takeIf { it in 1..7 } ?: 1,
            beginNumber = begin,
            length = length,
            weeks = parsedWeeks,
        )
    }

    private fun parseSectionTimes(curriculum: JSONObject): List<com.fengkao.timetable.data.model.PeriodTime> {
        val array = curriculum.optJSONArray("lessonTimeConfigArray") ?: return emptyList()
        val pattern = Regex("""(\d{1,2}):(\d{2})\s*[-–]\s*(\d{1,2}):(\d{2})""")
        return buildList {
            for (i in 0 until array.length()) {
                val match = pattern.find(array.optString(i)) ?: continue
                val values = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
                if (values.size == 4) {
                    add(
                        com.fengkao.timetable.data.model.PeriodTime(
                            period = i + 1,
                            startHour = values[0],
                            startMinute = values[1],
                            endHour = values[2],
                            endMinute = values[3],
                        )
                    )
                }
            }
        }
    }

    private fun parseWeeks(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()
        val out = sortedSetOf<Int>()
        raw.split(',', '，', ';', '；').forEach { part ->
            val text = part.trim()
            val range = Regex("""^(\d+)\s*-\s*(\d+)$""").find(text)
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return@forEach
                val end = range.groupValues[2].toIntOrNull() ?: return@forEach
                if (start <= end) out += start..end
            } else {
                Regex("""\d+""").findAll(text).forEach { out += it.value.toInt() }
            }
        }
        return out.filter { it > 0 }.toSet()
    }

    private fun intValue(item: JSONObject, key: String): Int = when (val value = item.opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: 0
        null -> 0
        else -> 0
    }

    private fun longValue(item: JSONObject, key: String): Long = when (val value = item.opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: 0L
        null -> 0L
        else -> 0L
    }

    private fun clean(value: String?): String =
        value?.takeUnless { it == "null" }?.trim().orEmpty()

    private fun parseNotice(item: JSONObject): XuexitongNotice {
        val title = NoticeFormat.stripHtml(text(item, "title"))
        val content = NoticeFormat.stripHtml(text(item, "content"))
        val sender = NoticeFormat.stripHtml(text(item, "createrName"))
        val timeText = text(item, "completeTime")
        val id = listOf("idCode", "uuid", "id", "noticeId", "noticeid", "notice_id", "noticeID")
            .map { text(item, it) }
            .firstOrNull { it.isNotBlank() }
            ?: hashOf("$title|$timeText|$sender")
        val attachment = parseAttachment(text(item, "attachment"))
        return XuexitongNotice(
            id = id,
            title = title,
            content = content,
            sender = sender,
            timeText = timeText,
            timeMillis = NoticeFormat.parseTime(timeText),
            serverUnread = isUnread(item),
            category = NoticeFormat.classify(title, content),
            deadline = NoticeFormat.extractDeadline(title, content),
            attachmentName = attachment?.name.orEmpty(),
            attachmentUrl = attachment?.url.orEmpty(),
            attachmentKind = attachment?.kind.orEmpty(),
            attachmentSize = attachment?.size.orEmpty(),
        )
    }

    private data class Attachment(
        val name: String,
        val url: String,
        val kind: String,
        val size: String,
    )

    private fun parseAttachment(raw: String): Attachment? {
        if (raw.isBlank() || raw == "[]" || raw == "null") return null
        val first = runCatching {
            when {
                raw.trimStart().startsWith("[") -> JSONArray(raw).optJSONObject(0)
                else -> JSONObject(raw)
            }
        }.getOrNull() ?: return null
        val web = first.optJSONObject("att_web")
        val disk = first.optJSONObject("att_clouddisk")
        return when {
            web != null -> Attachment(
                name = web.optString("title").ifBlank { web.optString("atypeName") },
                url = web.optString("url"),
                kind = web.optString("examOrWork").ifBlank { "work" },
                size = "",
            )
            disk != null -> Attachment(
                name = disk.optString("name"),
                url = disk.optString("downPath"),
                kind = "file",
                size = disk.optString("fileSize"),
            )
            else -> null
        }
    }

    private fun isUnread(item: JSONObject): Boolean {
        val value = item.opt("isread") ?: return true
        if (value == JSONObject.NULL) return true
        return when (value) {
            is Boolean -> !value
            is Number -> value.toInt() == 0
            is String -> value == "0" || value.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun text(item: JSONObject, key: String): String {
        val value = item.opt(key) ?: return ""
        return if (value == JSONObject.NULL) "" else value.toString()
    }

    private fun hashOf(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/118.0.0.0 Mobile Safari/537.36"
    }
}
