package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminShipmentService {

    private static final Logger log = LoggerFactory.getLogger(AdminShipmentService.class);

    private final LocalShipmentService localShipmentService;
    private final WechatShippingUploadCoordinator uploadCoordinator;

    public AdminShipmentService(
            LocalShipmentService localShipmentService,
            WechatShippingUploadCoordinator uploadCoordinator
    ) {
        this.localShipmentService = localShipmentService;
        this.uploadCoordinator = uploadCoordinator;
    }

    public OrderShipmentResponse ship(
            AuthenticatedPrincipal principal,
            Long orderId,
            AdminShipOrderRequest request
    ) {
        OrderShipmentResponse local = localShipmentService.create(principal, orderId, request);
        try {
            uploadCoordinator.attemptInitial(local.shipmentId());
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping initial coordination failed: orderId={}, shipmentId={}, exception={}",
                    orderId, local.shipmentId(), ex.getClass().getSimpleName()
            );
        }
        return localShipmentService.getForAdmin(orderId);
    }

    public OrderShipmentResponse retryWechatUpload(AuthenticatedPrincipal principal, Long orderId) {
        uploadCoordinator.retry(principal, orderId);
        return localShipmentService.getForAdmin(orderId);
    }
}
