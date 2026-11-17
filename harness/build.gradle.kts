import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
    id("com.ncorti.ktfmt.gradle")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies { implementation(project(":")) }

application {
    mainClass.set("dev.developedby.sdk.harness.MainKt")
    applicationName = "harness"
}

ktfmt { kotlinLangStyle() }

// 운영체제 포트 실제 확인(launch-checklist A31) — Windows · Linux 기기에서 `BYDEV_KEYCHAIN_CHECK=1 ./gradlew -q
// :harness:osCheck`
tasks.register<JavaExec>("osCheck") {
    group = "verification"
    description = "운영체제 보안 저장소 · 지문 출처 실제 확인 (BYDEV_KEYCHAIN_CHECK=1일 때만)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.developedby.sdk.oscheck.MainKt")
}
