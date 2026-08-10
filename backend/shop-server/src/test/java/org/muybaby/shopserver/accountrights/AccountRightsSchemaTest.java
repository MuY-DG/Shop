package org.muybaby.shopserver.accountrights;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AccountRightsSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationAddsVersionedRequestsAuditAndAppSessionVersion() {
        Integer appUserColumns = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'app_user'
                          and lower(column_name) in ('auth_version', 'cancelled_at')
                        """)
                .query(Integer.class)
                .single();
        Integer requestColumns = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'app_user_rights_request'
                          and lower(column_name) in (
                              'request_type', 'status', 'active_request_key',
                              'identity_verified_at', 'retention_explanation',
                              'retained_data_categories', 'version', 'completed_at'
                          )
                        """)
                .query(Integer.class)
                .single();
        Integer activeIndex = jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(table_name) = 'app_user_rights_request'
                          and lower(index_name) = 'uk_app_user_rights_active_request'
                        """)
                .query(Integer.class)
                .single();
        Integer permissions = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in ('account-rights:read', 'account-rights:manage')
                        """)
                .query(Integer.class)
                .single();
        String menuRoute = jdbcClient.sql("""
                        select concat(path, '|', component)
                        from admin_menu
                        where id = 910
                        """)
                .query(String.class)
                .single();

        assertThat(appUserColumns).isEqualTo(2);
        assertThat(requestColumns).isEqualTo(8);
        assertThat(activeIndex).isEqualTo(1);
        assertThat(permissions).isEqualTo(2);
        assertThat(menuRoute).isEqualTo("/account-rights|/account-rights/index");
        assertThat(AccountRightsRequestType.values()).containsExactly(
                AccountRightsRequestType.ACCOUNT_CANCELLATION,
                AccountRightsRequestType.PERSONAL_INFORMATION_DELETION,
                AccountRightsRequestType.ACCESS_COPY,
                AccountRightsRequestType.CORRECTION
        );
        assertThat(AccountRightsRequestStatus.values()).containsExactly(
                AccountRightsRequestStatus.PENDING,
                AccountRightsRequestStatus.IN_REVIEW,
                AccountRightsRequestStatus.APPROVED,
                AccountRightsRequestStatus.REJECTED,
                AccountRightsRequestStatus.WITHDRAWN,
                AccountRightsRequestStatus.COMPLETED
        );
    }

    @Test
    void oneUserCanHaveOnlyOneActiveRequestButKeepsTerminalHistory() {
        long userId = 9_001_001L;
        jdbcClient.sql("""
                        insert into app_user(id, openid, status)
                        values (:id, :openid, 'ENABLED')
                        """)
                .param("id", userId)
                .param("openid", "rights-schema-user")
                .update();
        insertRequest(9_001_011L, userId, "ACCOUNT_CANCELLATION", "PENDING", true);

        assertThatThrownBy(() -> insertRequest(
                9_001_012L, userId, "ACCESS_COPY", "PENDING", false))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("""
                        update app_user_rights_request
                        set status = 'WITHDRAWN', active_request_key = null
                        where id = 9001011
                        """)
                .update();
        insertRequest(9_001_012L, userId, "ACCESS_COPY", "PENDING", false);
        jdbcClient.sql("""
                        update app_user_rights_request
                        set status = 'COMPLETED', active_request_key = null
                        where id = 9001012
                        """)
                .update();
        insertRequest(9_001_013L, userId, "CORRECTION", "PENDING", false);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from app_user_rights_request
                        where user_id = :userId
                        """)
                .param("userId", userId)
                .query(Integer.class)
                .single()).isEqualTo(3);
    }

    private void insertRequest(
            long requestId,
            long userId,
            String requestType,
            String status,
            boolean identityVerified
    ) {
        jdbcClient.sql("""
                        insert into app_user_rights_request(
                            id, user_id, request_type, status, active_request_key,
                            identity_verified_at)
                        values(
                            :id, :userId, :requestType, :status, 1,
                            case when :identityVerified then current_timestamp else null end)
                        """)
                .param("id", requestId)
                .param("userId", userId)
                .param("requestType", requestType)
                .param("status", status)
                .param("identityVerified", identityVerified)
                .update();
    }
}
