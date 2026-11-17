package dev.developedby.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Java에서 부르는 모양 — 기본 인자 겹치기 · 정적 상수 · 람다 구독 · instanceof 분기 · 확인 예외 없음(ADR-0040 5절). */
class JavaInteropTest {
    @Test
    void callsFromJava() {
        LicenseClient client =
                new LicenseClient(
                        new LicenseClient.Config(
                                "http://127.0.0.1:1",
                                "cli_01J9M0Y6X4N3Q8ZD5R2KT7VCPW",
                                "prod_01J9KZ3F7W2X8YQ4N6M5R1TBCD",
                                List.of(Scope.ENTITLEMENT_READ, Scope.SESSION_WRITE),
                                new DeviceInfo("PC", DevicePlatform.WINDOWS),
                                new Ports().store(new MemoryCredentialStore())));
        BydevException e = assertThrows(BydevException.class, client::entitlement);
        assertEquals(Codes.NOT_SIGNED_IN, e.getCode());
        assertTrue(!e.isTransient());

        LicenseSession session = client.session();
        Subscription subscription =
                session.subscribe(
                        state -> {
                            if (state instanceof SessionState.Active active) {
                                assertTrue(active.getClaims().getExp() > 0);
                            }
                        });
        assertEquals(SessionStateType.STOPPED, session.getState().getType());
        subscription.unsubscribe();
        assertEquals(1, Rules.STORAGE_RECORD_VERSION);

        // 실행 중인 앱(ADR-0049)은 마지막 인자 — 앞의 겹치기는 그대로다
        LicenseClient withApp =
                new LicenseClient(
                        new LicenseClient.Config(
                                "http://127.0.0.1:1",
                                "cli_01J9M0Y6X4N3Q8ZD5R2KT7VCPW",
                                "prod_01J9KZ3F7W2X8YQ4N6M5R1TBCD",
                                List.of(Scope.SESSION_WRITE),
                                new DeviceInfo("PC", DevicePlatform.WINDOWS),
                                new Ports(),
                                new AppInfo(DistributionTarget.WINDOWS_X64, "1.4.0")));
        assertEquals(SessionStateType.STOPPED, withApp.session().getState().getType());
        assertEquals(UpdateCoverageType.ALL, UpdateCoverage.All.INSTANCE.getType());
    }

    @Test
    void webhookFromJava() {
        BydevException e =
                assertThrows(
                        BydevException.class,
                        () -> Webhooks.verify(List.of("whsec_AAAA"), Map.of("webhook-id", "evt_1"), "{}"));
        assertEquals(Codes.WEBHOOK_INVALID, e.getCode());
    }
}
