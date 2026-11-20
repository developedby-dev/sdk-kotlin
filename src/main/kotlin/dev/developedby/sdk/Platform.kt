package dev.developedby.sdk

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.concurrent.thread

// 명세 2절 표 — 운영체제별 기본 구현. 원본 지문 · 토큰을 명령 줄 인자로 넘기지 않는다(비밀은 표준 입력으로만). TS · Go와 같은 명령 모양이다 —
// JVM에서 네이티브 라이브러리(JNA 등)를 들이지 않는다. 클라이언트의 기본 저장소는 메모리다 — 앱이 이 중에서 고른다.

/** 명령 실행 · 파일 — 단위 시험이 바꿔 끼운다. */
internal interface Os {
    val name: String

    fun exec(program: String, args: List<String>, stdin: String? = null): String

    fun has(program: String): Boolean

    fun readFile(path: String): String?

    fun writeFile(path: String, content: String)

    fun remove(path: String)

    fun newId(): String

    /** 지문 출처를 다시 읽기 전 기다림(명세 10절) — 단위 시험은 기다리지 않게 바꿔 끼운다 */
    fun sleep(millis: Long) = Thread.sleep(millis)
}

/** 명령이 0이 아닌 코드로 끝났다. [stderr]는 그 명령의 오류 출력이다 */
internal class ExitCodeException(program: String, val code: Int, val stderr: String = "") :
    IOException("${program}가 ${code}로 끝났다")

internal object RealOs : Os {
    override val name: String =
        System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("windows") -> "windows"
                it.startsWith("mac") -> "macos"
                it.startsWith("linux") -> "linux"
                else -> "other"
            }
        }

    override fun exec(program: String, args: List<String>, stdin: String?): String {
        val process = ProcessBuilder(listOf(program) + args).start()
        // 오류 출력도 받아 실어 보낸다 — secret-tool은 "항목 없음"과 오류를 같은 종료 코드(1)로 내고 오류 출력으로만 가른다. 따로 읽는다
        // (한쪽 파이프가 차서 멈추지 않게)
        var errors = ""
        val errorReader =
            thread(isDaemon = true) {
                errors = process.errorStream.readBytes().toString(Charsets.UTF_8)
            }
        process.outputStream.use { out ->
            if (stdin != null) out.write(stdin.toByteArray(Charsets.UTF_8))
        }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        errorReader.join()
        if (code != 0) throw ExitCodeException(program, code, errors)
        return output
    }

    override fun has(program: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
            it.isNotEmpty() && File(it, program).canExecute()
        }

    override fun readFile(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    /** 사용자만 읽는 권한(0600, POSIX) 임시 파일 뒤 이름 바꾸기(반쯤 쓴 파일이 남지 않게, 다른 사용자가 읽는 틈이 없게). */
    override fun writeFile(path: String, content: String) {
        val target = Path.of(path).toAbsolutePath()
        Files.createDirectories(target.parent)
        val posix = "posix" in target.fileSystem.supportedFileAttributeViews()
        val temp =
            if (posix) {
                Files.createTempFile(
                    target.parent,
                    ".${target.fileName}.",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")
                    ),
                )
            } else {
                Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
            }
        try {
            Files.writeString(temp, content)
            Files.move(
                temp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun remove(path: String) {
        Files.deleteIfExists(Path.of(path))
    }

    override fun newId(): String = UUID.randomUUID().toString()
}

/** 운영체제별 기본 구현을 고른다. */
public object OsPorts {
    /** 운영체제 · 앱 이름별 데이터 폴더다. */
    @JvmStatic public fun appDataDir(appName: String): String = appDataDir(appName, RealOs.name)

    internal fun appDataDir(appName: String, os: String): String {
        val home = System.getProperty("user.home")
        return when (os) {
            "windows" ->
                File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, appName).path
            "macos" -> File(home, "Library/Application Support/$appName").path
            else ->
                File(
                        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
                            ?: File(home, ".config").path,
                        appName,
                    )
                    .path
        }
    }

    /** `Config.device`의 기본값 — 컴퓨터 이름(64자까지)과 운영체제. */
    @JvmStatic
    public fun systemDeviceInfo(): DeviceInfo {
        val platform =
            when (RealOs.name) {
                "windows" -> DevicePlatform.WINDOWS
                "macos" -> DevicePlatform.MACOS
                "linux" -> DevicePlatform.LINUX
                else -> DevicePlatform.OTHER
            }
        val name =
            (System.getenv("COMPUTERNAME")
                    ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
                ?.takeIf { it.isNotBlank() } ?: "PC"
        return DeviceInfo(name.take(64), platform)
    }

    /**
     * 명세 10절 · 2절 표의 지문 출처 — Windows MachineGuid · macOS IOPlatformUUID · Linux /etc/machine-id. 읽지
     * 못하면 [installIdFile]의 설치 ID(없으면 만든다). [installIdFile]이 null이면 null(SDK가 클라이언트 수명 동안의 난수 ID로
     * 물러난다).
     */
    @JvmStatic
    @JvmOverloads
    public fun fingerprintSource(installIdFile: String? = null): FingerprintSource =
        OsFingerprint(installIdFile, RealOs)

    /** 사용자 전용 권한(0600) 파일 — **암호화하지 않는다.** 운영체제 보안 저장소를 쓸 수 없을 때만(warn 로그). */
    @JvmStatic
    @JvmOverloads
    public fun fileCredentialStore(path: String, logger: LogSink? = null): CredentialStore =
        FileStore(path, logger, RealOs)

    /** Windows DPAPI(현재 사용자 범위)로 봉한 파일 — PowerShell에 표준 입력으로 넘긴다. */
    @JvmStatic
    public fun dpapiCredentialStore(path: String): CredentialStore = DpapiStore(path, RealOs)

    /** Linux Secret Service(libsecret `secret-tool`) — 비밀은 표준 입력으로 넘긴다. account는 clientId. */
    @JvmStatic
    public fun secretToolCredentialStore(account: String): CredentialStore =
        SecretToolStore(account, RealOs)

    /**
     * 운영체제에 맞는 기본 저장소 — Windows DPAPI 파일 · Linux Secret Service(`secret-tool`이 없으면 0600 파일 + warn)
     * · macOS는 0600 파일 + warn(JVM에서 키체인을 안전하게 쓸 길이 없다 — `security` 명령은 비밀을 인자로 받는다, ADR-0040 ⚠ 10).
     * account는 clientId.
     */
    @JvmStatic
    @JvmOverloads
    public fun defaultCredentialStore(
        appName: String,
        account: String,
        logger: LogSink? = null,
    ): CredentialStore = defaultStore(appName, account, logger, RealOs)

    internal fun defaultStore(
        appName: String,
        account: String,
        logger: LogSink?,
        os: Os,
    ): CredentialStore {
        val file = File(appDataDir(appName, os.name), "bydev-credentials").path
        return when {
            os.name == "windows" -> DpapiStore(file, os)
            os.name == "linux" && os.has("secret-tool") -> SecretToolStore(account, os)
            else -> FileStore(file, logger, os)
        }
    }

    internal fun parseRecord(text: String?): CredentialRecord? {
        val o = text?.let(JsonValue::parse)?.obj ?: return null
        if (o["version"]?.number != Rules.STORAGE_RECORD_VERSION.toDouble()) return null
        val token = o["refreshToken"]?.string?.takeIf { it.isNotEmpty() } ?: return null
        return CredentialRecord(1, token, o["scope"]?.string ?: "", o["savedAt"]?.string ?: "")
    }

    internal fun writeRecord(r: CredentialRecord): String =
        JsonValue.obj(
                "version" to JsonValue.of(r.version),
                "refreshToken" to JsonValue.Str(r.refreshToken),
                "scope" to JsonValue.Str(r.scope),
                "savedAt" to JsonValue.Str(r.savedAt),
            )
            .toJson()
}

internal class OsFingerprint(private val installIdFile: String?, private val os: Os) :
    FingerprintSource {
    /** 실패하거나 빈 값이면 설치 ID로 물러나기 전에 다시 읽는다(명세 10절) — 일시적인 실패 하나로 같은 PC가 다른 기기가 되지 않게. */
    override fun read(): String? {
        repeat(maxOf(Rules.FINGERPRINT_SOURCE_READ_ATTEMPTS, 1)) { attempt ->
            if (attempt > 0) os.sleep(Rules.FINGERPRINT_SOURCE_READ_RETRY_DELAY_MS.toLong())
            runCatching { machineId() }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    return it
                }
        }
        val file = installIdFile ?: return null
        os.readFile(file)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                return it
            }
        return os.newId().also { os.writeFile(file, it) }
    }

    private fun machineId(): String? =
        when (os.name) {
            "windows" ->
                MACHINE_GUID.find(
                        os.exec(
                            "reg",
                            listOf(
                                "query",
                                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                                "/v",
                                "MachineGuid",
                            ),
                        )
                    )
                    ?.groupValues
                    ?.get(1)
            "macos" ->
                PLATFORM_UUID.find(os.exec("ioreg", listOf("-rd1", "-c", "IOPlatformExpertDevice")))
                    ?.groupValues
                    ?.get(1)
            else ->
                os.readFile("/etc/machine-id")?.takeIf { it.isNotBlank() }
                    ?: os.readFile("/var/lib/dbus/machine-id")
        }

    private companion object {
        val MACHINE_GUID = Regex("MachineGuid\\s+REG_SZ\\s+(\\S+)")
        val PLATFORM_UUID = Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"")
    }
}

internal class FileStore(private val path: String, logger: LogSink?, private val os: Os) :
    CredentialStore {
    init {
        logger?.warn("로그인 기록을 암호화하지 않은 사용자 전용 파일에 둔다 — 운영체제 보안 저장소를 쓸 수 없는 환경")
    }

    override fun load(): CredentialRecord? = OsPorts.parseRecord(os.readFile(path))

    override fun save(record: CredentialRecord): Unit =
        os.writeFile(path, OsPorts.writeRecord(record))

    override fun clear(): Unit = os.remove(path)
}

/**
 * secret-tool이 항목을 찾지 못했다 — 오류 출력 없이 1로 끝난다. 오류(D-Bus 없음 · 잠긴 모음 · 없는 모음)도 1이지만 `secret-tool: …`를
 * 쓴다 (2026-10-17 Debian 12 libsecret 0.20에서 확인 — ADR-0040 Linux 실제 확인 기록)
 */
private fun ExitCodeException.secretToolFoundNothing(): Boolean = code == 1 && stderr.isBlank()

internal class SecretToolStore(private val account: String, private val os: Os) : CredentialStore {
    private fun attributes(verb: String, vararg extra: String) =
        listOf(verb) + extra + listOf("service", Rules.STORAGE_SERVICE, "account", account)

    override fun load(): CredentialRecord? =
        try {
            OsPorts.parseRecord(os.exec("secret-tool", attributes("lookup")))
        } catch (e: ExitCodeException) {
            if (e.secretToolFoundNothing()) null else throw e
        }

    override fun save(record: CredentialRecord) {
        os.exec(
            "secret-tool",
            attributes("store", "--label=bydev $account"),
            OsPorts.writeRecord(record),
        )
    }

    /** 없는 항목을 지우는 것은 성공이다(로그아웃은 몇 번 해도 된다) */
    override fun clear() {
        try {
            os.exec("secret-tool", attributes("clear"))
        } catch (e: ExitCodeException) {
            if (!e.secretToolFoundNothing()) throw e
        }
    }
}

internal class DpapiStore(private val path: String, private val os: Os) : CredentialStore {
    private fun ps(script: String, input: String) =
        os.exec(
            "powershell.exe",
            listOf("-NoProfile", "-NonInteractive", "-Command", script),
            input,
        )

    override fun load(): CredentialRecord? {
        val sealed = os.readFile(path)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return OsPorts.parseRecord(ps(UNPROTECT, sealed))
    }

    override fun save(record: CredentialRecord): Unit =
        os.writeFile(path, ps(PROTECT, OsPorts.writeRecord(record)).trim())

    override fun clear(): Unit = os.remove(path)

    private companion object {
        const val PROTECT =
            "Add-Type -AssemblyName System.Security; \$b=[Text.Encoding]::UTF8.GetBytes([Console]::In.ReadToEnd()); " +
                "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect(\$b,\$null,'CurrentUser'))"
        const val UNPROTECT =
            "Add-Type -AssemblyName System.Security; \$b=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); " +
                "[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect(\$b,\$null,'CurrentUser'))"
    }
}
