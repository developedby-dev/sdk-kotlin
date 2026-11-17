package dev.developedby.sdk

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun at(sec: Long) = WallClock { sec * 1000 }

class WebhookTest {
    // 안내서 11장의 서명 예시 — 문서와 SDK가 같은 값으로 맞는다(TS · Go · .NET 시험과 같은 값).
    private val secret = "whsec_/MU12AiKByBoT7xt+p81N/uKdp/WxQRb"
    private val id = "evt_01J9MC4D6E8F0G2H4J6K8M0N2P"
    private val sent = 1791295205L
    private val body =
        """{"id":"evt_01J9MC4D6E8F0G2H4J6K8M0N2P","type":"build.review_completed","occurredAt":"2026-10-06T14:00:00Z","mode":"LIVE","simulated":false,"data":{"build":{"id":"build_01J9M4D6F8H0J2K4M6N8P0R2S4","productId":"prod_01J9KZ3F7W2X8YQ4N6M5R1TBCD","version":"1.4.2","reviewedAt":"2026-10-06T13:58:00Z","result":"APPROVED"}}}"""
    private val signature = "v1,vmsiq2HY4gik/WNJjXJzuESzXAjJgr8Vi13SKwwhgEo="

    private fun headers(sig: String) =
        mapOf(
            "Webhook-Id" to id,
            "Webhook-Timestamp" to sent.toString(),
            "Webhook-Signature" to sig,
        )

    private fun rejects(code: String, verify: () -> Unit) =
        assertEquals(code, assertThrows<BydevException>(verify).code)

    @Test
    fun guideExample() {
        assertEquals(
            WebhookVerification(id, sent),
            Webhooks.verify(listOf(secret), headers(signature), body, at(sent + 10)),
        )
        assertEquals(
            id,
            Webhooks.verify(listOf(secret), headers(signature), body.toByteArray(), at(sent)).id,
        )
    }

    @Test
    fun rotation() {
        // 서명 여러 개 · 시크릿 여러 개 중 하나라도 맞으면
        Webhooks.verify(listOf(secret), headers("v1,AAAA $signature"), body, at(sent))
        Webhooks.verify(
            listOf("whsec_AAAAAAAAAAAAAAAAAAAAAAAA", secret),
            headers(signature),
            body,
            at(sent),
        )
    }

    @Test
    fun rejectsTamperingReplayAndMissingHeaders() {
        val secrets = listOf(secret)
        rejects(Codes.WEBHOOK_INVALID) {
            Webhooks.verify(secrets, headers(signature), "$body ", at(sent))
        }
        rejects(Codes.WEBHOOK_INVALID) {
            Webhooks.verify(secrets, headers(signature), body, at(sent + 301))
        }
        rejects(Codes.WEBHOOK_INVALID) {
            Webhooks.verify(secrets, headers(signature), body, at(sent - 301))
        }
        rejects(Codes.WEBHOOK_INVALID) {
            Webhooks.verify(secrets, mapOf("webhook-id" to id), body, at(sent))
        }
        rejects(Codes.WEBHOOK_INVALID) {
            Webhooks.verify(secrets, headers("v2," + signature.drop(3)), body, at(sent))
        }
        // 접두사 없는 시크릿은 설정 실수
        rejects(Codes.CONFIGURATION) {
            Webhooks.verify(
                listOf("/MU12AiKByBoT7xt+p81N/uKdp/WxQRb"),
                headers(signature),
                body,
                at(sent),
            )
        }
    }
}

/** 판매자 서버의 토큰 검증 — 발견 문서 · JWKS를 받아 at+jwt를 검증한다(실제 소켓 · 기본 전송). */
class ServerVerifierTest {
    private val clientId = "cli_01J9M0Y6X4N3Q8ZD5R2KT7VCPW"
    private val now = 1791295205L
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val jwksHits = AtomicInteger()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val base = "http://127.0.0.1:${server.address.port}"

    private fun b64(bytes: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    init {
        server.createContext("/") { exchange ->
            val (status, body) =
                when (exchange.requestURI.path) {
                    "/.well-known/openid-configuration" ->
                        200 to
                            """{"issuer":"$base","jwks_uri":"$base/.well-known/jwks.json","device_authorization_endpoint":"$base/v1/oauth/device/code","token_endpoint":"$base/v1/oauth/token","revocation_endpoint":"$base/v1/oauth/revoke"}"""
                    "/.well-known/jwks.json" -> {
                        jwksHits.incrementAndGet()
                        val x = b64(keys.public.encoded.takeLast(32).toByteArray())
                        200 to
                            """{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"srv-k1","use":"sig","alg":"EdDSA","x":"$x"}]}"""
                    }
                    else -> 404 to """{"code":"PRODUCT_NOT_FOUND","message":"없음"}"""
                }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach fun stop() = server.stop(0)

    private fun mint(kid: String, aud: String): String {
        val head = """{"alg":"EdDSA","typ":"at+jwt","kid":"$kid"}"""
        val claims =
            """{"iss":"$base","sub":"usr_01J9M0Y6X4N3Q8ZD5R2KT7VCPW","aud":"$aud","client_id":"$aud","scope":"entitlement.read","iat":$now,"exp":${now + 3600},"jti":"at_srv0001"}"""
        val input = b64(head.toByteArray()) + "." + b64(claims.toByteArray())
        val sig =
            Signature.getInstance("Ed25519").run {
                initSign(keys.private)
                update(input.toByteArray())
                sign()
            }
        return "$input.${b64(sig)}"
    }

    @Test
    fun verifiesAccessTokens() {
        val verifier = ServerVerifier(base, clientId, Ports().clock(at(now)))
        val got = verifier.verifyAccessToken(mint("srv-k1", clientId))
        assertEquals("usr_01J9M0Y6X4N3Q8ZD5R2KT7VCPW" to "entitlement.read", got.sub to got.scope)
        assertEquals(
            Codes.TOKEN_INVALID,
            assertThrows<BydevException> {
                    verifier.verifyAccessToken(mint("srv-k1", "cli_other_client_00000000"))
                }
                .code,
        )
        // 모르는 kid — 1분 안에는 다시 받지 않는다
        assertEquals(
            Codes.TOKEN_INVALID,
            assertThrows<BydevException> { verifier.verifyAccessToken(mint("srv-k9", clientId)) }
                .code,
        )
        assertEquals(1, jwksHits.get())
    }
}
