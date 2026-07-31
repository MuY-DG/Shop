package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CustomerServiceSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void customerServiceMigrationsCreateRichContextSchemaMenuAndPermissions() {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_name in (
                          'customer_service_conversation',
                          'customer_service_message',
                          'customer_service_assignment_log',
                          'customer_service_conversation_order'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(4);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_name = 'customer_service_consultation_resource'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where parent_id = 840
                          and id in (841, 842)
                          and full_page = true
                          and enabled = true
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu role_menu
                        join admin_role role_item on role_item.id = role_menu.role_id
                        where role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                          and role_menu.menu_id in (850, 851, 852)
                        """)
                .query(Integer.class)
                .single()).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                          and role_permission.permission_id = 16008
                        """)
                .query(Integer.class)
                .single()).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_name in (
                          'customer_service_agent_state',
                          'customer_service_transfer_request'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_name in (
                          'customer_service_config',
                          'customer_service_agent_profile'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role
                        where code = 'R_CUSTOMER_SERVICE_MANAGER'
                          and enabled = true
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where index_name in (
                          'uk_customer_service_transfer_pending',
                          'idx_customer_service_transfer_target'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'customer_service_conversation'
                          and column_name in (
                            'consultation_no', 'context_type', 'context_id', 'activated_at'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(4);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'customer_service_message'
                          and column_name in ('consultation_no', 'resource_id')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where index_name in (
                          'uk_customer_service_conversation_user',
                          'idx_customer_service_conversation_queue',
                          'idx_customer_service_message_conversation',
                          'uk_customer_service_message_client',
                          'idx_customer_service_assignment_conversation',
                          'uk_customer_service_conversation_order'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(6);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role
                        where code = 'R_CUSTOMER_SERVICE' and enabled = true
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 840
                          and parent_id is null
                          and path = '/customer-service'
                          and component = '/customer-service/index'
                          and full_page = true
                          and enabled = true
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                          'customer-service:conversation:read',
                          'customer-service:conversation:claim',
                          'customer-service:conversation:transfer',
                          'customer-service:conversation:close',
                          'customer-service:message:send',
                          'customer-service:order:link',
                          'customer-service:product:send',
                          'customer-service:agent:manage',
                          'customer-service:settings:update'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(9);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where r.code = 'R_CUSTOMER_SERVICE'
                          and p.auth_mark like 'customer-service:%'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(8);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu rm
                        join admin_role r on r.id = rm.role_id
                        where r.code = 'R_CUSTOMER_SERVICE'
                          and rm.menu_id in (830, 501, 821, 840, 842)
                        """)
                .query(Integer.class)
                .single()).isEqualTo(5);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where r.code = 'R_CUSTOMER_SERVICE'
                          and p.auth_mark in ('order:read', 'aftersale:read')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where r.code = 'R_CUSTOMER_SERVICE'
                          and p.auth_mark in (
                            'order:close', 'order:ship', 'order:shipping:retry', 'aftersale:audit'
                          )
                        """)
                .query(Integer.class)
                .single()).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where p.auth_mark = 'customer-service:agent:manage'
                          and r.code = 'R_SUPER'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where p.auth_mark = 'customer-service:agent:manage'
                          and r.code = 'R_CUSTOMER_SERVICE'
                        """)
                .query(Integer.class)
                .single()).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'customer_service_agent_profile'
                          and column_name in (
                            'auto_accept_enabled', 'auto_accept_below',
                            'auto_accept_count', 'bound_at'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(4);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'customer_service_agent_state'
                          and column_name = 'max_active_conversations'
                          and is_nullable = 'YES'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from customer_service_agent_state
                        where max_active_conversations is not null
                        """)
                .query(Integer.class)
                .single()).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'customer_service_config'
                          and (
                            column_name = 'avatar_file_id'
                            or (column_name = 'avatar' and character_maximum_length >= 500)
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from customer_service_config
                        where id = 1 and auto_assign_enabled = true
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where index_name = 'idx_customer_service_conversation_agent_active'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu role_menu
                        join admin_role role_item on role_item.id = role_menu.role_id
                        join admin_menu menu_item on menu_item.id = role_menu.menu_id
                        where role_item.code = 'R_GUEST'
                          and menu_item.path = '/guest'
                          and menu_item.component = '/guest/index'
                          and menu_item.full_page = true
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        join admin_permission permission_item
                          on permission_item.id = role_permission.permission_id
                        where permission_item.auth_mark = 'customer-service:settings:update'
                          and role_item.code in ('R_SUPER', 'R_CUSTOMER_SERVICE')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_permission.permission_id = 16011
                          and role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_permission.permission_id = 16011
                          and role_item.code <> 'R_CUSTOMER_SERVICE_MANAGER'
                        """)
                .query(Integer.class)
                .single()).isZero();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        join admin_permission permission_item
                          on permission_item.id = role_permission.permission_id
                        where role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                          and permission_item.auth_mark in ('asset:upload', 'asset:read')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }
}
