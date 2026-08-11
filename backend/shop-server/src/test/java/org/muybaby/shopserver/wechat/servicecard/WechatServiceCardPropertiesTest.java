package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

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
        var allowed = java.util.Set.of("admin.muybaby6.icu");
        assertThat(WechatServiceCardProperties.validPublicImage(
                "https://admin.muybaby6.icu/wechat/service-card-placeholder.png", allowed
        )).isTrue();
        assertThat(WechatServiceCardProperties.validPublicImage(
                "https://admin.muybaby6.icu/image.png?sign=temporary", allowed
        )).isFalse();
        assertThat(WechatServiceCardProperties.validPublicImage(
                "https://other.example/image.png", allowed
        )).isFalse();
    }

    public static WechatServiceCardProperties properties(Duration unknown, Duration maxUnknown) {
        return new WechatServiceCardProperties(
                true, true, "template-record",
                Duration.ofSeconds(15), 50, Duration.ofMinutes(2), 8,
                Duration.ofMinutes(1), Duration.ofMinutes(30), unknown, maxUnknown, 2,
                Duration.ofSeconds(3), Duration.ofSeconds(15),
                DataSize.ofMegabytes(1), DataSize.ofKilobytes(64),
                "https://admin.muybaby6.icu/wechat/service-card-placeholder.png",
                false, List.of("admin.muybaby6.icu"),
                new WechatServiceCardProperties.Callback(false, "", "", Duration.ofMinutes(5))
        );
    }
}
