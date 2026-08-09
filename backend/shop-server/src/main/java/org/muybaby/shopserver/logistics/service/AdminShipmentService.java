package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.waybill.registration.WechatWaybillRegistrationCoordinator;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminShipmentService {

    private static final Logger log = LoggerFactory.getLogger(AdminShipmentService.class);

    private final LocalShipmentService localShipmentService;
    private final WechatShippingUploadCoordinator uploadCoordinator;
    private final WechatWaybillRegistrationCoordinator registrationCoordinator;

    public AdminShipmentService(
            LocalShipmentService localShipmentService,
            WechatShippingUploadCoordinator uploadCoordinator,
            WechatWaybillRegistrationCoordinator registrationCoordinator
    ) {
        this.localShipmentService = localShipmentService;
        this.uploadCoordinator = uploadCoordinator;
        this.registrationCoordinator = registrationCoordinator;
    }

    public OrderShipmentResponse ship(
            AuthenticatedPrincipal principal,
            Long orderId,
            AdminShipOrderRequest request
    ) {
        OrderShipmentResponse local = localShipmentService.create(principal, orderId, request);
        coordinateAfterCommit(orderId, local.shipmentId());
        return localShipmentService.getForAdmin(orderId);
    }

    private void coordinateAfterCommit(long orderId, long shipmentId) {
        try {
            uploadCoordinator.attemptInitial(shipmentId);
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping initial coordination failed: orderId={}, shipmentId={}, exception={}",
                    orderId, shipmentId, ex.getClass().getSimpleName()
            );
        }
        try {
            registrationCoordinator.attemptInitial(shipmentId);
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat waybill registration initial coordination failed: orderId={}, shipmentId={}, exception={}",
                    orderId, shipmentId, ex.getClass().getSimpleName()
            );
        }
    }

    public OrderShipmentResponse retryWechatUpload(AuthenticatedPrincipal principal, Long orderId) {
        uploadCoordinator.retry(principal, orderId);
        return localShipmentService.getForAdmin(orderId);
    }

    public OrderShipmentResponse retryWaybillRegistration(
            AuthenticatedPrincipal principal,
            Long orderId
    ) {
        requireAdmin(principal);
        registrationCoordinator.retryForAdmin(orderId);
        return localShipmentService.getForAdmin(orderId);
    }

    public OrderShipmentResponse confirmElectronicWaybill(
            AuthenticatedPrincipal principal,
            Long orderId,
            Long waybillRecordId
    ) {
        OrderShipmentResponse local = localShipmentService.confirmElectronicWaybill(
                principal, orderId, waybillRecordId
        );
        coordinateAfterCommit(orderId, local.shipmentId());
        return localShipmentService.getForAdmin(orderId);
    }

    private void requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
