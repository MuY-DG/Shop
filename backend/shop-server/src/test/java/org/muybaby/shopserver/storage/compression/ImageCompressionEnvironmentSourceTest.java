package org.muybaby.shopserver.storage.compression;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionConfigSource;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionRuntimeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties =
        "shop.storage.image-compression.api-key=tinify-environment-secret-1234")
@ActiveProfiles("test")
class ImageCompressionEnvironmentSourceTest {

    @Autowired
    private ImageCompressionRuntimeConfigService configService;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanup() {
        jdbcClient.sql("delete from image_compression_runtime_setting").update();
    }

    @Test
    void autoSourcePrefersValidEnvironmentKeyWithoutPersistingIt() {
        assertThat(configService.effective().resolvedSource())
                .isEqualTo(ImageCompressionConfigSource.ENV);
        assertThat(configService.effective().apiKey())
                .isEqualTo("tinify-environment-secret-1234");
        assertThat(configService.current().defaultConfigSource()).isEqualTo("ENV");
        assertThat(configService.current().apiKeyMasked()).isEqualTo("tini******1234");
        assertThat(configService.current().persisted()).isFalse();
        assertThat(jdbcClient.sql("select count(*) from image_compression_runtime_setting")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void autoSourceDoesNotDecryptAnUnusedDamagedDatabaseKey() {
        jdbcClient.sql("""
                        insert into image_compression_runtime_setting
                            (id, admin_configured, requested_enabled, config_source,
                             api_key_ciphertext, monthly_limit)
                        values
                            (1, true, true, 'AUTO', 'damaged-envelope', 500)
                        """)
                .update();

        assertThat(configService.effective().apiKey())
                .isEqualTo("tinify-environment-secret-1234");
        assertThat(configService.current().effectiveEnabled()).isTrue();
        assertThat(configService.current().apiKeyMasked())
                .isEqualTo("tini******1234");
    }
}
