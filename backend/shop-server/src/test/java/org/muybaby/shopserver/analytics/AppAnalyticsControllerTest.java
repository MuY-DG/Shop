package org.muybaby.shopserver.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppAnalyticsControllerTest {

    private static final long USER_ID = 990_032L;
    private static final String VISITOR_ID = "00000000-0000-4000-8000-000000000001";
    private static final String SESSION_ID = "00000000-0000-4000-8000-000000000002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private AppUserDailyActivityService dailyActivityService;

    @BeforeEach
    void clearAnalyticsFacts() {
        jdbcClient.sql("delete from analytics_event").update();
        jdbcClient.sql("delete from app_user_daily_activity where user_id = :userId")
                .param("userId", USER_ID)
                .update();
    }

    @Test
    void acceptsAnonymousEventsAndBindsUserOnlyFromAValidAppPrincipal() throws Exception {
        postBatch(null, event("00000000-0000-4000-8000-000000000003", "PAGE_VIEW", "/pages/home/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1))
                .andExpect(jsonPath("$.data.duplicateCount").value(0));

        postBatch(appToken(), event("00000000-0000-4000-8000-000000000004", "PAGE_VIEW", "/pages/product/list/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1));

        List<Long> userIds = jdbcClient.sql("select user_id from analytics_event order by id")
                .query(Long.class)
                .list();
        assertThat(userIds).containsExactly(null, USER_ID);
        assertThat(jdbcClient.sql("select count(*) from app_user_daily_activity where user_id = :userId")
                .param("userId", USER_ID)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void retriesAreIdempotentAndConflictingPayloadsAreRejected() throws Exception {
        String eventId = "00000000-0000-4000-8000-000000000005";
        String occurredAt = Instant.now().toString();
        Map<String, Object> first = event(eventId, "PAGE_VIEW", "/pages/home/home", occurredAt);

        postBatch(null, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(1));
        postBatch(null, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedCount").value(0))
                .andExpect(jsonPath("$.data.duplicateCount").value(1));
        postBatch(null, event(eventId, "PAGE_VIEW", "/pages/product/list/list", occurredAt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        assertThat(jdbcClient.sql("select count(*) from analytics_event")
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void rejectsClientAuthoredBusinessFactsAndOutOfWindowEvents() throws Exception {
        postBatch(null, event("00000000-0000-4000-8000-000000000006", "CART_ADD", "/pages/product/detail/detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        postBatch(null, event(
                "00000000-0000-4000-8000-000000000007",
                "PAGE_VIEW",
                "/pages/home/home",
                Instant.now().minusSeconds(8 * 24 * 60 * 60).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void rejectsNullBatchItemsAndUnavailableProductReferences() throws Exception {
        mockMvc.perform(post("/app/analytics/events/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "visitorId", VISITOR_ID,
                                "events", java.util.Collections.singletonList(null)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        Map<String, Object> unavailableProduct = event(
                "00000000-0000-4000-8000-000000000008",
                "PRODUCT_VIEW",
                "/pages/product/detail/detail");
        unavailableProduct.put("spuId", 9_999_999L);
        postBatch(null, unavailableProduct)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void authenticatedBusinessRequestsIncrementTheShanghaiDailyActivityFact() throws Exception {
        String token = appToken();

        mockMvc.perform(get("/app/cart/items").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/app/cart/items").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                        select request_count
                        from app_user_daily_activity
                        where user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Long.class)
                .single()).isEqualTo(2L);
    }

    @Test
    void dailyActivityKeepsChronologicalBoundsWhenRequestsArriveOutOfOrder() {
        Instant earlier = Instant.parse("2026-07-15T01:00:00Z");
        Instant later = Instant.parse("2026-07-15T02:00:00Z");

        dailyActivityService.record(USER_ID, later);
        dailyActivityService.record(USER_ID, earlier);

        ActivityRow activity = jdbcClient.sql("""
                        select first_active_at, last_active_at, updated_at, request_count
                        from app_user_daily_activity
                        where user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query((rs, rowNum) -> new ActivityRow(
                        rs.getTimestamp("first_active_at").toInstant(),
                        rs.getTimestamp("last_active_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getLong("request_count")))
                .single();
        assertThat(activity).isEqualTo(new ActivityRow(earlier, later, later, 2L));
    }

    private org.springframework.test.web.servlet.ResultActions postBatch(
            String token,
            Map<String, Object> event
    ) throws Exception {
        var request = post("/app/analytics/events/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "visitorId", VISITOR_ID,
                        "events", List.of(event))));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private Map<String, Object> event(String id, String type, String pagePath) {
        return event(id, type, pagePath, Instant.now().toString());
    }

    private Map<String, Object> event(String id, String type, String pagePath, String occurredAt) {
        java.util.LinkedHashMap<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("clientEventId", id);
        event.put("sessionId", SESSION_ID);
        event.put("eventType", type);
        event.put("occurredAt", occurredAt);
        event.put("pagePath", pagePath);
        if ("PRODUCT_VIEW".equals(type)) {
            event.put("spuId", 1L);
        }
        return event;
    }

    private String appToken() {
        return opaqueTokenService.issue(
                TokenKind.APP,
                TokenSession.app(USER_ID, "openid***", Instant.now()))
                .accessToken();
    }

    private record ActivityRow(Instant firstActiveAt, Instant lastActiveAt, Instant updatedAt, long requestCount) {
    }
}
