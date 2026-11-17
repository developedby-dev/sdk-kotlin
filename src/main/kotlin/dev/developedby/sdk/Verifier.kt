package dev.developedby.sdk

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

// 명세 9절 — 토큰 검증 · JWKS 캐시 · 세대번호 단조 확인. Ed25519는 JDK(15 이상) 그대로.

internal class Expectation(val sid: String, val deviceId: String, val generation: Long)

internal class Verifier(
    private val api: Api,
    private val clientId: String,
    private val trackGenerations: Boolean,
) {
    private class KeySet(
        val keys: Map<String, PublicKey>,
        val fetchedAtMs: Long,
        val expiresAtMs: Long,
    )

    @Volatile private var set: KeySet? = null
    private val fetching = SingleFlight<KeySet>()
    private val generations = HashMap<String, Long>()

    private fun invalid(message: String) = BydevException(Codes.TOKEN_INVALID, message)

    private fun regressed(got: Long, seen: Long) =
        BydevException(Codes.GENERATION_REGRESSED, "세대번호 $got < 이미 본 $seen")

    /** 9절 1 ~ 4. 페이로드를 돌려준다. */
    private fun verifySigned(token: String, typ: String): JsonValue {
        val parts = token.split('.')
        if (parts.size != 3) throw invalid("JWT 형식이 아니다")
        val head = base64Url(parts[0])?.let(JsonValue::parse)
        val payload = base64Url(parts[1])?.let(JsonValue::parse)
        val sig = base64Url(parts[2])
        if (head !is JsonValue.Obj || payload !is JsonValue.Obj || sig == null)
            throw invalid("JWT를 읽지 못했다")
        if (head["alg"]?.string != "EdDSA") throw invalid("alg ${head["alg"] ?: "null"}")
        if (head["typ"]?.string != typ) throw invalid("typ ${head["typ"] ?: "null"}")
        val kid = head["kid"]?.string ?: throw invalid("kid가 없다")
        val key = keyFor(kid)
        val ok = runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(sig)
            }
        }
            .getOrDefault(false)
        if (!ok) throw invalid("서명이 맞지 않는다")
        val doc = api.discovery()
        if (payload["iss"]?.string != doc.issuer) throw invalid("iss가 다르다")
        if (payload["aud"]?.string != clientId) throw invalid("aud가 이 client_id가 아니다")
        val nowSec = api.ports.clock.nowMs() / 1000.0
        val exp = payload["exp"]?.number
        if (exp == null || exp <= nowSec - Rules.TOKEN_CLOCK_LEEWAY_SEC) throw invalid("만료된 토큰")
        return payload
    }

    fun verifyLicense(token: String, expect: Expectation?, minGeneration: Long): LicenseClaims {
        val payload = verifySigned(token, "license+jwt")
        val claims = runCatching {
            LicenseClaims.read(payload)
        }
            .getOrElse { throw invalid("클레임 모양이 다르다") }
        if (expect != null) {
            if (claims.sid != expect.sid) throw invalid("sid가 세션과 다르다")
            if (claims.deviceId != expect.deviceId) throw invalid("device_id가 세션과 다르다")
            if (claims.generation != expect.generation) throw invalid("generation이 세션과 다르다")
        }
        if (minGeneration > 0 && claims.generation < minGeneration)
            throw regressed(claims.generation, minGeneration)
        if (trackGenerations) {
            val slot = "${claims.sub}|${claims.deviceId}"
            val seen =
                synchronized(generations) {
                    val seen = generations[slot]
                    if (seen != null && claims.generation < seen) {
                        seen
                    } else {
                        generations[slot] = maxOf(seen ?: claims.generation, claims.generation)
                        null
                    }
                }
            if (seen != null) throw regressed(claims.generation, seen)
        }
        return claims
    }

    fun verifyAccess(token: String): AccessClaims {
        val payload = verifySigned(token, "at+jwt")
        return runCatching { AccessClaims.read(payload) }.getOrElse { throw invalid("클레임 모양이 다르다") }
    }

    /** 9.1절 — 지난 캐시는 쓰지 않는다 · 모르는 kid는 1분에 한 번만 다시 받는다. */
    private fun keyFor(kid: String): PublicKey {
        val now = api.ports.clock.nowMs()
        val current = set?.takeIf { now < it.expiresAtMs } ?: fetchKeys()
        current.keys[kid]?.let {
            return it
        }
        if (now - current.fetchedAtMs < Rules.JWKS_UNKNOWN_KID_REFETCH_MIN_INTERVAL_SEC * 1000L) {
            throw invalid("모르는 kid $kid")
        }
        return fetchKeys().keys[kid] ?: throw invalid("모르는 kid $kid")
    }

    private fun fetchKeys(): KeySet =
        fetching.run(api.ports.scheduler) {
            val doc = api.discovery()
            val res = api.send(Req("GET", doc.jwksUri))
            val r = Reader()
            val root = r.obj(res.json, "$")
            val keys = HashMap<String, PublicKey>()
            for (item in r.array(root["keys"], "keys")) {
                val jwk = item.obj ?: continue
                if (jwk["kty"]?.string != "OKP" || jwk["crv"]?.string != "Ed25519") continue
                val kid = jwk["kid"]?.string
                val raw = jwk["x"]?.string?.let(::base64Url)
                val key =
                    if (raw != null && raw.size == 32) runCatching { ed25519(raw) }.getOrNull()
                    else null
                if (kid.isNullOrEmpty() || key == null) {
                    api.ports.logger.warn("JWKS 키를 읽지 못했다")
                } else {
                    keys[kid] = key
                }
            }
            r.finish(Unit)
            val age =
                res.headers["cache-control"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.firstNotNullOfOrNull {
                        if (it.startsWith("max-age=")) it.removePrefix("max-age=").toLongOrNull()
                        else null
                    } ?: Rules.JWKS_DEFAULT_MAX_AGE_SEC.toLong()
            val fetched = api.ports.clock.nowMs()
            KeySet(keys, fetched, fetched + age * 1000).also { set = it }
        }

    private companion object {
        /** RFC 8410 SubjectPublicKeyInfo 머리 + 원래 32바이트 공개 키. */
        val PREFIX =
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun ed25519(raw: ByteArray): PublicKey =
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(PREFIX + raw))
    }
}
