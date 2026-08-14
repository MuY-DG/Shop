package org.muybaby.shopserver.wechat.platform;

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
public class WechatPlatformSecretRotationScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            WechatPlatformSecretRotationScheduler.class);

    private final WechatPlatformConfigService configService;

    public WechatPlatformSecretRotationScheduler(WechatPlatformConfigService configService) {
        this.configService = configService;
    }

    @Scheduled(
            fixedDelayString = "${shop.secret-encryption.rotation-delay:1m}",
            initialDelayString = "${shop.secret-encryption.rotation-delay:1m}"
    )
    public void runOnce() {
        try {
            if (configService.rotateSecretIfNeeded() > 0) {
                log.info("Re-encrypted the WeChat platform credential with the active key");
            }
        } catch (RuntimeException ex) {
            log.warn("WeChat platform credential rotation failed; it will be retried (type={})",
                    ex.getClass().getSimpleName());
        }
    }
}
