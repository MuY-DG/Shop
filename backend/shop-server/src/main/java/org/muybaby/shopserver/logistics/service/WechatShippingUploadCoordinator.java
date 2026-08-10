package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingCapabilityState;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.provider.RealWechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingItem;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingFact;
import org.muybaby.shopserver.logistics.provider.WechatShippingOrderQueryResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingOrderQueryStatus;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class WechatShippingUploadCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WechatShippingUploadCoordinator.class);
    private static final String MOCK_PROVIDER = "MOCK_PROVIDER";
    private static final String MOCK_PROVIDER_MESSAGE = "Mock provider cannot confirm WeChat shipping upload";
    private static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    private static final String PROVIDER_UNAVAILABLE_MESSAGE = "WeChat shipping provider is unavailable";
    private static final String CAPABILITY_UNKNOWN = "CAPABILITY_UNKNOWN";
    private static final String CAPABILITY_UNKNOWN_MESSAGE = "WeChat shipping capability is unavailable";
    private static final String PAYLOAD_RECONSTRUCTION_FAILED = "PAYLOAD_RECONSTRUCTION_FAILED";
    private static final String PAYLOAD_RECONSTRUCTION_FAILED_MESSAGE =
            "WeChat shipping upload data could not be reconstructed";
    private static final String UPLOAD_RESULT_UNKNOWN = "UPLOAD_RESULT_UNKNOWN";
    private static final String UPLOAD_RESULT_UNKNOWN_MESSAGE = "WeChat shipping upload outcome is unknown";

    private final ShippingProperties shippingProperties;
    private final WechatShippingProvider shippingProvider;
    private final WechatShippingUploadStateStore stateStore;
    private final WechatShippingErrorSanitizer errorSanitizer;

    public WechatShippingUploadCoordinator(
            ShippingProperties shippingProperties,
            WechatShippingProvider shippingProvider,
            WechatShippingUploadStateStore stateStore,
            WechatShippingErrorSanitizer errorSanitizer
    ) {
        this.shippingProperties = shippingProperties;
        this.shippingProvider = shippingProvider;
        this.stateStore = stateStore;
        this.errorSanitizer = errorSanitizer;
    }

    public void attemptInitial(long shipmentId) {
        if (!shippingProperties.isUploadEnabled()) {
            return;
        }
        stateStore.claimInitial(shipmentId, now()).ifPresent(this::executeClaimed);
    }

    public void retry(AuthenticatedPrincipal principal, long orderId) {
        requireAdmin(principal);
        LocalDateTime now = now();
        stateStore.reconcileStaleByOrder(orderId, now);
        WechatShippingUploadStateStore.UploadClaim claim = stateStore.claimOperatorRetry(
                orderId, shippingProperties.isUploadEnabled(), now
        );
        executeClaimed(claim);
    }

    public int deliverDue(int batchSize) {
        if (!shippingProperties.isUploadEnabled() || safeProviderMode() != WechatProviderMode.REAL) {
            return 0;
        }
        LocalDateTime scanTime = now();
        int attempted = 0;
        for (Long shipmentId : stateStore.findDueUploadShipmentIds(scanTime, batchSize)) {
            Optional<WechatShippingUploadStateStore.UploadClaim> claim =
                    stateStore.claimScheduled(shipmentId, now());
            if (claim.isPresent()) {
                executeClaimed(claim.get());
                attempted++;
            }
        }
        return attempted;
    }

    public int reconcileDueUnknown(int batchSize) {
        if (!shippingProperties.isUploadEnabled() || safeProviderMode() != WechatProviderMode.REAL) {
            return 0;
        }
        LocalDateTime scanTime = now();
        int attempted = 0;
        for (Long shipmentId : stateStore.findDueUnknownShipmentIds(scanTime, batchSize)) {
            Optional<WechatShippingUploadStateStore.UnknownClaim> claim =
                    stateStore.claimUnknownByShipment(shipmentId, false, now());
            if (claim.isPresent()) {
                reconcileClaimed(claim.get());
                attempted++;
            }
        }
        return attempted;
    }

    public void reconcile(AuthenticatedPrincipal principal, long orderId) {
        requireAdmin(principal);
        if (!shippingProperties.isUploadEnabled() || safeProviderMode() != WechatProviderMode.REAL) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        WechatShippingUploadStateStore.UnknownClaim claim = stateStore
                .claimUnknownByOrder(orderId, now())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        reconcileClaimed(claim);
    }

    private void executeClaimed(WechatShippingUploadStateStore.UploadClaim claim) {
        long shipmentId = claim.shipmentId();
        WechatProviderMode providerMode = safeProviderMode();
        WechatShippingUploadStateStore.AttemptContext context;
        try {
            context = stateStore.prepareAttempt(claim, providerMode);
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping reconstruction failed: shipmentId={}, mode={}, status={}, code={}, exception={}",
                    shipmentId, providerMode, WechatShippingUploadStatus.FAILED,
                    PAYLOAD_RECONSTRUCTION_FAILED, ex.getClass().getSimpleName()
            );
            writeTerminalSafely(
                    claim, providerMode, WechatShippingUploadStatus.FAILED,
                    PAYLOAD_RECONSTRUCTION_FAILED, PAYLOAD_RECONSTRUCTION_FAILED_MESSAGE,
                    List.of()
            );
            return;
        }

        if (!StringUtils.hasText(context.transactionId())) {
            writeTerminalSafely(
                    claim, providerMode, WechatShippingUploadStatus.FAILED,
                    RealWechatShippingProvider.MISSING_TRANSACTION_ID,
                    RealWechatShippingProvider.MISSING_TRANSACTION_ID_MESSAGE,
                    context.knownSecrets()
            );
            return;
        }
        if (providerMode == WechatProviderMode.MOCK) {
            writeTerminalSafely(
                    claim, providerMode, WechatShippingUploadStatus.UNAVAILABLE,
                    MOCK_PROVIDER, MOCK_PROVIDER_MESSAGE, context.knownSecrets()
            );
            return;
        }
        if (providerMode != WechatProviderMode.REAL) {
            writeTerminalSafely(
                    claim, providerMode, WechatShippingUploadStatus.UNAVAILABLE,
                    PROVIDER_UNAVAILABLE, PROVIDER_UNAVAILABLE_MESSAGE, context.knownSecrets()
            );
            return;
        }

        WechatShippingCapabilityResult capability;
        try {
            capability = shippingProvider.queryCapability();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping capability failed: shipmentId={}, mode={}, status={}, code={}, exception={}",
                    shipmentId, providerMode, WechatShippingUploadStatus.UNAVAILABLE,
                    CAPABILITY_UNKNOWN, ex.getClass().getSimpleName()
            );
            writeTerminalSafely(
                    claim, providerMode, WechatShippingUploadStatus.UNAVAILABLE,
                    CAPABILITY_UNKNOWN, CAPABILITY_UNKNOWN_MESSAGE, context.knownSecrets()
            );
            return;
        }
        if (capability == null
                || capability.state() != WechatShippingCapabilityState.AVAILABLE
                || !Boolean.TRUE.equals(capability.tradeManaged())) {
            writeTerminalSafely(
                    claim, providerMode, WechatShippingUploadStatus.UNAVAILABLE,
                    capability == null ? CAPABILITY_UNKNOWN : capability.errorCode(),
                    capability == null ? CAPABILITY_UNKNOWN_MESSAGE : capability.errorMessage(),
                    context.knownSecrets()
            );
            return;
        }

        WechatShippingUploadResult result;
        try {
            result = shippingProvider.upload(toRequest(context));
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping upload outcome unknown: shipmentId={}, mode={}, status={}, code={}, exception={}",
                    shipmentId, providerMode, WechatShippingUploadStatus.UNKNOWN,
                    UPLOAD_RESULT_UNKNOWN, ex.getClass().getSimpleName()
            );
            result = WechatShippingUploadResult.unknown(
                    UPLOAD_RESULT_UNKNOWN, UPLOAD_RESULT_UNKNOWN_MESSAGE
            );
        }
        if (result == null) {
            result = WechatShippingUploadResult.unknown(
                    UPLOAD_RESULT_UNKNOWN, UPLOAD_RESULT_UNKNOWN_MESSAGE
            );
        }
        writeTerminalSafely(
                claim, providerMode, result.status(), result.errorCode(), result.errorMessage(),
                context.knownSecrets()
        );
    }

    private void reconcileClaimed(WechatShippingUploadStateStore.UnknownClaim claim) {
        LocalDateTime checkedAt = now();
        if (!StringUtils.hasText(claim.transactionId())) {
            recordReconciliationUnknown(
                    claim,
                    RealWechatShippingProvider.MISSING_TRANSACTION_ID,
                    RealWechatShippingProvider.MISSING_TRANSACTION_ID_MESSAGE,
                    checkedAt
            );
            return;
        }

        WechatShippingOrderQueryResult result;
        try {
            result = shippingProvider.queryShippingOrder(claim.transactionId());
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping reconciliation query failed safely: shipmentId={}, status={}, exception={}",
                    claim.shipmentId(), WechatShippingUploadStatus.UNKNOWN,
                    ex.getClass().getSimpleName()
            );
            result = WechatShippingOrderQueryResult.unknown(
                    UPLOAD_RESULT_UNKNOWN, UPLOAD_RESULT_UNKNOWN_MESSAGE
            );
        }
        if (result == null) {
            result = WechatShippingOrderQueryResult.unknown(
                    UPLOAD_RESULT_UNKNOWN, UPLOAD_RESULT_UNKNOWN_MESSAGE
            );
        }

        if (result.status() == WechatShippingOrderQueryStatus.UPLOADED
                && matchesLocalShipment(claim, result)) {
            if (stateStore.markReconciledUploaded(claim, checkedAt)) {
                log.info(
                        "WeChat shipping unknown result reconciled: shipmentId={}, status={}",
                        claim.shipmentId(), WechatShippingUploadStatus.UPLOADED
                );
            }
            return;
        }
        if (result.status() == WechatShippingOrderQueryStatus.NOT_UPLOADED
                && claim.transactionId().equals(result.transactionId())) {
            if (stateStore.recordDefinitiveNotUploaded(claim, checkedAt)) {
                log.warn(
                        "WeChat shipping not-uploaded observation recorded: shipmentId={}",
                        claim.shipmentId()
                );
            }
            return;
        }

        String code = result.status() == WechatShippingOrderQueryStatus.UPLOADED
                ? "REMOTE_SHIPPING_MISMATCH"
                : result.errorCode();
        String message = result.status() == WechatShippingOrderQueryStatus.UPLOADED
                ? "WeChat shipping facts do not match the local shipment"
                : result.errorMessage();
        recordReconciliationUnknown(claim, code, message, checkedAt);
    }

    private boolean matchesLocalShipment(
            WechatShippingUploadStateStore.UnknownClaim claim,
            WechatShippingOrderQueryResult result
    ) {
        if (!claim.transactionId().equals(result.transactionId())
                || result.shipping() == null
                || !result.shipping().finishShipping()
                || result.shipping().logisticsType() != claim.logisticsType()
                || result.shipping().deliveryMode() != claim.deliveryMode()) {
            return false;
        }
        if (claim.logisticsType() != LogisticsType.EXPRESS) {
            return true;
        }
        return result.shipping().shippingList().stream().anyMatch(item -> sameExpressFact(claim, item));
    }

    private boolean sameExpressFact(
            WechatShippingUploadStateStore.UnknownClaim claim,
            WechatShippingFact remote
    ) {
        return remote != null
                && claim.trackingNo().equals(remote.trackingNo())
                && claim.expressCompanyCode().equals(remote.expressCompany());
    }

    private void recordReconciliationUnknown(
            WechatShippingUploadStateStore.UnknownClaim claim,
            String errorCode,
            String errorMessage,
            LocalDateTime checkedAt
    ) {
        WechatShippingErrorSanitizer.SanitizedError safe = errorSanitizer.sanitize(
                errorCode, errorMessage, claim.knownSecrets()
        );
        stateStore.recordReconciliationUnknown(
                claim, safe.code(), safe.message(), checkedAt
        );
    }

    private WechatShippingUploadRequest toRequest(WechatShippingUploadStateStore.AttemptContext context) {
        WechatShippingItem item;
        if (context.logisticsType() == LogisticsType.EXPRESS) {
            item = new WechatShippingItem(
                    context.trackingNo(), context.expressCompanyCode(), context.itemDesc(),
                    context.consignorContact(), context.receiverContact()
            );
        } else {
            item = new WechatShippingItem(null, null, context.itemDesc(), null, null);
        }
        return new WechatShippingUploadRequest(
                context.orderId(), context.transactionId(), context.openid(),
                context.logisticsType(), context.deliveryMode(), context.uploadTime(), List.of(item)
        );
    }

    private void writeTerminalSafely(
            WechatShippingUploadStateStore.UploadClaim claim,
            WechatProviderMode providerMode,
            WechatShippingUploadStatus uploadStatus,
            String errorCode,
            String errorMessage,
            List<String> knownSecrets
    ) {
        long shipmentId = claim.shipmentId();
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        String safeCode = "";
        String safeMessage = "";
        if (uploadStatus != WechatShippingUploadStatus.UPLOADED) {
            WechatShippingErrorSanitizer.SanitizedError sanitized =
                    errorSanitizer.sanitize(errorCode, errorMessage, knownSecrets);
            safeCode = sanitized.code();
            safeMessage = sanitized.message();
        }
        LocalDateTime uploadedAt = providerMode == WechatProviderMode.REAL
                && uploadStatus == WechatShippingUploadStatus.UPLOADED
                ? now
                : null;
        try {
            boolean persisted = stateStore.writeTerminal(
                    claim, providerMode, uploadStatus, safeCode, safeMessage, now, uploadedAt
            );
            if (persisted) {
                log.info(
                        "WeChat shipping upload terminal: shipmentId={}, mode={}, status={}, code={}",
                        shipmentId, providerMode, uploadStatus,
                        StringUtils.hasText(safeCode) ? safeCode : "NONE"
                );
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping terminal persistence failed: shipmentId={}, mode={}, status={}, code={}, exception={}",
                    shipmentId, providerMode, WechatShippingUploadStatus.UNKNOWN,
                    WechatShippingUploadStateStore.STALE_ERROR_CODE, ex.getClass().getSimpleName()
            );
            try {
                stateStore.fallbackUnknown(claim, providerMode, now());
            } catch (RuntimeException fallbackFailure) {
                log.warn(
                        "WeChat shipping fallback persistence failed: shipmentId={}, mode={}, status={}, code={}, exception={}",
                        shipmentId, providerMode, WechatShippingUploadStatus.UNKNOWN,
                        WechatShippingUploadStateStore.STALE_ERROR_CODE,
                        fallbackFailure.getClass().getSimpleName()
                );
            }
        }
    }

    private WechatProviderMode safeProviderMode() {
        try {
            WechatProviderMode mode = shippingProvider.mode();
            return mode == null ? WechatProviderMode.UNKNOWN : mode;
        } catch (RuntimeException ex) {
            return WechatProviderMode.UNKNOWN;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    private void requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
