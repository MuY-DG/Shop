package org.muybaby.shopserver.logistics.waybill.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatExpressHttpConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HttpPropertiesConfiguration.class);

    @Test
    void dedicatedClientAppliesExplicitTimeoutsWithoutMutatingSharedBuilder() {
        RestClient.Builder sharedBuilder = RestClient.builder();
        MockRestServiceServer sharedServer = MockRestServiceServer.bindTo(sharedBuilder).build();
        AtomicReference<ClientHttpRequestFactorySettings> capturedSettings = new AtomicReference<>();
        ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory> factoryBuilder = settings -> {
            capturedSettings.set(settings);
            return (uri, httpMethod) -> new MockClientHttpRequest(httpMethod, uri);
        };
        ClientHttpRequestFactorySettings baseSettings = ClientHttpRequestFactorySettings.defaults()
                .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);
        WechatExpressHttpProperties properties = new WechatExpressHttpProperties(
                Duration.ofSeconds(3), Duration.ofSeconds(15), DataSize.ofMegabytes(5)
        );

        new WechatExpressHttpConfiguration().wechatExpressRestClient(
                sharedBuilder, factoryBuilder, baseSettings, properties
        );

        assertThat(capturedSettings.get().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(capturedSettings.get().readTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(capturedSettings.get().redirects())
                .isEqualTo(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);

        sharedServer.expect(requestTo("/shared-client-still-mocked"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        String body = sharedBuilder.build()
                .get()
                .uri("/shared-client-still-mocked")
                .retrieve()
                .body(String.class);
        assertThat(body).isEqualTo("ok");
        sharedServer.verify();
    }

    @Test
    void responseLimitIsPositiveAndFitsBoundedReader() {
        WechatExpressHttpProperties properties = new WechatExpressHttpProperties(
                Duration.ofSeconds(3), Duration.ofSeconds(15), DataSize.ofMegabytes(5)
        );

        assertThat(properties.maxResponseBytes()).isEqualTo(5 * 1024 * 1024);
    }

    @Test
    void propertiesBindSafeDefaultsAndExplicitOverrides() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WechatExpressHttpProperties properties = context.getBean(WechatExpressHttpProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.maxResponseSize()).isEqualTo(DataSize.ofMegabytes(5));
        });
        contextRunner.withPropertyValues(
                        "shop.wechat.express.http.connect-timeout=4s",
                        "shop.wechat.express.http.read-timeout=21s",
                        "shop.wechat.express.http.max-response-size=6MB"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WechatExpressHttpProperties properties = context.getBean(WechatExpressHttpProperties.class);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(21));
                    assertThat(properties.maxResponseSize()).isEqualTo(DataSize.ofMegabytes(6));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WechatExpressHttpProperties.class)
    static class HttpPropertiesConfiguration {
    }
}
