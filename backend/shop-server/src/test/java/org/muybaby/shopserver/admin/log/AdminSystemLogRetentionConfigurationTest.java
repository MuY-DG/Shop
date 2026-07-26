package org.muybaby.shopserver.admin.log;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSystemLogRetentionConfigurationTest {

    @Test
    void usesSafeDefaultsAndBoundsInvalidOrExcessiveValues() {
        AdminSystemLogRetentionProperties defaults =
                new AdminSystemLogRetentionProperties(null, null, null, null);
        AdminSystemLogRetentionProperties invalid =
                new AdminSystemLogRetentionProperties(null, 0, 0, 0);
        AdminSystemLogRetentionProperties configured =
                new AdminSystemLogRetentionProperties(false, 30, 1_200, 12);
        AdminSystemLogRetentionProperties bounded =
                new AdminSystemLogRetentionProperties(null, 3_651, 50_001, 1_001);

        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.effectiveDays()).isEqualTo(400);
        assertThat(defaults.effectiveBatchSize()).isEqualTo(5_000);
        assertThat(defaults.effectiveMaxBatchesPerRun()).isEqualTo(100);

        assertThat(invalid.effectiveDays()).isEqualTo(400);
        assertThat(invalid.effectiveBatchSize()).isEqualTo(5_000);
        assertThat(invalid.effectiveMaxBatchesPerRun()).isEqualTo(100);

        assertThat(configured.isEnabled()).isFalse();
        assertThat(configured.effectiveDays()).isEqualTo(30);
        assertThat(configured.effectiveBatchSize()).isEqualTo(1_200);
        assertThat(configured.effectiveMaxBatchesPerRun()).isEqualTo(12);

        assertThat(bounded.effectiveDays()).isEqualTo(3_650);
        assertThat(bounded.effectiveBatchSize()).isEqualTo(50_000);
        assertThat(bounded.effectiveMaxBatchesPerRun()).isEqualTo(1_000);
    }

    @Test
    void applicationYamlExposesDailyOffPeakRetentionSettings() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .containsEntry(
                        "shop.admin-system-log.retention.enabled",
                        "${SHOP_ADMIN_SYSTEM_LOG_RETENTION_ENABLED:true}"
                )
                .containsEntry(
                        "shop.admin-system-log.retention.days",
                        "${SHOP_ADMIN_SYSTEM_LOG_RETENTION_DAYS:400}"
                )
                .containsEntry(
                        "shop.admin-system-log.retention.batch-size",
                        "${SHOP_ADMIN_SYSTEM_LOG_RETENTION_BATCH_SIZE:5000}"
                )
                .containsEntry(
                        "shop.admin-system-log.retention.max-batches-per-run",
                        "${SHOP_ADMIN_SYSTEM_LOG_RETENTION_MAX_BATCHES:100}"
                )
                .containsEntry(
                        "shop.admin-system-log.retention.cron",
                        "${SHOP_ADMIN_SYSTEM_LOG_RETENTION_CRON:0 45 3 * * *}"
                );
    }
}
