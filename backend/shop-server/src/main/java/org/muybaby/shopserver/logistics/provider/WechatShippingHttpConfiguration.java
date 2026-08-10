package org.muybaby.shopserver.logistics.provider;

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
public class WechatShippingHttpConfiguration {

    public static final String REST_CLIENT_BEAN_NAME = "wechatShippingRestClient";

    @Bean(name = REST_CLIENT_BEAN_NAME)
    RestClient wechatShippingRestClient(
            RestClient.Builder sharedBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientHttpRequestFactorySettings baseSettings,
            WechatShippingHttpProperties properties
    ) {
        ClientHttpRequestFactorySettings settings = baseSettings.withTimeouts(
                properties.connectTimeout(), properties.readTimeout()
        );
        return sharedBuilder.clone()
                .requestFactory(requestFactoryBuilder.build(settings))
                .build();
    }
}
