package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardDeliveryStore.DeliveryClaim;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardProvider;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardQueryRequest;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardQueryResult;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardSetRequest;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardSetResult;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WechatServiceCardDeliveryCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private WechatServiceCardDeliveryStore store;
    private WechatServiceCardPayloadFactory payloadFactory;
    private WechatServiceCardProvider provider;
    private WechatServiceCardRuntimeSettingService runtimeSettingService;
    private WechatServiceCardDeliveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        store = mock(WechatServiceCardDeliveryStore.class);
        payloadFactory = mock(WechatServiceCardPayloadFactory.class);
        provider = mock(WechatServiceCardProvider.class);
        runtimeSettingService = mock(WechatServiceCardRuntimeSettingService.class);
        when(runtimeSettingService.workerReadyFailClosed()).thenReturn(true);
        coordinator = new WechatServiceCardDeliveryCoordinator(
                readyProperties(), runtimeSettingService, store, payloadFactory, provider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void unknownIntentIsAlwaysQueriedBeforeAnyFurtherSetAttempt() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.RECONCILING, 2, "{}",
                NOW_LOCAL.minusMinutes(10), null, null
        );
        WechatServiceCardQueryResult found = WechatServiceCardQueryResult.found(
                2, 0, NOW.plus(Duration.ofDays(30))
        );
        when(store.dueIds(WechatServiceCardDeliveryState.UNKNOWN, NOW_LOCAL, 50))
                .thenReturn(List.of(claim.deliveryId()));
        when(store.claim(claim.deliveryId(), WechatServiceCardDeliveryState.UNKNOWN))
                .thenReturn(Optional.of(claim));
        when(store.prepareProviderCall(claim)).thenReturn(true);
        when(provider.getUserNotify(any(WechatServiceCardQueryRequest.class)))
                .thenReturn(found);

        assertThat(coordinator.reconcileDue()).isOne();

        verify(provider).getUserNotify(new WechatServiceCardQueryRequest(
                claim.payerOpenid(), claim.transactionId()
        ));
        verify(provider, never()).setUserNotify(any(WechatServiceCardSetRequest.class));
        verify(store).applyQueryResult(claim, found);
    }

    @Test
    void exactTwentyFourHourActivationBoundaryIsAccepted() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusHours(24), null, null
        );
        duePending(claim);
        when(provider.setUserNotify(any(WechatServiceCardSetRequest.class)))
                .thenReturn(WechatServiceCardSetResult.applied());

        assertThat(coordinator.deliverDue()).isOne();

        verify(provider).setUserNotify(new WechatServiceCardSetRequest(
                claim.payerOpenid(), claim.transactionId(), claim.contentJson(), claim.checkJson()
        ));
        verify(store).markApplied(claim, null, null);
    }

    @Test
    void activationBeforePaymentOrPastTwentyFourHoursFailsWithoutProviderIo() {
        DeliveryClaim expired = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusHours(24).minusSeconds(1), null, null
        );
        duePending(expired);

        assertThat(coordinator.deliverDue()).isOne();
        verify(store).markFailed(
                expired, "ACTIVATION_WINDOW_EXPIRED",
                "The WeChat service-card activation window expired"
        );
        verifyNoInteractions(provider);

        store = mock(WechatServiceCardDeliveryStore.class);
        provider = mock(WechatServiceCardProvider.class);
        coordinator = new WechatServiceCardDeliveryCoordinator(
                readyProperties(), runtimeSettingService, store, payloadFactory, provider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DeliveryClaim future = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.plusSeconds(1), null, null
        );
        duePending(future);

        assertThat(coordinator.deliverDue()).isOne();
        verify(store).markFailed(
                future, "ACTIVATION_WINDOW_EXPIRED",
                "The WeChat service-card activation window expired"
        );
        verifyNoInteractions(provider);
    }

    @Test
    void exactThirtyDayUpdateBoundaryIsAcceptedButOneSecondPastFails() {
        LocalDateTime activatedAt = NOW_LOCAL.minusDays(30);
        DeliveryClaim atBoundary = claim(
                WechatServiceCardDeliveryState.SENDING, 4, null,
                NOW_LOCAL.minusDays(31), activatedAt, null
        );
        duePending(atBoundary);
        when(provider.setUserNotify(any(WechatServiceCardSetRequest.class)))
                .thenReturn(WechatServiceCardSetResult.applied());

        assertThat(coordinator.deliverDue()).isOne();
        verify(store).markApplied(atBoundary, null, null);

        store = mock(WechatServiceCardDeliveryStore.class);
        provider = mock(WechatServiceCardProvider.class);
        coordinator = new WechatServiceCardDeliveryCoordinator(
                readyProperties(), runtimeSettingService, store, payloadFactory, provider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DeliveryClaim expired = claim(
                WechatServiceCardDeliveryState.SENDING, 4, null,
                NOW_LOCAL.minusDays(31), activatedAt.minusSeconds(1), null
        );
        duePending(expired);

        assertThat(coordinator.deliverDue()).isOne();
        verify(store).markFailed(
                expired, "UPDATE_WINDOW_EXPIRED",
                "The WeChat service-card update window expired"
        );
        verifyNoInteractions(provider);
    }

    @Test
    void remoteExpireTimeOverridesActivationFallbackAndIsInclusive() {
        DeliveryClaim atBoundary = claim(
                WechatServiceCardDeliveryState.SENDING, 6, null,
                NOW_LOCAL.minusDays(31), NOW_LOCAL.minusDays(40), NOW_LOCAL
        );
        duePending(atBoundary);
        when(provider.setUserNotify(any(WechatServiceCardSetRequest.class)))
                .thenReturn(WechatServiceCardSetResult.applied());

        assertThat(coordinator.deliverDue()).isOne();
        verify(store).markApplied(atBoundary, null, null);
    }

    @Test
    void exceptionAfterSetMayHaveStartedMovesIntentToUnknown() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        duePending(claim);
        doThrow(new IllegalStateException("timeout"))
                .when(provider).setUserNotify(any(WechatServiceCardSetRequest.class));

        assertThat(coordinator.deliverDue()).isOne();

        verify(store).markUnknown(
                claim, "PROVIDER_OUTCOME_UNKNOWN", "Provider attempt outcome is unknown"
        );
        verify(store, never()).markRetry(any(), any(), any());
    }

    @Test
    void ambiguousSetTransportResultKeepsItsSpecificSafeDiagnostic() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        duePending(claim);
        when(provider.setUserNotify(any(WechatServiceCardSetRequest.class)))
                .thenReturn(WechatServiceCardSetResult.unknown(
                        null, "WeChat set_user_notify outcome is unknown"
                ));

        assertThat(coordinator.deliverDue()).isOne();

        verify(store).markUnknown(
                claim, "SET_TRANSPORT_OUTCOME_UNKNOWN",
                "WeChat set_user_notify outcome is unknown"
        );
        verify(store, never()).markRetry(any(), any(), any());
    }

    @Test
    void categorizedSetTransportResultKeepsUnknownSemantics() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        duePending(claim);
        when(provider.setUserNotify(any(WechatServiceCardSetRequest.class)))
                .thenReturn(WechatServiceCardSetResult.unknown(
                        null, "WeChat set_user_notify transport failed: CONNECTION_RESET"
                ));

        assertThat(coordinator.deliverDue()).isOne();

        verify(store).markUnknown(
                claim, "SET_TRANSPORT_OUTCOME_UNKNOWN",
                "WeChat set_user_notify transport failed: CONNECTION_RESET"
        );
        verify(store, never()).markRetry(any(), any(), any());
    }

    @Test
    void unreadableSetHttpResponseKeepsUnknownSemantics() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        duePending(claim);
        when(provider.setUserNotify(any(WechatServiceCardSetRequest.class)))
                .thenReturn(WechatServiceCardSetResult.unknown(
                        null, "WeChat set_user_notify HTTP response is unavailable"
                ));

        assertThat(coordinator.deliverDue()).isOne();

        verify(store).markUnknown(
                claim, "SET_HTTP_RESPONSE_UNKNOWN",
                "WeChat set_user_notify HTTP response is unavailable"
        );
    }

    @Test
    void refusalAfterClaimButBeforeProviderPreflightPerformsNoProviderIo() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        duePending(claim);
        when(store.prepareProviderCall(claim)).thenReturn(false);

        assertThat(coordinator.deliverDue()).isOne();

        verify(store).prepareProviderCall(claim);
        verifyNoInteractions(provider);
    }

    @Test
    void reconciliationExpiresConservativelyAtPaidTimePlusThirtyOneDays() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.RECONCILING, 2, "{}",
                NOW_LOCAL.minusDays(31).minusSeconds(1), null, null
        );
        when(store.dueIds(WechatServiceCardDeliveryState.UNKNOWN, NOW_LOCAL, 50))
                .thenReturn(List.of(claim.deliveryId()));
        when(store.claim(claim.deliveryId(), WechatServiceCardDeliveryState.UNKNOWN))
                .thenReturn(Optional.of(claim));

        assertThat(coordinator.reconcileDue()).isOne();

        verify(store).markFailed(
                claim, "RECONCILIATION_WINDOW_EXPIRED",
                "The safe WeChat service-card reconciliation window expired"
        );
        verifyNoInteractions(provider);
    }

    @Test
    void runtimeNotReadyPreventsDueLookupAndClaim() {
        when(runtimeSettingService.workerReadyFailClosed()).thenReturn(false);
        coordinator = new WechatServiceCardDeliveryCoordinator(
                readyProperties(), runtimeSettingService, store, payloadFactory, provider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(coordinator.deliverDue()).isZero();
        assertThat(coordinator.reconcileDue()).isZero();

        verifyNoInteractions(store, payloadFactory, provider);
    }

    @Test
    void runtimeDisableBeforeClaimStopsTheBatch() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        when(store.dueIds(WechatServiceCardDeliveryState.PENDING, NOW_LOCAL, 50))
                .thenReturn(List.of(claim.deliveryId()));
        when(runtimeSettingService.workerReadyFailClosed()).thenReturn(true, false);

        assertThat(coordinator.deliverDue()).isZero();

        verify(store, never()).claim(claim.deliveryId(), WechatServiceCardDeliveryState.PENDING);
        verifyNoInteractions(provider);
    }

    @Test
    void runtimeDisableImmediatelyBeforeProviderCallReleasesClaim() {
        DeliveryClaim claim = claim(
                WechatServiceCardDeliveryState.SENDING, 2, "{}",
                NOW_LOCAL.minusMinutes(1), null, null
        );
        duePending(claim);
        when(runtimeSettingService.workerReadyFailClosed())
                .thenReturn(true, true, true, false);

        assertThat(coordinator.deliverDue()).isOne();

        verify(store).releaseWithoutProviderCall(claim);
        verifyNoInteractions(provider);
    }

    private void duePending(DeliveryClaim claim) {
        when(store.dueIds(WechatServiceCardDeliveryState.PENDING, NOW_LOCAL, 50))
                .thenReturn(List.of(claim.deliveryId()));
        when(store.claim(claim.deliveryId(), WechatServiceCardDeliveryState.PENDING))
                .thenReturn(Optional.of(claim));
        when(store.prepareProviderCall(claim)).thenReturn(true);
    }

    private DeliveryClaim claim(
            WechatServiceCardDeliveryState claimedState,
            int targetStatus,
            String checkJson,
            LocalDateTime paidAt,
            LocalDateTime activatedAt,
            LocalDateTime remoteExpiresAt
    ) {
        return new DeliveryClaim(
                101, 201, 301, 401, targetStatus,
                "{\"cur_status\":" + targetStatus + "}", checkJson,
                1, claimedState == WechatServiceCardDeliveryState.RECONCILING ? 1 : 0,
                0, "claim-token", claimedState,
                "420000000000000001", "openid-test", 100, paidAt,
                null, "fingerprint", "wx-service-card-test", activatedAt, remoteExpiresAt,
                activatedAt == null ? null : 2
        );
    }

    private WechatServiceCardProperties readyProperties() {
        return new WechatServiceCardProperties(
                true, true, "template-record", Duration.ofSeconds(15), 50,
                Duration.ofMinutes(2), 8, Duration.ofMinutes(1), Duration.ofMinutes(30),
                Duration.ofMinutes(1), Duration.ofHours(6), 2,
                Duration.ofSeconds(3), Duration.ofSeconds(15),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(64),
                "https://admin.muybaby6.icu/wechat/service-card-placeholder.png",
                false, List.of("admin.muybaby6.icu"),
                new WechatServiceCardProperties.Callback(
                        false, "", "", Duration.ofMinutes(5)
                )
        );
    }

}
