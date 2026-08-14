package org.muybaby.shopserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlSafetyTest {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(?=[:}])");

    @Test
    void baseApplicationYamlDoesNotActivateDevProfileOrMockWechat() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .doesNotContainKeys("spring.profiles.active", "spring.config.import")
                .containsEntry("shop.wechat.mini-program.mock-enabled", false)
                .containsEntry("spring.data.redis.host", "${SHOP_REDIS_HOST:127.0.0.1}")
                .containsEntry("spring.data.redis.port", "${SHOP_REDIS_PORT:6379}")
                .containsEntry("spring.data.redis.database", "${SHOP_REDIS_DATABASE:0}")
                .containsEntry("spring.servlet.multipart.max-file-size", "50MB")
                .containsEntry("shop.storage.direct-upload.max-active-sessions-per-principal", 10)
                .containsEntry("shop.pay.timeout-scan-enabled", true)
                .containsEntry("shop.pay.expire-minutes", 15)
                .containsEntry("shop.finance.reconciliation.worker-enabled", false)
                .containsEntry(
                        "shop.wechat.shipping.delivery.enabled",
                        "${SHOP_WECHAT_SHIPPING_DELIVERY_ENABLED:true}"
                )
                .containsEntry(
                        "shop.wechat.shipping.receipt-reconciliation.enabled",
                        "${SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED:true}"
                )
                .doesNotContainKeys(
                        "shop.storage.direct-upload.session-retention",
                        "shop.storage.direct-upload.cleanup-initial-delay",
                        "shop.storage.direct-upload.cleanup-fixed-delay",
                        "shop.analytics.retention.batch-size",
                        "shop.admin-system-log.retention.batch-size",
                        "shop.customer-service.retention.batch-size"
                )
                .containsEntry("logging.pattern.level", "%5p [requestId=%X{requestId:-}]")
                .containsEntry(
                        "spring.flyway.placeholders.seed_super_status",
                        "${SHOP_DEFAULT_ADMIN_STATUS:DISABLED}"
                );
        assertThat(properties.getProperty("spring.flyway.placeholders.seed_super_password_hash"))
                .doesNotContain("VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i");
    }

    @Test
    void developmentApplicationYamlImportsOnlyDevelopmentEnvironment() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .doesNotContainKeys("spring.profiles.active", "server.address")
                .containsEntry("spring.config.import", "optional:file:.env.dev.local[.properties]")
                .containsEntry("spring.flyway.placeholders.seed_super_status", "ENABLED")
                .containsEntry("logging.level.org.muybaby.shopserver", "debug")
                .containsEntry("springdoc.api-docs.enabled", true);
    }

    @Test
    void productionApplicationYamlUsesLoopbackAndSafeOperationalDefaults() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .doesNotContainKey("spring.profiles.active")
                .containsEntry("spring.config.import", "file:.env.prod.local[.properties]")
                .containsEntry("server.address", "${SERVER_ADDRESS:127.0.0.1}")
                .containsEntry("management.info.defaults.enabled", false)
                .containsEntry("management.info.build.enabled", false)
                .containsEntry("management.info.git.enabled", false)
                .containsEntry("management.info.env.enabled", false)
                .containsEntry(
                        "logging.level.org.muybaby.shopserver.logistics.provider.RealWechatShippingProvider",
                        "warn"
                )
                .doesNotContainKeys(
                        "server.port",
                        "logging.level.org.muybaby.shopserver",
                        "springdoc.api-docs.enabled",
                        "springdoc.swagger-ui.enabled",
                        "spring.flyway.placeholders.seed_super_status"
                );
        assertThat(properties.getProperty("spring.datasource.username"))
                .isEqualTo("${SHOP_DB_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .isEqualTo("${SHOP_DB_PASSWORD}");
    }

    @Test
    void productionEnvironmentExampleContainsOnlyStartupBoundaryKeys() throws IOException {
        Properties properties = loadEnvironmentExample(".env.prod.example");

        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder(
                "SHOP_DB_URL",
                "SHOP_DB_USERNAME",
                "SHOP_DB_PASSWORD",
                "SHOP_REDIS_HOST",
                "SHOP_REDIS_PORT",
                "SHOP_REDIS_DATABASE",
                "SHOP_REDIS_USERNAME",
                "SHOP_REDIS_PASSWORD",
                "SHOP_TRUSTED_PROXY_CIDRS",
                "SHOP_MAX_FORWARDED_HOPS",
                "SHOP_SECRET_ENCRYPTION_WRITE_VERSION",
                "SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID",
                "SHOP_SECRET_ENCRYPTION_KEY_RING",
                "SHOP_SECRET_ENCRYPTION_LEGACY_KEY",
                "SHOP_SECRET_ENCRYPTION_ROTATION_ENABLED",
                "SHOP_DEFAULT_ADMIN_STATUS",
                "SHOP_DEFAULT_ADMIN_PASSWORD_HASH"
        );
    }

    @Test
    void baseApplicationYamlKeepsOnlyStartupAndLegacyMigrationPlaceholders() throws IOException {
        Matcher matcher = ENV_PLACEHOLDER.matcher(
                Files.readString(Path.of("src/main/resources/application.yaml"))
        );
        Set<String> variables = new TreeSet<>();
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }

        assertThat(variables).containsExactlyInAnyOrder(
                "SHOP_DEFAULT_ADMIN_PASSWORD_HASH",
                "SHOP_DEFAULT_ADMIN_STATUS",
                "SHOP_MAX_FORWARDED_HOPS",
                "SHOP_PAY_NOTIFICATION_ROUTE_ENABLED",
                "SHOP_REDIS_DATABASE",
                "SHOP_REDIS_HOST",
                "SHOP_REDIS_PASSWORD",
                "SHOP_REDIS_PORT",
                "SHOP_REDIS_USERNAME",
                "SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID",
                "SHOP_SECRET_ENCRYPTION_KEY_RING",
                "SHOP_SECRET_ENCRYPTION_LEGACY_KEY",
                "SHOP_SECRET_ENCRYPTION_ROTATION_ENABLED",
                "SHOP_SECRET_ENCRYPTION_WRITE_VERSION",
                "SHOP_TRUSTED_PROXY_CIDRS",
                "SHOP_WECHAT_RECEIPT_RECONCILIATION_ENABLED",
                "SHOP_WECHAT_SERVICE_CARD_CALLBACK_AES_KEY",
                "SHOP_WECHAT_SERVICE_CARD_CALLBACK_ENABLED",
                "SHOP_WECHAT_SERVICE_CARD_CALLBACK_TOKEN",
                "SHOP_WECHAT_SERVICE_CARD_CAPTURE_ENABLED",
                "SHOP_WECHAT_SERVICE_CARD_FALLBACK_IMAGE",
                "SHOP_WECHAT_SERVICE_CARD_IMAGE_HOSTS",
                "SHOP_WECHAT_SERVICE_CARD_TEMPLATE_RECORD_ID",
                "SHOP_WECHAT_SERVICE_CARD_WORKER_ENABLED",
                "SHOP_WECHAT_SHIPPING_DELIVERY_ENABLED",
                "SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED",
                "WECHAT_MINI_PROGRAM_APP_ID",
                "WECHAT_MINI_PROGRAM_APP_SECRET",
                "WECHAT_PAY_API_V3_KEY",
                "WECHAT_PAY_APP_ID",
                "WECHAT_PAY_CONFIG_SOURCE",
                "WECHAT_PAY_ENABLED",
                "WECHAT_PAY_MCH_ID",
                "WECHAT_PAY_MERCHANT_SERIAL_NO",
                "WECHAT_PAY_MOCK_ENABLED",
                "WECHAT_PAY_NOTIFY_URL",
                "WECHAT_PAY_PRIVATE_KEY_PATH",
                "WECHAT_PAY_PUBLIC_KEY_ID",
                "WECHAT_PAY_PUBLIC_KEY_PATH",
                "WECHAT_PAY_REFUND_NOTIFY_URL",
                "WECHAT_PAY_VERIFY_MODE"
        );
    }

    @Test
    void infrastructureEnvironmentExampleRemainsIsolated() throws IOException {
        Properties properties = loadEnvironmentExample(".env.infrastructure.example");

        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder(
                "MYSQL_DATABASE",
                "MYSQL_USER",
                "MYSQL_PASSWORD",
                "MYSQL_ROOT_PASSWORD",
                "REDIS_PASSWORD"
        );
    }

    private Properties loadEnvironmentExample(String filename) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(filename))) {
            properties.load(reader);
        }
        return properties;
    }
}
