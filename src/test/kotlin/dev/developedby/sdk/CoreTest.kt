package dev.developedby.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CoreTest {
    /** 명세 3.4 — 일시적 실패 분류. 상태가 빈 칸이면 없음. */
    @ParameterizedTest
    @CsvSource(
        "RATE_LIMITED, 429, true",
        "INTERNAL_ERROR, 503, true",
        "NETWORK_ERROR, , true",
        // 429인 한도 초과 코드는 끝난 실패다
        "TAKEOVER_LIMIT_EXCEEDED, 429, false",
        "DEVICE_RELEASE_LIMIT_EXCEEDED, 429, false",
        // 모르는 코드만 HTTP 상태로
        "FUTURE_THROTTLE, 429, true",
        "FUTURE_OUTAGE, 502, true",
        "FUTURE_FORBIDDEN, 403, false",
        "FUTURE_NOTHING, , false",
        "STORAGE_FAILED, , false",
    )
    fun transient(code: String, status: Int?, want: Boolean) {
        assertEquals(want, BydevException(code, "시험", status).isTransient)
    }

    /** 키는 언제나 다 있다 — 없는 값은 null. */
    @Test
    fun errorJson() {
        val e = BydevException(Codes.RATE_LIMITED, "잠시 뒤에", 429, "req_err0001", 30)
        assertEquals(
            """{"code":"RATE_LIMITED","correlationId":"req_err0001","details":null,"httpStatus":429,"message":"잠시 뒤에","retryAfterSec":30}""",
            e.toJson(),
        )
    }

    @Test
    fun jsonReadsAndWrites() {
        val v =
            JsonValue.parse(
                """{"a":[1,2.5,-3e2,true,null],"s":"줄\n\"\\\u00e9\ud83d\ude00","o":{}}"""
            )!!
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"]!!.array!!.take(3).map { it.number })
        assertEquals("줄\n\"\\é😀", v["s"]!!.string)
        assertEquals("""{"a":[1,2.5,-300,true,null],"o":{},"s":"줄\n\"\\é😀"}""", v.toJson())
        for (bad in
            listOf("", "{", "[1,]", "{\"a\" 1}", "01", "\"\u0001\"", "nul", "1 2")) assertNull(
            JsonValue.parse(bad),
            bad,
        )
    }

    @Test
    fun isoTimeRoundTrip() {
        assertEquals(1_791_291_600_000L, IsoTime.parse("2026-10-06T13:00:00Z"))
        assertEquals("2026-10-06T13:00:00Z", IsoTime.format(1_791_291_600_000L))
        assertEquals("2026-10-06T13:00:00.250Z", IsoTime.format(1_791_291_600_250L))
        assertEquals(1_791_291_600_500L, IsoTime.parse("2026-10-06T22:00:00.5+09:00"))
        assertEquals(0L, IsoTime.parse("1970-01-01T00:00:00Z"))
        assertEquals("2000-02-29T00:00:00Z", IsoTime.format(951_782_400_000L))
        assertNull(IsoTime.parse("2026-10-06 13:00:00Z"))
        assertNull(IsoTime.parse("2026-10-06T13:00:00"))
    }

    /** 빠진 필드 · 틀린 형은 PROTOCOL_ERROR — 모르는 필드는 버린다. */
    @Test
    fun lenientButStrictReading() {
        val ok =
            Profile.read(
                JsonValue.parse(
                    """{"profile":{"pseudonymousId":"psub_1","nickname":"코드장인","extra":1}}"""
                )!!
            )
        assertEquals(Profile("psub_1", "코드장인", null), ok)
        val e =
            assertThrows<BydevException> {
                Profile.read(JsonValue.parse("""{"profile":{"nickname":"코드장인"}}""")!!)
            }
        assertEquals(Codes.PROTOCOL_ERROR, e.code)
    }

    private fun entitlement(coverage: String) =
        Entitlement.read(
            JsonValue.parse(
                """{"entitlement":{"id":"ent_1","productId":"prod_1","status":"ACTIVE",
                "term":{"type":"PERPETUAL","expiresAt":null,"remainingDays":null,"remainingSeconds":null},
                $coverage "subscription":null,
                "policy":{"maxDevices":3,"maxConcurrent":1,"heartbeatSec":150,"offlineGraceHours":0},
                "evaluatedAt":"2026-10-06T14:00:00Z"}}"""
            )!!
        )

    /** ADR-0049 — 업데이트 제공 범위. 모르는 방식은 이름만 남기고 다른 키는 버린다. 없으면 다른 필수 필드처럼 PROTOCOL_ERROR. */
    @Test
    fun updateCoverage() {
        assertEquals(
            UpdateCoverage.All,
            entitlement(""""updateCoverage":{"type":"ALL"},""").updateCoverage,
        )
        assertEquals(
            UpdateCoverage.Until("2027-11-12T03:00:00Z"),
            entitlement(""""updateCoverage":{"type":"UNTIL","until":"2027-11-12T03:00:00Z"},""")
                .updateCoverage,
        )
        val other = entitlement(""""updateCoverage":{"type":"BY_LINE","line":"2.x"},""")
        assertEquals(UpdateCoverage.Other("BY_LINE"), other.updateCoverage)
        assertEquals("""{"type":"BY_LINE"}""", other.updateCoverage.toJson())
        for (bad in listOf("", """"updateCoverage":{"type":"UNTIL"},""")) {
            assertEquals(
                Codes.PROTOCOL_ERROR,
                assertThrows<BydevException> { entitlement(bad) }.code,
                bad,
            )
        }
    }

    /** ADR-0049 — 확정한 빌드는 선택 클레임이다. 없으면 null이고 JSON에서도 빠진다. */
    @Test
    fun claimsBuildId() {
        val base =
            """"iss":"i","sub":"s","aud":"a","product_id":"p","device_id":"d","sid":"x","generation":1,"iat":1,"exp":2"""
        val without = LicenseClaims.read(JsonValue.parse("{$base}")!!)
        assertNull(without.buildId)
        assertNull(without.toJsonValue()["build_id"])
        val built = LicenseClaims.read(JsonValue.parse("""{$base,"build_id":"build_1"}""")!!)
        assertEquals("build_1", built.buildId)
        assertEquals(built, LicenseClaims.read(built.toJsonValue()))
    }
}
