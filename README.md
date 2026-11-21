# 데브 SDK: Kotlin / Java

데브 라이선스 API의 공식 JVM SDK입니다. Kotlin으로 작성했고, Java에서도 호출할 수 있습니다.

Compose Desktop, JavaFX, Swing 앱과 판매자
서버에서 씁니다. 의존성은 Kotlin 표준 라이브러리 하나뿐입니다.

HTTP는 `java.net.http`, Ed25519는 JDK 기능을 쓰고, JSON은 SDK가 직접 처리합니다.
모든 언어의 SDK가 함께 따르는 [공통 동작 명세](https://github.com/developedby-dev/sdk-spec/blob/main/spec/README.md)대로 동작하고, [적합성 시험 시나리오](https://github.com/developedby-dev/sdk-spec/tree/main/conformance/scenarios)를 모두 통과합니다.

| 지원 | 버전 |
|---|---|
| 동작 명세 | 1.2.0 |
| API | v1 |
| JVM | Java 17 이상(Kotlin 2.4, Java 모두) |
| 운영체제 | Windows, macOS, Linux |

Maven 좌표는 `dev.developedby:bydev-sdk`이고, 패키지는 `dev.developedby.sdk`입니다. Maven Central에서 받습니다
(Gradle `implementation("dev.developedby:bydev-sdk:1.0.0")`).

## 빠른 시작 (Kotlin)

이 SDK는 **블로킹 API**입니다. UI 스레드에서 부르지 마세요.

코루틴에서는 `withContext(Dispatchers.IO)` 안에서 호출해 주세요.

```kotlin
import dev.developedby.sdk.*

val clientId = "cli_01J9M0Y6X4N3Q8ZD5R2KT7VCPW" // 판매자 콘솔에서 제품마다 발급받습니다
val client = LicenseClient(
    LicenseClient.Config(
        baseUrl = "https://api.developedby.dev",
        clientId = clientId,
        productId = "prod_01J9KZ3F7W2X8YQ4N6M5R1TBCD",
        scopes = listOf(Scope.ENTITLEMENT_READ, Scope.SESSION_WRITE, Scope.VERSION_READ),
        device = OsPorts.systemDeviceInfo(),
        app = AppInfo(DistributionTarget.WINDOWS_X64, "1.4.0"), // 콘솔에 업로드한 빌드의 버전과 같게
        ports = Ports()
            .store(OsPorts.defaultCredentialStore("MyApp", clientId, logger))
            .fingerprint(OsPorts.fingerprintSource("${OsPorts.appDataDir("MyApp")}/install-id")),
    )
)

if (!client.hasCredentials()) {
    val login = client.startLogin()
    showCode(login.userCode, login.verificationUriComplete) // 화면에 코드와 QR 코드를 보여 줍니다
    login.await() // 사용자가 브라우저에서 승인할 때까지 기다립니다. 취소는 login.cancel()로 합니다
}

val session = client.session()
val subscription = session.subscribe { state -> // SDK의 스레드에서 불리므로, UI 작업은 UI 스레드로 넘기세요
    when (state) {
        is SessionState.Active -> enableFeatures(state.claims) // exp와 sid는 기능 코드에서 쓰고, 참/거짓 하나로만 판단하지 마세요
        is SessionState.Degraded -> enableFeatures(state.claims) // offlineUntil까지는 계속 쓸 수 있습니다
        is SessionState.NeedsDecision -> askUser(state) // DEVICE_IN_USE: "여기서 사용할까요?"에 동의하면 session.takeOver()를 부릅니다
        is SessionState.Locked -> lockApp(state.code, state.correlationId) // 오류 화면에 correlationId를 보여 주세요
        else -> {}
    }
}
session.start()
// 앱을 끌 때
session.stop()
subscription.unsubscribe()
```

## 빠른 시작 (Java)

```java
import dev.developedby.sdk.*;

LicenseClient client = new LicenseClient(new LicenseClient.Config(
        "https://api.developedby.dev", clientId, "prod_01J9KZ3F7W2X8YQ4N6M5R1TBCD",
        List.of(Scope.ENTITLEMENT_READ, Scope.SESSION_WRITE),
        OsPorts.systemDeviceInfo(),
        new Ports().store(OsPorts.defaultCredentialStore("MyApp", clientId)),
        new AppInfo(DistributionTarget.WINDOWS_X64, "1.4.0"))); // 콘솔에 업로드한 빌드의 버전과 같게
Subscription subscription = client.session().subscribe(state -> {
    if (state instanceof SessionState.Active active) enableFeatures(active.getClaims());
});
client.session().start();
// 실패하면 BydevException이 발생하며, checked 예외가 아닙니다. e.getCode()를 Codes.*와 비교하세요
```

## 포트

포트는 SDK가 HTTP 전송, 로그인 정보 저장, 시계 같은 바깥 기능을 쓰는 연결 지점입니다. 앱이 원하는 구현으로 교체할 수 있습니다.

명세에서 쓰는 포트 이름 일부가 `java.time.Clock`, `kotlin.random.Random`, `java.util.logging.Logger`와 겹쳐서 다른 이름을 붙였습니다. Swift SDK와 같은
이름입니다.

| 포트(명세 이름) | 기본 구현 | 직접 구현하거나 설정할 항목 |
|---|---|---|
| `HttpTransport`(Transport) | `JdkHttpTransport`입니다. `java.net.http`를 쓰고, 시간 초과는 30초이며, 리다이렉트를 따라가지 않습니다. | 프록시나 인증서 고정이 필요하면 `JdkHttpTransport(timeout, httpClient)`에 직접 만든 클라이언트를 전달해 주세요. 이때 리다이렉트를 막는 것은 앱이 맡습니다. |
| `CredentialStore` | **메모리**에만 둡니다. 앱을 끄면 로그인이 사라집니다. | `OsPorts.defaultCredentialStore`, `dpapiCredentialStore`, `secretToolCredentialStore`, `fileCredentialStore` |
| `FingerprintSource` | 없습니다. 실행할 때마다 새 기기로 보일 수 있으며, 이때 경고 로그를 남깁니다. | `OsPorts.fingerprintSource(설치 ID 파일)`를 사용해 주세요. 운영체제에 따라 MachineGuid, IOPlatformUUID, machine-id를 쓰고, 읽지 못하면 설치 ID를 씁니다. |
| `WallClock`, `TaskScheduler`, `RandomSource`(Clock, Scheduler, Random) | 시스템 시계, `ThreadScheduler`(데몬 스레드), `SecureRandom` | 시험할 때 교체합니다. SDK는 시간이나 다른 작업을 기다릴 때 `sleep`과 `wait`만 씁니다. |
| `LogSink`(Logger) | 아무것도 기록하지 않습니다(`SilentLog`). | 로그에는 토큰이나 기기 지문 원본이 넘어오지 않습니다. |

`defaultCredentialStore`는 운영체제마다 저장하는 곳이 다릅니다.

- Windows: DPAPI로 보호한 파일에 저장합니다.
- Linux: Secret Service에 저장합니다. `secret-tool`이 없으면 권한 0600 파일에 저장하고 경고를 남깁니다.
- macOS: 권한 0600 파일에 저장하고 경고를 남깁니다.

macOS에서 키체인을 쓰지 않는 이유는, JVM에서는 네이티브 라이브러리 없이 키체인을 안전하게 쓸 방법이 없기 때문입니다. `security` 명령은 비밀 값을
명령 인자로 받습니다. TS SDK, Go SDK와 같은 이유입니다.

키체인이 필요한 앱은 `CredentialStore`를 직접 구현해 주세요. SDK는 비밀 값(refresh_token)을
명령줄 인자로 넘기지 않고, `secret-tool`과 PowerShell에 표준 입력으로만 전달합니다.

**Linux 배포 패키지에는 `secret-tool`을 의존성으로 걸어 주세요.** 기본 저장소는 이 명령으로 Secret Service에 접근하는데, Debian과 Ubuntu 데스크톱의
기본 설치에는 이 명령이 없습니다.

Ubuntu 26.04.1에서 확인했으며, Secret Service 자체는 돌고 있었습니다. .deb 패키지는 `libsecret-tools`를 `Depends`나
`Recommends`에 넣고, RPM(Fedora 등)과 Arch 패키지는 `libsecret`을 넣어 주세요.

의존성을 지정할 수 없는 AppImage나 tar.gz 배포본에는 `secret-tool` 설치 방법을 안내해 주세요. 명령이 없으면 SDK는 권한 0600 파일에 저장하고 경고 로그를 남깁니다.

## 판매자 서버

```kotlin
// 웹훅: 받은 본문 그대로 검증하세요. 파싱했다가 다시 만든 바이트로는 서명이 맞지 않습니다. 시크릿을 교체하는 중이면 둘 다 넘기세요
val webhook = Webhooks.verify(listOf(secret), headers, rawBody)

// 앱이 보낸 access_token과 licenseToken을 검증합니다. JWKS를 받아 캐시하므로 인스턴스 하나를 오래 쓰세요
val verifier = ServerVerifier("https://api.developedby.dev", clientId)
val claims = verifier.verifyAccessToken(token)
```

## 사용 조건

- **업데이트**: `checkForUpdate(target, currentVersion)`가 반환한 `updateStatus`가 `REQUIRED`이면, 업데이트하기 전까지 앱 실행을 막아 주세요.
  다운로드한 파일은 설치하기 전에 `verifyDownload(data, (check.install as ProductInstall.Hosted).sha256)`로 확인해 주세요.
  업데이트 제공 기한이 있는 구매자는 결과에 `entitled`가 들어 있습니다. 그때는 최상위 대신 `entitled.release`를 따라 주세요.
  최상위 최신 버전은 그 구매자가 실행할 수 없는 버전일 수 있고, `entitled.release`가 비어 있으면 그 대상에 제공 범위 안의 버전이 없습니다.
- **오류**: 실패하면 `BydevException`이 발생합니다. 분기는 `code`로 하고, 비교할 값은 생성된 상수 `Codes.*`에 있습니다. `message`는 화면에 보여 주는
  용도로만 사용해 주세요. `isTransient`가 참이면 다시 시도해 볼 만한 오류입니다.
- **모르는 값**: `EntitlementStatus` 같은 열거 값은 `String`입니다. 그래서 이 버전의 SDK가 모르는 새 값이 와도 그대로 담깁니다. `when`에는 `else`를
  두어 주세요.

  모든 모델에는 `toJson()`이 있어서 기록하거나 진단할 때 쓸 수 있습니다.
- 라이선스는 MIT 또는 Apache-2.0입니다.

## 출처 확인

이 꾸러미는 공개 저장소 `developedby-dev/sdk-kotlin`의 GitHub Actions가 판 태그에서 짓고 올립니다. Maven Central에 올린 jar(PGP 서명도 함께 있습니다)에는 GitHub 출처 증명이 있습니다. 받은 파일을 `gh attestation verify <파일> --repo developedby-dev/sdk-kotlin`로 확인할 수 있습니다.

## 개발

| 명령 | 하는 일 |
|---|---|
| `./gradlew build` | 라이브러리를 빌드하고 시험(JUnit 6)을 돌립니다. Kotlin 시험과, Java에서 부르는 모양을 확인하는 시험이 있습니다. 실제 전송 시험은 적합성 시험용 가짜 서버를 HTTP 모드로 node에서 띄워 확인하며, 쓸 node는 `BYDEV_NODE`로 바꿀 수 있습니다. |
| `./gradlew ktfmtFormat` | ktfmt로 서식을 맞춥니다(Kotlin 공식 스타일). |
| `./gradlew :harness:installDist` | 적합성 하네스를 설치합니다. 실행 파일은 `harness/build/install/harness/bin/harness`에 생깁니다. |
| `pnpm sdk:check kotlin` | ktfmt 검사, 빌드(경고도 오류로 봅니다), 시험, 적합성 시나리오 48개를 모두 돌립니다. |

`src/main/kotlin/dev/developedby/sdk/Generated.kt`는 생성된 파일이며, `pnpm --filter @bydev/sdk-codegen generate`로 다시 만듭니다. 이 파일은 ktfmt
검사에서 뺍니다.

Gradle wrapper는 배포본의 SHA-256을 `gradle/wrapper/gradle-wrapper.properties`에 고정해 두었습니다.
