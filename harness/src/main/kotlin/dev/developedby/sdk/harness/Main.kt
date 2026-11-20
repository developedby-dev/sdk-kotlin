// Kotlin SDK의 적합성 하네스(sdks/spec/harness-protocol.md). 표준 입출력 JSON 줄로 실행기와 이야기하며 SDK를 가상 시계 · 가상
// 스케줄러 · 대리 전송으로
// 몬다.
//
// 블로킹 언어의 정착 판정: SDK 일을 하는 스레드 수(`running`)를 세고, 전송 · `sleep` · `wait`에서 기다리는 스레드는 그 기다림의 신호를 등록한다.
// `running`이 0이고, 답을 기다리는 요청이 없고, 등록된 신호가 하나도 켜지지 않았으면(깨어날 스레드가 없으면) 할 일이 없다.
package dev.developedby.sdk.harness

import dev.developedby.sdk.AppInfo
import dev.developedby.sdk.BydevException
import dev.developedby.sdk.Cancel
import dev.developedby.sdk.CredentialRecord
import dev.developedby.sdk.CredentialStore
import dev.developedby.sdk.DeviceInfo
import dev.developedby.sdk.FingerprintSource
import dev.developedby.sdk.HttpRequest
import dev.developedby.sdk.HttpResponse
import dev.developedby.sdk.HttpTransport
import dev.developedby.sdk.IsoTime
import dev.developedby.sdk.JsonValue
import dev.developedby.sdk.LicenseClient
import dev.developedby.sdk.LogSink
import dev.developedby.sdk.LoginAttempt
import dev.developedby.sdk.Ports
import dev.developedby.sdk.RandomSource
import dev.developedby.sdk.Signal
import dev.developedby.sdk.Spec
import dev.developedby.sdk.TaskScheduler
import dev.developedby.sdk.WallClock
import java.io.IOException
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

private class Timer(val due: Long, val seq: Long, val task: Runnable) {
    var cancelled = false
}

private class Exchange {
    val done = Signal()
    var response: HttpResponse? = null
}

private object H {
    val lock = Any()
    private val out = Any()
    var now = 0L
    val timers = ArrayList<Timer>()
    var seq = 0L
    var running = 0
    val waiters = HashMap<Long, Signal>()
    var waiterId = 0L
    val pending = HashMap<String, Exchange>()
    val randoms = ArrayDeque<Double>()
    var uuids = 0
    var httpSeq = 0
    var record: CredentialRecord? = null
    var failLoad = false
    var failSave = false
    var failClear = false
    /** set fingerprint.fail — 지문 출처를 읽지 못한다(하네스 규약 2절) */
    var failFingerprint = false
    var client: LicenseClient? = null
    var login: LoginAttempt? = null

    fun emit(message: Map<String, JsonValue>) {
        val line = JsonValue.Obj(message).toJson()
        synchronized(out) {
            System.out.println(line)
            System.out.flush()
        }
    }

    fun fatal(message: String): Nothing {
        emit(mapOf("type" to JsonValue.Str("fatal"), "message" to JsonValue.Str(message)))
        exitProcess(1)
    }

    fun clock(): Long = synchronized(lock) { now }

    /** SDK 스레드가 포트에서 기다리는 동안 `running`에서 빠지고 신호를 등록한다. */
    fun block(done: Signal) {
        val id =
            synchronized(lock) {
                running--
                waiterId++
                waiters[waiterId] = done
                waiterId
            }
        done.block()
        synchronized(lock) {
            waiters.remove(id)
            running++
        }
    }
}

private fun num(n: Long) = JsonValue.Num(n.toDouble())

private fun str(s: String) = JsonValue.Str(s)

// ── 포트 ──────────────────────────────────────────────────────

private class HarnessPorts(private val fingerprintValue: String?) :
    HttpTransport,
    CredentialStore,
    WallClock,
    TaskScheduler,
    RandomSource,
    FingerprintSource,
    LogSink {
    override fun nowMs(): Long = H.clock()

    override fun after(delayMs: Long, task: Runnable): Cancel {
        val timer =
            synchronized(H.lock) {
                Timer(H.now + delayMs.coerceAtLeast(0), H.seq++, task).also { H.timers.add(it) }
            }
        return Cancel { synchronized(H.lock) { timer.cancelled = true } }
    }

    override fun sleep(ms: Long) {
        val done = Signal()
        after(ms) { done.fire() }
        H.block(done)
    }

    override fun wait(signal: Signal): Unit = H.block(signal)

    override fun nextDouble(): Double =
        synchronized(H.lock) { H.randoms.removeFirstOrNull() }
            ?: H.fatal("시나리오의 random 값을 다 썼다 — random에 더 적는다")

    override fun uuid(): String =
        "00000000-0000-4000-8000-%012x".format(synchronized(H.lock) { ++H.uuids })

    override fun send(request: HttpRequest): HttpResponse {
        val exchange = Exchange()
        val (id, at) =
            synchronized(H.lock) {
                val id = "h${++H.httpSeq}"
                H.pending[id] = exchange
                id to H.now
            }
        H.emit(
            mapOf(
                "type" to str("http.request"),
                "id" to str(id),
                "now" to num(at),
                "request" to
                    JsonValue.Obj(
                        mapOf(
                            "method" to str(request.method),
                            "url" to str(request.url),
                            "headers" to
                                JsonValue.Obj(
                                    request.headers.entries.associate { (k, v) ->
                                        k.lowercase() to str(v)
                                    }
                                ),
                            "body" to
                                (request.body?.let { str(String(it, Charsets.UTF_8)) }
                                    ?: JsonValue.Null),
                        )
                    ),
            )
        )
        H.block(exchange.done)
        return synchronized(H.lock) { exchange.response } ?: throw IOException("대리 전송 실패")
    }

    override fun load(): CredentialRecord? =
        synchronized(H.lock) {
            if (H.failLoad) throw IOException("저장소 읽기 실패(시험)")
            H.record
        }

    override fun save(record: CredentialRecord) {
        val at =
            synchronized(H.lock) {
                if (H.failSave) throw IOException("저장소 쓰기 실패(시험)")
                H.record = record
                H.now
            }
        H.emit(
            mapOf(
                "type" to str("event"),
                "now" to num(at),
                "event" to
                    JsonValue.Obj(
                        mapOf(
                            "kind" to str("storage"),
                            "op" to str("save"),
                            "record" to recordJson(record),
                        )
                    ),
            )
        )
    }

    override fun clear() {
        val at =
            synchronized(H.lock) {
                if (H.failClear) throw IOException("저장소 지우기 실패(시험)")
                H.record = null
                H.now
            }
        H.emit(
            mapOf(
                "type" to str("event"),
                "now" to num(at),
                "event" to JsonValue.Obj(mapOf("kind" to str("storage"), "op" to str("clear"))),
            )
        )
    }

    override fun read(): String? = if (H.failFingerprint) null else fingerprintValue

    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(message: String) = System.err.println("warn: $message")
}

private fun recordJson(r: CredentialRecord) =
    JsonValue.Obj(
        mapOf(
            "version" to num(r.version.toLong()),
            "refreshToken" to str(r.refreshToken),
            "scope" to str(r.scope),
            "savedAt" to str(r.savedAt),
        )
    )

// ── 정착 ──────────────────────────────────────────────────────

private fun quiet(): Boolean =
    synchronized(H.lock) {
        H.running == 0 && H.pending.isEmpty() && H.waiters.values.none { it.isFired }
    }

private fun waitQuiet() {
    while (!quiet()) LockSupport.parkNanos(50_000)
}

/** `limit`까지 온, 취소되지 않은 가장 이른 예약을 꺼낸다. */
private fun takeTimer(limit: Long): Timer? {
    H.timers.removeAll { it.cancelled }
    val next =
        H.timers.filter { it.due <= limit }.minWithOrNull(compareBy({ it.due }, { it.seq }))
            ?: return null
    H.timers.remove(next)
    return next
}

/** 지금 시각까지 온 예약 작업을 하나씩 — 앞 작업이 끝나거나 기다릴 때까지 — 돌린다. */
private fun settle() {
    while (true) {
        waitQuiet()
        val timer = synchronized(H.lock) { takeTimer(H.now)?.also { H.running++ } } ?: return
        Thread {
            timer.task.run()
            synchronized(H.lock) { H.running-- }
        }
            .start()
    }
}

private fun advance(ms: Long) {
    val target = H.clock() + ms
    while (true) {
        settle()
        val next =
            synchronized(H.lock) {
                H.timers.removeAll { it.cancelled }
                H.timers.filter { it.due <= target }.minOfOrNull { it.due }
            } ?: break
        synchronized(H.lock) { H.now = next }
    }
    synchronized(H.lock) { H.now = target }
    settle()
}

// ── 호출 ──────────────────────────────────────────────────────

private fun arg(args: Map<String, JsonValue>, key: String): String =
    args[key]?.string ?: H.fatal("인자 ${key}가 문자열이 아니다")

private fun invoke(method: String, args: Map<String, JsonValue>): JsonValue {
    val c = synchronized(H.lock) { H.client } ?: H.fatal("init 전에 호출했다")
    return when (method) {
        "login.start" -> {
            val attempt = c.startLogin()
            synchronized(H.lock) { H.login = attempt }
            JsonValue.Obj(
                mapOf(
                    "userCode" to str(attempt.userCode),
                    "verificationUri" to str(attempt.verificationUri),
                    "verificationUriComplete" to str(attempt.verificationUriComplete),
                    "expiresAt" to str(attempt.expiresAt),
                )
            )
        }
        "login.wait" ->
            (synchronized(H.lock) { H.login } ?: H.fatal("login.start 전에 login.wait"))
                .await()
                .toJsonValue()
        "login.cancel" -> {
            synchronized(H.lock) { H.login }?.cancel()
            JsonValue.Null
        }
        "hasCredentials" -> JsonValue.Bool(c.hasCredentials())
        "entitlement" -> c.entitlement().toJsonValue()
        "devices" -> c.devices(args["cursor"]?.string, args["limit"]?.number?.toInt()).toJsonValue()
        "releaseDevice" -> c.releaseDevice(arg(args, "deviceId")).toJsonValue()
        "profile" -> c.profile().toJsonValue()
        "checkForUpdate" ->
            c.checkForUpdate(arg(args, "target"), arg(args, "currentVersion")).toJsonValue()
        "verifyDownload" -> {
            c.verifyDownload(
                Base64.getDecoder().decode(arg(args, "dataBase64")),
                arg(args, "sha256"),
            )
            JsonValue.Null
        }
        "verifyToken" -> c.verifyToken(arg(args, "token")).toJsonValue()
        "logout" -> JsonValue.Obj(mapOf("serverRevoked" to JsonValue.Bool(c.logout())))
        "session.start" -> JsonValue.Null.also { c.session().start() }
        "session.takeOver" -> JsonValue.Null.also { c.session().takeOver() }
        "session.retry" -> JsonValue.Null.also { c.session().retry() }
        "session.stop" -> JsonValue.Null.also { c.session().stop() }
        "session.state" -> c.session().state.toJsonValue()
        else -> H.fatal("모르는 호출 $method")
    }
}

private fun startCall(id: String, method: String, args: Map<String, JsonValue>) {
    synchronized(H.lock) { H.running++ }
    Thread {
        val message = linkedMapOf<String, JsonValue>("type" to str("result"), "id" to str(id))
        try {
            val value = invoke(method, args)
            message["ok"] = JsonValue.Bool(true)
            message["value"] = value
        } catch (e: BydevException) {
            message["ok"] = JsonValue.Bool(false)
            message["error"] = e.toJsonValue()
        } catch (e: Exception) {
            message["ok"] = JsonValue.Bool(false)
            message["error"] =
                JsonValue.Obj(mapOf("code" to str("HARNESS_ERROR"), "message" to str(e.toString())))
        }
        message["now"] = num(H.clock())
        H.emit(message)
        synchronized(H.lock) { H.running-- }
    }
        .start()
}

// ── 명령 ──────────────────────────────────────────────────────

private fun initialize(message: Map<String, JsonValue>) {
    val config = message["config"]?.obj ?: H.fatal("init.config")
    val device = config["device"]?.obj ?: H.fatal("init.config.device")
    fun text(o: Map<String, JsonValue>, key: String) = o[key]?.string ?: H.fatal("init.$key")
    val started = message["now"]?.string?.let(IsoTime::parse) ?: H.fatal("init.now")
    synchronized(H.lock) {
        H.now = started
        message["random"]?.array?.mapNotNull { it.number }?.let(H.randoms::addAll)
        message["storage"]?.obj?.let { s ->
            val token = s["refreshToken"]?.string ?: return@let
            H.record =
                CredentialRecord(
                    s["version"]?.number?.toInt() ?: 1,
                    token,
                    s["scope"]?.string ?: "",
                    s["savedAt"]?.string ?: "",
                )
        }
    }
    // 시나리오가 적을 때만 온다
    val app = config["app"]?.obj?.let { AppInfo(text(it, "target"), text(it, "version")) }
    val ports = HarnessPorts(device["fingerprintSource"]?.string)
    val client =
        try {
            LicenseClient(
                LicenseClient.Config(
                    text(config, "baseUrl"),
                    text(config, "clientId"),
                    text(config, "productId"),
                    config["scopes"]?.array?.mapNotNull { it.string } ?: emptyList(),
                    DeviceInfo(text(device, "name"), text(device, "platform")),
                    Ports()
                        .transport(ports)
                        .store(ports)
                        .clock(ports)
                        .scheduler(ports)
                        .random(ports)
                        .fingerprint(ports)
                        .logger(ports),
                    app,
                )
            )
        } catch (e: BydevException) {
            H.fatal("LicenseClient: $e")
        }
    client.session().subscribe { state ->
        H.emit(
            mapOf(
                "type" to str("event"),
                "now" to num(H.clock()),
                "event" to
                    JsonValue.Obj(
                        mapOf("kind" to str("session.state"), "state" to state.toJsonValue())
                    ),
            )
        )
    }
    synchronized(H.lock) { H.client = client }
}

private fun onHttpResponse(message: Map<String, JsonValue>) {
    val id = message["id"]?.string ?: ""
    // 지우기와 켜기를 한 잠금 안에서 — 둘 사이에 "할 일 없음"으로 보이는 틈이 없게
    val known =
        synchronized(H.lock) {
            val exchange = H.pending.remove(id) ?: return@synchronized false
            message["response"]?.obj?.let { r ->
                exchange.response =
                    HttpResponse(
                        r["status"]?.number?.toInt() ?: 0,
                        r["headers"]?.obj.orEmpty().entries.associate { (k, v) ->
                            k.lowercase() to (v.string ?: "")
                        },
                        (r["body"]?.string ?: "").toByteArray(Charsets.UTF_8),
                    )
            }
            exchange.done.fire()
            true
        }
    if (!known) H.fatal("모르는 http.response id $id")
}

public fun main() {
    H.emit(
        mapOf(
            "type" to str("ready"),
            "sdk" to str("kotlin"),
            "version" to str(LicenseClient.SDK_VERSION),
            "specVersion" to str(Spec.VERSION),
        )
    )
    val queue = LinkedBlockingQueue<Map<String, JsonValue>>()
    Thread {
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = input.readLine() ?: break
            val message = JsonValue.parse(line)?.obj ?: H.fatal("JSON이 아닌 입력: $line")
            if (message["type"]?.string == "http.response") onHttpResponse(message)
            else queue.put(message)
        }
        queue.put(mapOf("type" to str("end")))
    }
        .apply { isDaemon = true }
        .start()
    while (true) {
        val message = queue.take()
        when (val type = message["type"]?.string ?: "") {
            "init" -> {
                initialize(message)
                settle()
            }
            "call" -> {
                message["calls"]?.array?.forEach { call ->
                    val c = call.obj ?: return@forEach
                    startCall(
                        c["id"]?.string ?: "",
                        c["method"]?.string ?: "",
                        c["args"]?.obj ?: emptyMap(),
                    )
                }
                settle()
            }
            "advance" -> advance(message["ms"]?.number?.toLong() ?: 0)
            "set" -> {
                val value = message["value"] == JsonValue.Bool(true)
                synchronized(H.lock) {
                    when (message["key"]?.string) {
                        "storage.failLoad" -> H.failLoad = value
                        "storage.failSave" -> H.failSave = value
                        "storage.failClear" -> H.failClear = value
                        "fingerprint.fail" -> H.failFingerprint = value
                    }
                }
                settle()
            }
            "end" -> exitProcess(0)
            else -> H.fatal("모르는 명령 $type")
        }
        H.emit(mapOf("type" to str("idle"), "now" to num(H.clock())))
    }
}
