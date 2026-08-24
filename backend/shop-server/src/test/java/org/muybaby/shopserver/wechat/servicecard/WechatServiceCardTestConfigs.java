package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfig;

import java.util.Set;

public final class WechatServiceCardTestConfigs {

    private WechatServiceCardTestConfigs() {
    }

    public static WechatServiceCardConfig readyConfig() {
        return new WechatServiceCardConfig(
                "template-record",
                "https://admin.junxiangshiping.cn/wechat/service-card-placeholder.png",
                Set.of("admin.junxiangshiping.cn"),
                false,
                true,
                "callbackToken123",
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY",
                WechatServiceCardConfig.Source.DATABASE
        );
    }

    public static WechatServiceCardConfig disabledConfig() {
        return new WechatServiceCardConfig(
                "", "", Set.of(), false, false, "", "",
                WechatServiceCardConfig.Source.DATABASE
        );
    }
}
