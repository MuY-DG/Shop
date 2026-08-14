package org.muybaby.shopserver.wechat.servicecard.config;

import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Effective business configuration for the WeChat 2001 service-card integration.
 * Operational timing, retry and payload limits deliberately remain in
 * {@link WechatServiceCardProperties}.
 */
public record WechatServiceCardConfig(
        String accountTemplateRecordId,
        String fallbackProductImage,
        Set<String> allowedImageHosts,
        boolean preferOrderSnapshotImages,
        boolean callbackEnabled,
        String callbackToken,
        String callbackEncodingAesKey,
        Source source
) {

    public WechatServiceCardConfig {
        accountTemplateRecordId = accountTemplateRecordId == null
                ? "" : accountTemplateRecordId.trim();
        fallbackProductImage = fallbackProductImage == null
                ? "" : fallbackProductImage.trim();
        allowedImageHosts = allowedImageHosts == null ? Set.of() : Set.copyOf(allowedImageHosts);
        callbackToken = callbackToken == null ? "" : callbackToken.trim();
        callbackEncodingAesKey = callbackEncodingAesKey == null
                ? "" : callbackEncodingAesKey.trim();
        source = source == null ? Source.DATABASE : source;
    }

    public boolean imageConfigurationReady() {
        return WechatServiceCardProperties.validPublicImage(
                fallbackProductImage, allowedImageHosts);
    }

    public boolean templateConfigurationReady() {
        return StringUtils.hasText(accountTemplateRecordId)
                && accountTemplateRecordId.codePointCount(0, accountTemplateRecordId.length()) <= 128;
    }

    public boolean callbackSecureReady() {
        if (!callbackEnabled
                || !callbackToken.matches("[A-Za-z0-9]{3,32}")
                || !callbackEncodingAesKey.matches("[A-Za-z0-9]{43}")) {
            return false;
        }
        try {
            return java.util.Base64.getDecoder()
                    .decode(callbackEncodingAesKey + "=").length == 32;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "WechatServiceCardConfig[accountTemplateRecordIdConfigured="
                + StringUtils.hasText(accountTemplateRecordId)
                + ", fallbackProductImageConfigured=" + StringUtils.hasText(fallbackProductImage)
                + ", allowedImageHostsConfigured=" + !allowedImageHosts.isEmpty()
                + ", preferOrderSnapshotImages=" + preferOrderSnapshotImages
                + ", callbackEnabled=" + callbackEnabled
                + ", callbackToken=<redacted>, callbackEncodingAesKey=<redacted>"
                + ", source=" + source + "]";
    }

    public enum Source {
        DATABASE,
        ENVIRONMENT
    }
}
