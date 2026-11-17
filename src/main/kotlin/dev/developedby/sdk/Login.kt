package dev.developedby.sdk

/** 로그인이 끝났을 때 받은 스코프다. */
public data class LoginResult(public val scope: String) : BydevModel {
    override fun toJsonValue(): JsonValue = JsonValue.obj("scope" to JsonValue.Str(scope))
}

/**
 * 디바이스 플로우 하나다(명세 5절). 화면에 `userCode` · `verificationUriComplete`(QR)를 보이고 [await]로 기다린다. 코드를 받은
 * 순간부터 폴링한다.
 */
public class LoginAttempt
internal constructor(
    code: DeviceCode,
    private val clientId: String,
    private val api: Api,
    private val tokens: TokenManager,
) {
    public val userCode: String = code.userCode
    public val verificationUri: String = code.verificationUri
    public val verificationUriComplete: String = code.verificationUriComplete
    private val expiresAtMs: Long = api.ports.clock.nowMs() + code.expiresIn * 1000

    /** 코드가 만료되는 시각(ISO 8601). */
    public val expiresAt: String = IsoTime.format(expiresAtMs)

    private val deviceCode = code.deviceCode
    private val done = Signal()
    private val lock = Any()
    private var interval = code.interval
    private var settled = false
    private var handle: Cancel? = null
    private var result: Result<LoginResult>? = null

    /**
     * 로그인이 끝나기를 기다린다(블로킹). 실패는 [BydevException](`EXPIRED_TOKEN` · `INVALID_GRANT` ·
     * `LOGIN_CANCELLED` · `STORAGE_FAILED` …). 이름이 `wait`가 아닌 것은 `Object.wait`와 겹치지 않게다.
     */
    public fun await(): LoginResult {
        api.ports.scheduler.wait(done)
        return (synchronized(lock) { result } ?: Result.failure(cancelled())).getOrThrow()
    }

    /** 로그인을 취소한다 — [await]는 `LOGIN_CANCELLED`로 끝난다. */
    public fun cancel(): Unit = finish(Result.failure(cancelled()))

    private fun cancelled() = BydevException(Codes.LOGIN_CANCELLED, "로그인을 취소했다")

    private fun finish(outcome: Result<LoginResult>) {
        val pending =
            synchronized(lock) {
                if (settled) return
                settled = true
                result = outcome
                handle.also { handle = null }
            }
        pending?.cancel()
        done.fire()
    }

    private val isSettled: Boolean
        get() = synchronized(lock) { settled }

    /** 폴링 지터는 위로만(interval보다 이르면 SLOW_DOWN이다). */
    internal fun schedulePoll() {
        val ports = api.ports
        val every = synchronized(lock) { interval }
        val delay =
            jittered(
                ports.random,
                every.coerceAtLeast(0) * 1000,
                Rules.LOGIN_POLL_JITTER_MIN,
                Rules.LOGIN_POLL_JITTER_MAX,
            )
        if (ports.clock.nowMs() + delay > expiresAtMs) {
            finish(Result.failure(BydevException(Codes.EXPIRED_TOKEN, "로그인 코드가 만료됐다 — 처음부터 다시")))
            return
        }
        val next = ports.scheduler.after(delay) { poll() }
        val late =
            synchronized(lock) {
                if (!settled) handle = next
                settled
            }
        if (late) next.cancel()
    }

    private fun poll() {
        if (isSettled) return
        val outcome = runCatching {
            val doc = api.discovery()
            val body =
                JsonValue.obj(
                    "grant_type" to JsonValue.Str(Spec.DEVICE_CODE_GRANT_TYPE),
                    "client_id" to JsonValue.Str(clientId),
                    "device_code" to JsonValue.Str(deviceCode),
                )
            api.send(Req("POST", doc.tokenEndpoint, body))
        }
        if (isSettled) return
        val e = outcome.exceptionOrNull()
        if (e == null) {
            finish(
                runCatching {
                    val set = TokenSet.read(outcome.getOrThrow().json)
                    tokens.adopt(set)
                    LoginResult(set.scope)
                }
            )
            return
        }
        val error = BydevException.wrap(e, Codes.NETWORK_ERROR, "API에 연결하지 못했다")
        when {
            error.code == Codes.AUTHORIZATION_PENDING || error.isTransient -> schedulePoll()
            error.code == Codes.SLOW_DOWN -> {
                synchronized(lock) {
                    val n = error.retryAfterSec
                    interval =
                        if (n != null && n > 0) n.toLong()
                        else interval + Rules.LOGIN_SLOW_DOWN_INCREMENT_SEC
                }
                schedulePoll()
            }
            else -> finish(Result.failure(error))
        }
    }
}
