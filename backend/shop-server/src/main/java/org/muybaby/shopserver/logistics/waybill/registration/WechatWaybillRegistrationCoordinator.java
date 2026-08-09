package org.muybaby.shopserver.logistics.waybill.registration;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationProvider;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WechatWaybillRegistrationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WechatWaybillRegistrationCoordinator.class);

    private final WechatWaybillRegistrationStateStore stateStore;
    private final WechatWaybillRegistrationProvider provider;

    public WechatWaybillRegistrationCoordinator(
            WechatWaybillRegistrationStateStore stateStore,
            WechatWaybillRegistrationProvider provider
    ) {
        this.stateStore = stateStore;
        this.provider = provider;
    }

    public void attemptInitial(long shipmentId) {
        attempt(shipmentId);
    }

    public void retryForAdmin(long orderId) {
        long shipmentId = stateStore.requireEligibleShipmentForOrder(orderId);
        attempt(shipmentId);
    }

    public String tokenForOwner(AuthenticatedPrincipal principal, long orderId) {
        long userId = requireAppUser(principal);
        long shipmentId = stateStore.requireEligibleShipmentForOwner(orderId, userId);
        var existing = stateStore.registeredToken(shipmentId);
        if (existing.isPresent()) {
            return existing.get();
        }

        attempt(shipmentId);
        return stateStore.registeredToken(shipmentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.WECHAT_WAYBILL_REGISTRATION_UNAVAILABLE
                ));
    }

    private void attempt(long shipmentId) {
        var claim = stateStore.claim(shipmentId);
        if (claim.isEmpty()) {
            return;
        }
        WaybillRegistrationClaim registrationClaim = claim.get();
        WechatWaybillRegistrationResult result;
        try {
            result = switch (registrationClaim.kind()) {
                case TRACE -> provider.trace(registrationClaim.request());
                case FOLLOW -> provider.follow(registrationClaim.request());
            };
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat waybill registration provider threw: shipmentId={}, kind={}, exception={}",
                    shipmentId,
                    registrationClaim.kind(),
                    ex.getClass().getSimpleName()
            );
            result = WechatWaybillRegistrationResult.failure(
                    WechatProviderOutcome.UNKNOWN,
                    "PROVIDER_EXCEPTION",
                    "WeChat waybill registration result is unknown"
            );
        }
        stateStore.complete(registrationClaim, result);
        logResult(shipmentId, registrationClaim.kind(), result);
    }

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private void logResult(
            long shipmentId,
            WaybillRegistrationKind kind,
            WechatWaybillRegistrationResult result
    ) {
        WechatProviderOutcome outcome = result == null || result.outcome() == null
                ? WechatProviderOutcome.UNKNOWN
                : result.outcome();
        if (outcome == WechatProviderOutcome.SUCCESS) {
            log.info(
                    "WeChat waybill registration coordinated: shipmentId={}, kind={}, outcome={}",
                    shipmentId, kind, outcome
            );
        } else {
            log.warn(
                    "WeChat waybill registration coordinated: shipmentId={}, kind={}, outcome={}, errorCode={}",
                    shipmentId, kind, outcome, safeCode(result == null ? null : result.errorCode())
            );
        }
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 64));
    }
}
