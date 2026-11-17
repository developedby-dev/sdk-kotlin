package dev.developedby.sdk

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** 검증된 웹훅의 id와 시각이다. 중복 수신은 id로 거른다. */
public data class WebhookVerification(public val id: String, public val timestamp: Long)

/** 명세 12절 — 판매자 서버의 웹훅 서명 검증(안내서 11장 · Standard Webhooks v1). */
public object Webhooks {
    /**
     * 서명을 검증한다. [rawBody]는 받은 그대로의 본문이다 — 파싱했다가 다시 만든 바이트는 서명이 맞지 않는다. 시크릿을 교체하는 중이면 둘 다 넘긴다. 헤더
     * 이름의 대소문자는 가리지 않는다. [clock]을 주지 않으면 시스템 시계.
     */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        secrets: List<String>,
        headers: Map<String, String>,
        rawBody: ByteArray,
        clock: WallClock = SystemClock,
    ): WebhookVerification {
        fun header(name: String) =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val id = header("webhook-id")
        val timestamp = header("webhook-timestamp")
        val signatures = header("webhook-signature")
        if (id.isNullOrEmpty() || timestamp.isNullOrEmpty() || signatures.isNullOrEmpty()) {
            throw BydevException(
                Codes.WEBHOOK_INVALID,
                "웹훅 헤더(webhook-id · webhook-timestamp · webhook-signature)가 없다",
            )
        }
        val sent = timestamp.toLongOrNull()
        if (sent == null || abs(clock.nowMs() / 1000.0 - sent) > Rules.WEBHOOK_TOLERANCE_SEC) {
            throw BydevException(Codes.WEBHOOK_INVALID, "웹훅 시각이 허용 범위 밖이다 — 재전송 공격일 수 있다")
        }
        val candidates =
            signatures
                .split(' ')
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val comma = entry.indexOf(',')
                    if (comma < 0 || entry.substring(0, comma) != Rules.WEBHOOK_SIGNATURE_VERSION)
                        return@mapNotNull null
                    runCatching { Base64.getDecoder().decode(entry.substring(comma + 1)) }
                        .getOrNull()
                }
        val signed = "$id.$timestamp.".toByteArray(Charsets.UTF_8) + rawBody
        for (secret in secrets) {
            if (!secret.startsWith(Rules.WEBHOOK_SECRET_PREFIX)) {
                throw BydevException(
                    Codes.CONFIGURATION,
                    "웹훅 시크릿은 ${Rules.WEBHOOK_SECRET_PREFIX}로 시작한다",
                )
            }
            val key = runCatching {
                Base64.getDecoder().decode(secret.removePrefix(Rules.WEBHOOK_SECRET_PREFIX))
            }
                .getOrElse {
                    throw BydevException(Codes.CONFIGURATION, "웹훅 시크릿을 읽지 못했다", cause = it)
                }
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            val expected = mac.doFinal(signed)
            if (candidates.any { MessageDigest.isEqual(it, expected) })
                return WebhookVerification(id, sent)
        }
        throw BydevException(Codes.WEBHOOK_INVALID, "웹훅 서명이 맞지 않는다")
    }

    /** 문자열 본문(UTF-8)으로. */
    @JvmStatic
    @JvmOverloads
    public fun verify(
        secrets: List<String>,
        headers: Map<String, String>,
        rawBody: String,
        clock: WallClock = SystemClock,
    ): WebhookVerification = verify(secrets, headers, rawBody.toByteArray(Charsets.UTF_8), clock)
}

/** 판매자 서버의 토큰 검증이다(안내서 09장) — JWKS를 받아 캐시한다. 인스턴스 하나를 오래 쓴다. [ports]는 전송 · 시계만 쓴다. */
public class ServerVerifier
@JvmOverloads
public constructor(baseUrl: String, clientId: String, ports: Ports = Ports()) {
    private val verifier =
        Verifier(Api(baseUrl, ports.resolve(), LicenseClient.USER_AGENT), clientId, false)

    /** access_token(at+jwt)을 검증한다. */
    public fun verifyAccessToken(token: String): AccessClaims = verifier.verifyAccess(token)

    /** licenseToken을 검증한다. minGeneration — 같은 sub · device_id에서 이미 본 가장 큰 세대번호(0이면 보지 않는다). */
    @JvmOverloads
    public fun verifyLicenseToken(token: String, minGeneration: Long = 0): LicenseClaims =
        verifier.verifyLicense(token, null, minGeneration)
}
