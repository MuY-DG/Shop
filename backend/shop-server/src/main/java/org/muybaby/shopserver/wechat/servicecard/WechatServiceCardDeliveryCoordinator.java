package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardDeliveryStore.DeliveryClaim;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardProvider;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardQueryRequest;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardQueryResult;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardSetRequest;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardSetResult;
import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class WechatServiceCardDeliveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WechatServiceCardDeliveryCoordinator.class);

    private final WechatServiceCardProperties properties;
    private final WechatServiceCardDeliveryStore store;
    private final WechatServiceCardPayloadFactory payloadFactory;
    private final WechatServiceCardProvider provider;
    private final WechatMiniProgramProperties miniProgramProperties;
    private final Clock clock;

    public WechatServiceCardDeliveryCoordinator(
            WechatServiceCardProperties properties,
            WechatServiceCardDeliveryStore store,
            WechatServiceCardPayloadFactory payloadFactory,
            WechatServiceCardProvider provider,
            WechatMiniProgramProperties miniProgramProperties,
            Clock clock
    ) {
        this.properties = properties;
        this.store = store;
        this.payloadFactory = payloadFactory;
        this.provider = provider;
        this.miniProgramProperties = miniProgramProperties;
        this.clock = clock;
    }

    public int deliverDue() {
        if (!workerReady()) {
            return 0;
        }
        int claimed = 0;
        LocalDateTime now = now();
        for (Long id : store.dueIds(
                WechatServiceCardDeliveryState.PENDING, now, properties.batchSize())) {
            Optional<DeliveryClaim> claim = store.claim(id, WechatServiceCardDeliveryState.PENDING);
            if (claim.isPresent()) {
                executeSet(claim.get());
                claimed++;
            }
        }
        return claimed;
    }

    public int reconcileDue() {
        if (!workerReady()) {
            return 0;
        }
        int claimed = 0;
        LocalDateTime now = now();
        for (Long id : store.dueIds(
                WechatServiceCardDeliveryState.UNKNOWN, now, properties.batchSize())) {
            Optional<DeliveryClaim> claim = store.claim(id, WechatServiceCardDeliveryState.UNKNOWN);
            if (claim.isPresent()) {
                executeQuery(claim.get());
                claimed++;
            }
        }
        return claimed;
    }

    private void executeSet(DeliveryClaim claim) {
        if (store.settleFromKnownRemote(claim)) {
            return;
        }
        if (!validProviderIdentity(claim)) {
            store.markFailed(
                    claim, "PAYMENT_IDENTITY_INVALID",
                    "Payment identity is incomplete or belongs to a different Mini Program"
            );
            return;
        }
        if (claim.checkJson() != null
                && (claim.paidAt().isAfter(now()) || claim.paidAt().plusHours(24).isBefore(now()))) {
            store.markFailed(
                    claim, "ACTIVATION_WINDOW_EXPIRED",
                    "The WeChat service-card activation window expired"
            );
            return;
        }
        if (claim.checkJson() == null) {
            LocalDateTime updateDeadline = claim.remoteCodeExpireAt() != null
                    ? claim.remoteCodeExpireAt()
                    : claim.activatedAt() == null ? null : claim.activatedAt().plusDays(30);
            if (updateDeadline == null || now().isAfter(updateDeadline)) {
                store.markFailed(
                        claim, "UPDATE_WINDOW_EXPIRED",
                        "The WeChat service-card update window expired"
                );
                return;
            }
        }
        if (!store.prepareProviderCall(claim)) {
            return;
        }
        WechatServiceCardSetResult result;
        try {
            result = provider.setUserNotify(new WechatServiceCardSetRequest(
                    claim.payerOpenid(), claim.transactionId(),
                    claim.contentJson(), claim.checkJson()
            ));
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat 2001 provider result unknown: deliveryId={}, type={}",
                    claim.deliveryId(), ex.getClass().getSimpleName()
            );
            store.markUnknown(
                    claim, "PROVIDER_OUTCOME_UNKNOWN",
                    "Provider attempt outcome is unknown"
            );
            return;
        }
        if (result == null) {
            store.markUnknown(claim, "PROVIDER_OUTCOME_UNKNOWN", "Provider attempt outcome is unknown");
            return;
        }
        switch (result.outcome()) {
            case APPLIED -> store.markApplied(claim, null, null);
            case RETRYABLE -> store.markRetry(
                    claim, providerCode(result.errorCode()), "Provider was unavailable before the request started"
            );
            case UNKNOWN -> store.markUnknown(
                    claim, providerCode(result.errorCode()), "Provider attempt outcome requires reconciliation"
            );
            case REJECTED -> store.markFailed(
                    claim, terminalCode(result.errorCode()), terminalMessage(result.errorCode())
            );
        }
    }

    private void executeQuery(DeliveryClaim claim) {
        if (!validProviderIdentity(claim)) {
            store.markFailed(
                    claim, "PAYMENT_IDENTITY_INVALID",
                    "Payment identity is incomplete or belongs to a different Mini Program"
            );
            return;
        }
        LocalDateTime deadline = claim.remoteCodeExpireAt() != null
                ? claim.remoteCodeExpireAt()
                : claim.activatedAt() != null
                ? claim.activatedAt().plusDays(30)
                : claim.paidAt().plusDays(31);
        if (now().isAfter(deadline)) {
            store.markFailed(
                    claim, "RECONCILIATION_WINDOW_EXPIRED",
                    "The safe WeChat service-card reconciliation window expired"
            );
            return;
        }
        if (!store.prepareProviderCall(claim)) {
            return;
        }
        WechatServiceCardQueryResult result;
        try {
            result = provider.getUserNotify(new WechatServiceCardQueryRequest(
                    claim.payerOpenid(), claim.transactionId()
            ));
        } catch (RuntimeException ex) {
            result = WechatServiceCardQueryResult.retryable(
                    null, "Provider reconciliation is unavailable"
            );
        }
        store.applyQueryResult(claim, result);
    }

    private boolean validProviderIdentity(DeliveryClaim claim) {
        if (claim == null || !StringUtils.hasText(claim.transactionId())
                || !StringUtils.hasText(claim.payerOpenid())
                || claim.amountCent() < 0 || claim.paidAt() == null) {
            return false;
        }
        try {
            payloadFactory.validatePaymentMiniProgram(claim.paymentSnapshot());
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat 2001 payment identity validation failed: deliveryId={}, type={}",
                    claim.deliveryId(), ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private boolean workerReady() {
        return properties.enabled()
                && properties.workerEnabled()
                && properties.templateConfigurationReady()
                && properties.imageConfigurationReady()
                && StringUtils.hasText(miniProgramProperties.appId())
                && StringUtils.hasText(miniProgramProperties.appSecret());
    }

    private static String providerCode(Integer code) {
        return code == null ? "PROVIDER_UNAVAILABLE" : "WECHAT_" + code;
    }

    private static String terminalCode(Integer code) {
        if (code == null) {
            return "PROVIDER_REJECTED";
        }
        return switch (code) {
            case 85438 -> "EXPIRED";
            case 85442 -> "CONTENT_SECURITY_REJECTED";
            case 85461, 85462 -> "CONFIGURATION_BLOCKED";
            case 85433, 85434, 85435, 85439, 85440, 85441, 85443 -> "PAYLOAD_REJECTED";
            case 40003, 85436 -> "IDENTITY_REJECTED";
            default -> "PROVIDER_REJECTED";
        };
    }

    private static String terminalMessage(Integer code) {
        return switch (terminalCode(code)) {
            case "EXPIRED" -> "The WeChat service-card update window expired";
            case "CONTENT_SECURITY_REJECTED" -> "WeChat content security rejected the service card";
            case "CONFIGURATION_BLOCKED" -> "WeChat service-card capability is not authorized";
            case "PAYLOAD_REJECTED" -> "WeChat rejected the service-card payload or transition";
            case "IDENTITY_REJECTED" -> "WeChat rejected the payment notification identity";
            default -> "WeChat rejected the service-card request";
        };
    }
}
