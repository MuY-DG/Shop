package org.muybaby.shopserver.logistics.waybill.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "shop.wechat.mini-program",
        name = "mock-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class WechatExpressHttpConfiguration {

    public static final String REST_CLIENT_BEAN_NAME = "wechatExpressRestClient";

    @Bean(name = REST_CLIENT_BEAN_NAME)
    RestClient wechatExpressRestClient(
            RestClient.Builder sharedBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientHttpRequestFactorySettings baseSettings,
            WechatExpressHttpProperties properties
    ) {
        ClientHttpRequestFactorySettings expressSettings = baseSettings.withTimeouts(
                properties.connectTimeout(), properties.readTimeout()
        );
        return sharedBuilder.clone()
                .requestFactory(requestFactoryBuilder.build(expressSettings))
                .build();
    }
}
