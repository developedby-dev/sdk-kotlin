package dev.developedby.sdk

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// 명세 2절 — 기본 구현. 운영체제 저장소 · 지문 출처는 앱이 고른다(OsPorts).

/** 시스템 시계 — SDK에서 시계를 직접 읽는 유일한 곳. */
public object SystemClock : WallClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** 데몬 스레드 스케줄러 — 시각은 스레드 하나가 재고, 작업은 캐시 풀에서 돈다(작업이 기다려도 다른 예약이 밀리지 않게). */
public object ThreadScheduler : TaskScheduler {
    private val daemon = ThreadFactory { r -> Thread(r, "bydev-sdk").apply { isDaemon = true } }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemon)
    private val workers = Executors.newCachedThreadPool(daemon)

    override fun after(delayMs: Long, task: Runnable): Cancel {
        val cancelled = AtomicBoolean(false)
        val future =
            timer.schedule(
                { if (!cancelled.get()) workers.execute(task) },
                delayMs.coerceAtLeast(0),
                TimeUnit.MILLISECONDS,
            )
        return Cancel {
            cancelled.set(true)
            future.cancel(false)
        }
    }

    override fun sleep(ms: Long): Unit = Thread.sleep(ms.coerceAtLeast(0))

    override fun wait(signal: Signal): Unit = signal.block()
}

/** 운영체제 난수(`SecureRandom`). */
public object SecureRandomSource : RandomSource {
    private val random = SecureRandom()

    override fun nextDouble(): Double = random.nextDouble()

    override fun uuid(): String = UUID.randomUUID().toString()
}

/** JDK `java.net.http` 전송 — 시간 초과 `Rules.HTTP_TIMEOUT_SEC`, 리다이렉트를 따라가지 않는다. */
public class JdkHttpTransport
@JvmOverloads
public constructor(
    private val timeout: Duration = Duration.ofSeconds(Rules.HTTP_TIMEOUT_SEC.toLong()),
    /** 앱이 만든 클라이언트(프록시 · 인증서 고정) — 리다이렉트를 막는 것은 앱의 몫이다. */
    private val client: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .build(),
) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val builder =
            java.net.http.HttpRequest.newBuilder(URI.create(request.url))
                .timeout(timeout)
                .method(
                    request.method,
                    request.body?.let { java.net.http.HttpRequest.BodyPublishers.ofByteArray(it) }
                        ?: java.net.http.HttpRequest.BodyPublishers.noBody(),
                )
        for ((name, value) in request.headers) builder.header(name, value)
        val response =
            try {
                client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: HttpTimeoutException) {
                throw BydevException(Codes.NETWORK_ERROR, "API 응답이 시간 안에 오지 않았다", cause = e)
            } catch (e: IOException) {
                throw BydevException(Codes.NETWORK_ERROR, "API에 연결하지 못했다", cause = e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw BydevException(Codes.NETWORK_ERROR, "요청이 끊겼다", cause = e)
            }
        val headers =
            response.headers().map().entries.associate { (k, v) ->
                k.lowercase() to v.joinToString(", ")
            }
        return HttpResponse(response.statusCode(), headers, response.body() ?: ByteArray(0))
    }
}

/** 메모리 저장소 — 앱을 끄면 로그인이 사라진다. 클라이언트의 기본값이다(운영체제 저장소는 [OsPorts]에서 고른다). */
public class MemoryCredentialStore
@JvmOverloads
public constructor(initial: CredentialRecord? = null) : CredentialStore {
    @Volatile private var record: CredentialRecord? = initial

    override fun load(): CredentialRecord? = record

    override fun save(record: CredentialRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

/** 아무것도 하지 않는 로그. */
public object SilentLog : LogSink {
    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(message: String) {}
}
