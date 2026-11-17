package dev.developedby.sdk

/**
 * bydev 라이선스 API 클라이언트다. 서버 응답 하나의 참/거짓으로 기능을 열지 않는다 — [session]의 상태와 검증된 토큰의 클레임을 쓴다. **블로킹 API**다
 * (Kotlin 코루틴에서는 `withContext(Dispatchers.IO)`로 부른다). 스레드 사이에 나눠 쓴다.
 */
public class LicenseClient(private val config: Config) {
    /** 명세 1절의 설정이다. */
    public class Config
    @JvmOverloads
    public constructor(
        public val baseUrl: String,
        public val clientId: String,
        public val productId: String,
        public val scopes: List<String>,
        /** 세션을 쓰면 필요하다([OsPorts.systemDeviceInfo]). */
        public val device: DeviceInfo? = null,
        public val ports: Ports = Ports(),
        /**
         * 실행 중인 앱의 배포 대상 · 버전이다 — 세션을 쓰면 넣는다(명세 1절 · ADR-0049). 업데이트 제공 기한이 있는 영구 이용권은 이 값이 없으면 세션
         * 획득이 `ENTITLEMENT_VERSION_NOT_COVERED`로 거절된다.
         */
        public val app: AppInfo? = null,
    )

    public companion object {
        /** User-Agent(`bydev-kotlin/<판>`)에 실린다. */
        public const val SDK_VERSION: String = "0.1.0"
        internal const val USER_AGENT: String = "bydev-kotlin/$SDK_VERSION"
    }

    private val api: Api
    private val tokens: TokenManager
    private val verifier: Verifier
    private val lock = Any()
    private var login: LoginAttempt? = null
    private var licenseSession: LicenseSession? = null

    init {
        if (config.scopes.isEmpty()) throw BydevException(Codes.CONFIGURATION, "scopes가 비었다")
        api = Api(config.baseUrl, config.ports.resolve(), USER_AGENT)
        tokens = TokenManager(api, config.clientId)
        verifier = Verifier(api, config.clientId, true)
    }

    /** 디바이스 로그인을 시작한다(명세 5절). 진행 중인 로그인은 취소한다. */
    public fun startLogin(): LoginAttempt {
        synchronized(lock) { login.also { login = null } }?.cancel()
        val doc = api.discovery()
        val body =
            JsonValue.obj(
                "client_id" to JsonValue.Str(config.clientId),
                "scope" to JsonValue.Str(config.scopes.joinToString(" ")),
            )
        val res = api.send(Req("POST", doc.deviceAuthorizationEndpoint, body))
        val attempt = LoginAttempt(DeviceCode.read(res.json), config.clientId, api, tokens)
        attempt.schedulePoll()
        synchronized(lock) { login = attempt }
        return attempt
    }

    /** 저장된 로그인이 있는지다 — 이용 가능 여부가 아니다. */
    public fun hasCredentials(): Boolean = tokens.load() != null

    private fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
    ): JsonValue {
        val url = api.url(path, params, query)
        val res = tokens.authed { bearer -> api.send(Req("GET", url, bearer = bearer)) }
        noteLease(res)
        return res.json
    }

    private fun noteLease(res: ApiResponse) {
        synchronized(lock) { licenseSession }?.noteLease(res.headers["x-lease-expires-at"])
    }

    /** 이 제품의 이용권이다. */
    public fun entitlement(): Entitlement = Entitlement.read(get(Routes.GET_MY_ENTITLEMENT.path))

    /** 등록된 기기 한 쪽이다. 다음 쪽을 저절로 따라가지 않는다. */
    @JvmOverloads
    public fun devices(cursor: String? = null, limit: Int? = null): DevicePage {
        val query = buildList {
            if (!cursor.isNullOrEmpty()) add("cursor" to cursor)
            if (limit != null && limit > 0) add("limit" to limit.toString())
        }
        return DevicePage.read(get(Routes.LIST_DEVICES.path, query = query))
    }

    /** 기기를 해제한다(월 횟수 제한). */
    public fun releaseDevice(deviceId: String): DeviceRelease {
        val key = api.ports.random.uuid()
        val url = api.url(Routes.RELEASE_DEVICE.path, mapOf("deviceId" to deviceId))
        val res = tokens.authed { bearer ->
            api.send(Req("DELETE", url, bearer = bearer, idempotencyKey = key))
        }
        noteLease(res)
        return DeviceRelease.read(res.json)
    }

    /** 닉네임과 가명 식별자다. */
    public fun profile(): Profile = Profile.read(get(Routes.GET_PROFILE.path))

    /**
     * 업데이트 확인이다. `updateStatus`가 `REQUIRED`면 업데이트 전까지 실행을 막는다. `entitled`가 있으면 최상위 대신
     * `entitled.release`를 따른다. 받은 파일은 설치 전에 [verifyDownload].
     */
    public fun checkForUpdate(target: String, currentVersion: String): UpdateCheck =
        UpdateCheck.read(
            get(
                Routes.GET_PRODUCT_UPDATES.path,
                mapOf("id" to config.productId),
                listOf("target" to target, "currentVersion" to currentVersion),
            )
        )

    /** 내려받은 파일의 SHA-256을 확인한다 — 다르면 `DOWNLOAD_INTEGRITY`. */
    public fun verifyDownload(data: ByteArray, sha256: String) {
        if (sha256Hex(data) != sha256.lowercase()) {
            throw BydevException(Codes.DOWNLOAD_INTEGRITY, "내려받은 파일의 SHA-256이 다르다 — 설치하지 않는다")
        }
    }

    /** licenseToken을 검증한다(명세 9절) — 앱이 기능 코드 여러 곳에서 다시 검증할 때. */
    public fun verifyToken(token: String): LicenseClaims = verifier.verifyLicense(token, null, 0)

    /** 세션을 멈추고 폐기 요청을 한 번 보낸 뒤 기록을 지운다. 서버 폐기에 성공했는지를 돌려준다. */
    public fun logout(): Boolean {
        synchronized(lock) { login.also { login = null } }?.cancel()
        synchronized(lock) { licenseSession }?.stop()
        return tokens.logout()
    }

    /** 이 클라이언트의 세션이다(하나뿐). */
    public fun session(): LicenseSession =
        synchronized(lock) {
            licenseSession
                ?: LicenseSession(
                        api,
                        tokens,
                        verifier,
                        config.device
                            ?: throw BydevException(
                                Codes.CONFIGURATION,
                                "세션을 쓰려면 device { name, platform }을 준다",
                            ),
                        config.app,
                    )
                    .also { licenseSession = it }
        }
}
