package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StorageSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void storageMigrationCreatesTablesColumnsAndSeeds() {
        assertThat(tableExists("storage_asset")).isTrue();
        assertThat(tableExists("storage_asset_folder")).isTrue();
        assertThat(tableExists("storage_asset_folder_guard")).isTrue();
        assertThat(tableExists("storage_asset_usage")).isTrue();
        assertThat(tableExists("storage_file")).isFalse();
        assertThat(tableExists("storage_asset_category")).isFalse();
        assertThat(tableExists("storage_file_usage")).isFalse();
        assertThat(tableExists("home_banner")).isTrue();
        assertThat(columnIsAutoIncrement("storage_asset", "id")).isTrue();
        assertThat(columnIsAutoIncrement("storage_asset_folder", "id")).isTrue();
        assertThat(columnIsAutoIncrement("storage_asset_usage", "id")).isTrue();
        assertThat(columnNames("storage_runtime_setting"))
                .contains("cos_public_base_url", "cos_region", "cos_bucket")
                .doesNotContain("provider", "public_base_url", "local_public_base_url", "local_root");

        assertThat(columnNames("storage_asset")).contains(
                "id", "scope", "media_kind", "folder_id", "visibility", "provider",
                "storage_container", "storage_region",
                "object_key", "original_filename", "content_type", "extension", "size_bytes", "sha256",
                "width", "height", "duration_seconds", "alt_text", "tags_json", "public_url", "status",
                "uploaded_by_type", "uploaded_by_id", "upload_context_type", "upload_context_id", "expires_at",
                "cleanup_attempts", "cleanup_next_retry_at", "cleanup_lease_token",
                "deleted_at", "created_at", "updated_at"
        ).doesNotContain("purpose", "asset_category_id");
        assertThat(columnNames("storage_asset_folder")).containsExactly(
                "id", "parent_id", "parent_key", "name", "sort_order", "status", "created_at", "updated_at"
        );
        assertThat(jdbcClient.sql("select count(*) from storage_asset_folder_guard where id = 1")
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(columnNames("storage_asset_usage")).containsExactly(
                "id", "asset_id", "usage_type", "owner_type", "owner_id", "owner_label", "snapshot_url",
                "sort_order", "protected", "status", "created_at", "updated_at"
        );

        assertThat(constraintExists("storage_asset", "uk_storage_asset_object_key", "UNIQUE")).isTrue();
        assertThat(constraintExists("storage_asset", "chk_storage_asset_cos_only", "CHECK")).isTrue();
        assertThat(indexExists("storage_asset", "idx_storage_asset_scope_kind_status_created")).isTrue();
        assertThat(indexExists("storage_asset", "idx_storage_asset_folder_status_created")).isTrue();
        assertThat(indexExists("storage_asset", "idx_storage_asset_upload_context")).isTrue();
        assertThat(indexExists("storage_asset", "idx_storage_asset_expiry")).isTrue();
        assertThat(indexExists("storage_asset", "idx_storage_asset_cleanup_retry")).isTrue();
        assertThat(constraintExists("storage_asset_folder", "uk_storage_asset_folder_parent_name", "UNIQUE")).isTrue();
        assertThat(indexExists("storage_asset_folder", "idx_storage_asset_folder_parent_status_sort")).isTrue();
        assertThat(indexExists("storage_asset_usage", "idx_storage_asset_usage_asset_status")).isTrue();
        assertThat(indexExists("storage_asset_usage", "idx_storage_asset_usage_owner_status")).isTrue();

        assertThat(columnExists("product_category", "icon_file_id")).isTrue();
        assertThat(columnExists("product_spu", "main_image_file_id")).isTrue();
        assertThat(columnExists("product_spu_image", "file_id")).isTrue();
        assertThat(columnExists("product_sku", "image_file_id")).isTrue();
        assertThat(columnExists("order_item", "main_image_file_id")).isTrue();
        assertThat(columnExists("order_item", "sku_image_file_id")).isTrue();
        assertThat(columnExists("order_item", "display_image_file_id")).isTrue();

        Integer folderCount = jdbcClient.sql("select count(*) from storage_asset_folder")
                .query(Integer.class)
                .single();
        assertThat(folderCount).isZero();

        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'asset:upload', 'asset:read', 'asset:delete', 'asset:folder',
                            'content:banner:read', 'content:banner:create', 'content:banner:update', 'content:banner:publish'
                        )
                        """)
                .query(Integer.class)
                .single();
        Integer assetRouteCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where (id = 600 and parent_id = 620 and path = 'assets' and component = '/storage/files')
                        """)
                .query(Integer.class)
                .single();

        assertThat(permissionCount).isEqualTo(8);
        assertThat(assetRouteCount).isEqualTo(1);
        assertThat(jdbcClient.sql("select title from admin_menu where id = 600")
                .query(String.class)
                .single()).isEqualTo("素材库");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_name = :tableName
                        """)
                .param("tableName", tableName)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = :tableName
                          and column_name = :columnName
                        """)
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }

    private List<String> columnNames(String tableName) {
        return jdbcClient.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = :tableName
                        order by ordinal_position
                        """)
                .param("tableName", tableName)
                .query(String.class)
                .list();
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where table_name = :tableName
                          and index_name = :indexName
                        """)
                .param("tableName", tableName)
                .param("indexName", indexName)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private boolean constraintExists(String tableName, String constraintName, String constraintType) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from information_schema.table_constraints
                        where table_name = :tableName
                          and constraint_name = :constraintName
                          and constraint_type = :constraintType
                        """)
                .param("tableName", tableName)
                .param("constraintName", constraintName)
                .param("constraintType", constraintType)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private boolean columnIsAutoIncrement(String tableName, String columnName) {
        String isIdentity = jdbcClient.sql("""
                        select is_identity
                        from information_schema.columns
                        where table_name = :tableName
                          and column_name = :columnName
                        """)
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(String.class)
                .single();
        return "YES".equalsIgnoreCase(isIdentity) || "TRUE".equalsIgnoreCase(isIdentity);
    }
}
