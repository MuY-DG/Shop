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
                          and parent_id = 830
                          and path = 'customer-service'
                          and component = '/customer-service/index'
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
                          'customer-service:agent:manage'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(8);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where r.code = 'R_CUSTOMER_SERVICE'
                          and p.auth_mark like 'customer-service:%'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(7);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu rm
                        join admin_role r on r.id = rm.role_id
                        where r.code = 'R_CUSTOMER_SERVICE'
                          and rm.menu_id in (830, 501, 821, 840)
                        """)
                .query(Integer.class)
                .single()).isEqualTo(4);

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
    }
}
