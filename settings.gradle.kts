rootProject.name = "bydev-sdk"

// 적합성 하네스(sdks/spec/harness-protocol.md) — 실행기가 띄운다. 라이브러리는 루트 프로젝트다
include("harness")

dependencyResolutionManagement { repositories { mavenCentral() } }
