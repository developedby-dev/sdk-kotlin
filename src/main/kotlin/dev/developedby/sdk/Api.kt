package dev.developedby.sdk

// 명세 3 · 4절 — 요청 만들기 · 응답 읽기 · 발견 문서.

internal class ApiResponse(val headers: Map<String, String>, val json: JsonValue)

internal class Req(
    val method: String,
    val url: String,
    val body: JsonValue? = null,
    val bearer: String? = null,
    val idempotencyKey: String? = null,
)

internal class Api(baseUrl: String, val ports: ResolvedPorts, private val userAgent: String) {
    val baseUrl: String = baseUrl.trimEnd('/')
    @Volatile private var doc: Discovery? = null
    private val discovering = SingleFlight<Discovery>()

    /** 경로 `{이름}`을 채우고 질의를 붙인다(이름순 — 언어마다 같은 URL). */
    fun url(
        path: String,
        params: Map<String, String> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        val out = StringBuilder(baseUrl)
        var rest = path
        while (true) {
            val open = rest.indexOf('{')
            if (open < 0) break
            val close = rest.indexOf('}', open).let { if (it < 0) rest.length else it }
            out.append(rest, 0, open)
            out.append(escape(params[rest.substring(open + 1, close)] ?: "", spacePlus = false))
            rest = if (close < rest.length) rest.substring(close + 1) else ""
        }
        out.append(rest)
        if (query.isNotEmpty()) {
            out.append('?')
            out.append(
                query
                    .sortedBy { it.first }
                    .joinToString("&") {
                        "${escape(it.first, spacePlus = true)}=${escape(it.second, spacePlus = true)}"
                    }
            )
        }
        return out.toString()
    }

    /** 처음 한 번 받아 클라이언트 수명 동안 둔다. 실패하면 두지 않는다(명세 4절). */
    fun discovery(): Discovery {
        doc?.let {
            return it
        }
        return discovering.run(ports.scheduler) {
            val res = send(Req("GET", baseUrl + Routes.GET_OPEN_ID_CONFIGURATION.path))
            val found = Discovery.read(res.json)
            if (found.issuer.trimEnd('/') != baseUrl) {
                throw BydevException(
                    Codes.CONFIGURATION,
                    "발견 문서의 issuer(${found.issuer})가 baseUrl($baseUrl)과 다르다",
                )
            }
            doc = found
            found
        }
    }

    fun send(req: Req): ApiResponse {
        val headers = linkedMapOf("accept" to "application/json", "user-agent" to userAgent)
        val body =
            req.body?.let {
                headers["content-type"] = "application/json"
                it.toJson().toByteArray(Charsets.UTF_8)
            }
        req.bearer?.let { headers["authorization"] = "Bearer $it" }
        req.idempotencyKey?.let { headers["idempotency-key"] = it }
        val res =
            try {
                ports.transport.send(HttpRequest(req.method, req.url, headers, body))
            } catch (e: Exception) {
                throw BydevException.wrap(e, Codes.NETWORK_ERROR, "API에 연결하지 못했다")
            }
        val correlation = res.headers["x-correlation-id"]
        val parsed = if (res.body.isEmpty()) null else JsonValue.parse(res.body)
        if (res.status in 200..299) {
            parsed
                ?: throw BydevException(
                    Codes.PROTOCOL_ERROR,
                    "응답 본문이 JSON이 아니다",
                    res.status,
                    correlation,
                )
            return ApiResponse(res.headers, parsed)
        }
        throw error(res.status, res.headers, parsed, correlation)
    }

    private companion object {
        /** 경로 조각 — 예약되지 않은 문자(A–Z a–z 0–9 - . _ ~)만 그대로. */
        fun escape(text: String, spacePlus: Boolean): String {
            val out = StringBuilder()
            for (b in text.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                when {
                    c in 'A'.code..'Z'.code ||
                        c in 'a'.code..'z'.code ||
                        c in '0'.code..'9'.code ||
                        c.toChar() in "-._~" -> out.append(c.toChar())
                    c == ' '.code && spacePlus -> out.append('+')
                    else -> out.append("%%%02X".format(c))
                }
            }
            return out.toString()
        }

        /** 3.2절. 실패 응답 → [BydevException]. */
        fun error(
            status: Int,
            headers: Map<String, String>,
            parsed: JsonValue?,
            correlation: String?,
        ): BydevException {
            var retry = headers["retry-after"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val code = parsed?.get("code")?.string
            val message = parsed?.get("message")?.string
            if (parsed is JsonValue.Obj && code != null && message != null) {
                parsed["retryAfterSec"]?.number?.let { retry = it.toInt() }
                return BydevException(
                    code,
                    message,
                    status,
                    correlation,
                    retry,
                    parsed["details"]?.obj,
                )
            }
            val fallback =
                when {
                    status >= 500 -> Codes.INTERNAL_ERROR
                    status == 429 -> Codes.RATE_LIMITED
                    else -> Codes.PROTOCOL_ERROR
                }
            return BydevException(fallback, "HTTP $status", status, correlation, retry)
        }
    }
}
