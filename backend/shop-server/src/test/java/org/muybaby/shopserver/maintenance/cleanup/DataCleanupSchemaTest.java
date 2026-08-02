package org.muybaby.shopserver.maintenance.cleanup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DataCleanupSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationSeedsAllCleanupTasksWithSafeDefaults() {
        Long revision = jdbcClient.sql("select revision from data_cleanup_config where id = 1")
                .query(Long.class)
                .single();
        Map<DataCleanupTaskCode, SeededTask> tasks = new EnumMap<>(DataCleanupTaskCode.class);
        jdbcClient.sql("""
                        select task_code, enabled, retention_days, batch_size,
                               cron_expression, zone_id, batch_interval_seconds,
                               upload_pending_grace_minutes, retain_reviews, config_revision,
                               run_sequence, next_run_at, last_status,
                               last_processed_count
                        from data_cleanup_task_setting
                        """)
                .query((rs, rowNum) -> new SeededTask(
                        DataCleanupTaskCode.valueOf(rs.getString("task_code")),
                        rs.getBoolean("enabled"),
                        rs.getObject("retention_days", Integer.class),
                        rs.getInt("batch_size"),
                        rs.getString("cron_expression"),
                        rs.getString("zone_id"),
                        rs.getInt("batch_interval_seconds"),
                        rs.getObject("upload_pending_grace_minutes", Integer.class),
                        rs.getObject("retain_reviews", Boolean.class),
                        rs.getLong("config_revision"),
                        rs.getLong("run_sequence"),
                        rs.getObject("next_run_at", java.time.LocalDateTime.class),
                        rs.getString("last_status"),
                        rs.getInt("last_processed_count")
                ))
                .list()
                .forEach(task -> tasks.put(task.taskCode(), task));

        assertThat(revision).isZero();
        assertThat(tasks).hasSize(6);
        assertThat(tasks.get(DataCleanupTaskCode.ANALYTICS_EVENT))
                .isEqualTo(seed(DataCleanupTaskCode.ANALYTICS_EVENT, true, 400, 5_000,
                        "0 15 3 * * *", null));
        assertThat(tasks.get(DataCleanupTaskCode.ADMIN_SYSTEM_LOG))
                .isEqualTo(seed(DataCleanupTaskCode.ADMIN_SYSTEM_LOG, true, 400, 5_000,
                        "0 45 3 * * *", null));
        assertThat(tasks.get(DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE))
                .isEqualTo(seed(DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE, false, 365, 1_000,
                        "0 15 4 * * *", null));
        assertThat(tasks.get(DataCleanupTaskCode.ORDER_AGGREGATE))
                .isEqualTo(new SeededTask(
                        DataCleanupTaskCode.ORDER_AGGREGATE,
                        true,
                        1_095,
                        20,
                        "0 45 4 * * *",
                        "Asia/Shanghai",
                        300,
                        null,
                        true,
                        0L,
                        0L,
                        null,
                        "NEVER",
                        0
                ));
        assertThat(tasks.get(DataCleanupTaskCode.STORAGE_ASSET))
                .isEqualTo(seed(DataCleanupTaskCode.STORAGE_ASSET, true, null, 100,
                        "0 */10 * * * *", 30));
        assertThat(tasks.get(DataCleanupTaskCode.DIRECT_UPLOAD_SESSION))
                .isEqualTo(seed(DataCleanupTaskCode.DIRECT_UPLOAD_SESSION, true, 7, 100,
                        "0 */10 * * * *", null));
    }

    @Test
    void migrationAddsCleanupMenuPermissionsAndSupportingIndexes() {
        Integer menuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 805
                          and parent_id = 800
                          and name = 'DataCleanupConfig'
                          and path = 'data-cleanup'
                          and component = '/configuration/data-cleanup'
                          and enabled = true
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where (id = 18005 and auth_mark = 'data-cleanup:config:read')
                           or (id = 18006 and auth_mark = 'data-cleanup:config:write')
                        """)
                .query(Integer.class)
                .single();
        Integer menuPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission
                        where menu_id = 805
                          and permission_id in (18005, 18006)
                        """)
                .query(Integer.class)
                .single();
        Integer superRolePermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_item.code = 'R_SUPER'
                          and role_permission.permission_id in (18005, 18006)
                        """)
                .query(Integer.class)
                .single();
        Integer superRoleMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu role_menu
                        join admin_role role_item on role_item.id = role_menu.role_id
                        where role_item.code = 'R_SUPER'
                          and role_menu.menu_id = 805
                        """)
                .query(Integer.class)
                .single();
        Integer indexCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(index_name) in (
                            'idx_data_cleanup_task_due',
                            'idx_analytics_event_retention'
                        )
                        """)
                .query(Integer.class)
                .single();

        assertThat(menuCount).isOne();
        assertThat(permissionCount).isEqualTo(2);
        assertThat(menuPermissionCount).isEqualTo(2);
        assertThat(superRolePermissionCount).isEqualTo(2);
        assertThat(superRoleMenuCount).isOne();
        assertThat(indexCount).isEqualTo(2);
    }

    @Test
    void migrationAddsOrderArchiveTombstonesAndReviewSnapshots() {
        Integer archiveTableCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where lower(table_name) in (
                            'order_archive_manifest',
                            'purged_order_identity',
                            'purged_payment_identity',
                            'purged_refund_identity'
                        )
                        """)
                .query(Integer.class)
                .single();
        Integer archiveLocationColumnCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'order_archive_manifest'
                          and lower(column_name) in (
                              'provider',
                              'storage_container',
                              'storage_region'
                          )
                        """)
                .query(Integer.class)
                .single();
        Integer failureTableCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where lower(table_name) = 'order_cleanup_failure'
                        """)
                .query(Integer.class)
                .single();
        Integer reviewColumnCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'product_review'
                          and lower(column_name) in (
                              'source_order_item_id',
                              'product_title_snapshot',
                              'spec_text_snapshot',
                              'verified_purchase'
                          )
                        """)
                .query(Integer.class)
                .single();
        Integer callbackTombstoneColumnCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where (lower(table_name) = 'purged_payment_identity'
                               and lower(column_name) in ('amount_cent', 'currency'))
                           or (lower(table_name) = 'purged_refund_identity'
                               and lower(column_name) in (
                                   'final_callback_status', 'refund_amount_cent'
                               ))
                        """)
                .query(Integer.class)
                .single();
        Integer supportingIndexCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(index_name) in (
                            'idx_shop_order_cleanup_candidate',
                            'idx_stock_log_order',
                            'idx_refund_order_order',
                            'idx_payment_callback_trade',
                            'idx_payment_callback_refund',
                            'idx_order_cleanup_failure_retry',
                            'idx_customer_service_message_order_resource',
                            'idx_customer_service_conversation_context'
                        )
                        """)
                .query(Integer.class)
                .single();

        assertThat(archiveTableCount).isEqualTo(4);
        assertThat(archiveLocationColumnCount).isEqualTo(3);
        assertThat(failureTableCount).isOne();
        assertThat(reviewColumnCount).isEqualTo(4);
        assertThat(callbackTombstoneColumnCount).isEqualTo(4);
        assertThat(supportingIndexCount).isEqualTo(8);
    }

    private SeededTask seed(
            DataCleanupTaskCode taskCode,
            boolean enabled,
            Integer retentionDays,
            int batchSize,
            String cron,
            Integer uploadGrace
    ) {
        return new SeededTask(
                taskCode,
                enabled,
                retentionDays,
                batchSize,
                cron,
                "Asia/Shanghai",
                60,
                uploadGrace,
                null,
                0L,
                0L,
                null,
                "NEVER",
                0
        );
    }

    private record SeededTask(
            DataCleanupTaskCode taskCode,
            boolean enabled,
            Integer retentionDays,
            int batchSize,
            String cronExpression,
            String zoneId,
            int batchIntervalSeconds,
            Integer uploadPendingGraceMinutes,
            Boolean retainReviews,
            long configRevision,
            long runSequence,
            java.time.LocalDateTime nextRunAt,
            String lastStatus,
            int lastProcessedCount
    ) {
    }
}
