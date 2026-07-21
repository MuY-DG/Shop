package org.muybaby.shopserver.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AssetModelMigrationTest {

    private static final long LEGACY_FILE_ID = 917001L;

    @Test
    void populatedH2SchemaMigratesFromV16ToLatestWithAStorageCleanBreak() throws SQLException {
        String databaseName = "asset_model_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

        migrateToV16(jdbcUrl, "sa", "");
        seedLegacyStorageBindings(jdbcUrl, "sa", "");
        migrateToLatest(jdbcUrl, "sa", "");

        assertFinalAssetSchema(jdbcUrl, "sa", "");
        assertLegacyBindingsWereCleared(jdbcUrl, "sa", "");
    }

    public static void migrateToV16(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .target("16")
                .load()
                .migrate();
    }

    public static void migrateToLatest(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .placeholders(safeSeedPlaceholders())
                .load()
                .migrate();
    }

    public static void seedLegacyStorageBindings(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into storage_file
                        (id, purpose, asset_category_id, visibility, provider, bucket, object_key,
                         original_filename, content_type, extension, size_bytes, sha256, width, height,
                         alt_text, tags_json, public_url, status, uploaded_by_type, uploaded_by_id)
                    values
                        (917001, 'PRODUCT_IMAGE', 1, 'PUBLIC', 'LOCAL', '',
                         'public/product/legacy-storage.png', 'legacy-storage.png', 'image/png', 'png',
                         68, 'legacy-sha', 1, 1, '', null,
                         'https://legacy.test/storage.png', 'ACTIVE', 'ADMIN', 1)
                    """);
            statement.executeUpdate("""
                    insert into storage_file_usage
                        (id, file_id, usage_type, owner_type, owner_id, owner_label, snapshot_url,
                         sort_order, protected, status)
                    values
                        (917002, 917001, 'PRODUCT_SPU_MAIN', 'PRODUCT_SPU', 917011,
                         'Legacy product', 'https://legacy.test/storage.png', 1, true, 'ACTIVE')
                    """);
            statement.executeUpdate("""
                    insert into product_category
                        (id, parent_id, name, icon, icon_file_id, sort_order, status)
                    values
                        (917010, 0, 'Legacy Asset Category', 'https://legacy.test/category.png',
                         917001, 1, 'ENABLED')
                    """);
            statement.executeUpdate("""
                    insert into product_spu
                        (id, category_id, title, subtitle, main_image, main_image_file_id,
                         main_video, main_video_file_id, selling_points, detail_html, sort_order, status)
                    values
                        (917011, 917010, 'Legacy Asset Product', '',
                         'https://legacy.test/product.png', 917001,
                         'https://legacy.test/product.mp4', 917001,
                         '', '', 1, 'OFF_SALE')
                    """);
            statement.executeUpdate("""
                    insert into product_spu_image
                        (id, spu_id, url, file_id, sort_order)
                    values
                        (917012, 917011, 'https://legacy.test/gallery.png', 917001, 1)
                    """);
            statement.executeUpdate("""
                    insert into product_sku
                        (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                         stock_available, weight_gram, image, image_file_id, status, sort_order,
                         combination_key, is_default)
                    values
                        (917013, 917011, 'LEGACY-ASSET-SKU', '{}', 'Default', 100, 100,
                         1, 1, 'https://legacy.test/sku.png', 917001, 'ENABLED', 1,
                         'legacy-asset-sku', true)
                    """);
            statement.executeUpdate("""
                    insert into product_spu_spec_group
                        (id, spu_id, group_key, name, image_enabled, sort_order)
                    values
                        (917014, 917011, 'color', 'Color', true, 1)
                    """);
            statement.executeUpdate("""
                    insert into product_spu_spec_value
                        (id, group_id, value_key, value_name, image, image_file_id, sort_order)
                    values
                        (917015, 917014, 'red', 'Red', 'https://legacy.test/spec.png', 917001, 1)
                    """);
            statement.executeUpdate("""
                    insert into product_guarantee_service
                        (id, terms_name, content_description, icon, icon_file_id, sort_order, visible)
                    values
                        (917016, 'Legacy guarantee', '', 'https://legacy.test/guarantee.png',
                         917001, 1, true)
                    """);
            statement.executeUpdate("""
                    insert into home_banner
                        (id, title, subtitle, image_file_id, image_url, jump_type, status, sort_order)
                    values
                        (917017, 'Legacy banner', '', 917001,
                         'https://legacy.test/banner.png', 'NONE', 'DISABLED', 1)
                    """);
            statement.executeUpdate("""
                    insert into order_item
                        (id, order_id, sku_id, spu_id, product_title, product_subtitle,
                         main_image, main_image_file_id, sku_image, sku_image_file_id,
                         display_image, display_image_file_id, sku_code, spec_text,
                         original_price_cent, unit_price_cent, quantity,
                         line_original_amount_cent, line_amount_cent)
                    values
                        (917018, 917019, 917013, 917011, 'Legacy order item', '',
                         'https://legacy.test/order-main.png', 917001,
                         'https://legacy.test/order-sku.png', 917001,
                         'https://legacy.test/order-display.png', 917001,
                         'LEGACY-ASSET-SKU', 'Default', 100, 100, 1, 100, 100)
                    """);
            statement.executeUpdate("""
                    insert into after_sale_evidence
                        (id, after_sale_id, file_id, sort_order)
                    values
                        (917020, 917021, 917001, 1)
                    """);
            statement.executeUpdate("""
                    insert into payment_config
                        (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                         private_key_file_id, merchant_certificate_file_id, verify_mode,
                         wechat_public_key_id, wechat_public_key_file_id,
                         notify_url, refund_notify_url, enabled, status)
                    values
                        (917022, 'Legacy DB payment', 'wx-app', 'mch-id', 'serial', 'ciphertext',
                         917001, 917001, 'PUBLIC_KEY', 'public-key-id', 917001,
                         'https://legacy.test/pay-notify', 'https://legacy.test/refund-notify',
                         true, 'ACTIVE')
                    """);
            statement.executeUpdate("""
                    insert into payment_runtime_setting (id, config_source)
                    values (1, 'DB')
                    """);
        }
    }

    public static void assertFinalAssetSchema(String jdbcUrl, String username, String password) throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .placeholders(safeSeedPlaceholders())
                .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("49");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "storage_asset")).isTrue();
            assertThat(tableExists(statement, "storage_asset_folder")).isTrue();
            assertThat(tableExists(statement, "storage_asset_folder_guard")).isTrue();
            assertThat(tableExists(statement, "storage_asset_usage")).isTrue();
            assertThat(tableExists(statement, "storage_file")).isFalse();
            assertThat(tableExists(statement, "storage_asset_category")).isFalse();
            assertThat(tableExists(statement, "storage_file_usage")).isFalse();
            assertThat(columnNames(statement, "payment_order"))
                    .contains("payment_config_fingerprint", "notification_route_token");
            assertThat(columnNames(statement, "refund_order"))
                    .contains("notification_route_token");
            assertThat(columnNames(statement, "payment_callback_log"))
                    .contains("route_mode", "route_digest");
            assertThat(columnNames(statement, "payment_config"))
                    .contains("secret_cipher_version", "secret_key_id", "secret_revision",
                            "secret_reencrypted_at");
            assertThat(columnNames(statement, "payment_config_snapshot"))
                    .contains("secret_cipher_version", "secret_key_id", "secret_revision",
                            "secret_reencrypted_at");
            assertThat(columnNames(statement, "storage_runtime_setting"))
                    .contains("secret_cipher_version", "secret_key_id", "secret_revision",
                            "secret_reencrypted_at");

            assertThat(columnNames(statement, "storage_asset")).containsExactly(
                    "id", "scope", "media_kind", "folder_id", "visibility", "provider",
                    "storage_container", "storage_region",
                    "object_key", "original_filename", "content_type", "extension", "size_bytes", "sha256",
                    "width", "height", "duration_seconds", "alt_text", "tags_json", "public_url", "status",
                    "uploaded_by_type", "uploaded_by_id", "upload_context_type", "upload_context_id", "expires_at",
                    "cleanup_attempts", "cleanup_next_retry_at", "cleanup_lease_token",
                    "deleted_at", "created_at", "updated_at"
            ).doesNotContain("purpose", "asset_category_id");
            assertThat(columnNames(statement, "storage_asset_folder")).containsExactly(
                    "id", "parent_id", "parent_key", "name", "sort_order", "status", "created_at", "updated_at"
            );
            assertThat(columnNames(statement, "storage_asset_usage")).containsExactly(
                    "id", "asset_id", "usage_type", "owner_type", "owner_id", "owner_label", "snapshot_url",
                    "sort_order", "protected", "status", "created_at", "updated_at"
            );

            assertThat(constraintExists(statement,
                    "storage_asset", "uk_storage_asset_object_key", "UNIQUE")).isTrue();
            assertThat(constraintExists(statement,
                    "storage_asset_folder", "uk_storage_asset_folder_parent_name", "UNIQUE")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset", "idx_storage_asset_scope_kind_status_created")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset", "idx_storage_asset_folder_status_created")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset", "idx_storage_asset_upload_context")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset", "idx_storage_asset_expiry")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset_folder", "idx_storage_asset_folder_parent_status_sort")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset_usage", "idx_storage_asset_usage_asset_status")).isTrue();
            assertThat(indexExists(connection,
                    "storage_asset_usage", "idx_storage_asset_usage_owner_status")).isTrue();
            assertThat(indexExists(connection,
                    "payment_order", "uk_payment_order_notification_route")).isTrue();
            assertThat(indexExists(connection,
                    "refund_order", "uk_refund_order_notification_route")).isTrue();
            assertThat(indexExists(connection,
                    "payment_config", "idx_payment_config_secret_key")).isTrue();
            assertThat(indexExists(connection,
                    "payment_config_snapshot", "idx_payment_config_snapshot_secret_key")).isTrue();

            assertThat(singleLong(statement, "select count(*) from storage_asset")).isZero();
            assertThat(singleLong(statement, "select count(*) from storage_asset_folder")).isZero();
            assertThat(singleLong(statement, "select count(*) from storage_asset_usage")).isZero();

            assertThat(singleLong(statement, """
                    select count(*)
                    from admin_permission
                    where (id = 7001 and auth_mark = 'asset:upload')
                       or (id = 7002 and auth_mark = 'asset:read')
                       or (id = 7003 and auth_mark = 'asset:delete')
                       or (id = 7004 and auth_mark = 'asset:folder')
                    """)).isEqualTo(4);
            assertThat(singleString(statement, "select title from admin_menu where id = 600"))
                    .isEqualTo("素材库");
        }
    }

    private static Map<String, String> safeSeedPlaceholders() {
        return Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash", "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
    }

    public static void assertLegacyBindingsWereCleared(
            String jdbcUrl,
            String username,
            String password
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleLong(statement,
                    "select count(*) from home_banner where image_file_id is not null")).isZero();
            assertThat(singleLong(statement, """
                    select count(*) from order_item
                    where main_image_file_id is not null
                       or sku_image_file_id is not null
                       or display_image_file_id is not null
                    """)).isZero();
            assertThat(singleLong(statement,
                    "select count(*) from product_category where icon_file_id is not null")).isZero();
            assertThat(singleLong(statement, """
                    select count(*) from product_spu
                    where main_image_file_id is not null or main_video_file_id is not null
                    """)).isZero();
            assertThat(singleLong(statement,
                    "select count(*) from product_spu_image where file_id is not null")).isZero();
            assertThat(singleLong(statement,
                    "select count(*) from product_sku where image_file_id is not null")).isZero();
            assertThat(singleLong(statement,
                    "select count(*) from product_spu_spec_value where image_file_id is not null")).isZero();
            assertThat(singleLong(statement,
                    "select count(*) from product_guarantee_service where icon_file_id is not null")).isZero();
            assertThat(singleLong(statement, "select count(*) from after_sale_evidence")).isZero();
            assertThat(singleLong(statement, """
                    select count(*) from payment_config
                    where private_key_file_id is not null
                       or merchant_certificate_file_id is not null
                       or wechat_public_key_file_id is not null
                       or enabled = true
                    """)).isZero();
            assertThat(singleString(statement,
                    "select config_source from payment_runtime_setting where id = 1")).isEqualTo("AUTO");

            assertThat(singleString(statement,
                    "select icon from product_category where id = 917010"))
                    .isEqualTo("https://legacy.test/category.png");
            assertThat(singleString(statement,
                    "select main_image from product_spu where id = 917011"))
                    .isEqualTo("https://legacy.test/product.png");
            assertThat(singleString(statement,
                    "select main_video from product_spu where id = 917011"))
                    .isEqualTo("https://legacy.test/product.mp4");
            assertThat(singleString(statement,
                    "select url from product_spu_image where id = 917012"))
                    .isEqualTo("https://legacy.test/gallery.png");
            assertThat(singleString(statement,
                    "select image from product_sku where id = 917013"))
                    .isEqualTo("https://legacy.test/sku.png");
            assertThat(singleString(statement,
                    "select image_url from home_banner where id = 917017"))
                    .isEqualTo("https://legacy.test/banner.png");
            assertThat(singleString(statement,
                    "select display_image from order_item where id = 917018"))
                    .isEqualTo("https://legacy.test/order-display.png");
        }
    }

    private static boolean tableExists(Statement statement, String tableName) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("""
                select count(*)
                from information_schema.tables
                where lower(table_name) = '%s'
                """.formatted(tableName.toLowerCase()))) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1) > 0;
        }
    }

    private static List<String> columnNames(Statement statement, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery("""
                select lower(column_name)
                from information_schema.columns
                where lower(table_name) = '%s'
                order by ordinal_position
                """.formatted(tableName.toLowerCase()))) {
            while (resultSet.next()) {
                columns.add(resultSet.getString(1));
            }
        }
        return columns;
    }

    private static boolean constraintExists(
            Statement statement,
            String tableName,
            String constraintName,
            String constraintType
    ) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("""
                select count(*)
                from information_schema.table_constraints
                where lower(table_name) = '%s'
                  and lower(constraint_name) = '%s'
                  and upper(constraint_type) = '%s'
                """.formatted(
                        tableName.toLowerCase(),
                        constraintName.toLowerCase(),
                        constraintType.toUpperCase()
                ))) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1) > 0;
        }
    }

    private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String schema : new String[]{connection.getSchema(), null}) {
            for (String candidateTable : new String[]{tableName, tableName.toUpperCase()}) {
                try (ResultSet resultSet = metadata.getIndexInfo(
                        connection.getCatalog(), schema, candidateTable, false, false)) {
                    while (resultSet.next()) {
                        String actualIndexName = resultSet.getString("INDEX_NAME");
                        if (indexName.equalsIgnoreCase(actualIndexName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            long value = resultSet.getLong(1);
            assertThat(resultSet.next()).isFalse();
            return value;
        }
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            String value = resultSet.getString(1);
            assertThat(resultSet.next()).isFalse();
            return value;
        }
    }
}
