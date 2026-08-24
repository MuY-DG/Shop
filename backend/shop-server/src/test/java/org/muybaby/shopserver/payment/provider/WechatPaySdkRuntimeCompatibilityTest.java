package org.muybaby.shopserver.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import okio.Options;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;

import java.net.URL;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WechatPaySdkRuntimeCompatibilityTest {

    @Test
    void resolvesExactlyOneModernOkioImplementationAtRuntime() throws Exception {
        List<URL> implementations = Collections.list(
                Options.class.getClassLoader().getResources("okio/Options.class"));

        assertThat(implementations)
                .singleElement()
                .satisfies(location -> assertThat(location.toString()).contains("okio-jvm"));
    }

    @Test
    void initializesDefaultHttpClientAndRealProviderWithPackagedRuntimeDependencies() throws Exception {
        ResolvedPaymentConfig paymentConfig = paymentConfig();
        Config sdkConfig = WechatPaySdkConfigFactory.create(paymentConfig);

        assertThatCode(() -> new DefaultHttpClientBuilder()
                .config(sdkConfig)
                .build())
                .doesNotThrowAnyException();

        RealWechatPayProvider provider = new RealWechatPayProvider(
                new ObjectMapper(), Clock.systemUTC());
        assertThat(provider.refundService(paymentConfig)).isNotNull();
    }

    private ResolvedPaymentConfig paymentConfig() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return new ResolvedPaymentConfig(
                PaymentConfigSource.DB,
                1L,
                "SDK runtime compatibility test",
                true,
                "wx-sdk-runtime-test",
                "mch-sdk-runtime-test",
                "0123456789ABCDEF0123456789ABCDEF01234567",
                "0123456789abcdef0123456789abcdef",
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                "https://pay.example.test/wxpay/pay/notify",
                "https://pay.example.test/wxpay/refund/notify",
                PaymentVerifyMode.PUBLIC_KEY,
                "PUB_KEY_ID_SDK_RUNTIME_TEST",
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded())
        );
    }

    private String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
