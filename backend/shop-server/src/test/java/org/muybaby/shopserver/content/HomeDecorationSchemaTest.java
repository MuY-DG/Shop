package org.muybaby.shopserver.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class HomeDecorationSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationCreatesDecorationTablesMenusPermissionsAndContactSeed() {
        assertThat(tableExists("home_category_item")).isTrue();
        assertThat(tableExists("home_product_item")).isTrue();
        assertThat(tableExists("home_product_fill_guard")).isTrue();
        assertThat(tableExists("app_contact_setting")).isTrue();
        assertThat(tableExists("product_parameter_definition")).isTrue();
        assertThat(tableExists("product_parameter_option")).isTrue();
        assertThat(tableExists("product_category_parameter")).isTrue();
        assertThat(tableExists("product_spu_parameter_value")).isTrue();

        assertThat(columnNames("home_category_item")).containsExactly(
                "id", "category_id", "image_file_id", "image_url", "sort_order", "status",
                "created_at", "updated_at"
        );
        assertThat(columnNames("home_product_item")).containsExactly(
                "id", "section_type", "spu_id", "image_file_id", "image_url", "sort_order", "status",
                "created_at", "updated_at", "badge_mode", "custom_badge_text"
        );
        assertThat(columnNames("app_contact_setting")).containsExactly(
                "id", "phone_number", "updated_at"
        );

        assertThat(jdbcClient.sql("select count(*) from app_contact_setting where id = 1")
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select section_type from home_product_fill_guard order by section_type")
                .query(String.class)
                .list()).containsExactly("HOT", "RECOMMENDED");

        Integer parentCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 620
                          and parent_id is null
                          and name = 'Decoration'
                          and path = '/decoration'
                          and component = '/index/index'
                          and title = '装修管理'
                        """)
                .query(Integer.class)
                .single();
        Integer childCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where parent_id = 620
                          and id in (600, 610, 624)
                          and enabled = true
                        """)
                .query(Integer.class)
                .single();
        Integer consolidatedMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 610
                          and parent_id = 620
                          and name = 'HomeDecoration'
                          and path = 'home'
                          and component = '/content/home-decoration'
                          and title = '首页装修'
                          and visible = true
                          and enabled = true
                        """)
                .query(Integer.class)
                .single();
        Integer legacyVisibleCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id in (621, 622, 623)
                          and (visible = true or enabled = true)
                        """)
                .query(Integer.class)
                .single();
        Integer decorationPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission
                        where menu_id = 610
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'content:home-category:read', 'content:home-category:write',
                            'content:home-hot:read', 'content:home-hot:write',
                            'content:home-recommended:read', 'content:home-recommended:write',
                            'content:contact:read', 'content:contact:write'
                        )
                        """)
                .query(Integer.class)
                .single();
        Integer superRoleMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu
                        where role_id = 1
                          and menu_id in (610, 620, 624)
                        """)
                .query(Integer.class)
                .single();
        Integer productParameterMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 305
                          and parent_id = 300
                          and path = 'parameter'
                          and component = '/product/parameter'
                          and title = '商品参数'
                          and visible = true
                          and enabled = true
                        """)
                .query(Integer.class)
                .single();
        Integer productParameterPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in ('product:parameter:read', 'product:parameter:write')
                        """)
                .query(Integer.class)
                .single();

        assertThat(parentCount).isEqualTo(1);
        assertThat(childCount).isEqualTo(3);
        assertThat(consolidatedMenuCount).isEqualTo(1);
        assertThat(legacyVisibleCount).isZero();
        assertThat(decorationPermissionCount).isEqualTo(10);
        assertThat(permissionCount).isEqualTo(8);
        assertThat(superRoleMenuCount).isEqualTo(3);
        assertThat(productParameterMenuCount).isEqualTo(1);
        assertThat(productParameterPermissionCount).isEqualTo(2);
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
}
