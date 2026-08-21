package org.muybaby.shopserver.accountcancellation;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminAccountCancellationControllerTest {

    private static final long USER_ID = 9_823_456_789_012_345L;
    private static final long CANCELLATION_ID = 9_823_456_789_012_346L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void readPermissionListsCompletedCancellationWithoutRestorableIdentity() throws Exception {
        seedCancellation();

        mockMvc.perform(get("/admin/compliance/account-cancellations")
                        .header("Authorization", "Bearer " + tokenWith("compliance:document:read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(get("/admin/compliance/account-cancellations")
                        .header("Authorization", "Bearer " + tokenWith("compliance:cancellation:read"))
                        .param("userId", Long.toString(USER_ID))
                        .param("miniProgramEnv", "release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(CANCELLATION_ID)))
                .andExpect(jsonPath("$.data.records[0].userId").value(Long.toString(USER_ID)))
                .andExpect(jsonPath("$.data.records[0].noticeVersion").value("1.0"))
                .andExpect(jsonPath("$.data.records[0].miniProgramEnv").value("release"))
                .andExpect(jsonPath("$.data.records[0].deletedDataCategories[0]").value("昵称与头像"))
                .andExpect(jsonPath("$.data.records[0].retainedDataCategories[0]").value("已完成订单"))
                .andExpect(jsonPath("$.data.records[0].openid").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].phoneNumber").doesNotExist());
    }

    private void seedCancellation() {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user (
                            id, openid, nickname, status, auth_version, cancelled_at,
                            created_at, updated_at
                        ) values (
                            :id, :openid, '', 'CANCELLED', 1, :now, :now, :now
                        )
                        """)
                .param("id", USER_ID)
                .param("openid", "cancelled-admin-list-test")
                .param("now", now)
                .update();
        Notice notice = jdbcClient.sql("""
                        select id, version, content_sha256
                        from legal_document_revision
                        where current_publication_key = 'ACCOUNT_CANCELLATION_NOTICE'
                        """)
                .query((rs, rowNum) -> new Notice(
                        rs.getLong("id"),
                        rs.getString("version"),
                        rs.getString("content_sha256")
                ))
                .single();
        jdbcClient.sql("""
                        insert into app_user_account_cancellation (
                            id, user_id, legal_document_revision_id,
                            notice_version, notice_content_sha256,
                            channel, mini_program_env, identity_verified_at,
                            deleted_data_categories, retained_data_categories,
                            completed_at, created_at
                        ) values (
                            :id, :userId, :noticeId,
                            :noticeVersion, :noticeSha,
                            'WECHAT_MINIPROGRAM', 'release', :now,
                            '昵称与头像,手机号', '已完成订单,支付与退款',
                            :now, :now
                        )
                        """)
                .param("id", CANCELLATION_ID)
                .param("userId", USER_ID)
                .param("noticeId", notice.id())
                .param("noticeVersion", notice.version())
                .param("noticeSha", notice.contentSha256())
                .param("now", now)
                .update();
    }

    private String tokenWith(String... permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of(permissions)
        );
    }

    private record Notice(Long id, String version, String contentSha256) {
    }
}
