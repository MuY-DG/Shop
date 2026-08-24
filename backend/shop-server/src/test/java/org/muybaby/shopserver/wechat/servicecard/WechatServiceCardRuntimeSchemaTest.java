package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WechatServiceCardRuntimeSchemaTest {

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void migrationSeedsFailClosedRuntimeAndEnforcesSingletonInvariant() {
        assertThat(jdbcClient.sql(
                        "select count(*) from wechat_service_card_runtime_setting")
                .query(Long.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("""
                        select count(*) from wechat_service_card_runtime_setting
                        where id = 1 and capture_enabled = false and worker_enabled = false
                          and revision = 1 and change_reason = 'INITIAL_FAIL_CLOSED'
                        """).query(Long.class).single()).isOne();
        assertThat(jdbcClient.sql(
                        "select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isZero();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into wechat_service_card_runtime_setting
                            (id, capture_enabled, worker_enabled, revision, change_reason)
                        values (2, false, false, 1, 'invalid singleton')
                        """).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update wechat_service_card_runtime_setting
                        set capture_enabled = false, worker_enabled = true
                        where id = 1
                        """).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void migrationAddsDedicatedPermissionsAndConfigurationMenu() {
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_permission
                        where (id = 22001 and auth_mark = 'wechat-service-card:read')
                           or (id = 22002 and auth_mark = 'wechat-service-card:runtime:write')
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_menu
                        where id = 806 and parent_id = 800
                          and path = 'wechat-service-card'
                          and component = '/configuration/wechat-service-card'
                          and title = '微信服务动态'
                          and enabled = true
                        """).query(Integer.class).single()).isOne();
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_menu_permission
                        where menu_id = 806 and permission_id in (22001, 22002)
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_item.code = 'R_SUPER'
                          and role_permission.permission_id in (22001, 22002)
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission order_grant
                        join admin_permission order_permission
                          on order_permission.id = order_grant.permission_id
                         and order_permission.auth_mark = 'order:read'
                        where not exists (
                            select 1 from admin_role_permission service_card_grant
                            where service_card_grant.role_id = order_grant.role_id
                              and service_card_grant.permission_id = 22001
                        )
                        """).query(Integer.class).single()).isZero();
    }
}
