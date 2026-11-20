import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
    `maven-publish`
    signing
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
}

group = "dev.developedby"

version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Maven Central은 소스 · javadoc jar를 요구한다(javadoc은 Kotlin만이라 비어 있다)
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    // 라이브러리 — 공개 범위를 모두 적는다
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}

ktfmt { kotlinLangStyle() }

// 생성 파일은 생성기가 모양을 정한다(pnpm verify에 JVM이 필요 없게)
tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
    exclude("**/Generated.kt")
}

// 출시(docs/runbooks/sdk-release.md) — `./gradlew publish`는 build/staging-deploy에 Maven Central
// 묶음(POM · 소스 · javadoc · 체크섬, 키를 주면
// 서명)을 쓴다. 올리기는 Central Portal에서 사람이 한다. scm은 공개 거울 저장소(바꾸려면 -Pbydev.scmUrl=<주소>)
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "bydev-sdk"
            from(components["java"])
            pom {
                name.set("bydev SDK")
                description.set(
                    "데브 라이선스 API 공식 Kotlin/Java SDK — 디바이스 로그인 · 토큰 회전 · 세션 리스 · licenseToken 검증 · 업데이트 확인"
                )
                url.set("https://developedby.dev/sell/docs")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit")
                    }
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("bydev")
                        name.set("한비트 주식회사")
                        url.set("https://developedby.dev")
                    }
                }
                scm {
                    val repository =
                        providers
                            .gradleProperty("bydev.scmUrl")
                            .orElse("https://github.com/developedby-dev/sdk-kotlin")
                    url.set(repository)
                    connection.set(repository.map { "scm:git:$it.git" })
                    developerConnection.set(repository.map { "scm:git:$it.git" })
                }
            }
        }
    }
    repositories { maven { url = uri(layout.buildDirectory.dir("staging-deploy")) } }
}

// 서명 키는 출시 때만 준다(-PsigningKey=<ASCII 갑옷 비밀 키> -PsigningPassword=…) — 없으면 서명하지 않는다
signing {
    val key = providers.gradleProperty("signingKey").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull)
        sign(publishing.publications["maven"])
    }
}
