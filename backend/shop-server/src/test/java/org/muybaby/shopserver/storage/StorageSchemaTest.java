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
        assertThat(tableExists("storage_file")).isTrue();
        assertThat(tableExists("storage_asset_category")).isTrue();
        assertThat(tableExists("storage_file_usage")).isTrue();
        assertThat(tableExists("home_banner")).isTrue();

        assertThat(columnExists("product_category", "icon_file_id")).isTrue();
        assertThat(columnExists("product_spu", "main_image_file_id")).isTrue();
        assertThat(columnExists("product_spu_image", "file_id")).isTrue();
        assertThat(columnExists("product_sku", "image_file_id")).isTrue();
        assertThat(columnExists("order_item", "main_image_file_id")).isTrue();
        assertThat(columnExists("order_item", "sku_image_file_id")).isTrue();
        assertThat(columnExists("order_item", "display_image_file_id")).isTrue();

        List<String> categoryNames = jdbcClient.sql("""
                        select name
                        from storage_asset_category
                        order by sort_order, id
                        """)
                .query(String.class)
                .list();
        assertThat(categoryNames).containsExactly(
                "商品图片",
                "首页轮播",
                "分类图标",
                "小程序图标",
                "富文本图片",
                "运营活动",
                "售后凭证",
                "支付证书",
                "通用素材"
        );

        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'file:upload', 'file:read', 'file:delete', 'file:category',
                            'content:banner:read', 'content:banner:create', 'content:banner:update', 'content:banner:publish'
                        )
                        """)
                .query(Integer.class)
                .single();
        Integer routeCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where path in ('/storage/files', '/content/banner')
                        """)
                .query(Integer.class)
                .single();

        assertThat(permissionCount).isEqualTo(8);
        assertThat(routeCount).isEqualTo(2);
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
}
