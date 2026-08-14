package org.muybaby.shopserver.wechat.servicecard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shop.secret-encryption",
        name = "rotation-enabled",
        havingValue = "true"
)
public class WechatServiceCardSecretRotationScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            WechatServiceCardSecretRotationScheduler.class);

    private final WechatServiceCardConfigService configService;

    public WechatServiceCardSecretRotationScheduler(WechatServiceCardConfigService configService) {
        this.configService = configService;
    }

    @Scheduled(
            fixedDelayString = "${shop.secret-encryption.rotation-delay:1m}",
            initialDelayString = "${shop.secret-encryption.rotation-delay:1m}"
    )
    public void runOnce() {
        try {
            int rotated = configService.rotateSecretsIfNeeded();
            if (rotated > 0) {
                log.info("Re-encrypted {} WeChat service-card callback secret fields", rotated);
            }
        } catch (RuntimeException ex) {
            log.warn("WeChat service-card secret rotation failed; it will be retried (type={})",
                    ex.getClass().getSimpleName());
        }
    }
}
