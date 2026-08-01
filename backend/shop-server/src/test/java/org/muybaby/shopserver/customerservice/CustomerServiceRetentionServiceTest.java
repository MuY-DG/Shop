package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.customerservice.service.CustomerServiceRetentionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest
@ActiveProfiles("test")
class CustomerServiceRetentionServiceTest {

    private static final long ACTIVE_APP_USER_ID = 9_810_001L;
    private static final long CLOSED_APP_USER_ID = 9_810_002L;
    private static final String ASSET_KEY = "retention/customer-service-image.webp";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CustomerServiceRetentionService retentionService;

    @BeforeEach
    @AfterEach
    void clearFixtures() {
        List<Long> conversationIds = jdbcClient.sql("""
                        select id from customer_service_conversation
                        where app_user_id in (:appUserIds)
                        """)
                .param("appUserIds", List.of(ACTIVE_APP_USER_ID, CLOSED_APP_USER_ID))
                .query(Long.class)
                .list();
        if (!conversationIds.isEmpty()) {
            jdbcClient.sql("delete from customer_service_message where conversation_id in (:ids)")
                    .param("ids", conversationIds)
                    .update();
            jdbcClient.sql("delete from customer_service_conversation where id in (:ids)")
                    .param("ids", conversationIds)
                    .update();
        }
        jdbcClient.sql("delete from storage_asset where object_key = :objectKey")
                .param("objectKey", ASSET_KEY)
                .update();
    }

    @Test
    void deletesOnlyExpiredFinishedConsultationsAndExpiresTheirImages() {
        long activeConversationId = insertConversation(ACTIVE_APP_USER_ID, "ACTIVE", 2, null);
        long closedConversationId = insertConversation(
                CLOSED_APP_USER_ID,
                "CLOSED",
                1,
                LocalDateTime.of(1999, 1, 10, 0, 0)
        );
        long assetId = insertImageAsset();

        insertMessage(activeConversationId, 1, "TEXT", null, "expired previous", date(1999, 1));
        insertMessage(activeConversationId, 2, "TEXT", null, "old active current", date(1999, 2));
        insertMessage(activeConversationId, 1, "TEXT", null, "recent previous", date(2001, 1));
        insertMessage(closedConversationId, 1, "IMAGE", assetId, "expired image", date(1999, 3));
        insertMessage(closedConversationId, 1, "TEXT", null, "cutoff boundary", date(2000, 2));

        assertThat(retentionService.deleteBatchBefore(date(2000, 2), 10)).isEqualTo(2);
        assertThat(messageContents()).containsExactly(
                "old active current",
                "cutoff boundary",
                "recent previous"
        );
        assertThat(jdbcClient.sql("select expires_at from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(LocalDateTime.class)
                .single()).isNotNull();
    }

    @Test
    void rejectsInvalidBatches() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteBatchBefore(null, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteBatchBefore(LocalDateTime.now(), 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteBatchBefore(LocalDateTime.now(), 10_001));
    }

    private long insertConversation(
            long appUserId,
            String status,
            int consultationNo,
            LocalDateTime closedAt
    ) {
        jdbcClient.sql("""
                        insert into customer_service_conversation (
                            app_user_id, status, consultation_no, context_type,
                            closed_at, created_at, updated_at
                        ) values (
                            :appUserId, :status, :consultationNo, 'GENERAL',
                            :closedAt, current_timestamp, current_timestamp
                        )
                        """)
                .param("appUserId", appUserId)
                .param("status", status)
                .param("consultationNo", consultationNo)
                .param("closedAt", closedAt)
                .update();
        return jdbcClient.sql("""
                        select id from customer_service_conversation
                        where app_user_id = :appUserId
                        """)
                .param("appUserId", appUserId)
                .query(Long.class)
                .single();
    }

    private long insertImageAsset() {
        jdbcClient.sql("""
                        insert into storage_asset (
                            scope, media_kind, visibility, provider, object_key,
                            original_filename, content_type, extension, size_bytes,
                            status, uploaded_by_type, uploaded_by_id,
                            upload_context_type, upload_context_id
                        ) values (
                            'ATTACHMENT', 'IMAGE', 'PRIVATE', 'TENCENT_COS', :objectKey,
                            'image.webp', 'image/webp', 'webp', 100,
                            'ACTIVE', 'APP', :appUserId,
                            'CUSTOMER_SERVICE_CONVERSATION', 1
                        )
                        """)
                .param("objectKey", ASSET_KEY)
                .param("appUserId", CLOSED_APP_USER_ID)
                .update();
        return jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", ASSET_KEY)
                .query(Long.class)
                .single();
    }

    private void insertMessage(
            long conversationId,
            int consultationNo,
            String messageType,
            Long resourceId,
            String content,
            LocalDateTime createdAt
    ) {
        jdbcClient.sql("""
                        insert into customer_service_message (
                            conversation_id, consultation_no, sender_type, sender_id,
                            message_type, content, resource_id, created_at
                        ) values (
                            :conversationId, :consultationNo, 'APP_USER', :appUserId,
                            :messageType, :content, :resourceId, :createdAt
                        )
                        """)
                .param("conversationId", conversationId)
                .param("consultationNo", consultationNo)
                .param("appUserId", ACTIVE_APP_USER_ID)
                .param("messageType", messageType)
                .param("content", content)
                .param("resourceId", resourceId)
                .param("createdAt", createdAt)
                .update();
    }

    private List<String> messageContents() {
        return jdbcClient.sql("""
                        select message.content
                        from customer_service_message message
                        join customer_service_conversation conversation
                          on conversation.id = message.conversation_id
                        where conversation.app_user_id in (:appUserIds)
                        order by message.created_at asc, message.id asc
                        """)
                .param("appUserIds", List.of(ACTIVE_APP_USER_ID, CLOSED_APP_USER_ID))
                .query(String.class)
                .list();
    }

    private LocalDateTime date(int year, int month) {
        return LocalDateTime.of(year, month, 1, 0, 0);
    }
}
