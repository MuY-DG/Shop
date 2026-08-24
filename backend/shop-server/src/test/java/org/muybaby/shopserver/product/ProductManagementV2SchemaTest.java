package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProductManagementV2SchemaTest {

    private static final List<String> V13_TABLES = List.of(
            "freight_template",
            "product_guarantee_service",
            "product_sku_spec_value",
            "product_spec_template",
            "product_spec_template_group",
            "product_spec_template_value",
            "product_spu_coupon",
            "product_spu_guarantee_service",
            "product_spu_spec_group",
            "product_spu_spec_value"
    );

    private static final List<String> V13_PERMISSIONS = List.of(
            "product:coupon:bind",
            "product:coupon:create",
            "product:freight:create",
            "product:freight:update",
            "product:guarantee:create",
            "product:guarantee:delete",
            "product:guarantee:update",
            "product:guarantee:visibility",
            "product:spec-template:create",
            "product:spec-template:update",
            "product:spu:delete"
    );

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void v13CreatesEveryProductManagementTableAndColumn() {
        List<String> actualTables = jdbcClient.sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_name in (
                            'freight_template',
                            'product_guarantee_service',
                            'product_sku_spec_value',
                            'product_spec_template',
                            'product_spec_template_group',
                            'product_spec_template_value',
                            'product_spu_coupon',
                            'product_spu_guarantee_service',
                            'product_spu_spec_group',
                            'product_spu_spec_value'
                          )
                        order by table_name
                        """)
                .query(String.class)
                .list();
        assertThat(actualTables).containsExactlyElementsOf(V13_TABLES);

        assertColumns("freight_template",
                "id", "name", "charge_mode", "fixed_amount_cent", "status", "sort_order",
                "deleted_at", "created_at", "updated_at");
        assertColumns("product_spec_template", "id", "name", "created_at", "updated_at");
        assertColumns("product_spec_template_group",
                "id", "template_id", "group_key", "name", "image_enabled", "sort_order");
        assertColumns("product_spec_template_value",
                "id", "group_id", "value_key", "value_name", "sort_order");
        assertColumns("product_spu_spec_group",
                "id", "spu_id", "group_key", "name", "image_enabled", "sort_order", "deleted_at",
                "created_at", "updated_at");
        assertColumns("product_spu_spec_value",
                "id", "group_id", "value_key", "value_name", "image", "image_file_id", "sort_order",
                "deleted_at", "created_at", "updated_at");
        assertColumns("product_sku_spec_value", "sku_id", "spec_value_id", "created_at");
        assertColumns("product_guarantee_service",
                "id", "terms_name", "content_description", "icon", "icon_file_id", "sort_order", "visible",
                "deleted_at", "created_at", "updated_at");
        assertColumns("product_spu_guarantee_service", "spu_id", "service_id", "sort_order", "created_at");
        assertColumns("product_spu_coupon", "spu_id", "coupon_template_id", "created_at");
        assertThat(tableExists("product_spu_tag")).isFalse();
    }

    @Test
    void v13ExtendsSpuAndSkuWithCompatibleDefaultsAndTypes() {
        assertThat(columns("product_spu")).contains(
                "spec_type", "main_video", "main_video_file_id", "freight_template_id",
                "virtual_sales", "deleted_at", "purged_at", "display_badge_text", "display_badge_tone");
        assertThat(columns("product_sku")).contains(
                "cost_price_cent", "volume_cubic_meter", "is_default", "combination_key", "deleted_at");

        assertThat(columnValue("product_spu", "spec_type", "column_default")).isEqualTo("'SINGLE'");
        assertThat(columnValue("product_spu", "freight_template_id", "is_nullable")).isEqualTo("NO");
        assertThat(columnValue("product_spu", "virtual_sales", "column_default")).isEqualTo("'0'");
        assertThat(columnValue("product_sku", "weight_gram", "is_nullable")).isEqualTo("YES");
        assertThat(columnValue("product_sku", "combination_key", "is_nullable")).isEqualTo("NO");
        assertThat(columnValue("product_sku", "volume_cubic_meter", "numeric_scale")).isEqualTo("6");
    }

    @Test
    void v14AddsRecycleBinIndexPermissionsAndSuperMappings() {
        assertThat(jdbcClient.sql("""
                        select lower(index_name)
                        from information_schema.indexes
                        where table_schema = 'public'
                          and lower(index_name) = 'idx_product_spu_recycle_bin'
                        """)
                .query(String.class)
                .single()).isEqualTo("idx_product_spu_recycle_bin");

        assertThat(jdbcClient.sql("""
                        select auth_mark
                        from admin_permission
                        where auth_mark in ('product:spu:restore', 'product:spu:purge')
                        order by auth_mark
                        """)
                .query(String.class)
                .list()).containsExactly("product:spu:purge", "product:spu:restore");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission arp
                        join admin_permission ap on ap.id = arp.permission_id
                        where arp.role_id = 1
                          and ap.auth_mark in ('product:spu:restore', 'product:spu:purge')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission amp
                        join admin_permission ap on ap.id = amp.permission_id
                        where amp.menu_id = 302
                          and ap.auth_mark in ('product:spu:restore', 'product:spu:purge')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void v13CreatesRequiredLookupAndUniquenessIndexes() {
        List<String> expectedIndexes = List.of(
                "idx_freight_template_active_sort",
                "idx_order_item_spu",
                "idx_product_guarantee_active_sort",
                "idx_product_sku_spu_deleted_status_sort",
                "idx_product_sku_spec_value_value",
                "idx_product_spec_template_group_template_sort",
                "idx_product_spec_template_value_group_sort",
                "idx_product_spu_active_status_sort",
                "idx_product_spu_coupon_template",
                "idx_product_spu_freight_template",
                "idx_product_spu_guarantee_service_service",
                "idx_product_spu_spec_group_spu_deleted_sort",
                "idx_product_spu_spec_value_group_deleted_sort",
                "uk_product_sku_spu_combination",
                "uk_product_spec_template_group_key",
                "uk_product_spec_template_name",
                "uk_product_spec_template_value_key",
                "uk_product_spu_spec_group_key",
                "uk_product_spu_spec_value_key"
        );

        List<String> actualIndexes = jdbcClient.sql("""
                        select lower(index_name) as object_name
                        from information_schema.indexes
                        where table_schema = 'public'
                        union
                        select lower(constraint_name) as object_name
                        from information_schema.table_constraints
                        where table_schema = 'public'
                          and constraint_type = 'UNIQUE'
                        """)
                .query(String.class)
                .list();

        assertThat(actualIndexes).containsAll(expectedIndexes);
    }

    @Test
    void v13SeedsDefaultFreightNavigationPermissionsAndSuperMappings() {
        assertThat(jdbcClient.sql("""
                        select concat(name, '|', charge_mode, '|', fixed_amount_cent, '|', status)
                        from freight_template
                        where id = 1
                        """)
                .query(String.class)
                .single()).isEqualTo("全国包邮|FREE|0|ENABLED");

        assertThat(jdbcClient.sql("select title from admin_menu where id = 302")
                .query(String.class)
                .single()).isEqualTo("商品管理");
        assertThat(jdbcClient.sql("""
                        select concat(id, '|', name, '|', path, '|', component, '|', title)
                        from admin_menu
                        where id in (303, 304)
                        order by id
                        """)
                .query(String.class)
                .list()).containsExactly(
                "303|ProductSpecTemplate|spec-template|/product/spec-template|商品规格",
                "304|ProductGuaranteeService|guarantee-service|/product/guarantee-service|保障服务"
        );

        List<String> actualPermissions = jdbcClient.sql("""
                        select auth_mark
                        from admin_permission
                        where auth_mark in (
                          'product:coupon:bind', 'product:coupon:create',
                          'product:freight:create', 'product:freight:update',
                          'product:guarantee:create', 'product:guarantee:delete',
                          'product:guarantee:update', 'product:guarantee:visibility',
                          'product:spec-template:create', 'product:spec-template:update',
                          'product:spu:delete'
                        )
                        order by auth_mark
                        """)
                .query(String.class)
                .list();
        assertThat(actualPermissions).containsExactlyElementsOf(V13_PERMISSIONS);

        Integer superPermissionMappings = jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission arp
                        join admin_permission ap on ap.id = arp.permission_id
                        where arp.role_id = 1
                          and ap.auth_mark in (
                            'product:coupon:bind', 'product:coupon:create',
                            'product:freight:create', 'product:freight:update',
                            'product:guarantee:create', 'product:guarantee:delete',
                            'product:guarantee:update', 'product:guarantee:visibility',
                            'product:spec-template:create', 'product:spec-template:update',
                            'product:spu:delete'
                          )
                        """)
                .query(Integer.class)
                .single();
        assertThat(superPermissionMappings).isEqualTo(V13_PERMISSIONS.size());
        assertThat(jdbcClient.sql("select count(*) from admin_role_menu where role_id = 1 and menu_id in (303, 304)")
                .query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission amp
                        join admin_permission ap on ap.id = amp.permission_id
                        where amp.menu_id in (302, 303, 304)
                          and ap.auth_mark in (
                            'product:coupon:bind', 'product:coupon:create',
                            'product:freight:create', 'product:freight:update',
                            'product:guarantee:create', 'product:guarantee:delete',
                            'product:guarantee:update', 'product:guarantee:visibility',
                            'product:spec-template:create', 'product:spec-template:update',
                            'product:spu:delete'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(V13_PERMISSIONS.size());
    }

    private void assertColumns(String tableName, String... expectedColumns) {
        assertThat(columns(tableName)).containsExactly(expectedColumns);
    }

    private List<String> columns(String tableName) {
        return jdbcClient.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = :tableName
                        order by ordinal_position
                        """)
                .param("tableName", tableName)
                .query(String.class)
                .list();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcClient.sql("""
                        select count(*) from information_schema.tables
                        where table_schema = 'public' and table_name = :tableName
                        """)
                .param("tableName", tableName)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }

    private String columnValue(String tableName, String columnName, String metadataColumn) {
        if (!List.of("column_default", "is_nullable", "numeric_scale").contains(metadataColumn)) {
            throw new IllegalArgumentException("Unsupported metadata column: " + metadataColumn);
        }
        return jdbcClient.sql("select " + metadataColumn + " from information_schema.columns "
                        + "where table_schema = 'public' and table_name = :tableName and column_name = :columnName")
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(String.class)
                .single();
    }
}
