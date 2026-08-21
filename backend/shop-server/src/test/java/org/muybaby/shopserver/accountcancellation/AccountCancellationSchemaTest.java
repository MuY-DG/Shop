package org.muybaby.shopserver.accountcancellation;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.compliance.LegalDocumentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AccountCancellationSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationReplacesRightsApprovalWithImmutableCancellationRecord() {
        assertThat(tableCount("app_user_rights_request")).isZero();
        assertThat(tableCount("app_user_rights_request_audit")).isZero();
        assertThat(tableCount("app_user_account_cancellation")).isOne();

        Integer oldPermissions = jdbcClient.sql("""
                        select count(*) from admin_permission
                        where auth_mark in ('account-rights:read', 'account-rights:manage')
                        """)
                .query(Integer.class)
                .single();
        Integer oldMenu = jdbcClient.sql("select count(*) from admin_menu where id = 910")
                .query(Integer.class)
                .single();
        String notice = jdbcClient.sql("""
                        select concat(document_type, '|', status, '|', title)
                        from legal_document_revision
                        where current_publication_key = 'ACCOUNT_CANCELLATION_NOTICE'
                        """)
                .query(String.class)
                .single();

        assertThat(oldPermissions).isZero();
        assertThat(oldMenu).isZero();
        assertThat(notice).isEqualTo("ACCOUNT_CANCELLATION_NOTICE|PUBLISHED|账号注销须知");
        assertThat(LegalDocumentType.values()).contains(
                LegalDocumentType.ACCOUNT_CANCELLATION_NOTICE);
    }

    private int tableCount(String tableName) {
        return jdbcClient.sql("""
                        select count(*) from information_schema.tables
                        where lower(table_name) = :tableName
                        """)
                .param("tableName", tableName)
                .query(Integer.class)
                .single();
    }
}
