package dev.developedby.sdk

import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64

/** 한 번에 하나만 도는 작업이다 — 기다리는 쪽은 [TaskScheduler.wait]로 기다린다(명세 2절 · 6절 1). */
internal class SingleFlight<T> {
    private class Flight<T> {
        val done = Signal()
        @Volatile var result: Result<T>? = null
    }

    private var current: Flight<T>? = null

    fun run(scheduler: TaskScheduler, work: () -> T): T {
        val (flight, mine) =
            synchronized(this) {
                current?.let { it to false } ?: Flight<T>().also { current = it }.let { it to true }
            }
        if (!mine) {
            scheduler.wait(flight.done)
            return (flight.result
                    ?: Result.failure(BydevException(Codes.PROTOCOL_ERROR, "작업 결과가 없다")))
                .getOrThrow()
        }
        val result = runCatching(work)
        flight.result = result
        synchronized(this) { current = null }
        flight.done.fire()
        return result.getOrThrow()
    }
}

/** `floor(x + 0.5)` 밀리초 — 명세 2절의 반올림. */
internal fun roundedMs(x: Double): Long = Math.floor(x + 0.5).toLong()

/** 초(소수 가능) → 밀리초. */
internal fun seconds(sec: Double): Long = roundedMs(sec * 1000)

/** `round(base × (min + (max − min) × r))`. */
internal fun jittered(random: RandomSource, baseMs: Long, min: Double, max: Double): Long =
    roundedMs(baseMs * (min + (max - min) * random.nextDouble()))

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun base64Url(text: String): ByteArray? =
    try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

/** UTC 시각 ↔ ISO 8601(밀리초가 0이면 뗀다 — `2026-10-06T13:00:00Z` · `…:00.250Z`). */
public object IsoTime {
    private val seconds =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    @JvmStatic
    public fun format(ms: Long): String {
        val millis = Math.floorMod(ms, 1000L)
        val base = seconds.format(Instant.ofEpochMilli(ms))
        return if (millis == 0L) "${base}Z" else "$base.${"%03d".format(millis)}Z"
    }

    /** RFC 3339(`Z` 또는 `±hh:mm`, 소수 초 허용) → Unix 밀리초. 읽지 못하면 null. */
    @JvmStatic
    public fun parse(text: String): Long? =
        try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
}
