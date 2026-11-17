package dev.developedby.sdk

// 명세 3.5 · 3.6 · 6절 — 기록 보관 · 갱신 · 인증 요청 감싸기 · 로그아웃.

internal class TokenManager(val api: Api, private val clientId: String) {
    private class AccessToken(val token: String, val expiresAtMs: Long)

    @Volatile private var access: AccessToken? = null
    private val refreshing = SingleFlight<String>()

    fun load(): CredentialRecord? {
        val record =
            try {
                api.ports.store.load()
            } catch (e: Exception) {
                throw BydevException.storage(e, "보안 저장소를 읽지 못했다")
            }
        return record?.takeIf { it.version == Rules.STORAGE_RECORD_VERSION }
    }

    /** 6절 3 · 5절 — 저장한 뒤에 쓴다. */
    fun adopt(set: TokenSet) {
        access = null
        val ports = api.ports
        val record =
            CredentialRecord(1, set.refreshToken, set.scope, IsoTime.format(ports.clock.nowMs()))
        try {
            ports.store.save(record)
        } catch (e: Exception) {
            throw BydevException.storage(e, "새 로그인을 보안 저장소에 두지 못했다 — 새 토큰을 쓰지 않는다")
        }
        access = AccessToken(set.accessToken, ports.clock.nowMs() + set.expiresIn * 1000)
    }

    private fun accessToken(): String {
        access?.let {
            if (it.expiresAtMs - Rules.TOKEN_REFRESH_SKEW_SEC * 1000L > api.ports.clock.nowMs())
                return it.token
        }
        return refresh()
    }

    /** 6절 1 — 한 번에 하나. */
    fun refresh(): String {
        access = null
        return refreshing.run(api.ports.scheduler) { doRefresh() }
    }

    private fun doRefresh(): String {
        val record = load() ?: throw BydevException(Codes.NOT_SIGNED_IN, "로그인이 없다 — 먼저 로그인한다")
        val doc = api.discovery()
        var attempt = 0
        while (true) {
            val body =
                JsonValue.obj(
                    "grant_type" to JsonValue.Str("refresh_token"),
                    "client_id" to JsonValue.Str(clientId),
                    "refresh_token" to JsonValue.Str(record.refreshToken),
                )
            try {
                val set = TokenSet.read(api.send(Req("POST", doc.tokenEndpoint, body)).json)
                adopt(set)
                return set.accessToken
            } catch (e: BydevException) {
                if (e.code == Codes.INVALID_GRANT) {
                    forgetStored()
                    throw e
                }
                if (!e.isTransient || attempt >= Rules.TOKEN_REFRESH_RETRY_DELAYS_SEC.size) throw e
                api.ports.scheduler.sleep(seconds(Rules.TOKEN_REFRESH_RETRY_DELAYS_SEC[attempt]))
                attempt++
            }
        }
    }

    /** 3.5절 — 만료면 한 번만 갱신하고 다시 보낸다. */
    fun authed(send: (String) -> ApiResponse): ApiResponse {
        val token = accessToken()
        try {
            return send(token)
        } catch (e: BydevException) {
            if (e.code != Codes.EXPIRED_TOKEN) throw e
        }
        val fresh = refresh()
        try {
            return send(fresh)
        } catch (e: BydevException) {
            // 갓 받은 토큰도 거절됐다 — 다음 요청이 같은 토큰을 다시 쓰지 않게 버린다
            if (e.code == Codes.EXPIRED_TOKEN) access = null
            throw e
        }
    }

    private fun forgetStored() {
        access = null
        try {
            api.ports.store.clear()
        } catch (_: Exception) {
            api.ports.logger.warn("끝난 로그인의 기록을 지우지 못했다")
        }
    }

    /** 7절 — 폐기 요청은 한 번, 결과와 상관없이 지운다. 서버가 폐기했는지를 돌려준다. */
    fun logout(): Boolean {
        val record = load()
        var revoked = false
        if (record != null) {
            val doc = runCatching { api.discovery() }.getOrNull()
            if (doc != null) {
                val body =
                    JsonValue.obj(
                        "client_id" to JsonValue.Str(clientId),
                        "token" to JsonValue.Str(record.refreshToken),
                        "token_type_hint" to JsonValue.Str("refresh_token"),
                    )
                revoked =
                    runCatching { api.send(Req("POST", doc.revocationEndpoint, body)) }.isSuccess
            }
        }
        access = null
        try {
            api.ports.store.clear()
        } catch (e: Exception) {
            throw BydevException.storage(e, "로그인 기록을 지우지 못했다")
        }
        return revoked
    }
}
