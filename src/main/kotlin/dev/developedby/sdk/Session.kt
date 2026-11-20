package dev.developedby.sdk

import dev.developedby.sdk.JsonValue.Companion.obj
import dev.developedby.sdk.JsonValue.Companion.of

// 명세 8절 — 라이선스 세션(실행 리스) 상태 기계. 참/거짓 하나로 이용 가능을 알리지 않는다.

/** 상태에 싣는 에러다(값으로 비교한다). */
public data class ErrorView(
    public val code: String,
    public val message: String,
    public val httpStatus: Int?,
    public val correlationId: String?,
    public val retryAfterSec: Int?,
    public val details: Map<String, JsonValue>?,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "code" to JsonValue.Str(code),
            "message" to JsonValue.Str(message),
            "httpStatus" to of(httpStatus),
            "correlationId" to of(correlationId),
            "retryAfterSec" to of(retryAfterSec),
            "details" to (details?.let { JsonValue.Obj(it) } ?: JsonValue.Null),
        )

    internal companion object {
        fun of(e: BydevException) =
            ErrorView(e.code, e.message, e.httpStatus, e.correlationId, e.retryAfterSec, e.details)
    }
}

/** 지금 쓰는 다른 기기(인수 판단에 보인다). */
public data class ActiveSession(
    public val deviceId: String,
    public val deviceName: String,
    public val lastSeenAt: String,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "deviceId" to JsonValue.Str(deviceId),
            "deviceName" to JsonValue.Str(deviceName),
            "lastSeenAt" to JsonValue.Str(lastSeenAt),
        )
}

/**
 * 세션 상태(판별 유니온, 명세 8.1절). JSON은 `{"type": "Active", …}`. Java에서는 `instanceof SessionState.Active`.
 */
public sealed class SessionState : BydevModel {
    /** 명세 이름([SessionStateType]). */
    public abstract val type: String

    protected abstract fun fields(): Map<String, JsonValue>

    override fun toJsonValue(): JsonValue =
        JsonValue.Obj(fields() + ("type" to JsonValue.Str(type)))

    /** 획득 중. */
    public data class Starting(public val attempt: Int, public val lastError: ErrorView?) :
        SessionState() {
        override val type: String = SessionStateType.STARTING

        override fun fields(): Map<String, JsonValue> =
            mapOf(
                "attempt" to of(attempt),
                "lastError" to (lastError?.toJsonValue() ?: JsonValue.Null),
            )
    }

    /** 리스를 쥔 상태 — 기능 코드는 `claims`(exp · sid)를 쓴다. */
    public data class Active(
        public val session: SessionInfo,
        public val licenseToken: LicenseTokenInfo,
        public val claims: LicenseClaims,
    ) : SessionState() {
        override val type: String = SessionStateType.ACTIVE

        override fun fields(): Map<String, JsonValue> =
            mapOf(
                "session" to session.toJsonValue(),
                "licenseToken" to licenseToken.toJsonValue(),
                "claims" to claims.toJsonValue(),
            )
    }

    /** 하트비트가 실패하는 중 — `offlineUntil`까지는 계속 쓸 수 있다. */
    public data class Degraded(
        public val session: SessionInfo,
        public val licenseToken: LicenseTokenInfo,
        public val claims: LicenseClaims,
        public val consecutiveFailures: Int,
        public val offlineUntil: String,
        public val lastError: ErrorView?,
    ) : SessionState() {
        override val type: String = SessionStateType.DEGRADED

        override fun fields(): Map<String, JsonValue> =
            mapOf(
                "session" to session.toJsonValue(),
                "licenseToken" to licenseToken.toJsonValue(),
                "claims" to claims.toJsonValue(),
                "consecutiveFailures" to of(consecutiveFailures),
                "offlineUntil" to JsonValue.Str(offlineUntil),
                "lastError" to (lastError?.toJsonValue() ?: JsonValue.Null),
            )
    }

    /**
     * 사용자 판단이 필요하다 — `DEVICE_IN_USE` · `INSTANCE_ALREADY_RUNNING`은 `takeOver()`,
     * `DEVICE_LIMIT_EXCEEDED`는 기기 해제 뒤 `retry()`.
     */
    public data class NeedsDecision(
        public val reason: String,
        public val activeSession: ActiveSession?,
        public val maxDevices: Int?,
        public val correlationId: String?,
    ) : SessionState() {
        override val type: String = SessionStateType.NEEDS_DECISION

        override fun fields(): Map<String, JsonValue> =
            mapOf(
                "reason" to JsonValue.Str(reason),
                "activeSession" to (activeSession?.toJsonValue() ?: JsonValue.Null),
                "maxDevices" to of(maxDevices),
                "correlationId" to of(correlationId),
            )
    }

    /** 잠금 — 오류 화면에 `correlationId`를 보인다. `retrying`이면 스스로 풀릴 수 있다(OFFLINE_EXPIRED). */
    public data class Locked(
        public val code: String,
        public val message: String,
        public val correlationId: String?,
        public val details: Map<String, JsonValue>?,
        public val retrying: Boolean,
    ) : SessionState() {
        override val type: String = SessionStateType.LOCKED

        override fun fields(): Map<String, JsonValue> =
            mapOf(
                "code" to JsonValue.Str(code),
                "message" to JsonValue.Str(message),
                "correlationId" to of(correlationId),
                "details" to (details?.let { JsonValue.Obj(it) } ?: JsonValue.Null),
                "retrying" to of(retrying),
            )
    }

    /** 멈춤. */
    public data object Stopped : SessionState() {
        override val type: String = SessionStateType.STOPPED

        override fun fields(): Map<String, JsonValue> = emptyMap()
    }
}

/** 세션 획득 때 보내는 기기 정보다(`platform`은 [DevicePlatform]). */
public data class DeviceInfo(public val name: String, public val platform: String)

/**
 * 실행 중인 앱이다(명세 1절 · ADR-0049) — 배포 대상(`target`, 업데이트 확인의 `target`과 같은 값 — [DistributionTarget])과
 * 콘솔에 업로드한 버전 문자열. 세션 획득에 싣는다. 업데이트 제공 기한이 있는 영구 이용권은 이 값이 없으면 획득이
 * `ENTITLEMENT_VERSION_NOT_COVERED`로 거절된다.
 */
public data class AppInfo(public val target: String, public val version: String)

/** 상태가 바뀔 때마다 불린다 — SDK의 스레드에서. */
public fun interface SessionListener {
    public fun onState(state: SessionState)
}

/** 구독 — [unsubscribe](또는 `close`)로 끊는다. */
public class Subscription internal constructor(private val onCancel: () -> Unit) : AutoCloseable {
    public fun unsubscribe(): Unit = onCancel()

    override fun close(): Unit = onCancel()
}

/**
 * 라이선스 세션이다. `start` · `takeOver` · `retry` · `stop`과 상태 구독. 구독 함수는 SDK의 스레드에서 불린다 — UI 스레드로 넘긴다.
 */
public class LicenseSession
internal constructor(
    private val api: Api,
    private val tokens: TokenManager,
    private val verifier: Verifier,
    private val device: DeviceInfo,
    private val app: AppInfo?,
) {
    private val lock = Any()
    private var current: SessionState = SessionState.Stopped
    private val listeners = LinkedHashMap<Long, SessionListener>()
    private var nextListener = 0L
    private var run = 0L
    private var instance = ""
    private var held: SessionState.Active? = null
    private var failures = 0
    private var heartbeat: Cancel? = null
    private var offline: Cancel? = null
    private var fallback: String? = null
    /** 처음 계산한 지문 해시 — 한 실행 동안 다시 쓴다(명세 10절). 획득마다 다시 읽으면 일시적인 읽기 실패 하나로 다른 기기가 된다 */
    private var fingerprint: String? = null

    private fun cancelTimers() {
        heartbeat?.cancel()
        heartbeat = null
        offline?.cancel()
        offline = null
    }

    /** 지금 상태다. */
    public val state: SessionState
        get() = synchronized(lock) { current }

    /** 상태가 바뀔 때마다 부른다. */
    public fun subscribe(listener: SessionListener): Subscription {
        val id =
            synchronized(lock) {
                listeners[nextListener] = listener
                nextListener++
            }
        return Subscription { synchronized(lock) { listeners.remove(id) } }
    }

    /** `Stopped`에서만 의미가 있다(8.2절). */
    public fun start() {
        synchronized(lock) {
            if (current != SessionState.Stopped) return
            run++
            instance = api.ports.random.uuid()
        }
        beginAcquire(false, null)
    }

    /**
     * `NeedsDecision`(다른 기기 · 같은 기기 실행 중) 또는 `Locked(SESSION_SUPERSEDED)`에서 인수한다 — 새
     * Idempotency-Key.
     */
    public fun takeOver() {
        val allowed =
            when (val s = state) {
                is SessionState.NeedsDecision ->
                    s.reason != NeedsDecisionReason.DEVICE_LIMIT_EXCEEDED
                is SessionState.Locked -> s.code == Codes.SESSION_SUPERSEDED
                else -> false
            }
        if (!allowed) return
        synchronized(lock) { run++ }
        beginAcquire(true, null)
    }

    /** `NeedsDecision`(기기 수) 또는 스스로 풀리지 않는 `Locked`에서 다시 얻는다. */
    public fun retry() {
        val allowed =
            when (val s = state) {
                is SessionState.NeedsDecision ->
                    s.reason == NeedsDecisionReason.DEVICE_LIMIT_EXCEEDED
                is SessionState.Locked -> !s.retrying
                else -> false
            }
        if (!allowed) return
        synchronized(lock) { run++ }
        beginAcquire(false, null)
    }

    /** 예약을 모두 취소하고 반납을 한 번 보낸다(8.7절, 블로킹). 진행 중인 요청의 결과는 버린다. */
    public fun stop() {
        val sid =
            synchronized(lock) {
                if (current == SessionState.Stopped) return
                run++
                cancelTimers()
                instance = ""
                held?.session?.id.also { held = null }
            }
        if (sid != null) {
            val url = api.url(Routes.RELEASE_SESSION.path, mapOf("sid" to sid))
            try {
                tokens.authed { bearer -> api.send(Req("DELETE", url, bearer = bearer)) }
            } catch (_: BydevException) {
                api.ports.logger.debug("세션 반납을 보내지 못했다 — 리스가 저절로 빈다")
            }
        }
        setState(SessionState.Stopped)
    }

    /** 8.5절 — 다른 인증 응답의 `X-Lease-Expires-At`. */
    internal fun noteLease(value: String?) {
        if (value.isNullOrEmpty()) return
        val next =
            synchronized(lock) {
                val h = held ?: return
                val next =
                    when (val s = current) {
                        is SessionState.Active -> {
                            if (s.session.leaseExpiresAt == value) return
                            s.copy(session = s.session.copy(leaseExpiresAt = value))
                        }
                        is SessionState.Degraded -> {
                            if (s.session.leaseExpiresAt == value) return
                            s.copy(session = s.session.copy(leaseExpiresAt = value))
                        }
                        else -> return
                    }
                held = h.copy(session = h.session.copy(leaseExpiresAt = value))
                next
            }
        setState(next)
    }

    // ── 안 ──────────────────────────────────────────────────────

    private fun setState(next: SessionState) {
        val targets =
            synchronized(lock) {
                if (current == next) return
                current = next
                listeners.values.toList()
            }
        for (listener in targets) listener.onState(next)
    }

    private fun live(run: Long): Boolean = synchronized(lock) { this.run == run }

    private fun after(delayMs: Long, task: () -> Unit) {
        val cancel = api.ports.scheduler.after(delayMs, task)
        val old = synchronized(lock) { heartbeat.also { heartbeat = cancel } }
        old?.cancel()
    }

    private fun beginAcquire(force: Boolean, lastError: BydevException?) {
        val run =
            synchronized(lock) {
                cancelTimers()
                held = null
                failures = 0
                this.run
            }
        setState(SessionState.Starting(0, lastError?.let(ErrorView::of)))
        val key = api.ports.random.uuid()
        after(0) { acquire(run, force, key, 0) }
    }

    private fun fingerprintHash(): String {
        synchronized(lock) { fingerprint }
            ?.let {
                return it
            }
        val computed = computeFingerprintHash()
        return synchronized(lock) {
            fingerprint ?: computed.also { fingerprint = it }
        } // 동시에 계산했으면 먼저 정한 값
    }

    private fun computeFingerprintHash(): String {
        val ports = api.ports
        val source = runCatching {
            ports.fingerprint.read()
        }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val value =
            source
                ?: synchronized(lock) {
                    fallback
                        ?: ports.random.uuid().also {
                            ports.logger.warn(
                                "기기 지문 출처를 읽지 못해 이번 실행 동안만 쓰는 ID를 쓴다 — 출처 포트를 준다(명세 10절)"
                            )
                            fallback = it
                        }
                }
        return sha256Hex((value + Rules.FINGERPRINT_SALT).toByteArray(Charsets.UTF_8))
    }

    /** 8.2절. 일시적 실패의 재시도는 같은 키. 설정에 `app`이 있으면 본문에 싣는다(인수 · 재획득도 같다). */
    private fun acquire(run: Long, force: Boolean, key: String, attempt: Int) {
        if (!live(run)) return
        val instanceId = synchronized(lock) { instance }
        val body =
            JsonValue.Obj(
                buildMap {
                    put(
                        "device",
                        obj(
                            "fingerprintHash" to JsonValue.Str(fingerprintHash()),
                            "name" to JsonValue.Str(device.name),
                            "platform" to JsonValue.Str(device.platform),
                        ),
                    )
                    put("instanceId", JsonValue.Str(instanceId))
                    put("force", JsonValue.Bool(force))
                    app?.let {
                        put(
                            "app",
                            obj(
                                "target" to JsonValue.Str(it.target),
                                "version" to JsonValue.Str(it.version),
                            ),
                        )
                    }
                }
            )
        val url = api.url(Routes.ACQUIRE_SESSION.path)
        val error =
            try {
                val res = tokens.authed { bearer -> api.send(Req("POST", url, body, bearer, key)) }
                if (!live(run)) return
                adopt(run, res.json)
                return
            } catch (e: Exception) {
                if (!live(run)) return
                BydevException.wrap(e, Codes.PROTOCOL_ERROR, "세션 응답을 읽지 못했다")
            }
        when {
            error.isTransient -> {
                val delays = Rules.SESSION_START_RETRY_DELAYS_SEC
                val base = delays[minOf(attempt, delays.size - 1)]
                setState(SessionState.Starting(attempt + 1, ErrorView.of(error)))
                val delay =
                    jittered(
                        api.ports.random,
                        seconds(base),
                        Rules.SESSION_HEARTBEAT_JITTER_MIN,
                        Rules.SESSION_HEARTBEAT_JITTER_MAX,
                    )
                after(delay) { acquire(run, force, key, attempt + 1) }
            }
            error.code in Rules.NEEDS_DECISION_REASONS -> {
                val details = error.details
                val activeSession =
                    details?.get("activeSession")?.obj?.let { a ->
                        ActiveSession(
                            a["deviceId"]?.string ?: "",
                            a["deviceName"]?.string ?: "",
                            a["lastSeenAt"]?.string ?: "",
                        )
                    }
                val maxDevices = details?.get("maxDevices")?.number?.toInt()
                setState(
                    SessionState.NeedsDecision(
                        error.code,
                        activeSession,
                        maxDevices,
                        error.correlationId,
                    )
                )
            }
            else -> lockWith(error, false)
        }
    }

    /** 200 응답 → 검증(9절) → Active → 다음 하트비트. */
    private fun adopt(run: Long, json: JsonValue) {
        val (session, token) = readSessionResponse(json)
        val claims =
            verifier.verifyLicense(
                token.token,
                Expectation(session.id, session.deviceId, session.generation),
                0,
            )
        if (!live(run)) return
        val active = SessionState.Active(session, token, claims)
        val pending =
            synchronized(lock) {
                held = active
                failures = 0
                offline.also { offline = null }
            }
        pending?.cancel()
        setState(active)
        scheduleHeartbeat(run, session.heartbeatSec, 0)
    }

    private fun scheduleHeartbeat(run: Long, heartbeatSec: Int, retryAfterSec: Int) {
        val delay =
            maxOf(
                jittered(
                    api.ports.random,
                    heartbeatSec.coerceAtLeast(0) * 1000L,
                    Rules.SESSION_HEARTBEAT_JITTER_MIN,
                    Rules.SESSION_HEARTBEAT_JITTER_MAX,
                ),
                retryAfterSec * 1000L,
            )
        after(delay) { beat(run) }
    }

    /** 8.3절 하트비트. */
    private fun beat(run: Long) {
        val active = synchronized(lock) { if (this.run == run) held else null } ?: return
        val url = api.url(Routes.HEARTBEAT_SESSION.path, mapOf("sid" to active.session.id))
        val body = obj("generation" to of(active.session.generation))
        val error =
            try {
                val res = tokens.authed { bearer -> api.send(Req("POST", url, body, bearer)) }
                if (!live(run)) return
                adopt(run, res.json)
                return
            } catch (e: Exception) {
                if (!live(run)) return
                BydevException.wrap(e, Codes.PROTOCOL_ERROR, "세션 응답을 읽지 못했다")
            }
        when {
            error.isTransient -> {
                val (count, holding) =
                    synchronized(lock) {
                        failures++
                        failures to
                            (current is SessionState.Active || current is SessionState.Degraded)
                    }
                if (holding) {
                    val offlineUntil = active.licenseToken.expiresAt
                    setState(
                        SessionState.Degraded(
                            active.session,
                            active.licenseToken,
                            active.claims,
                            count,
                            offlineUntil,
                            ErrorView.of(error),
                        )
                    )
                    armOfflineExpiry(run, offlineUntil)
                }
                val retry =
                    if (error.code == Codes.RATE_LIMITED || error.httpStatus == 429)
                        error.retryAfterSec ?: 0
                    else 0
                scheduleHeartbeat(run, active.session.heartbeatSec, retry)
            }
            // 리스가 끝났을 때도 이 코드다 — 한 번 다시 얻는다(같은 instanceId, 새 키)
            error.code == Codes.SESSION_REVOKED -> beginAcquire(false, error)
            else -> lockWith(error, false)
        }
    }

    /** 8.4절 — `offlineUntil`이 지나도록 성공하지 못하면 잠근다. 재시도는 그대로. */
    private fun armOfflineExpiry(run: Long, offlineUntil: String) {
        if (synchronized(lock) { offline != null }) return
        val now = api.ports.clock.nowMs()
        val delay = IsoTime.parse(offlineUntil)?.let { (it - now).coerceAtLeast(0) } ?: 0
        val cancel =
            api.ports.scheduler.after(delay) {
                val ok =
                    synchronized(lock) {
                        offline = null
                        this.run == run && current is SessionState.Degraded
                    }
                if (ok) {
                    setState(
                        SessionState.Locked(
                            Codes.OFFLINE_EXPIRED,
                            "하트비트에 성공하지 못한 채 licenseToken이 만료됐다",
                            null,
                            null,
                            true,
                        )
                    )
                }
            }
        synchronized(lock) { offline = cancel }
    }

    private fun lockWith(error: BydevException, retrying: Boolean) {
        synchronized(lock) { cancelTimers() }
        setState(
            SessionState.Locked(
                error.code,
                error.message,
                error.correlationId,
                error.details,
                retrying,
            )
        )
    }
}
