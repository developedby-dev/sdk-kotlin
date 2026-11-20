package dev.developedby.sdk

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** 명령 · 파일을 기록하는 가짜 — 운영체제 명령이 없는 곳에서 명령 모양과 해석을 본다. */
private class FakeOs(override val name: String, var answers: Map<String, Any> = emptyMap()) : Os {
    data class Call(val program: String, val args: List<String>, val stdin: String?)

    val calls = ArrayList<Call>()
    val files = HashMap<String, String>()
    val tools = HashSet<String>()

    val slept = ArrayList<Long>()

    override fun exec(program: String, args: List<String>, stdin: String?): String {
        calls.add(Call(program, args, stdin))
        val key =
            if (answers.containsKey("$program ${args.first()}")) "$program ${args.first()}"
            else program
        var a = answers[key]
        if (a is List<*>) { // 차례로 — 마지막 값을 되풀이
            if (a.size > 1) answers = answers + (key to a.drop(1))
            a = a.first()
        }
        return when (a) {
            is Exception -> throw a
            is String -> a
            else -> ""
        }
    }

    override fun sleep(millis: Long) {
        slept.add(millis)
    }

    override fun has(program: String): Boolean = program in tools

    override fun readFile(path: String): String? = files[path]

    override fun writeFile(path: String, content: String) {
        files[path] = content
    }

    override fun remove(path: String) {
        files.remove(path)
    }

    override fun newId(): String = "00000000-0000-4000-8000-00000000abcd"
}

private class RecordingLog : LogSink {
    val warns = ArrayList<String>()

    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(message: String) {
        warns.add(message)
    }
}

class PlatformTest {
    private val record =
        CredentialRecord(1, "rt_node_test_00000000001", "entitlement.read", "2026-10-06T13:00:00Z")
    private val recordJson =
        """{"version":1,"refreshToken":"rt_node_test_00000000001","scope":"entitlement.read","savedAt":"2026-10-06T13:00:00Z"}"""

    @Test
    fun fingerprintPerOs() {
        val win =
            FakeOs(
                "windows",
                mapOf(
                    "reg" to
                        "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography\r\n    MachineGuid    REG_SZ    3f1e9a2b-1111-2222-3333-444455556666\r\n"
                ),
            )
        assertEquals("3f1e9a2b-1111-2222-3333-444455556666", OsFingerprint(null, win).read())
        val mac =
            FakeOs(
                "macos",
                mapOf("ioreg" to "  \"IOPlatformUUID\" = \"A1B2C3D4-0000-1111-2222-333344445555\""),
            )
        assertEquals("A1B2C3D4-0000-1111-2222-333344445555", OsFingerprint(null, mac).read())
        val linux =
            FakeOs("linux").apply {
                files["/var/lib/dbus/machine-id"] = "0123456789abcdef0123456789abcdef\n"
            }
        assertEquals("0123456789abcdef0123456789abcdef", OsFingerprint(null, linux).read())
    }

    /** 1.2.0 — 2026-11-20 Windows에서 MachineGuid 읽기가 한 번 실패해 설치 ID로 등록됐다(같은 PC가 다른 기기) */
    @Test
    fun fingerprintRetriesBeforeInstallId() {
        val guid = "    MachineGuid    REG_SZ    3f1e9a2b-1111-2222-3333-444455556666\r\n"
        val win = FakeOs("windows", mapOf("reg" to listOf(IOException("잠시 실패"), "", guid)))
        assertEquals(
            "3f1e9a2b-1111-2222-3333-444455556666",
            OsFingerprint("/app/install-id", win).read(),
        )
        assertTrue(win.files.isEmpty(), "설치 ID를 만들지 않는다")
        assertEquals(3, win.calls.size)
        val delay = Rules.FINGERPRINT_SOURCE_READ_RETRY_DELAY_MS.toLong()
        assertEquals(listOf(delay, delay), win.slept)
        val failing = FakeOs("windows", mapOf("reg" to IOException("계속 실패")))
        assertEquals(
            "00000000-0000-4000-8000-00000000abcd",
            OsFingerprint("/app/install-id", failing).read(),
        )
        assertEquals(Rules.FINGERPRINT_SOURCE_READ_ATTEMPTS, failing.calls.size)
    }

    @Test
    fun fingerprintInstallIdFallback() {
        val os = FakeOs("macos", mapOf("ioreg" to java.io.IOException("권한 없음")))
        val source = OsFingerprint("/app/install-id", os)
        val first = source.read()
        assertEquals("00000000-0000-4000-8000-00000000abcd", first)
        assertEquals(first, os.files["/app/install-id"])
        os.files["/app/install-id"] = "kept-install-id\n"
        assertEquals("kept-install-id", source.read())
        // 파일 자리도 없으면 null(SDK가 수명 동안의 난수 ID로 물러난다)
        assertNull(OsFingerprint(null, os).read())
    }

    @Test
    fun secretToolStore() {
        val os = FakeOs("linux", mapOf("secret-tool lookup" to ExitCodeException("secret-tool", 1)))
        val store = SecretToolStore("cli_test", os)
        assertNull(store.load()) // 항목 없음
        store.save(record)
        val save = os.calls[1]
        assertEquals(
            "store --label=bydev cli_test service ${Rules.STORAGE_SERVICE} account cli_test",
            save.args.joinToString(" "),
        )
        // 비밀은 인자에 없고 표준 입력으로만
        assertFalse(save.args.any { record.refreshToken in it })
        assertTrue(record.refreshToken in save.stdin!!)
        os.answers = mapOf("secret-tool lookup" to recordJson)
        assertEquals(record, store.load())
        // 다른 실패는 던진다 — SDK가 STORAGE_FAILED로 감싼다
        os.answers = mapOf("secret-tool lookup" to ExitCodeException("secret-tool", 2))
        assertThrows<ExitCodeException> { store.load() }
        // 없는 항목 지우기는 성공 — 오류 출력이 있으면(D-Bus 없음 등) 읽기 · 지우기 모두 던진다
        os.answers = mapOf("secret-tool clear" to ExitCodeException("secret-tool", 1))
        store.clear()
        val broken =
            ExitCodeException(
                "secret-tool",
                1,
                "secret-tool: Cannot autolaunch D-Bus without X11 \$DISPLAY\n",
            )
        os.answers = mapOf("secret-tool clear" to broken, "secret-tool lookup" to broken)
        assertThrows<ExitCodeException> { store.clear() }
        assertThrows<ExitCodeException> { store.load() }
    }

    @Test
    fun dpapiStore() {
        val os = FakeOs("windows", mapOf("powershell.exe" to "c2VhbGVk\r\n"))
        val store = DpapiStore("C:\\app\\bydev-credentials", os)
        store.save(record)
        assertEquals("c2VhbGVk", os.files["C:\\app\\bydev-credentials"])
        val protect = os.calls[0]
        assertFalse(protect.args.joinToString(" ").contains(record.refreshToken))
        assertTrue(record.refreshToken in protect.stdin!!)
        os.answers = mapOf("powershell.exe" to recordJson)
        assertEquals(record, store.load())
        assertEquals("c2VhbGVk", os.calls[1].stdin)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun fileStoreRealDisk(@TempDir dir: File) {
        val path = File(dir, "sub/bydev-credentials").path
        val log = RecordingLog()
        val store = OsPorts.fileCredentialStore(path, log)
        assertEquals(1, log.warns.size) // 암호화하지 않는다는 warn
        assertNull(store.load())
        store.save(record)
        if ("posix" in File(path).toPath().fileSystem.supportedFileAttributeViews()) {
            assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(File(path).toPath())),
            )
        }
        assertEquals(record, store.load())
        // 판이 다른 기록은 없는 것으로 읽는다
        File(path).writeText("""{"version":99,"refreshToken":"x"}""")
        assertNull(store.load())
        store.clear()
        store.clear() // 없는 파일 지우기는 실패가 아니다
        assertEquals(0, File(dir, "sub").list()!!.size) // 임시 파일이 남지 않는다
    }

    @Test
    fun defaultStoreChoice() {
        assertInstanceOf(
            DpapiStore::class.java,
            OsPorts.defaultStore("App", "cli", null, FakeOs("windows")),
        )
        val log = RecordingLog()
        assertInstanceOf(
            FileStore::class.java,
            OsPorts.defaultStore("App", "cli", log, FakeOs("linux")),
        ) // secret-tool이 없으면
        assertEquals(1, log.warns.size)
        val linux = FakeOs("linux").apply { tools.add("secret-tool") }
        assertInstanceOf(
            SecretToolStore::class.java,
            OsPorts.defaultStore("App", "cli", null, linux),
        )
        assertInstanceOf(
            FileStore::class.java,
            OsPorts.defaultStore("App", "cli", null, FakeOs("macos")),
        )
        assertTrue(OsPorts.appDataDir("App", "macos").endsWith("Library/Application Support/App"))
    }

    /** 이 기기의 실제 지문 출처를 한 번 읽는다(값은 출력하지 않는다). */
    @Test
    fun fingerprintThisMachine() {
        if (RealOs.name == "other") return
        assertFalse(OsPorts.fingerprintSource().read().isNullOrEmpty(), "이 기기의 지문 출처를 읽지 못했다")
        assertTrue(OsPorts.systemDeviceInfo().name.length in 1..64)
    }
}
