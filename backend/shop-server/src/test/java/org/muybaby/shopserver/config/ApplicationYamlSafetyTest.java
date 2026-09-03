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
    void baseApplicationYamlDoesNotActivateLocalProfileOrMockWechat() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .doesNotContainKeys("spring.profiles.active", "spring.config.import")
                .containsEntry("shop.wechat.mini-program.mock-enabled", false)
                .containsEntry("spring.data.redis.host", "127.0.0.1")
                .containsEntry("spring.data.redis.port", 6379)
                .containsEntry("spring.data.redis.database", 0)
                .containsEntry("spring.data.redis.password", "${SHOP_REDIS_PASSWORD}")
                .containsEntry("shop.security.client-ip.trusted-proxy-cidrs", "127.0.0.0/8,::1/128")
                .containsEntry("spring.servlet.multipart.max-file-size", "50MB")
                .containsEntry("shop.storage.direct-upload.max-active-sessions-per-principal", 10)
                .containsEntry("shop.pay.timeout-scan-enabled", true)
                .containsEntry("shop.pay.timeout-scan-delay", "5m")
                .containsEntry("shop.pay.timeout-zset.enabled", true)
                .containsEntry("shop.pay.timeout-zset.poll-delay", "1s")
                .containsEntry("shop.pay.timeout-zset.batch-size", 50)
                .containsEntry("shop.pay.timeout-zset.retry-delay", "30s")
                .containsEntry("shop.pay.expire-minutes", 15)
                .containsEntry("shop.admin-system-log.slow-request-threshold", "1s")
                .containsEntry("shop.admin-system-log.request-retention-days", 14)
                .containsEntry("shop.runtime-logging.directory", "logs")
                .doesNotContainKeys(
                        "shop.storage.direct-upload.session-retention",
                        "shop.storage.direct-upload.cleanup-initial-delay",
                        "shop.storage.direct-upload.cleanup-fixed-delay",
                        "shop.analytics.retention.batch-size",
                        "shop.admin-system-log.retention.batch-size",
                        "shop.customer-service.retention.batch-size"
                )
                .containsEntry("logging.pattern.level", "%5p [requestId=%X{requestId:-}]")
                .doesNotContainKeys(
                        "spring.flyway.placeholders.seed_super_status",
                        "spring.flyway.placeholders.seed_super_password_hash",
                        "shop.pay.notification-route.enabled",
                        "shop.secret-encryption.write-version",
                        "shop.secret-encryption.legacy-key",
                        "shop.wechat.shipping.upload-enabled",
                        "shop.wechat.shipping.delivery.enabled",
                        "shop.wechat.shipping.receipt-reconciliation.enabled",
                        "shop.finance.reconciliation.worker-enabled",
                        "shop.finance.reconciliation.daily-enabled"
                );
    }

    @Test
    void localApplicationYamlImportsOnlyLocalRuntimeManifest() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-local.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .doesNotContainKeys("spring.profiles.active", "server.address")
                .containsEntry("spring.config.import", "file:config/runtime/local.env[.properties]")
                .containsEntry("spring.datasource.username", "root")
                .containsEntry("spring.datasource.password", "${SHOP_DB_ROOT_PASSWORD}")
                .containsEntry("logging.level.org.muybaby.shopserver", "debug")
                .containsEntry("springdoc.api-docs.enabled", true)
                .doesNotContainKeys(
                        "spring.flyway.placeholders.seed_super_status",
                        "spring.flyway.placeholders.seed_super_password_hash"
                );
    }

    @Test
    void serverApplicationYamlUsesComposeTopologyAndSafeOperationalDefaults() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-server.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .doesNotContainKeys("spring.profiles.active", "spring.config.import")
                .containsEntry("server.address", "0.0.0.0")
                .containsEntry("spring.datasource.username", "shop")
                .containsEntry("spring.datasource.password", "${SHOP_DB_PASSWORD}")
                .containsEntry("spring.data.redis.host", "redis")
                .containsEntry("spring.data.redis.password", "${SHOP_REDIS_PASSWORD}")
                .containsEntry("shop.security.client-ip.max-forwarded-hops", 1)
                .containsEntry("shop.runtime-logging.directory", "/var/log/shop-server")
                .containsEntry(
                        "shop.security.client-ip.trusted-proxy-cidrs",
                        "127.0.0.0/8,::1/128,172.23.0.1/32"
                )
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
                        "spring.flyway.placeholders.seed_super_status",
                        "spring.flyway.placeholders.seed_super_password_hash"
                );
    }

    @Test
    void runtimeEnvironmentExampleContainsOnlyPerEnvironmentBoundaryKeys() throws IOException {
        Properties properties = loadEnvironmentExample("config/runtime/runtime.env.example");

        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder(
                "SHOP_DB_PASSWORD",
                "SHOP_DB_ROOT_PASSWORD",
                "SHOP_REDIS_PASSWORD",
                "SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID",
                "SHOP_SECRET_ENCRYPTION_KEY_RING"
        );
    }

    @Test
    void baseApplicationYamlKeepsOnlyRuntimeSecretPlaceholders() throws IOException {
        Matcher matcher = ENV_PLACEHOLDER.matcher(
                Files.readString(Path.of("src/main/resources/application.yaml"))
        );
        Set<String> variables = new TreeSet<>();
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }

        assertThat(variables).containsExactlyInAnyOrder(
                "SHOP_REDIS_PASSWORD",
                "SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID",
                "SHOP_SECRET_ENCRYPTION_KEY_RING"
        );
    }

    @Test
    void composeUsesOneManifestAndExplicitPerServiceWhitelists() throws IOException {
        String compose = Files.readString(Path.of("compose.prod.yaml"));

        assertThat(compose)
                .contains("SPRING_PROFILES_ACTIVE: server")
                .contains("MYSQL_PASSWORD: ${SHOP_DB_PASSWORD:")
                .contains("REDIS_PASSWORD: ${SHOP_REDIS_PASSWORD:")
                .contains("SHOP_SECRET_ENCRYPTION_KEY_RING: ${SHOP_SECRET_ENCRYPTION_KEY_RING:")
                .contains("gateway: 172.23.0.1")
                .contains("shop-server-logs:/var/log/shop-server")
                .contains("shop-server-log-init:")
                .contains("install -d --owner=10001 --group=10001 --mode=0750 /var/log/shop-server")
                .contains("condition: service_completed_successfully")
                .contains("network_mode: none")
                .contains("driver: local")
                .contains("compress: \"true\"")
                .doesNotContain("env_file:")
                .doesNotContain(".env.prod.local")
                .doesNotContain(".env.infrastructure.local");
    }

    @Test
    void deploymentInitializesTheLogVolumeBeforeStartingTheApplication() throws IOException {
        String deploy = Files.readString(Path.of("../../deploy.sh"));

        assertThat(deploy)
                .contains("compose run --rm --no-deps shop-server-log-init")
                .contains("compose up -d --no-deps --force-recreate shop-server")
                .contains("compose up -d --no-deps --wait --wait-timeout 300 shop-server");
    }

    @Test
    void logbackKeepsConsoleOutputAndDailyCompressedArchives() throws IOException {
        String logback = Files.readString(Path.of("src/main/resources/logback-spring.xml"));

        assertThat(logback)
                .contains("<appender name=\"CONSOLE\"")
                .contains("<appender name=\"ROLLING_FILE\"")
                .contains("shop-server.%d{yyyy-MM-dd}.%i.log.gz")
                .contains("<maxFileSize>100MB</maxFileSize>")
                .contains("<maxHistory>30</maxHistory>")
                .contains("<totalSizeCap>2GB</totalSizeCap>")
                .contains("requestId=%X{requestId:-}");
    }

    @Test
    void bootstrapAdminRequiresTheGenerationTwoMarkerAndFlywayHistory() throws IOException {
        String bootstrap = Files.readString(Path.of("scripts/config/bootstrap-admin.sh"));

        assertThat(bootstrap)
                .contains("marker_value = 'generation-2'")
                .contains("version = '7'")
                .contains("description = 'reference and bootstrap data'")
                .contains("WHERE @schema_generation_ready = 1 AND id = 1");
    }

    private Properties loadEnvironmentExample(String filename) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(filename))) {
            properties.load(reader);
        }
        return properties;
    }
}
