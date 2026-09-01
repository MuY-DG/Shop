package org.muybaby.shopserver.admin.log;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminSystemLogSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminSystemLogSchemaAndQueryIndexesExist() {
        List<String> columns = jdbcClient.sql("""
                        select lower(column_name)
                        from information_schema.columns
                        where lower(table_schema) = 'public'
                          and lower(table_name) = 'admin_system_log'
                        order by ordinal_position
                        """)
                .query(String.class)
                .list();

        assertThat(columns).containsExactly(
                "id",
                "log_type",
                "result",
                "level",
                "event_code",
                "summary",
                "target_type",
                "target_id",
                "operator_id",
                "operator_name",
                "module",
                "action",
                "request_method",
                "request_path",
                "route_pattern",
                "http_status",
                "duration_ms",
                "client_ip",
                "user_agent",
                "request_id",
                "error_code",
                "error_message",
                "occurred_at"
        );

        List<IndexColumn> indexColumns = jdbcClient.sql("""
                        select lower(index_name), lower(column_name)
                        from information_schema.index_columns
                        where lower(table_schema) = 'public'
                          and lower(table_name) = 'admin_system_log'
                          and lower(index_name) in (
                              'idx_admin_system_log_occurred_id',
                              'idx_admin_system_log_type_result_occurred',
                              'idx_admin_system_log_event_occurred',
                              'idx_admin_system_log_module_occurred',
                              'idx_admin_system_log_operator_occurred',
                              'idx_admin_system_log_client_ip_occurred',
                              'idx_admin_system_log_request_id',
                              'idx_admin_system_log_target'
                          )
                        order by lower(index_name), ordinal_position
                        """)
                .query((rs, rowNum) -> new IndexColumn(rs.getString(1), rs.getString(2)))
                .list();

        assertThat(indexColumns).containsExactly(
                new IndexColumn("idx_admin_system_log_client_ip_occurred", "client_ip"),
                new IndexColumn("idx_admin_system_log_client_ip_occurred", "occurred_at"),
                new IndexColumn("idx_admin_system_log_event_occurred", "event_code"),
                new IndexColumn("idx_admin_system_log_event_occurred", "occurred_at"),
                new IndexColumn("idx_admin_system_log_module_occurred", "module"),
                new IndexColumn("idx_admin_system_log_module_occurred", "occurred_at"),
                new IndexColumn("idx_admin_system_log_occurred_id", "occurred_at"),
                new IndexColumn("idx_admin_system_log_occurred_id", "id"),
                new IndexColumn("idx_admin_system_log_operator_occurred", "operator_id"),
                new IndexColumn("idx_admin_system_log_operator_occurred", "occurred_at"),
                new IndexColumn("idx_admin_system_log_request_id", "request_id"),
                new IndexColumn("idx_admin_system_log_target", "target_type"),
                new IndexColumn("idx_admin_system_log_target", "target_id"),
                new IndexColumn("idx_admin_system_log_target", "occurred_at"),
                new IndexColumn("idx_admin_system_log_type_result_occurred", "log_type"),
                new IndexColumn("idx_admin_system_log_type_result_occurred", "result"),
                new IndexColumn("idx_admin_system_log_type_result_occurred", "occurred_at")
        );
    }

    @Test
    void adminSystemLogSupportsAutoIncrementAndSafeDefaults() {
        jdbcClient.sql("""
                        insert into admin_system_log (
                            log_type, result, level, request_method, request_path,
                            http_status, duration_ms, client_ip, request_id
                        )
                        values (
                            'OPERATION', 'SUCCESS', 'INFO', 'GET', '/admin/system/logs',
                            200, 12, '127.0.0.1', 'admin-system-log-schema-test'
                        )
                        """)
                .update();

        Long rowCount = jdbcClient.sql("""
                        select count(*)
                        from admin_system_log
                        where request_id = 'admin-system-log-schema-test'
                          and id > 0
                          and operator_id is null
                          and operator_name = ''
                          and module = ''
                          and action = ''
                          and route_pattern = ''
                          and user_agent = ''
                          and error_code = ''
                          and error_message = ''
                          and occurred_at is not null
                        """)
                .query(Long.class)
                .single();

        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void auditLogMenusAndReadPermissionAreGrantedOnlyToSuperRole() {
        Integer rootMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 204
                          and parent_id is null
                          and name = 'AuditLog'
                          and path = '/audit-log'
                          and component = '/index/index'
                          and title = 'menus.auditLog.title'
                          and enabled = true
                          and visible = true
                        """)
                .query(Integer.class)
                .single();
        List<String> childNames = jdbcClient.sql("""
                        select name
                        from admin_menu
                        where parent_id = 204
                        order by sort_order
                        """)
                .query(String.class)
                .list();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where id = 1300
                          and auth_mark = 'system:log:read'
                        """)
                .query(Integer.class)
                .single();
        Integer menuPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission
                        where menu_id in (205,206,207,208,209)
                          and permission_id = 1300
                        """)
                .query(Integer.class)
                .single();
        List<String> menuRoleCodes = jdbcClient.sql("""
                        select r.code
                        from admin_role_menu rm
                        join admin_role r on r.id = rm.role_id
                        where rm.menu_id in (204,205,206,207,208,209)
                        order by rm.menu_id, r.code
                        """)
                .query(String.class)
                .list();
        List<String> permissionRoleCodes = jdbcClient.sql("""
                        select r.code
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        where rp.permission_id = 1300
                        order by r.code
                        """)
                .query(String.class)
                .list();

        assertThat(rootMenuCount).isEqualTo(1);
        assertThat(childNames).containsExactly(
                "AuditOperation",
                "AuditSecurity",
                "AuditException",
                "AuditRequest",
                "AuditTask"
        );
        assertThat(permissionCount).isEqualTo(1);
        assertThat(menuPermissionCount).isEqualTo(5);
        assertThat(menuRoleCodes).containsExactly(
                "R_SUPER", "R_SUPER", "R_SUPER", "R_SUPER", "R_SUPER", "R_SUPER"
        );
        assertThat(permissionRoleCodes).containsExactly("R_SUPER");
    }

    private record IndexColumn(String indexName, String columnName) {
    }
}
