package dev.developedby.sdk

import dev.developedby.sdk.JsonValue.Companion.obj
import dev.developedby.sdk.JsonValue.Companion.of

// 공개 API 응답의 SDK 모델 — JSON 이름은 contracts 그대로, 없을 수 있는 값은 null. 열거 값은 `String`(모르는 값도 — 명세 0절 5, 아는
// 값은
// 생성된 [EntitlementStatus] 등).

/** 모델은 모두 `toJson()`(contracts 모양 — 기록 · 진단용)이 있다. */
public interface BydevModel {
    public fun toJsonValue(): JsonValue

    public fun toJson(): String = toJsonValue().toJson()
}

private fun str(s: String) = JsonValue.Str(s)

/** 기간이다. `type`을 먼저 분기한다 — 영구(`PERPETUAL`)는 잔여 필드가 null이다. */
public data class EntitlementTerm(
    public val type: String,
    public val expiresAt: String?,
    public val remainingDays: Long?,
    public val remainingSeconds: Long?,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "type" to str(type),
            "expiresAt" to of(expiresAt),
            "remainingDays" to of(remainingDays),
            "remainingSeconds" to of(remainingSeconds),
        )
}

/** 구독형일 때만 있다. */
public data class EntitlementSubscription(
    public val id: String,
    public val status: String,
    public val willRenew: Boolean,
    public val nextBillingAt: String?,
    public val graceEndsAt: String?,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "id" to str(id),
            "status" to str(status),
            "willRenew" to of(willRenew),
            "nextBillingAt" to of(nextBillingAt),
            "graceEndsAt" to of(graceEndsAt),
        )
}

/** 판매자가 정한 정책 수치다 — 앱에 하드코딩하지 않는다. */
public data class LicensePolicy(
    public val maxDevices: Int,
    public val maxConcurrent: Int,
    public val heartbeatSec: Int,
    public val offlineGraceHours: Int,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "maxDevices" to of(maxDevices),
            "maxConcurrent" to of(maxConcurrent),
            "heartbeatSec" to of(heartbeatSec),
            "offlineGraceHours" to of(offlineGraceHours),
        )
}

/**
 * 업데이트 제공 범위다(ADR-0049). `type`을 먼저 분기한다 — [Until]이면 `until` 전에 출시된 버전을 실행할 수 있다. 모르는 방식이면
 * [Other](방식 이름만). `when`에는 `else`를 둔다.
 */
public sealed class UpdateCoverage : BydevModel {
    /** [UpdateCoverageType] */
    public abstract val type: String

    /** 모든 업데이트를 받는다. */
    public data object All : UpdateCoverage() {
        override val type: String = UpdateCoverageType.ALL

        override fun toJsonValue(): JsonValue = obj("type" to str(type))
    }

    /** [until] 전에 출시된 버전까지 받는다. */
    public data class Until(public val until: String) : UpdateCoverage() {
        override val type: String = UpdateCoverageType.UNTIL

        override fun toJsonValue(): JsonValue = obj("type" to str(type), "until" to str(until))
    }

    /** 이 SDK가 모르는 방식이다 — 다른 필드는 버린다. */
    public data class Other(override val type: String) : UpdateCoverage() {
        override fun toJsonValue(): JsonValue = obj("type" to str(type))
    }
}

/**
 * 이 제품의 이용권이다. [updateCoverage]는 응답에 늘 있다 — 생성자의 기본값([UpdateCoverage.All])은 앱이 직접 만드는 값(시험용 가짜 등)을
 * 위한 것이다.
 */
public data class Entitlement
@JvmOverloads
public constructor(
    public val id: String,
    public val productId: String,
    public val status: String,
    public val term: EntitlementTerm,
    public val subscription: EntitlementSubscription?,
    public val policy: LicensePolicy,
    public val evaluatedAt: String,
    public val updateCoverage: UpdateCoverage = UpdateCoverage.All,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "id" to str(id),
            "productId" to str(productId),
            "status" to str(status),
            "term" to term.toJsonValue(),
            "updateCoverage" to updateCoverage.toJsonValue(),
            "subscription" to (subscription?.toJsonValue() ?: JsonValue.Null),
            "policy" to policy.toJsonValue(),
            "evaluatedAt" to str(evaluatedAt),
        )

    internal companion object {
        fun read(json: JsonValue): Entitlement {
            val r = Reader()
            val e = r.obj(r.obj(json, "$")["entitlement"], "entitlement")
            val t = r.obj(e["term"], "entitlement.term")
            val type = r.str(t["type"], "entitlement.term.type")
            val term =
                if (type == "PERPETUAL") {
                    EntitlementTerm(type, null, null, null)
                } else {
                    EntitlementTerm(
                        type,
                        r.optStr(t["expiresAt"], "entitlement.term.expiresAt"),
                        r.optLong(t["remainingDays"], "entitlement.term.remainingDays"),
                        r.optLong(t["remainingSeconds"], "entitlement.term.remainingSeconds"),
                    )
                }
            val c = r.obj(e["updateCoverage"], "entitlement.updateCoverage")
            val updateCoverage =
                when (val coverage = r.str(c["type"], "entitlement.updateCoverage.type")) {
                    UpdateCoverageType.ALL -> UpdateCoverage.All
                    UpdateCoverageType.UNTIL ->
                        UpdateCoverage.Until(r.str(c["until"], "entitlement.updateCoverage.until"))
                    else -> UpdateCoverage.Other(coverage)
                }
            val p = r.obj(e["policy"], "entitlement.policy")
            val policy =
                LicensePolicy(
                    r.int(p["maxDevices"], "entitlement.policy.maxDevices"),
                    r.int(p["maxConcurrent"], "entitlement.policy.maxConcurrent"),
                    r.int(p["heartbeatSec"], "entitlement.policy.heartbeatSec"),
                    r.int(p["offlineGraceHours"], "entitlement.policy.offlineGraceHours"),
                )
            val sub = e["subscription"]
            val subscription =
                if (sub == null || sub == JsonValue.Null) {
                    null
                } else {
                    val s = r.obj(sub, "entitlement.subscription")
                    EntitlementSubscription(
                        r.str(s["id"], "entitlement.subscription.id"),
                        r.str(s["status"], "entitlement.subscription.status"),
                        r.bool(s["willRenew"], "entitlement.subscription.willRenew"),
                        r.optStr(s["nextBillingAt"], "entitlement.subscription.nextBillingAt"),
                        r.optStr(s["graceEndsAt"], "entitlement.subscription.graceEndsAt"),
                    )
                }
            return r.finish(
                Entitlement(
                    r.str(e["id"], "entitlement.id"),
                    r.str(e["productId"], "entitlement.productId"),
                    r.str(e["status"], "entitlement.status"),
                    term,
                    subscription,
                    policy,
                    r.str(e["evaluatedAt"], "entitlement.evaluatedAt"),
                    updateCoverage,
                )
            )
        }
    }
}

/** 등록된 기기다. */
public data class Device(
    public val id: String,
    public val name: String,
    public val platform: String,
    public val registeredAt: String,
    public val lastSeenAt: String,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "id" to str(id),
            "name" to str(name),
            "platform" to str(platform),
            "registeredAt" to str(registeredAt),
            "lastSeenAt" to str(lastSeenAt),
        )
}

/** 기기 목록 한 쪽이다. `nextCursor`가 null이면 마지막 쪽이다. */
public data class DevicePage(public val items: List<Device>, public val nextCursor: String?) :
    BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "items" to JsonValue.Arr(items.map { it.toJsonValue() }),
            "nextCursor" to of(nextCursor),
        )

    internal companion object {
        fun read(json: JsonValue): DevicePage {
            val r = Reader()
            val page = r.obj(json, "$")
            val items =
                r.array(page["items"], "items").map { v ->
                    val d = r.obj(v, "items[]")
                    Device(
                        r.str(d["id"], "items[].id"),
                        r.str(d["name"], "items[].name"),
                        r.str(d["platform"], "items[].platform"),
                        r.str(d["registeredAt"], "items[].registeredAt"),
                        r.str(d["lastSeenAt"], "items[].lastSeenAt"),
                    )
                }
            return r.finish(DevicePage(items, r.optStr(page["nextCursor"], "nextCursor")))
        }
    }
}

/** 기기 해제 결과다. */
public data class DeviceRelease(
    public val id: String,
    public val status: String,
    public val releasedAt: String,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj("id" to str(id), "status" to str(status), "releasedAt" to str(releasedAt))

    internal companion object {
        fun read(json: JsonValue): DeviceRelease {
            val r = Reader()
            val d = r.obj(r.obj(json, "$")["device"], "device")
            return r.finish(
                DeviceRelease(
                    r.str(d["id"], "device.id"),
                    r.str(d["status"], "device.status"),
                    r.str(d["releasedAt"], "device.releasedAt"),
                )
            )
        }
    }
}

/** 닉네임과 가명 식별자다. `pseudonymousId`를 판매자 쪽 고객 키로 쓴다. */
public data class Profile(
    public val pseudonymousId: String,
    public val nickname: String,
    public val email: String?,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "pseudonymousId" to str(pseudonymousId),
            "nickname" to str(nickname),
            "email" to of(email),
        )

    internal companion object {
        fun read(json: JsonValue): Profile {
            val r = Reader()
            val p = r.obj(r.obj(json, "$")["profile"], "profile")
            return r.finish(
                Profile(
                    r.str(p["pseudonymousId"], "profile.pseudonymousId"),
                    r.str(p["nickname"], "profile.nickname"),
                    r.optStr(p["email"], "profile.email"),
                )
            )
        }
    }
}

/** 외부 채널의 설치 위치다. */
public data class ExternalChannel(public val channel: String, public val locator: String) :
    BydevModel {
    override fun toJsonValue(): JsonValue =
        obj("channel" to str(channel), "locator" to str(locator))
}

/** 설치 방법이다. 모르는 방식이면 [Other](방식 이름만). */
public sealed class ProductInstall : BydevModel {
    public abstract val mode: String

    public data class Hosted(
        public val buildId: String,
        public val downloadUrl: String,
        public val downloadUrlExpiresAt: String,
        public val sha256: String,
        public val sizeBytes: Long,
    ) : ProductInstall() {
        override val mode: String = InstallMode.HOSTED

        override fun toJsonValue(): JsonValue =
            obj(
                "mode" to str(mode),
                "buildId" to str(buildId),
                "downloadUrl" to str(downloadUrl),
                "downloadUrlExpiresAt" to str(downloadUrlExpiresAt),
                "sha256" to str(sha256),
                "sizeBytes" to of(sizeBytes),
            )
    }

    public data class External(public val channels: List<ExternalChannel>) : ProductInstall() {
        override val mode: String = InstallMode.EXTERNAL

        override fun toJsonValue(): JsonValue =
            obj("mode" to str(mode), "channels" to JsonValue.Arr(channels.map { it.toJsonValue() }))
    }

    public data class Other(override val mode: String) : ProductInstall() {
        override fun toJsonValue(): JsonValue = obj("mode" to str(mode))
    }
}

/** 최신 버전. */
public data class UpdateLatest(
    public val version: String,
    public val releasedAt: String,
    public val notes: String?,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj("version" to str(version), "releasedAt" to str(releasedAt), "notes" to of(notes))
}

/** 최신 버전 · 업데이트 판정 · 설치 방법이다 — [EntitledUpdate.release]가 업데이트 확인의 최상위와 같은 모양 · 같은 읽기 규칙이다. */
public data class UpdateRelease(
    public val latest: UpdateLatest,
    /** `UP_TO_DATE` · `AVAILABLE` · `REQUIRED` …([UpdateStatus]) */
    public val updateStatus: String,
    public val install: ProductInstall,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "latest" to latest.toJsonValue(),
            "update" to obj("status" to str(updateStatus)),
            "install" to install.toJsonValue(),
        )
}

/**
 * 업데이트 제공 기한이 있는 구매자에게만 오는 값이다(ADR-0049). 있으면 앱은 [UpdateCheck]의 최상위 `latest` · `updateStatus` ·
 * `install` 대신 [release]를 따른다 — 최상위 최신은 그 구매자가 실행할 수 없는 버전일 수 있다(범위 밖 버전을 설치하면 세션 획득이 거절된다). 이 대상에
 * 범위 안 버전이 없으면 [release]는 null.
 */
public data class EntitledUpdate(
    public val coveredUntil: String,
    public val release: UpdateRelease?,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "coveredUntil" to str(coveredUntil),
            "release" to (release?.toJsonValue() ?: JsonValue.Null),
        )
}

/**
 * 업데이트 확인 결과다. `updateStatus`가 `REQUIRED`면 업데이트 전까지 실행을 막는다. [entitled]가 있으면 최상위 대신
 * [EntitledUpdate.release]를 따른다.
 */
public data class UpdateCheck
@JvmOverloads
public constructor(
    public val productId: String,
    public val target: String,
    public val latest: UpdateLatest,
    /** `UP_TO_DATE` · `AVAILABLE` · `REQUIRED` …([UpdateStatus]) */
    public val updateStatus: String,
    public val install: ProductInstall,
    /** 업데이트 제공 기한이 있는 구매자에게만 있다. */
    public val entitled: EntitledUpdate? = null,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        JsonValue.Obj(
            buildMap {
                put("product", obj("id" to str(productId)))
                put("target", str(target))
                put("latest", latest.toJsonValue())
                put("update", obj("status" to str(updateStatus)))
                put("install", install.toJsonValue())
                entitled?.let { put("entitled", it.toJsonValue()) }
            }
        )

    internal companion object {
        fun read(json: JsonValue): UpdateCheck {
            val r = Reader()
            val o = r.obj(json, "$")
            val productId = r.str(r.obj(o["product"], "product")["id"], "product.id")
            val target = r.str(o["target"], "target")
            val top = readRelease(r, o, "")
            val e = o["entitled"]
            val entitled =
                if (e == null || e == JsonValue.Null) {
                    null
                } else {
                    val eo = r.obj(e, "entitled")
                    val release = eo["release"]
                    EntitledUpdate(
                        r.str(eo["coveredUntil"], "entitled.coveredUntil"),
                        if (release == null || release == JsonValue.Null) null
                        else
                            readRelease(r, r.obj(release, "entitled.release"), "entitled.release."),
                    )
                }
            return r.finish(
                UpdateCheck(productId, target, top.latest, top.updateStatus, top.install, entitled)
            )
        }

        /** 최상위와 `entitled.release`가 같은 규칙으로 읽는다. [prefix]는 에러 메시지의 경로 앞부분이다. */
        private fun readRelease(
            r: Reader,
            o: Map<String, JsonValue>,
            prefix: String,
        ): UpdateRelease {
            val l = r.obj(o["latest"], "${prefix}latest")
            val latest =
                UpdateLatest(
                    r.str(l["version"], "${prefix}latest.version"),
                    r.str(l["releasedAt"], "${prefix}latest.releasedAt"),
                    r.optStr(l["notes"], "${prefix}latest.notes"),
                )
            val status =
                r.str(r.obj(o["update"], "${prefix}update")["status"], "${prefix}update.status")
            return UpdateRelease(latest, status, readInstall(r, o["install"], "${prefix}install"))
        }

        private fun readInstall(r: Reader, v: JsonValue?, path: String): ProductInstall {
            val i = r.obj(v, path)
            return when (val mode = r.str(i["mode"], "$path.mode")) {
                InstallMode.HOSTED ->
                    ProductInstall.Hosted(
                        r.str(i["buildId"], "$path.buildId"),
                        r.str(i["downloadUrl"], "$path.downloadUrl"),
                        r.str(i["downloadUrlExpiresAt"], "$path.downloadUrlExpiresAt"),
                        r.str(i["sha256"], "$path.sha256"),
                        r.long(i["sizeBytes"], "$path.sizeBytes"),
                    )
                InstallMode.EXTERNAL ->
                    ProductInstall.External(
                        r.array(i["channels"], "$path.channels").map { item ->
                            val c = r.obj(item, "$path.channels[]")
                            ExternalChannel(
                                r.str(c["channel"], "$path.channels[].channel"),
                                r.str(c["locator"], "$path.channels[].locator"),
                            )
                        }
                    )
                else -> ProductInstall.Other(mode)
            }
        }
    }
}

/** 세션(실행 리스)이다. */
public data class SessionInfo(
    public val id: String,
    public val productId: String,
    public val deviceId: String,
    public val generation: Long,
    public val leaseExpiresAt: String,
    public val heartbeatSec: Int,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "id" to str(id),
            "productId" to str(productId),
            "deviceId" to str(deviceId),
            "generation" to of(generation),
            "leaseExpiresAt" to str(leaseExpiresAt),
            "heartbeatSec" to of(heartbeatSec),
        )
}

/** 세션 보유 증명 JWT와 그 만료(오프라인 유예의 끝)다. */
public data class LicenseTokenInfo(public val token: String, public val expiresAt: String) :
    BydevModel {
    override fun toJsonValue(): JsonValue =
        obj("token" to str(token), "expiresAt" to str(expiresAt))
}

internal fun readSessionResponse(json: JsonValue): Pair<SessionInfo, LicenseTokenInfo> {
    val r = Reader()
    val o = r.obj(json, "$")
    val s = r.obj(o["session"], "session")
    val t = r.obj(o["licenseToken"], "licenseToken")
    val session =
        SessionInfo(
            r.str(s["id"], "session.id"),
            r.str(s["productId"], "session.productId"),
            r.str(s["deviceId"], "session.deviceId"),
            r.long(s["generation"], "session.generation"),
            r.str(s["leaseExpiresAt"], "session.leaseExpiresAt"),
            r.int(s["heartbeatSec"], "session.heartbeatSec"),
        )
    val token =
        LicenseTokenInfo(
            r.str(t["token"], "licenseToken.token"),
            r.str(t["expiresAt"], "licenseToken.expiresAt"),
        )
    return r.finish(session to token)
}

/**
 * 검증된 licenseToken 클레임이다(명세 9절). JSON 이름은 JWT 그대로(`product_id` · `device_id` · `build_id`).
 *
 * [buildId]는 세션을 얻을 때 앱이 보낸 대상 · 버전([AppInfo])으로 서버가 확정한 빌드다(ADR-0049). 확정하지 못했으면 null.
 */
public data class LicenseClaims
@JvmOverloads
public constructor(
    public val iss: String,
    public val sub: String,
    public val aud: String,
    public val productId: String,
    public val deviceId: String,
    public val sid: String,
    public val generation: Long,
    public val iat: Long,
    public val exp: Long,
    public val buildId: String? = null,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        JsonValue.Obj(
            buildMap {
                put("iss", str(iss))
                put("sub", str(sub))
                put("aud", str(aud))
                put("product_id", str(productId))
                put("device_id", str(deviceId))
                put("sid", str(sid))
                put("generation", of(generation))
                put("iat", of(iat))
                put("exp", of(exp))
                buildId?.let { put("build_id", str(it)) }
            }
        )

    internal companion object {
        fun read(json: JsonValue): LicenseClaims {
            val r = Reader()
            val c = r.obj(json, "claims")
            return r.finish(
                LicenseClaims(
                    r.str(c["iss"], "claims.iss"),
                    r.str(c["sub"], "claims.sub"),
                    r.str(c["aud"], "claims.aud"),
                    r.str(c["product_id"], "claims.product_id"),
                    r.str(c["device_id"], "claims.device_id"),
                    r.str(c["sid"], "claims.sid"),
                    r.long(c["generation"], "claims.generation"),
                    r.long(c["iat"], "claims.iat"),
                    r.long(c["exp"], "claims.exp"),
                    r.optStr(c["build_id"], "claims.build_id"),
                )
            )
        }
    }
}

/** 검증된 access_token 클레임이다(명세 12절 — 판매자 서버). */
public data class AccessClaims(
    public val iss: String,
    public val sub: String,
    public val aud: String,
    public val clientId: String,
    public val scope: String,
    public val iat: Long,
    public val exp: Long,
    public val jti: String,
) : BydevModel {
    override fun toJsonValue(): JsonValue =
        obj(
            "iss" to str(iss),
            "sub" to str(sub),
            "aud" to str(aud),
            "client_id" to str(clientId),
            "scope" to str(scope),
            "iat" to of(iat),
            "exp" to of(exp),
            "jti" to str(jti),
        )

    internal companion object {
        fun read(json: JsonValue): AccessClaims {
            val r = Reader()
            val c = r.obj(json, "claims")
            return r.finish(
                AccessClaims(
                    r.str(c["iss"], "claims.iss"),
                    r.str(c["sub"], "claims.sub"),
                    r.str(c["aud"], "claims.aud"),
                    r.str(c["client_id"], "claims.client_id"),
                    r.str(c["scope"], "claims.scope"),
                    r.long(c["iat"], "claims.iat"),
                    r.long(c["exp"], "claims.exp"),
                    r.str(c["jti"], "claims.jti"),
                )
            )
        }
    }
}

internal class TokenSet(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val scope: String,
) {
    companion object {
        fun read(json: JsonValue): TokenSet {
            val r = Reader()
            val t = r.obj(json, "$")
            return r.finish(
                TokenSet(
                    r.str(t["access_token"], "access_token"),
                    r.long(t["expires_in"], "expires_in"),
                    r.str(t["refresh_token"], "refresh_token"),
                    r.str(t["scope"], "scope"),
                )
            )
        }
    }
}

internal class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Long,
    val interval: Long,
) {
    companion object {
        fun read(json: JsonValue): DeviceCode {
            val r = Reader()
            val d = r.obj(json, "$")
            return r.finish(
                DeviceCode(
                    r.str(d["device_code"], "device_code"),
                    r.str(d["user_code"], "user_code"),
                    r.str(d["verification_uri"], "verification_uri"),
                    r.str(d["verification_uri_complete"], "verification_uri_complete"),
                    r.long(d["expires_in"], "expires_in"),
                    r.long(d["interval"], "interval"),
                )
            )
        }
    }
}

internal class Discovery(
    val issuer: String,
    val jwksUri: String,
    val deviceAuthorizationEndpoint: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String,
) {
    companion object {
        fun read(json: JsonValue): Discovery {
            val r = Reader()
            val d = r.obj(json, "$")
            return r.finish(
                Discovery(
                    r.str(d["issuer"], "issuer"),
                    r.str(d["jwks_uri"], "jwks_uri"),
                    r.str(d["device_authorization_endpoint"], "device_authorization_endpoint"),
                    r.str(d["token_endpoint"], "token_endpoint"),
                    r.str(d["revocation_endpoint"], "revocation_endpoint"),
                )
            )
        }
    }
}
