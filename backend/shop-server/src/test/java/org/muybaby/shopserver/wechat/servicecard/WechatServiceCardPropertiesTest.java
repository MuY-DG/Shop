package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfig;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WechatServiceCardPropertiesTest {

    @Test
    void reconciliationBackoffStartsAtOneMinuteIsDeterministicAndCapsAtSixHours() {
        WechatServiceCardProperties properties = properties(Duration.ofMinutes(1), Duration.ofHours(6));

        assertThat(properties.reconciliationDelay(1, 91L)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.reconciliationDelay(4, 91L))
                .isEqualTo(properties.reconciliationDelay(4, 91L));
        assertThat(properties.reconciliationDelay(100, 91L)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void unknownReconciliationCannotBeConfiguredBelowOfficialOneMinuteFloor() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(59), Duration.ofHours(6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one minute");
    }

    @Test
    void publicImageRejectsQueriesUserInfoAndUnapprovedHosts() {
        var allowed = java.util.Set.of("admin.junxiangshiping.cn");
        assertThat(WechatServiceCardProperties.validPublicImage(
                "https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png", allowed
        )).isTrue();
        assertThat(WechatServiceCardProperties.validPublicImage(
                "https://admin.junxiangshiping.cn/image.png?sign=temporary", allowed
        )).isFalse();
        assertThat(WechatServiceCardProperties.validPublicImage(
                "https://other.example/image.png", allowed
        )).isFalse();
    }

    @Test
    void databaseCallbackCredentialsRequireWechatAlphanumericCharacterSet() {
        assertThat(config("Token2026", "A".repeat(43)).callbackSecureReady()).isTrue();
        assertThat(config("token-with-dash", "A".repeat(43)).callbackSecureReady()).isFalse();
        assertThat(config("Token2026", "A".repeat(20) + "/" + "A".repeat(22))
                .callbackSecureReady()).isFalse();
    }

    @Test
    void databaseConfigToStringRedactsCallbackSecretsAndIntegrationValues() {
        String sensitive = "sensitive-service-card-value";
        WechatServiceCardConfig config = new WechatServiceCardConfig(
                sensitive, "https://" + sensitive + ".example/image.png",
                Set.of(sensitive + ".example"), false, true, sensitive,
                "A".repeat(43), WechatServiceCardConfig.Source.DATABASE
        );

        assertThat(config.toString()).doesNotContain(sensitive, "A".repeat(43));
    }

    public static WechatServiceCardProperties properties(Duration unknown, Duration maxUnknown) {
        return new WechatServiceCardProperties(
                Duration.ofSeconds(15), 50, Duration.ofMinutes(2), 8,
                Duration.ofMinutes(1), Duration.ofMinutes(30), unknown, maxUnknown, 2,
                Duration.ofSeconds(3), Duration.ofSeconds(15),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(64),
                new WechatServiceCardProperties.Callback(Duration.ofMinutes(5))
        );
    }

    private static WechatServiceCardConfig config(String token, String aesKey) {
        return new WechatServiceCardConfig(
                "template-record",
                "https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png",
                Set.of("admin.junxiangshiping.cn"), false, true, token, aesKey,
                WechatServiceCardConfig.Source.DATABASE
        );
    }
}
