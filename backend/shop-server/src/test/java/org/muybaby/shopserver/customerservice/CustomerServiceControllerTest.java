package org.muybaby.shopserver.customerservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.realtime.RealtimeConnectionPrincipal;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.socket.WebSocketSession;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CustomerServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RealtimeSessionHub realtimeSessionHub;

    @BeforeEach
    void clearCustomerServiceState() {
        jdbcClient.sql("delete from customer_service_transfer_request").update();
        jdbcClient.sql("delete from customer_service_agent_state").update();
        jdbcClient.sql("delete from customer_service_consultation_resource").update();
        jdbcClient.sql("delete from customer_service_conversation_order").update();
        jdbcClient.sql("delete from customer_service_assignment_log").update();
        jdbcClient.sql("delete from customer_service_message").update();
        jdbcClient.sql("delete from customer_service_conversation").update();
    }

    @Test
    void appCanOpenConversationSendIdempotentMessageAndLinkOnlyOwnedOrder() throws Exception {
        AppLogin app = appLogin("customer-service-app-user");
        AppLogin other = appLogin("customer-service-other-user");
        long ownedOrderId = insertOrder(app.userId(), "CS-OWNED-ORDER");
        long otherOrderId = insertOrder(other.userId(), "CS-OTHER-ORDER");

        String opened = mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d}
                                """.formatted(ownedOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.appUserId").value(String.valueOf(app.userId())))
                .andExpect(jsonPath("$.data.consultationNo").value(1))
                .andExpect(jsonPath("$.data.currentContext.type").value("ORDER"))
                .andExpect(jsonPath("$.data.currentContext.order.orderId").value(ownedOrderId))
                .andExpect(jsonPath("$.data.linkedOrders[0].orderId").value(ownedOrderId))
                .andExpect(jsonPath("$.data.messages.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(opened).path("data").path("conversationId").asLong();

        String request = """
                {"content":"请问什么时候发货？","clientMessageId":"app-message-1"}
                """;
        String first = mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.senderType").value("APP_USER"))
                .andExpect(jsonPath("$.data.content").value("请问什么时候发货？"))
                .andReturn().getResponse().getContentAsString();
        long messageId = objectMapper.readTree(first).path("data").path("messageId").asLong();

        mockMvc.perform(get("/app/customer-service/conversation")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].messageType").value("ORDER_CARD"))
                .andExpect(jsonPath("$.data.messages[1].messageId").value(messageId));

        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(messageId));

        assertThat(jdbcClient.sql("""
                        select count(*) from customer_service_message
                        where conversation_id = :conversationId and message_type = 'TEXT'
                        """)
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single()).isEqualTo(1);

        mockMvc.perform(get("/admin/customer-service/conversations")
                        .header("Authorization", bearer(adminLogin("Super", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/app/customer-service/conversation/orders/{orderId}", otherOrderId)
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900004));

        mockMvc.perform(get("/app/customer-service/conversation/order-candidates")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderId").value(ownedOrderId));
    }

    @Test
    void agentsCanClaimReplyTransferCloseAndUserMessageReopensConversation() throws Exception {
        AppLogin app = appLogin("customer-service-lifecycle-user");
        String superToken = adminLogin("Super", "123456");
        long targetAdminId = insertCustomerServiceAgent("AgentTwo", "客服二号", "agent-pass");
        String targetToken = adminLogin("AgentTwo", "agent-pass");
        prepareAdminForService(superToken, 1L);
        prepareAdminForService(targetToken, targetAdminId);

        String opened = mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(opened).path("data").path("conversationId").asLong();

        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"我需要人工帮助","clientMessageId":"app-lifecycle-first"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"这是等待期间的补充信息","clientMessageId":"app-lifecycle-second"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/customer-service/conversations")
                        .header("Authorization", bearer(superToken))
                        .param("status", "WAITING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$.data.records[0].lastMessagePreview").value("我需要人工帮助"));

        mockMvc.perform(get("/admin/customer-service/conversations/workspace")
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waitingTotal").value(1))
                .andExpect(jsonPath("$.data.activeTotal").value(0))
                .andExpect(jsonPath("$.data.waiting[0].lastMessagePreview").value("我需要人工帮助"));

        mockMvc.perform(get("/admin/customer-service/conversations/{conversationId}", conversationId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900001));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.assignedAdminUserId").value(1));

        mockMvc.perform(get("/admin/customer-service/conversations/workspace")
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waitingTotal").value(0))
                .andExpect(jsonPath("$.data.activeTotal").value(0));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"您好，今天可以发货。","clientMessageId":"admin-message-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.senderType").value("ADMIN"));

        String transferRequest = mockMvc.perform(post(
                                "/admin/customer-service/conversations/{conversationId}/transfer-requests",
                                conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetAdminUserId":%d,"reasonCode":"EXPERTISE","reasonNote":"转给客服二号"}
                                """.formatted(targetAdminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long transferRequestId = objectMapper.readTree(transferRequest).path("data").path("requestId").asLong();

        mockMvc.perform(get("/admin/customer-service/conversations")
                        .header("Authorization", bearer(targetToken))
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/admin/customer-service/conversations/{conversationId}", conversationId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900001));
        mockMvc.perform(get(
                                "/admin/customer-service/conversations/{conversationId}/messages",
                                conversationId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900001));

        mockMvc.perform(post("/admin/customer-service/transfer-requests/{requestId}/accept", transferRequestId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAdminUserId").value(targetAdminId));

        mockMvc.perform(get("/admin/customer-service/conversations/workspace")
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeTotal").value(1))
                .andExpect(jsonPath("$.data.active[0].conversationId").value(conversationId));

        String acceptedDetail = mockMvc.perform(
                                get("/admin/customer-service/conversations/{conversationId}", conversationId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        JsonNode acceptedMessages = objectMapper.readTree(acceptedDetail).path("data").path("messages");
        long latestPageCursor = acceptedMessages.get(acceptedMessages.size() - 2).path("messageId").asLong();

        mockMvc.perform(get(
                                "/admin/customer-service/conversations/{conversationId}/messages",
                                conversationId)
                        .header("Authorization", bearer(targetToken))
                        .param("beforeId", String.valueOf(latestPageCursor))
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].messageId").value(
                        acceptedMessages.get(acceptedMessages.size() - 3).path("messageId").asLong()
                ));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"不应再发送","clientMessageId":"admin-message-after-transfer"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900003));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/close", conversationId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"我还有一个问题","clientMessageId":"app-message-reopen"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/customer-service/conversation")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.assignedAdminUserId").doesNotExist())
                .andExpect(jsonPath("$.data.consultationNo").value(2))
                .andExpect(jsonPath("$.data.currentContext.type").value("GENERAL"))
                .andExpect(jsonPath("$.data.messages.length()").value(2));
    }

    @Test
    void productAndOrderEntriesSetCurrentContextAndClosedConversationStartsCleanConsultation() throws Exception {
        AppLogin app = appLogin("customer-service-context-user");
        long productId = insertProduct("咨询商品一", 2590);
        long orderId = insertOrder(app.userId(), "CS-CONTEXT-ORDER");
        String adminToken = adminLogin("Super", "123456");
        prepareAdminForService(adminToken, 1L);

        String opened = mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contextType":"PRODUCT","contextId":%d}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.currentContext.type").value("PRODUCT"))
                .andExpect(jsonPath("$.data.currentContext.product.productId").value(productId))
                .andExpect(jsonPath("$.data.linkedProducts[0].productId").value(productId))
                .andExpect(jsonPath("$.data.linkedOrders.length()").value(0))
                .andExpect(jsonPath("$.data.messages.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(opened).path("data").path("conversationId").asLong();

        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"想了解这个商品","clientMessageId":"product-context-first"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/close", conversationId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contextType":"ORDER","contextId":%d}
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.consultationNo").value(2))
                .andExpect(jsonPath("$.data.currentContext.type").value("ORDER"))
                .andExpect(jsonPath("$.data.currentContext.order.orderId").value(orderId))
                .andExpect(jsonPath("$.data.linkedOrders[0].orderId").value(orderId))
                .andExpect(jsonPath("$.data.linkedProducts.length()").value(0))
                .andExpect(jsonPath("$.data.messages.length()").value(0));

        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"想咨询这个订单","clientMessageId":"order-context-second"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/customer-service/conversation")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.messages.length()").value(3))
                .andExpect(jsonPath("$.data.messages[0].messageType").value("SYSTEM"))
                .andExpect(jsonPath("$.data.messages[1].messageType").value("ORDER_CARD"))
                .andExpect(jsonPath("$.data.messages[2].messageType").value("TEXT"));
    }

    @Test
    void draftConversationIsHiddenFromAgentsAndLatestEntryReplacesPendingContext() throws Exception {
        AppLogin app = appLogin("customer-service-draft-user");
        long productId = insertProduct("草稿咨询商品", 3990);
        long orderId = insertOrder(app.userId(), "CS-DRAFT-ORDER");
        String adminToken = adminLogin("Super", "123456");
        prepareAdminForService(adminToken, 1L);

        String opened = mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contextType":"PRODUCT","contextId":%d}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.messages.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(opened).path("data").path("conversationId").asLong();

        mockMvc.perform(get("/admin/customer-service/conversations")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/admin/customer-service/conversations/{conversationId}", conversationId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contextType":"ORDER","contextId":%d}
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.currentContext.type").value("ORDER"))
                .andExpect(jsonPath("$.data.linkedOrders.length()").value(1))
                .andExpect(jsonPath("$.data.linkedProducts.length()").value(0))
                .andExpect(jsonPath("$.data.messages.length()").value(0));

        mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentContext.type").value("GENERAL"))
                .andExpect(jsonPath("$.data.linkedOrders.length()").value(0))
                .andExpect(jsonPath("$.data.linkedProducts.length()").value(0))
                .andExpect(jsonPath("$.data.messages.length()").value(0));
    }

    @Test
    void appAndAdminCanUploadCustomerServiceImagesAndOnlyOwningAppCanReadThem() throws Exception {
        AppLogin app = appLogin("customer-service-image-user");
        AppLogin other = appLogin("customer-service-image-other");
        String adminToken = adminLogin("Super", "123456");
        prepareAdminForService(adminToken, 1L);
        String opened = mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(opened).path("data").path("conversationId").asLong();
        MockMultipartFile appImage = new MockMultipartFile(
                "file", "app.png", "image/png", png(1440, 900)
        );

        String appImageResponse = mockMvc.perform(multipart("/app/customer-service/conversation/images")
                        .file(appImage)
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageType").value("IMAGE"))
                .andExpect(jsonPath("$.data.senderType").value("APP_USER"))
                .andExpect(jsonPath("$.data.image.accessMode").value("AUTHENTICATED_BLOB"))
                .andExpect(jsonPath("$.data.image.accessUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long appMessageId = objectMapper.readTree(appImageResponse).path("data").path("messageId").asLong();

        MvcResult originalResult = mockMvc.perform(
                        get("/app/customer-service/conversation/messages/{messageId}/image", appMessageId)
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andReturn();
        String originalEtag = originalResult.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(originalEtag).isNotBlank();
        mockMvc.perform(get("/app/customer-service/conversation/messages/{messageId}/image", appMessageId)
                        .header("Authorization", bearer(app.token()))
                        .header(HttpHeaders.IF_NONE_MATCH, originalEtag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, originalEtag));

        MvcResult thumbnailResult = mockMvc.perform(
                        get("/app/customer-service/conversation/messages/{messageId}/thumbnail", appMessageId)
                                .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isIn("image/webp", "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andReturn();
        String thumbnailContentType = thumbnailResult.getResponse().getContentType();
        String thumbnailEtag = thumbnailResult.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(thumbnailEtag).isNotBlank().isNotEqualTo(originalEtag);
        mockMvc.perform(get("/app/customer-service/conversation/messages/{messageId}/thumbnail", appMessageId)
                        .header("Authorization", bearer(app.token()))
                        .header(HttpHeaders.IF_NONE_MATCH, thumbnailEtag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, thumbnailEtag));
        assertThat(jdbcClient.sql("""
                        select thumbnail_status, thumbnail_content_type,
                               thumbnail_width, thumbnail_height
                        from storage_asset
                        where id = (
                            select resource_id
                            from customer_service_message
                            where id = :messageId
                        )
                        """)
                .param("messageId", appMessageId)
                .query((rs, rowNum) -> List.of(
                        rs.getString("thumbnail_status"),
                        rs.getString("thumbnail_content_type"),
                        rs.getString("thumbnail_width"),
                        rs.getString("thumbnail_height")
                ))
                .single()).containsExactly("READY", thumbnailContentType, "720", "450");
        mockMvc.perform(get(
                        "/app/customer-service/conversation/messages/{messageId}/image-access",
                        appMessageId
                ).header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessMode").value("AUTHENTICATED_BLOB"))
                .andExpect(jsonPath("$.data.accessUrl").doesNotExist());
        mockMvc.perform(get("/app/customer-service/conversation/messages/{messageId}/image", appMessageId)
                        .header("Authorization", bearer(other.token())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(
                        "/app/customer-service/conversation/messages/{messageId}/image-access",
                        appMessageId
                ).header("Authorization", bearer(other.token())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"请协助查看问题","clientMessageId":"image-policy-text"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        MockMultipartFile adminImage = new MockMultipartFile(
                "file", "admin.png", "image/png", onePixelPng()
        );
        String adminImageResponse = mockMvc.perform(
                        multipart("/admin/customer-service/conversations/{conversationId}/images", conversationId)
                                .file(adminImage)
                                .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageType").value("IMAGE"))
                .andExpect(jsonPath("$.data.image.accessMode").value("AUTHENTICATED_BLOB"))
                .andExpect(jsonPath("$.data.image.accessUrl").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long adminMessageId = objectMapper.readTree(adminImageResponse).path("data").path("messageId").asLong();
        mockMvc.perform(get("/app/customer-service/conversation/messages/{messageId}/image", adminMessageId)
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/png"));
        mockMvc.perform(get("/app/customer-service/conversation/messages/{messageId}/image", adminMessageId)
                        .header("Authorization", bearer(other.token())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/customer-service/messages/{messageId}/image", adminMessageId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get(
                        "/admin/customer-service/messages/{messageId}/image-access",
                        adminMessageId
                ).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessMode").value("AUTHENTICATED_BLOB"))
                .andExpect(jsonPath("$.data.accessUrl").doesNotExist());

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from customer_service_message message
                        join storage_asset asset on asset.id = message.resource_id
                        where message.conversation_id = :conversationId
                          and message.message_type = 'IMAGE'
                          and asset.status = 'ACTIVE'
                          and asset.expires_at is null
                          and asset.upload_context_type = 'CUSTOMER_SERVICE_CONVERSATION'
                          and asset.upload_context_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void customerServiceApisEnforceTokenKindAndPermission() throws Exception {
        AppLogin app = appLogin("customer-service-auth-user");

        mockMvc.perform(get("/admin/customer-service/conversations")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/app/customer-service/conversation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void realtimeTicketEndpointsRequireTheMatchingTokenKind() throws Exception {
        AppLogin app = appLogin("customer-service-ticket-user");
        String adminToken = adminLogin("Super", "123456");

        mockMvc.perform(post("/app/realtime/tickets")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticket").isString())
                .andExpect(jsonPath("$.data.expiresIn").value(60));

        mockMvc.perform(post("/admin/realtime/tickets")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticket").isString());

        mockMvc.perform(post("/admin/realtime/tickets")
                        .header("Authorization", bearer(app.token())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/app/realtime/tickets")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isUnauthorized());
    }

    private AppLogin appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new AppLogin(data.path("token").asText(), data.path("user").path("userId").asLong());
    }

    private String adminLogin(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long insertCustomerServiceAgent(String username, String displayName, String password) {
        jdbcClient.sql("""
                        insert into admin_user
                            (username, password_hash, display_name, email, avatar, status, created_at, updated_at)
                        values
                            (:username, :passwordHash, :displayName, :email, '', 'ENABLED', current_timestamp, current_timestamp)
                        """)
                .param("username", username)
                .param("passwordHash", passwordEncoder.encode(password))
                .param("displayName", displayName)
                .param("email", username.toLowerCase() + "@shop.local")
                .update();
        long userId = jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        select :userId, id from admin_role where code = 'R_CUSTOMER_SERVICE'
                        """)
                .param("userId", userId)
                .update();
        return userId;
    }

    private long insertOrder(long userId, String orderNo) {
        jdbcClient.sql("""
                        insert into shop_order
                            (order_no, user_id, status, idempotency_key, payable_amount_cent, created_at, updated_at)
                        values
                            (:orderNo, :userId, 'PAID', :idempotencyKey, 3990, current_timestamp, current_timestamp)
                        """)
                .param("orderNo", orderNo)
                .param("userId", userId)
                .param("idempotencyKey", "customer-service-" + orderNo)
                .update();
        return jdbcClient.sql("select id from shop_order where order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    private long insertProduct(String title, long priceCent) {
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html,
                             status, created_at, updated_at)
                        values
                            (1, :title, '', '/product.png', '', '',
                             'ON_SALE', current_timestamp, current_timestamp)
                        """)
                .param("title", title)
                .update();
        long productId = jdbcClient.sql("select id from product_spu where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, status, created_at, updated_at)
                        values
                            (:productId, :skuCode, '{}', '', :priceCent, :priceCent,
                             10, 'ENABLED', current_timestamp, current_timestamp)
                        """)
                .param("productId", productId)
                .param("skuCode", "CS-" + productId)
                .param("priceCent", priceCent)
                .update();
        return productId;
    }

    private byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(196, 64, 48));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void prepareAdminForService(String token, long adminUserId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("customer-service-test-" + adminUserId + "-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "admin-" + adminUserId,
                List.of("customer-service:conversation:read")
        ));
        mockMvc.perform(put("/admin/customer-service/agent-state")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workStatus\":\"AVAILABLE\"}"))
                .andExpect(status().isOk());
    }

    private record AppLogin(String token, long userId) {
    }
}
