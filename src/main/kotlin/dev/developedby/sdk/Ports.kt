package dev.developedby.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 명세 2절 — 바꿔 끼울 수 있는 포트. SDK 안에서 시계 · 타이머 · 난수를 직접 읽지 않는다. 이름은 명세의 Transport · CredentialStore ·
// Clock ·
// Scheduler · Random · FingerprintSource · Logger이고, java.time.Clock · kotlin.random.Random ·
// java.util.logging.Logger와 겹치지 않게 바꿨다
// (Swift SDK와 같은 이름).

/** 전송 포트가 보낼 요청이다. `body`가 null이면 본문이 없다. 헤더 이름은 소문자다. */
public class HttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray?,
)

/** 받은 응답이다. 헤더 이름은 소문자다. */
public class HttpResponse(
    public val status: Int,
    public val headers: Map<String, String>,
    public val body: ByteArray,
)

/** 요청을 보낸다. 연결 실패 · 시간 초과는 던진다(SDK가 `NETWORK_ERROR`로 감싼다). 3xx를 따라가지 않는다. */
public fun interface HttpTransport {
    public fun send(request: HttpRequest): HttpResponse
}

/** 보안 저장소에 두는 기록이다(명세 3.6). access_token은 두지 않는다. */
public data class CredentialRecord(
    public val version: Int,
    public val refreshToken: String,
    public val scope: String,
    public val savedAt: String,
)

/** 로그인 기록을 둔다. 없으면 `load`가 null. 실패는 던진다(SDK가 `STORAGE_FAILED`로 감싼다). */
public interface CredentialStore {
    public fun load(): CredentialRecord?

    public fun save(record: CredentialRecord)

    public fun clear()
}

/** 지금 시각(Unix 밀리초) — 명세의 Clock. */
public fun interface WallClock {
    public fun nowMs(): Long
}

/** 예약 취소 손잡이. 놓아도 취소하지 않는다 — `cancel()`을 부른다. */
public class Cancel(private val onCancel: Runnable) {
    private val done = AtomicBoolean(false)

    public fun cancel() {
        if (done.compareAndSet(false, true)) onCancel.run()
    }
}

/**
 * 한 번 켜지는 신호 — 다른 작업의 끝(single-flight · 로그인)을 기다릴 때 쓴다. SDK는 `block()`을 직접 부르지 않고
 * [TaskScheduler.wait]를 거친다.
 */
public class Signal {
    private val latch = CountDownLatch(1)

    public fun fire(): Unit = latch.countDown()

    public val isFired: Boolean
        get() = latch.count == 0L

    /** 켜질 때까지 막는다. */
    public fun block(): Unit = latch.await()

    /** 켜지거나 시간이 다 될 때까지 막는다. 켜졌으면 true. */
    public fun block(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}

/**
 * 나중 작업 · 기다림 — 명세의 Scheduler. SDK가 시간이나 다른 작업을 기다리는 곳은 모두 `sleep` · `wait`를 거친다(명세 2절 — 블로킹 언어).
 * SDK는 스스로 스레드를 만들지 않고 `after`로만 백그라운드 작업을 시작한다.
 */
public interface TaskScheduler {
    public fun after(delayMs: Long, task: Runnable): Cancel

    public fun sleep(ms: Long)

    public fun wait(signal: Signal)
}

/** `[0, 1)` 실수와 UUID v4(소문자) — 명세의 Random. */
public interface RandomSource {
    public fun nextDouble(): Double

    public fun uuid(): String
}

/** 운영체제의 기기 식별자다. 없으면 null — 원본은 SDK 밖으로 나가지 않는다(해시만, 명세 10절). */
public fun interface FingerprintSource {
    public fun read(): String?
}

/** 토큰 · 지문 출처 값은 넘어오지 않는다 — 명세의 Logger. */
public interface LogSink {
    public fun debug(message: String)

    public fun info(message: String)

    public fun warn(message: String)
}

/** 클라이언트가 쓰는 포트 묶음이다. 비운 칸은 기본 구현이다. Java에서는 `new Ports().store(s).fingerprint(f)`. */
public class Ports {
    public var transport: HttpTransport? = null
    public var store: CredentialStore? = null
    public var clock: WallClock? = null
    public var scheduler: TaskScheduler? = null
    public var random: RandomSource? = null
    public var fingerprint: FingerprintSource? = null
    public var logger: LogSink? = null

    public fun transport(value: HttpTransport): Ports = apply { transport = value }

    public fun store(value: CredentialStore): Ports = apply { store = value }

    public fun clock(value: WallClock): Ports = apply { clock = value }

    public fun scheduler(value: TaskScheduler): Ports = apply { scheduler = value }

    public fun random(value: RandomSource): Ports = apply { random = value }

    public fun fingerprint(value: FingerprintSource): Ports = apply { fingerprint = value }

    public fun logger(value: LogSink): Ports = apply { logger = value }

    internal fun resolve(): ResolvedPorts =
        ResolvedPorts(
            transport ?: JdkHttpTransport(),
            store ?: MemoryCredentialStore(),
            clock ?: SystemClock,
            scheduler ?: ThreadScheduler,
            random ?: SecureRandomSource,
            fingerprint ?: FingerprintSource { null },
            logger ?: SilentLog,
        )
}

/** 기본값을 채운 포트. */
internal class ResolvedPorts(
    val transport: HttpTransport,
    val store: CredentialStore,
    val clock: WallClock,
    val scheduler: TaskScheduler,
    val random: RandomSource,
    val fingerprint: FingerprintSource,
    val logger: LogSink,
)
