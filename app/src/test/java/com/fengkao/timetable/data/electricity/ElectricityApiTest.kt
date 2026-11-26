package com.fengkao.timetable.data.electricity

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

class ElectricityApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ElectricityApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = ElectricityApi(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsExpectedPostForm() {
        val response = """{"body":"{\"roomlist\":[]}"}"""
        server.enqueue(MockResponse().setBody(response))

        assertEquals(response, api.fetch("20260001"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/smartWaterAndElectricityService/SWAEServlet", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val form = formBody(request.body.readUtf8())
        assertEquals("getbindroom", form["method"])
        assertEquals(SCHOOL_CODE_ZZNU, form["customercode"])
        val param = JSONObject(form.getValue("param"))
        assertEquals("getbindroom", param.getString("cmd"))
        assertEquals("20260001", param.getString("account"))
    }

    @Test
    fun placeholderAccountsAreTreatedAsMissing() {
        assertEquals("", ElectricityAccount.normalize("网页导入"))
        assertEquals("", ElectricityAccount.normalize("123"))
        assertEquals("20260001", ElectricityAccount.normalize(" 20260001 "))
    }

    @Test
    fun httpFailureDoesNotExposeResponseBody() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("server-secret"))

        val error = assertThrows(IOException::class.java) { api.fetch("20260001") }

        assertFalse(error.message.orEmpty().contains("server-secret"))
        assertFalse(error.message.orEmpty().contains("20260001"))
    }

    @Test
    fun blankResponseIsRejected() {
        server.enqueue(MockResponse().setBody(""))

        assertThrows(IOException::class.java) { api.fetch("20260001") }
    }

    private fun formBody(raw: String): Map<String, String> = raw.split('&').associate { part ->
        val pieces = part.split('=', limit = 2)
        URLDecoder.decode(pieces[0], "UTF-8") to
            URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
    }
}
