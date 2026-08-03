package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationDetailResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.MessageResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.SendMessageRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.TransferRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonQuestionRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.OfflineAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.SmartAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.SmartReplyRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.WelcomeAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.service.CustomerServiceReplyService;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.realtime.RealtimeConnectionPrincipal;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerServiceAutoReplyRuntimeTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CustomerServiceService customerServiceService;

    @Autowired
    private CustomerServiceReplyService replyService;

    @Autowired
    private RealtimeSessionHub realtimeSessionHub;

    @Test
    void openingCommonSmartAndOfflineRepliesFollowPriorityAndThrottleRules() {
        disableAutomaticAssignment();
        var commonConfig = replyService.updateCommon(1L, new CommonAutoReplyUpdateRequest(
                replyService.autoReplies(1L).revision(),
                "您好，欢迎来到客服会话",
                List.of(new CommonQuestionRequest(
                        null,
                        "什么时候发货",
                        "常见问题：付款后 48 小时内发货",
                        true,
                        0
                ))
        ));
        var smartConfig = replyService.updateSmart(1L, new SmartAutoReplyUpdateRequest(
                commonConfig.revision(),
                List.of(
                        new SmartReplyRequest(
                                null,
                                "物流问题",
                                List.of("什么时候发货", "查询物流"),
                                "智能回复：请在订单详情查看物流",
                                true,
                                0
                        ),
                        new SmartReplyRequest(
                                null,
                                "第一组",
                                List.of(),
                                "",
                                false,
                                1
                        )
                )
        ));
        replyService.updateOffline(
                1L,
                new OfflineAutoReplyUpdateRequest(
                        smartConfig.revision(),
                        "客服当前全部离线，请稍后再试"
                )
        );

        long appUserId = insertAppUser("auto-reply-runtime");
        AuthenticatedPrincipal appPrincipal = appPrincipal(appUserId);

        ConversationDetailResponse firstOpen = customerServiceService.openForApp(
                appPrincipal, "GENERAL", null, null);
        customerServiceService.openForApp(appPrincipal, "GENERAL", null, null);
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "您好，欢迎来到客服会话"))
                .isEqualTo(1);

        customerServiceService.sendFromApp(
                appPrincipal,
                new SendMessageRequest("什么时候发货？！！", "auto-reply-common")
        );
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "常见问题：付款后 48 小时内发货"))
                .isEqualTo(1);
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "智能回复：请在订单详情查看物流"))
                .isZero();
        assertThat(customerServiceService.currentForApp(appPrincipal).messages())
                .extracting(MessageResponse::content)
                .contains(
                        "您好，欢迎来到客服会话",
                        "什么时候发货？！！",
                        "常见问题：付款后 48 小时内发货"
                );

        customerServiceService.sendFromApp(
                appPrincipal,
                new SendMessageRequest("麻烦查询物流，谢谢", "auto-reply-smart")
        );
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "智能回复：请在订单详情查看物流"))
                .isEqualTo(1);

        customerServiceService.sendFromApp(
                appPrincipal,
                new SendMessageRequest("这是未匹配的问题", "auto-reply-offline-first")
        );
        customerServiceService.sendFromApp(
                appPrincipal,
                new SendMessageRequest("这是另一个未匹配问题", "auto-reply-offline-second")
        );
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "客服当前全部离线，请稍后再试"))
                .isEqualTo(1);

        jdbcClient.sql("""
                        update customer_service_offline_reply_state
                        set last_replied_at = :lastRepliedAt
                        where app_user_id = :appUserId
                        """)
                .param("lastRepliedAt", LocalDateTime.now().minusHours(2))
                .param("appUserId", appUserId)
                .update();
        customerServiceService.sendFromApp(
                appPrincipal,
                new SendMessageRequest("超过一小时后的问题", "auto-reply-offline-third")
        );
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "客服当前全部离线，请稍后再试"))
                .isEqualTo(2);

        insertAgent("manual-online-no-socket-agent");
        jdbcClient.sql("""
                        update customer_service_offline_reply_state
                        set last_replied_at = :lastRepliedAt
                        where app_user_id = :appUserId
                        """)
                .param("lastRepliedAt", LocalDateTime.now().minusHours(2))
                .param("appUserId", appUserId)
                .update();
        customerServiceService.sendFromApp(
                appPrincipal,
                new SendMessageRequest("客服手动在线后不应回复离线文案", "manual-online-no-offline-reply")
        );
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 1, "客服当前全部离线，请稍后再试"))
                .isEqualTo(2);

        ConversationDetailResponse detail = customerServiceService.currentForApp(appPrincipal);
        assertThat(detail.messages())
                .filteredOn(message -> "BOT".equals(message.senderType()))
                .allSatisfy(message -> {
                    assertThat(message.messageType()).isEqualTo("AUTO_REPLY");
                    assertThat(message.senderId()).isNull();
                    assertThat(message.senderName()).isEqualTo("商城客服");
                });

        jdbcClient.sql("""
                        update customer_service_conversation
                        set status = 'CLOSED',
                            assigned_admin_user_id = null,
                            closed_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :conversationId
                        """)
                .param("conversationId", firstOpen.conversationId())
                .update();
        ConversationDetailResponse reopened = customerServiceService.openForApp(
                appPrincipal, "GENERAL", null, null);
        assertThat(reopened.consultationNo()).isEqualTo(2);
        assertThat(automationMessageCount(
                firstOpen.conversationId(), 2, "您好，欢迎来到客服会话"))
                .isEqualTo(1);
    }

    @Test
    void manualClaimSendsOnlyAssignedAgentsWelcomeMessage() {
        disableAutomaticAssignment();
        long adminUserId = insertAgent("manual-welcome-agent");
        replyService.updateWelcome(
                adminUserId,
                new WelcomeAutoReplyUpdateRequest("您好，我是手动接入的专属客服")
        );
        AuthenticatedPrincipal adminPrincipal = adminPrincipal(adminUserId);
        WebSocketSession session = registerOnlineAdmin(adminPrincipal);

        try {
            long appUserId = insertAppUser("manual-welcome-app");
            AuthenticatedPrincipal appPrincipal = appPrincipal(appUserId);
            ConversationDetailResponse opened = customerServiceService.openForApp(
                    appPrincipal, "GENERAL", null, null);
            customerServiceService.sendFromApp(
                    appPrincipal,
                    new SendMessageRequest("需要人工客服", "manual-welcome-message")
            );

            ConversationDetailResponse claimed = customerServiceService.claim(
                    adminPrincipal, opened.conversationId());
            List<MessageResponse> welcomeMessages = claimed.messages().stream()
                    .filter(message -> "ADMIN".equals(message.senderType()))
                    .filter(message -> "您好，我是手动接入的专属客服".equals(message.content()))
                    .toList();
            assertThat(welcomeMessages).hasSize(1);
            assertThat(welcomeMessages.getFirst().messageType()).isEqualTo("AUTO_REPLY");
            assertThat(welcomeMessages.getFirst().senderId()).isEqualTo(adminUserId);
            assertThat(welcomeMessages.getFirst().senderName()).isEqualTo("手动接入客服");
            assertThat(claimed.assignedAdminDisplayName()).isEqualTo("手动接入客服");

            jdbcClient.sql("""
                            update customer_service_agent_profile
                            set service_name_override = ''
                            where admin_user_id = :adminUserId
                            """)
                    .param("adminUserId", adminUserId)
                    .update();
            ConversationDetailResponse defaultNamed = customerServiceService.currentForApp(appPrincipal);
            assertThat(defaultNamed.assignedAdminDisplayName()).isEqualTo("商城客服");
            assertThat(defaultNamed.messages()).filteredOn(message ->
                            "您好，我是手动接入的专属客服".equals(message.content()))
                    .singleElement()
                    .satisfies(message -> assertThat(message.senderName()).isEqualTo("商城客服"));

            customerServiceService.claim(adminPrincipal, opened.conversationId());
            assertThat(automationMessageCount(
                    opened.conversationId(), 1, "您好，我是手动接入的专属客服"))
                    .isEqualTo(1);
        } finally {
            realtimeSessionHub.unregister(session);
        }
    }

    @Test
    void appProductCardsTriggerOnlyOfflineReplyAndRespectTheSameThrottle() {
        disableAutomaticAssignment();
        var commonConfig = replyService.updateCommon(1L, new CommonAutoReplyUpdateRequest(
                replyService.autoReplies(1L).revision(),
                "",
                List.of(new CommonQuestionRequest(
                        null,
                        "常见问题商品",
                        "不应由商品卡片触发常见问题回复",
                        true,
                        0
                ))
        ));
        var smartConfig = replyService.updateSmart(1L, new SmartAutoReplyUpdateRequest(
                commonConfig.revision(),
                List.of(new SmartReplyRequest(
                        null,
                        "商品咨询",
                        List.of("智能问题商品"),
                        "不应由商品卡片触发智能回复",
                        true,
                        0
                ))
        ));
        replyService.updateOffline(1L, new OfflineAutoReplyUpdateRequest(
                smartConfig.revision(),
                "商品卡片触发的离线回复"
        ));

        long appUserId = insertAppUser("non-text-offline-app");
        AuthenticatedPrincipal appPrincipal = appPrincipal(appUserId);
        ConversationDetailResponse opened = customerServiceService.openForApp(
                appPrincipal, "GENERAL", null, null);
        long commonProductId = insertProduct("常见问题商品", 3990);
        long smartProductId = insertProduct("智能问题商品", 4990);

        customerServiceService.linkProductFromApp(appPrincipal, commonProductId);
        assertThat(automationMessageCount(
                opened.conversationId(), 1, "商品卡片触发的离线回复"))
                .isEqualTo(1);
        assertThat(automationMessageCount(
                opened.conversationId(), 1, "不应由商品卡片触发常见问题回复"))
                .isZero();

        jdbcClient.sql("""
                        update customer_service_offline_reply_state
                        set last_replied_at = :lastRepliedAt
                        where app_user_id = :appUserId
                        """)
                .param("lastRepliedAt", LocalDateTime.now().minusHours(2))
                .param("appUserId", appUserId)
                .update();
        customerServiceService.linkProductFromApp(appPrincipal, smartProductId);
        assertThat(automationMessageCount(
                opened.conversationId(), 1, "商品卡片触发的离线回复"))
                .isEqualTo(2);
        assertThat(automationMessageCount(
                opened.conversationId(), 1, "不应由商品卡片触发智能回复"))
                .isZero();
    }

    @Test
    void returningToPreviouslyAssignedAgentSendsWelcomeForEachAssignmentEvent() {
        disableAutomaticAssignment();
        long firstAdminUserId = insertAgent("welcome-transfer-first");
        long secondAdminUserId = insertAgent("welcome-transfer-second");
        replyService.updateWelcome(
                firstAdminUserId,
                new WelcomeAutoReplyUpdateRequest("您好，我是第一位客服")
        );
        replyService.updateWelcome(
                secondAdminUserId,
                new WelcomeAutoReplyUpdateRequest("您好，我是第二位客服")
        );
        AuthenticatedPrincipal firstPrincipal = managerPrincipal(firstAdminUserId);
        AuthenticatedPrincipal secondPrincipal = managerPrincipal(secondAdminUserId);
        WebSocketSession firstSession = registerOnlineAdmin(firstPrincipal);
        WebSocketSession secondSession = registerOnlineAdmin(secondPrincipal);

        try {
            long appUserId = insertAppUser("welcome-transfer-app");
            AuthenticatedPrincipal appPrincipal = appPrincipal(appUserId);
            ConversationDetailResponse opened = customerServiceService.openForApp(
                    appPrincipal, "GENERAL", null, null);
            customerServiceService.sendFromApp(
                    appPrincipal,
                    new SendMessageRequest("需要转接客服", "welcome-transfer-message")
            );

            customerServiceService.claim(firstPrincipal, opened.conversationId());
            long firstAssignmentMessageId = latestSystemMessageId(opened.conversationId());
            assertThat(replyService.welcomeMessage(
                    opened.conversationId(),
                    1,
                    firstAdminUserId,
                    firstAssignmentMessageId
            )).isNull();
            customerServiceService.forceTransfer(
                    firstPrincipal,
                    opened.conversationId(),
                    new TransferRequest(secondAdminUserId, "EXPERTISE", "转给第二位客服")
            );
            customerServiceService.forceTransfer(
                    secondPrincipal,
                    opened.conversationId(),
                    new TransferRequest(firstAdminUserId, "EXPERTISE", "转回第一位客服")
            );

            assertThat(automationMessageCount(
                    opened.conversationId(), 1, "您好，我是第一位客服"))
                    .isEqualTo(2);
            assertThat(automationMessageCount(
                    opened.conversationId(), 1, "您好，我是第二位客服"))
                    .isEqualTo(1);
        } finally {
            realtimeSessionHub.unregister(secondSession);
            realtimeSessionHub.unregister(firstSession);
        }
    }

    private void disableAutomaticAssignment() {
        jdbcClient.sql("""
                        update customer_service_config
                        set auto_assign_enabled = false
                        where id = 1
                        """)
                .update();
    }

    private int automationMessageCount(long conversationId, int consultationNo, String content) {
        return jdbcClient.sql("""
                        select count(*)
                        from customer_service_message
                        where conversation_id = :conversationId
                          and consultation_no = :consultationNo
                          and message_type = 'AUTO_REPLY'
                          and content = :content
                        """)
                .param("conversationId", conversationId)
                .param("consultationNo", consultationNo)
                .param("content", content)
                .query(Integer.class)
                .single();
    }

    private long latestSystemMessageId(long conversationId) {
        return jdbcClient.sql("""
                        select id
                        from customer_service_message
                        where conversation_id = :conversationId
                          and sender_type = 'SYSTEM'
                        order by id desc
                        limit 1
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
    }

    private long insertAppUser(String fixtureName) {
        long appUserId = jdbcClient.sql("select coalesce(max(id), 0) + 1 from app_user")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, nickname, status, created_at, updated_at)
                        values
                            (:appUserId, :openid, :nickname, 'ENABLED',
                             current_timestamp, current_timestamp)
                        """)
                .param("appUserId", appUserId)
                .param("openid", fixtureName + "-" + UUID.randomUUID())
                .param("nickname", fixtureName)
                .update();
        return appUserId;
    }

    private long insertAgent(String username) {
        jdbcClient.sql("""
                        insert into admin_user
                            (username, password_hash, display_name, email, avatar,
                             status, created_at, updated_at)
                        values
                            (:username, 'unused', :displayName, :email, '',
                             'ENABLED', current_timestamp, current_timestamp)
                        """)
                .param("username", username)
                .param("displayName", "手动接入客服")
                .param("email", username + "@shop.test")
                .update();
        long adminUserId = jdbcClient.sql(
                        "select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        select :adminUserId, id
                        from admin_role
                        where code = 'R_CUSTOMER_SERVICE'
                        """)
                .param("adminUserId", adminUserId)
                .update();
        jdbcClient.sql("""
                        insert into customer_service_agent_profile
                            (admin_user_id, service_name_override)
                        values (:adminUserId, '手动接入客服')
                        """)
                .param("adminUserId", adminUserId)
                .update();
        jdbcClient.sql("""
                        insert into customer_service_agent_state
                            (admin_user_id, work_status, max_active_conversations, updated_at)
                        values (:adminUserId, 'AVAILABLE', null, current_timestamp)
                        """)
                .param("adminUserId", adminUserId)
                .update();
        return adminUserId;
    }

    private long insertProduct(String title, long priceCent) {
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points,
                             detail_html, status, created_at, updated_at)
                        values
                            (1, :title, '', '/product.png', '', '',
                             'ON_SALE', current_timestamp, current_timestamp)
                        """)
                .param("title", title)
                .update();
        long productId = jdbcClient.sql(
                        "select id from product_spu where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent,
                             original_price_cent, stock_available, status,
                             created_at, updated_at)
                        values
                            (:productId, :skuCode, '{}', '', :priceCent,
                             :priceCent, 10, 'ENABLED', current_timestamp, current_timestamp)
                        """)
                .param("productId", productId)
                .param("skuCode", "AUTO-REPLY-" + productId)
                .param("priceCent", priceCent)
                .update();
        return productId;
    }

    private WebSocketSession registerOnlineAdmin(AuthenticatedPrincipal principal) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("manual-welcome-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                principal.subjectId(),
                principal.subjectName(),
                principal.permissions()
        ));
        return session;
    }

    private AuthenticatedPrincipal appPrincipal(long appUserId) {
        return new AuthenticatedPrincipal(
                TokenKind.APP,
                appUserId,
                "app-user-" + appUserId,
                List.of(),
                List.of()
        );
    }

    private AuthenticatedPrincipal adminPrincipal(long adminUserId) {
        return new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "manual-welcome-agent",
                List.of("R_CUSTOMER_SERVICE"),
                List.of("customer-service:conversation:read")
        );
    }

    private AuthenticatedPrincipal managerPrincipal(long adminUserId) {
        return new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "welcome-transfer-manager",
                List.of("R_CUSTOMER_SERVICE"),
                List.of(
                        "customer-service:conversation:read",
                        "customer-service:agent:manage"
                )
        );
    }
}
