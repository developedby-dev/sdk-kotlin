package dev.developedby.sdk

/**
 * SDK의 에러 하나다(명세 3.3). `code`로 분기하고(생성된 [Codes]) `message`는 보여 주기만 한다. 앱의 오류 화면에 `correlationId`를
 * 보이면 문의를 바로 찾는다. 확인 예외가 아니다(Java에서도 `catch (BydevException e)`).
 */
public class BydevException
@JvmOverloads
public constructor(
    public val code: String,
    public override val message: String,
    public val httpStatus: Int? = null,
    public val correlationId: String? = null,
    public val retryAfterSec: Int? = null,
    /** 에러 상세(객체) — 없으면 null. */
    public val details: Map<String, JsonValue>? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** 일시적 실패인지다(명세 3.4). 아는 코드는 그 뜻대로 — 429인 한도 초과 코드는 끝난 실패 — 모르는 코드만 HTTP 상태로 나눈다. */
    public val isTransient: Boolean
        get() {
            if (code in Rules.TRANSIENT_CODES) return true
            if (code in Codes.API_HTTP_STATUS || code in Codes.SDK) return false
            val status = httpStatus ?: return false
            return status in Rules.TRANSIENT_UNKNOWN_CODE_STATUSES ||
                Rules.TRANSIENT_UNKNOWN_CODE_STATUS_RANGES.any { status in it }
        }

    /**
     * `{"code","message","httpStatus","correlationId","retryAfterSec","details"}` — 키는 언제나 다 있다(없는
     * 값은 null).
     */
    public fun toJsonValue(): JsonValue =
        JsonValue.obj(
            "code" to JsonValue.Str(code),
            "message" to JsonValue.Str(message),
            "httpStatus" to JsonValue.of(httpStatus),
            "correlationId" to JsonValue.of(correlationId),
            "retryAfterSec" to JsonValue.of(retryAfterSec),
            "details" to (details?.let { JsonValue.Obj(it) } ?: JsonValue.Null),
        )

    public fun toJson(): String = toJsonValue().toJson()

    override fun toString(): String = "$code: $message"

    internal companion object {
        /** 포트가 던진 에러를 `code`로 감싼다 — 이미 [BydevException]이면 그대로. */
        fun wrap(e: Throwable, code: String, message: String): BydevException =
            e as? BydevException ?: BydevException(code, message, cause = e)

        /** 저장소 실패는 언제나 `STORAGE_FAILED`(명세 2절). */
        fun storage(e: Throwable, message: String): BydevException =
            (e as? BydevException)?.takeIf { it.code == Codes.STORAGE_FAILED }
                ?: BydevException(Codes.STORAGE_FAILED, message, cause = e)
    }
}
