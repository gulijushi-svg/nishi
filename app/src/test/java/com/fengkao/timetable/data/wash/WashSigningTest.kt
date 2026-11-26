package com.fengkao.timetable.data.wash

import org.junit.Assert.assertEquals
import org.junit.Test

class WashSigningTest {
    @Test fun signatureMatchesOriginalClientOrder() {
        // 拼接顺序：appSecret、channel、timestamp、token、version、path。
        assertEquals(
            "2ae2e15010c05cb6088a967118df5d03a5891f83c392f6b622ad62fff61e0dd6",
            washSignature("/goods/latestUsed", "token-123", "1700000000000"),
        )
    }
}
