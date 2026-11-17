package dev.developedby.sdk

/** JSON 값 — 에러 상세(`details`)처럼 모양이 정해지지 않은 값과, 모델의 `toJson()`(기록 · 진단용)이다. 의존성 없이 읽고 쓴다. */
public sealed class JsonValue {
    public data object Null : JsonValue()

    public data class Bool(public val value: Boolean) : JsonValue()

    public data class Num(public val value: Double) : JsonValue()

    public data class Str(public val value: String) : JsonValue()

    public data class Arr(public val items: List<JsonValue>) : JsonValue()

    public data class Obj(public val fields: Map<String, JsonValue>) : JsonValue()

    public operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    public val string: String?
        get() = (this as? Str)?.value

    public val number: Double?
        get() = (this as? Num)?.value

    public val obj: Map<String, JsonValue>?
        get() = (this as? Obj)?.fields

    public val array: List<JsonValue>?
        get() = (this as? Arr)?.items

    /** 간결한 JSON 문자열 — 객체 키는 이름순(언제나 같은 문자열). */
    public fun toJson(): String = StringBuilder().also { write(this, it) }.toString()

    override fun toString(): String = toJson()

    public companion object {
        /** JSON 문자열을 읽는다. JSON이 아니면 null. */
        @JvmStatic
        public fun parse(text: String): JsonValue? =
            try {
                val p = Parser(text)
                val value = p.value()
                p.skipSpace()
                if (p.done) value else null
            } catch (_: IllegalArgumentException) {
                null
            }

        @JvmStatic
        public fun parse(bytes: ByteArray): JsonValue? = parse(String(bytes, Charsets.UTF_8))

        internal fun of(s: String?): JsonValue = s?.let(::Str) ?: Null

        internal fun of(n: Int?): JsonValue = n?.let { Num(it.toDouble()) } ?: Null

        internal fun of(n: Long?): JsonValue = n?.let { Num(it.toDouble()) } ?: Null

        internal fun of(b: Boolean): JsonValue = Bool(b)

        internal fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = Obj(mapOf(*pairs))

        private fun write(v: JsonValue, out: StringBuilder) {
            when (v) {
                Null -> out.append("null")
                is Bool -> out.append(if (v.value) "true" else "false")
                is Num ->
                    if (v.value % 1.0 == 0.0 && kotlin.math.abs(v.value) < 9e15) {
                        out.append(v.value.toLong())
                    } else {
                        out.append(v.value)
                    }
                is Str -> writeString(v.value, out)
                is Arr -> {
                    out.append('[')
                    v.items.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        write(item, out)
                    }
                    out.append(']')
                }
                is Obj -> {
                    out.append('{')
                    v.fields.keys.sorted().forEachIndexed { i, key ->
                        if (i > 0) out.append(',')
                        writeString(key, out)
                        out.append(':')
                        write(v.fields.getValue(key), out)
                    }
                    out.append('}')
                }
            }
        }

        private fun writeString(s: String, out: StringBuilder) {
            out.append('"')
            for (c in s) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c < ' ' -> out.append(String.format("\\u%04x", c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }

    private class Parser(private val s: String) {
        private var i = 0
        val done: Boolean
            get() = i == s.length

        fun skipSpace() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        private fun fail(): Nothing = throw IllegalArgumentException("JSON이 아니다")

        fun value(): JsonValue {
            skipSpace()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(str())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> num()
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return value
        }

        private fun obj(): JsonValue {
            i++
            val fields = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (i < s.length && s[i] == '}') {
                i++
                return Obj(fields)
            }
            while (true) {
                skipSpace()
                if (i >= s.length || s[i] != '"') fail()
                val key = str()
                skipSpace()
                if (i >= s.length || s[i] != ':') fail()
                i++
                fields[key] = value()
                skipSpace()
                if (i >= s.length) fail()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return Obj(fields)
                    else -> fail()
                }
            }
        }

        private fun arr(): JsonValue {
            i++
            val items = ArrayList<JsonValue>()
            skipSpace()
            if (i < s.length && s[i] == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                items.add(value())
                skipSpace()
                if (i >= s.length) fail()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return Arr(items)
                    else -> fail()
                }
            }
        }

        private fun str(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                out.append(
                                    s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail()
                                )
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    c < ' ' -> fail()
                    else -> out.append(c)
                }
            }
        }

        private fun num(): JsonValue {
            val m = NUMBER.matchAt(s, i) ?: fail()
            i = m.range.last + 1
            return Num(m.value.toDouble())
        }

        companion object {
            val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }
}

/** 응답 읽기(명세 3.2) — 처음 어긋난 곳을 `PROTOCOL_ERROR`로 기억하고 기본값으로 이어 읽는다. 모르는 필드는 버린다. */
internal class Reader {
    var error: BydevException? = null
        private set

    private fun fail(path: String, want: String, got: JsonValue?) {
        if (error != null) return
        val message =
            if (got == null) "응답에 ${path}가 없다" else "응답의 ${path}가 ${want}가 아니다 (${got.toJson()})"
        error = BydevException(Codes.PROTOCOL_ERROR, message)
    }

    fun obj(v: JsonValue?, path: String): Map<String, JsonValue> {
        if (v is JsonValue.Obj) return v.fields
        fail(path, "객체", v)
        return emptyMap()
    }

    fun str(v: JsonValue?, path: String): String {
        if (v is JsonValue.Str) return v.value
        fail(path, "문자열", v)
        return ""
    }

    fun long(v: JsonValue?, path: String): Long {
        if (v is JsonValue.Num && v.value % 1.0 == 0.0 && kotlin.math.abs(v.value) < 9e15)
            return v.value.toLong()
        fail(path, "정수", v)
        return 0
    }

    fun int(v: JsonValue?, path: String): Int = long(v, path).toInt()

    fun bool(v: JsonValue?, path: String): Boolean {
        if (v is JsonValue.Bool) return v.value
        fail(path, "참/거짓", v)
        return false
    }

    fun array(v: JsonValue?, path: String): List<JsonValue> {
        if (v is JsonValue.Arr) return v.items
        fail(path, "배열", v)
        return emptyList()
    }

    fun optStr(v: JsonValue?, path: String): String? =
        if (v == null || v == JsonValue.Null) null else str(v, path)

    fun optLong(v: JsonValue?, path: String): Long? =
        if (v == null || v == JsonValue.Null) null else long(v, path)

    fun <T> finish(value: T): T {
        error?.let { throw it }
        return value
    }
}
