package org.muybaby.shopserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlSafetyTest {

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
                .containsEntry("springdoc.api-docs.enabled", "${SHOP_OPENAPI_ENABLED:true}");
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
}
