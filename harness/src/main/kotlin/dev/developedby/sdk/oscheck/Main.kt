// 운영체제 포트 실제 확인 — Windows · Linux 기기에서 돌린다(launch-checklist A31 · ADR-0040 ⚠ 51). 사용자 보안 저장소를 건드리므로
// BYDEV_KEYCHAIN_CHECK=1일 때만 — `BYDEV_KEYCHAIN_CHECK=1 ./gradlew -q :harness:osCheck`.
//
// 기본 저장소(defaultCredentialStore — Windows DPAPI 파일 · Linux Secret Service)에 항목 하나(계정
// bydev-keychain-check)를 넣고 · 고치고 · 읽고 ·
// 지운다. 보안 저장소 대신 0600 파일로 떨어지면(경고 로그) 실패다. 지문 출처(fingerprintSource — MachineGuid · machine-id)가 설치
// ID 파일 없이
// 운영체제 값을 읽고 두 번 읽어도 같은지 본다.
package dev.developedby.sdk.oscheck

import dev.developedby.sdk.CredentialRecord
import dev.developedby.sdk.LogSink
import dev.developedby.sdk.OsPorts
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

private const val APP = "bydev-os-check"
private const val ACCOUNT = "bydev-keychain-check"

private class Warnings : LogSink {
    val seen = mutableListOf<String>()

    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(message: String) {
        seen.add(message)
    }
}

fun main() {
    // 결과 줄의 ✓ · — 는 Windows ANSI 코드 페이지(한국어는 CP949)에 없다 — Java 17은 출력을 그 코드 페이지로 쓴다. UTF-8로 쓴다
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    exitProcess(run())
}

private fun run(): Int {
    if (System.getenv("BYDEV_KEYCHAIN_CHECK") != "1") {
        println("BYDEV_KEYCHAIN_CHECK=1이 아니라 건너뛴다 — 사용자 보안 저장소를 건드린다")
        return 0
    }
    val os = System.getProperty("os.name").lowercase()
    val windows = os.startsWith("windows")
    if (!windows && !os.startsWith("linux")) {
        println("${System.getProperty("os.name")}는 대상이 아니다 — Windows · Linux에서 돌린다")
        return 0
    }
    val failures = mutableListOf<String>()
    val device = OsPorts.systemDeviceInfo()
    println("기기 ${device.name} · ${device.platform}")

    val fingerprint = OsPorts.fingerprintSource()
    val first = fingerprint.read()
    val again = fingerprint.read()
    when {
        first.isNullOrEmpty() -> failures.add("지문 — 운영체제 값을 읽지 못했다(설치 ID 없이)")
        first != again -> failures.add("지문 — 두 번 읽은 값이 다르다")
        else -> println("✓ 지문 출처 — 운영체제 값(길이 ${first.length})을 읽었고 두 번 같다")
    }

    val logger = Warnings()
    val store = OsPorts.defaultCredentialStore(APP, ACCOUNT, logger)
    val record =
        CredentialRecord(1, "rt_node_test_00000000001", "entitlement.read", "2026-10-06T13:00:00Z")
    val rotated = record.copy(refreshToken = "rt_keychain_rotated_000001")
    try {
        store.clear()
        if (store.load() != null) failures.add("저장소 — 지운 뒤에도 항목이 있다")
        for (want in listOf(record, rotated)) {
            store.save(want)
            if (store.load() != want) failures.add("저장소 — 넣은 것을 읽지 못했다")
        }
        store.clear()
        if (store.load() != null) failures.add("저장소 — 지운 항목이 남았다")
        if (logger.seen.isNotEmpty()) {
            failures.add("저장소 — 보안 저장소가 아니라 파일로 떨어졌다: ${logger.seen.joinToString(" / ")}")
        } else if (failures.none { it.startsWith("저장소") }) {
            println(
                "✓ 기본 저장소(${if (windows) "DPAPI 파일" else "Secret Service"}) — 넣기 · 고치기 · 읽기 · 지우기"
            )
        }
    } catch (e: Exception) {
        failures.add("저장소 — ${e.message}${e.cause?.message?.let { " — $it" } ?: ""}")
    } finally {
        runCatching { store.clear() }
        File(OsPorts.appDataDir(APP)).deleteRecursively()
    }
    for (failure in failures) println("✗ $failure")
    return if (failures.isEmpty()) 0 else 1
}
