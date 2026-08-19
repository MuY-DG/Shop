package org.muybaby.shopserver.logistics.tracking;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.tracking.dto.ShipmentTrackingResponse;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathResult;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingProvider;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryResult;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ShipmentTrackingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ShipmentTrackingCoordinator.class);

    private final ShipmentTrackingStateStore stateStore;
    private final WechatTrackingProvider provider;

    public ShipmentTrackingCoordinator(
            ShipmentTrackingStateStore stateStore,
            WechatTrackingProvider provider
    ) {
        this.stateStore = stateStore;
        this.provider = provider;
    }

    public ShipmentTrackingResponse readForOwner(
            AuthenticatedPrincipal principal,
            long orderId
    ) {
        return stateStore.snapshotForOwner(orderId, requireAppUser(principal));
    }

    public ShipmentTrackingResponse readForOwner(
            AuthenticatedPrincipal principal, long orderId, long shipmentId
    ) {
        return stateStore.snapshotForOwner(orderId, shipmentId, requireAppUser(principal));
    }

    public ShipmentTrackingResponse syncForOwner(
            AuthenticatedPrincipal principal,
            long orderId
    ) {
        long userId = requireAppUser(principal);
        stateStore.claimForOwner(orderId, userId, false).ifPresent(this::execute);
        return stateStore.snapshotForOwner(orderId, userId);
    }

    public ShipmentTrackingResponse syncForOwner(
            AuthenticatedPrincipal principal, long orderId, long shipmentId
    ) {
        long userId = requireAppUser(principal);
        stateStore.claimForOwner(orderId, shipmentId, userId, false).ifPresent(this::execute);
        return stateStore.snapshotForOwner(orderId, shipmentId, userId);
    }

    public ShipmentTrackingResponse readForAdmin(long orderId) {
        return stateStore.snapshotForAdmin(orderId);
    }

    public ShipmentTrackingResponse readForAdmin(long orderId, long shipmentId) {
        return stateStore.snapshotForAdmin(orderId, shipmentId);
    }

    public ShipmentTrackingResponse syncForAdmin(long orderId) {
        stateStore.claimForAdmin(orderId, true).ifPresent(this::execute);
        return stateStore.snapshotForAdmin(orderId);
    }

    public ShipmentTrackingResponse syncForAdmin(long orderId, long shipmentId) {
        stateStore.claimForAdmin(orderId, shipmentId, true).ifPresent(this::execute);
        return stateStore.snapshotForAdmin(orderId, shipmentId);
    }

    private void execute(ShipmentTrackingClaim claim) {
        WechatTrackingQueryResult queryResult = null;
        WechatTrackingPathResult pathResult = null;
        if (claim.queryRequest() != null) {
            try {
                queryResult = provider.query(claim.queryRequest());
            } catch (RuntimeException ex) {
                queryResult = WechatTrackingQueryResult.failure(
                        WechatProviderOutcome.UNKNOWN,
                        "PROVIDER_EXCEPTION",
                        "WeChat tracking query result is unknown"
                );
                logProviderException(claim.shipmentId(), "QUERY", ex);
            }
        }
        if (claim.pathRequest() != null) {
            try {
                pathResult = provider.getPath(claim.pathRequest());
            } catch (RuntimeException ex) {
                pathResult = WechatTrackingPathResult.failure(
                        WechatProviderOutcome.UNKNOWN,
                        "PROVIDER_EXCEPTION",
                        "WeChat tracking query result is unknown"
                );
                logProviderException(claim.shipmentId(), "GET_PATH", ex);
            }
        }
        boolean completed = stateStore.complete(
                claim,
                new ShipmentTrackingSyncResult(queryResult, pathResult)
        );
        if (!completed) {
            log.warn(
                    "WeChat tracking synchronization lost claim: shipmentId={}",
                    claim.shipmentId()
            );
        }
    }

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private void logProviderException(long shipmentId, String source, RuntimeException ex) {
        log.warn(
                "WeChat tracking provider threw: shipmentId={}, source={}, exception={}",
                shipmentId, source, ex.getClass().getSimpleName()
        );
    }
}
