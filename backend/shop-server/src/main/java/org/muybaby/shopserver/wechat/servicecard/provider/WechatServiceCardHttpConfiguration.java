package org.muybaby.shopserver.wechat.servicecard.provider;

import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "shop.wechat.mini-program",
        name = "mock-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class WechatServiceCardHttpConfiguration {

    public static final String REST_CLIENT_BEAN_NAME = "wechatServiceCardRestClient";

    @Bean(name = REST_CLIENT_BEAN_NAME)
    RestClient wechatServiceCardRestClient(
            RestClient.Builder sharedBuilder,
            ClientHttpRequestFactorySettings baseSettings,
            WechatServiceCardProperties properties
    ) {
        ClientHttpRequestFactorySettings settings = baseSettings.withTimeouts(
                properties.connectTimeout(), properties.readTimeout()
        );
        // WeChat rejects chunked JSON POST bodies with HTTP 412. Buffering is bounded by the
        // service-card payload limit and restores an explicit Content-Length header.
        BufferingClientHttpRequestFactory requestFactory = new BufferingClientHttpRequestFactory(
                ClientHttpRequestFactoryBuilder.jdk()
                        .withHttpClientCustomizer(builder -> builder.version(HttpClient.Version.HTTP_1_1))
                        .build(settings)
        );
        return sharedBuilder.clone()
                .requestFactory(requestFactory)
                .build();
    }
}
