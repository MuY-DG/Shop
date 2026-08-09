package org.muybaby.shopserver.logistics.waybill.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatElectronicWaybillProvider implements WechatElectronicWaybillProvider {

    private static final String SANDBOX_DELIVERY_ID = "TEST";
    private static final String SANDBOX_BIZ_ID = "test_biz_id";
    private static final int SANDBOX_SERVICE_TYPE = 1;
    private static final String SANDBOX_SERVICE_NAME = "test_service_name";
    private static final Set<Integer> SANDBOX_ACTION_TYPES = Set.of(100001, 200001, 300002, 300003);

    @Override
    public WechatElectronicWaybillResult add(WechatElectronicWaybillAddRequest request) {
        String validationCode = validateAdd(request);
        if (validationCode != null) {
            return unavailable(validationCode);
        }
        return WechatElectronicWaybillResult.success(
                request.providerOrderId(),
                request.deliveryId(),
                "MOCK-WAYBILL-" + request.localRecordId(),
                0,
                null
        );
    }

    @Override
    public WechatElectronicWaybillResult get(WechatElectronicWaybillGetRequest request) {
        if (!validGet(request)) {
            return unavailable("INVALID_REQUEST");
        }
        String waybillId = StringUtils.hasText(request.waybillId())
                ? request.waybillId()
                : "MOCK-WAYBILL-" + request.localRecordId();
        return WechatElectronicWaybillResult.success(
                request.providerOrderId(),
                request.deliveryId(),
                waybillId,
                0,
                request.printType() == null
                        ? null
                        : "PGh0bWw+PGJvZHk+TW9jayBsYWJlbDwvYm9keT48L2h0bWw+"
        );
    }

    @Override
    public WechatElectronicWaybillResult cancel(WechatElectronicWaybillCancelRequest request) {
        return !validCancel(request) ? unavailable("INVALID_REQUEST") : WechatElectronicWaybillResult.success(
                request.providerOrderId(), request.deliveryId(), request.waybillId(), 1, null
        );
    }

    @Override
    public WechatElectronicWaybillResult testUpdate(WechatElectronicWaybillTestUpdateRequest request) {
        return !validTestUpdate(request) ? unavailable("INVALID_SANDBOX_EVENT") : WechatElectronicWaybillResult.success(
                request.providerOrderId(), request.deliveryId(), request.waybillId(), 0, null
        );
    }

    private String validateAdd(WechatElectronicWaybillAddRequest request) {
        if (request == null
                || request.localRecordId() == null
                || request.environment() == null
                || missing(request.providerOrderId(), request.openid(), request.deliveryId(), request.bizId())
                || !validContact(request.sender())
                || !validContact(request.receiver())
                || request.parcelCount() < 1
                || !positive(request.weightKg())
                || !positive(request.lengthCm())
                || !positive(request.widthCm())
                || !positive(request.heightCm())
                || request.cargoItems() == null
                || request.cargoItems().isEmpty()
                || request.cargoItems().stream().anyMatch(
                item -> item == null || !StringUtils.hasText(item.name()) || item.count() < 1
        )
                || !StringUtils.hasText(request.miniProgramOrderPath())
                || request.shopItems() == null
                || request.shopItems().isEmpty()
                || request.shopItems().stream().anyMatch(item -> !validShopItem(item))
                || request.serviceType() < 0
                || !StringUtils.hasText(request.serviceName())
                || (request.expectedPickupTime() != null && request.expectedPickupTime() < 0)) {
            return "INVALID_REQUEST";
        }
        if (request.environment() == WechatElectronicWaybillEnvironment.SANDBOX
                && (!SANDBOX_DELIVERY_ID.equals(request.deliveryId())
                || !SANDBOX_BIZ_ID.equals(request.bizId())
                || request.serviceType() != SANDBOX_SERVICE_TYPE
                || !SANDBOX_SERVICE_NAME.equals(request.serviceName()))) {
            return "INVALID_SANDBOX_CONFIGURATION";
        }
        return null;
    }

    private boolean validGet(WechatElectronicWaybillGetRequest request) {
        return request != null
                && request.localRecordId() != null
                && !missing(request.providerOrderId(), request.deliveryId())
                && (request.printType() == null || request.printType() == 0 || request.printType() == 1);
    }

    private boolean validCancel(WechatElectronicWaybillCancelRequest request) {
        return request != null
                && request.localRecordId() != null
                && !missing(request.providerOrderId(), request.deliveryId(), request.waybillId());
    }

    private boolean validTestUpdate(WechatElectronicWaybillTestUpdateRequest request) {
        return request != null
                && request.localRecordId() != null
                && SANDBOX_BIZ_ID.equals(request.bizId())
                && SANDBOX_DELIVERY_ID.equals(request.deliveryId())
                && !missing(request.providerOrderId(), request.waybillId(), request.actionMessage())
                && request.actionTime() >= 1
                && SANDBOX_ACTION_TYPES.contains(request.actionType());
    }

    private boolean validContact(WechatExpressContact contact) {
        return contact != null && !missing(
                contact.name(), contact.mobile(), contact.province(), contact.city(), contact.area(), contact.address()
        );
    }

    private boolean validShopItem(WechatExpressShopItem item) {
        if (item == null || missing(item.goodsName(), item.goodsImageUrl(), item.goodsDescription())) {
            return false;
        }
        try {
            URI uri = URI.create(item.goodsImageUrl().trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean missing(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private WechatElectronicWaybillResult unavailable(String code) {
        return WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.UNAVAILABLE,
                code,
                "Mock WeChat express request is invalid"
        );
    }
}
