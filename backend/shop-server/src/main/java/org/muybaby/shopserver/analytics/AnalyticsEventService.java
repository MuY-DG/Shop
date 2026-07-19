package org.muybaby.shopserver.analytics;

import org.muybaby.shopserver.analytics.dto.AnalyticsEventBatchRequest;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventBatchResponse;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventRequest;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AnalyticsEventService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final Set<AnalyticsEventType> CLIENT_EVENT_TYPES = Set.of(
            AnalyticsEventType.APP_LAUNCH,
            AnalyticsEventType.PAGE_VIEW,
            AnalyticsEventType.PRODUCT_VIEW,
            AnalyticsEventType.SEARCH,
            AnalyticsEventType.CHECKOUT_START);
    private static final Duration MAX_EVENT_AGE = Duration.ofDays(7);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final JdbcClient jdbcClient;

    public AnalyticsEventService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public AnalyticsEventBatchResponse accept(
            AuthenticatedPrincipal principal,
            AnalyticsEventBatchRequest request
    ) {
        requireUuid(request.visitorId());
        Instant receivedAt = Instant.now();
        Long userId = appUserId(principal);
        List<NormalizedEvent> normalizedEvents = request.events().stream()
                .map(event -> normalizeClientEvent(request.visitorId(), event, receivedAt))
                .toList();
        validateProductReferences(normalizedEvents);
        int accepted = 0;
        int duplicates = 0;
        for (NormalizedEvent normalized : normalizedEvents) {
            InsertResult result = insert(normalized, userId, "CLIENT", receivedAt);
            if (result == InsertResult.ACCEPTED) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        return new AnalyticsEventBatchResponse(accepted, duplicates, receivedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCartAdd(
            AuthenticatedPrincipal principal,
            String visitorId,
            String sessionId,
            String entryScene,
            Long spuId,
            Long skuId,
            Integer quantity
    ) {
        if (visitorId == null || sessionId == null) {
            return;
        }
        requireUuid(visitorId);
        requireUuid(sessionId);
        if (skuId == null || skuId <= 0 || quantity == null || quantity <= 0) {
            throw validationFailed();
        }
        Instant now = Instant.now();
        String clientEventId = java.util.UUID.randomUUID().toString();
        NormalizedEvent event = new NormalizedEvent(
                clientEventId,
                visitorId,
                sessionId,
                AnalyticsEventType.CART_ADD,
                now,
                "",
                "",
                safe(entryScene, 32),
                "",
                "",
                spuId,
                skuId,
                quantity);
        insert(event, appUserId(principal), "SERVER", now);
    }

    public static String optionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireUuid(value);
        return value.toLowerCase(Locale.ROOT);
    }

    public static String optionalEntryScene(String value) {
        return value == null ? "" : safe(value, 32);
    }

    private NormalizedEvent normalizeClientEvent(String visitorId, AnalyticsEventRequest event, Instant receivedAt) {
        requireUuid(event.clientEventId());
        requireUuid(event.sessionId());
        AnalyticsEventType type;
        try {
            type = AnalyticsEventType.valueOf(event.eventType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw validationFailed();
        }
        if (!CLIENT_EVENT_TYPES.contains(type)) {
            throw validationFailed();
        }
        if (event.occurredAt().isBefore(receivedAt.minus(MAX_EVENT_AGE))
                || event.occurredAt().isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
            throw validationFailed();
        }
        String pagePath = safePath(event.pagePath());
        String sourcePage = safePath(event.sourcePage());
        String entryScene = safe(event.entryScene(), 32);
        String keyword = safe(event.searchKeyword(), 80);
        String checkoutSource = safe(event.checkoutSource(), 20).toUpperCase(Locale.ROOT);
        if (type == AnalyticsEventType.PRODUCT_VIEW && event.spuId() == null) {
            throw validationFailed();
        }
        if (type == AnalyticsEventType.SEARCH && keyword.isBlank()) {
            throw validationFailed();
        }
        if (type == AnalyticsEventType.CHECKOUT_START
                && !("CART".equals(checkoutSource) || "DIRECT".equals(checkoutSource))) {
            throw validationFailed();
        }
        return new NormalizedEvent(
                event.clientEventId().toLowerCase(Locale.ROOT),
                visitorId.toLowerCase(Locale.ROOT),
                event.sessionId().toLowerCase(Locale.ROOT),
                type,
                event.occurredAt(),
                pagePath,
                sourcePage,
                entryScene,
                keyword,
                checkoutSource,
                event.spuId(),
                event.skuId(),
                event.quantity());
    }

    private InsertResult insert(NormalizedEvent event, Long userId, String source, Instant receivedAt) {
        String digest = digest(event);
        String insertMarker = "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        jdbcClient.sql("""
                        INSERT INTO analytics_event (
                            client_event_id, payload_digest, visitor_id, session_id, user_id,
                            event_source, event_type, page_path, source_page, entry_scene,
                            search_keyword, checkout_source, spu_id, sku_id, quantity,
                            occurred_at, received_at, business_date
                        ) VALUES (
                            :clientEventId, :payloadDigest, :visitorId, :sessionId, :userId,
                            :eventSource, :eventType, :pagePath, :sourcePage, :entryScene,
                            :searchKeyword, :checkoutSource, :spuId, :skuId, :quantity,
                            :occurredAt, :receivedAt, :businessDate
                        ) ON DUPLICATE KEY UPDATE id = id
                        """)
                .param("clientEventId", event.clientEventId())
                .param("payloadDigest", digest)
                .param("visitorId", event.visitorId())
                .param("sessionId", event.sessionId())
                .param("userId", userId)
                .param("eventSource", insertMarker)
                .param("eventType", event.type().name())
                .param("pagePath", event.pagePath())
                .param("sourcePage", event.sourcePage())
                .param("entryScene", event.entryScene())
                .param("searchKeyword", event.searchKeyword())
                .param("checkoutSource", event.checkoutSource())
                .param("spuId", event.spuId())
                .param("skuId", event.skuId())
                .param("quantity", event.quantity())
                .param("occurredAt", event.occurredAt())
                .param("receivedAt", receivedAt)
                .param("businessDate", LocalDate.ofInstant(event.occurredAt(), BUSINESS_ZONE))
                .update();
        StoredEvent stored = jdbcClient.sql("""
                        SELECT payload_digest, event_source
                        FROM analytics_event
                        WHERE visitor_id = :visitorId AND client_event_id = :clientEventId
                        FOR UPDATE
                        """)
                .param("visitorId", event.visitorId())
                .param("clientEventId", event.clientEventId())
                .query((rs, rowNum) -> new StoredEvent(
                        rs.getString("payload_digest"),
                        rs.getString("event_source")))
                .single();
        if (!stored.payloadDigest().equals(digest)) {
            throw validationFailed();
        }
        if (!insertMarker.equals(stored.eventSource())) {
            return InsertResult.DUPLICATE;
        }
        int normalized = jdbcClient.sql("""
                        UPDATE analytics_event
                        SET event_source = :eventSource
                        WHERE visitor_id = :visitorId
                          AND client_event_id = :clientEventId
                          AND event_source = :insertMarker
                        """)
                .param("eventSource", source)
                .param("visitorId", event.visitorId())
                .param("clientEventId", event.clientEventId())
                .param("insertMarker", insertMarker)
                .update();
        if (normalized != 1) {
            throw new IllegalStateException("Analytics event insert marker was not normalized");
        }
        return InsertResult.ACCEPTED;
    }

    private void validateProductReferences(List<NormalizedEvent> events) {
        Set<Long> spuIds = new HashSet<>();
        Set<Long> skuIds = new HashSet<>();
        for (NormalizedEvent event : events) {
            if (event.spuId() != null) {
                spuIds.add(event.spuId());
            }
            if (event.skuId() != null) {
                skuIds.add(event.skuId());
            }
        }
        if (!spuIds.isEmpty()) {
            Set<Long> visibleSpuIds = new HashSet<>(jdbcClient.sql("""
                            SELECT p.id
                            FROM product_spu p
                            JOIN product_category c ON c.id = p.category_id
                            WHERE p.id IN (:spuIds)
                              AND p.status = 'ON_SALE'
                              AND p.deleted_at IS NULL
                              AND p.purged_at IS NULL
                              AND c.status = 'ENABLED'
                            """)
                    .param("spuIds", spuIds)
                    .query(Long.class)
                    .list());
            if (!visibleSpuIds.containsAll(spuIds)) {
                throw validationFailed();
            }
        }
        Map<Long, Long> visibleSkuSpuIds = new HashMap<>();
        if (!skuIds.isEmpty()) {
            jdbcClient.sql("""
                            SELECT s.id AS sku_id, s.spu_id
                            FROM product_sku s
                            JOIN product_spu p ON p.id = s.spu_id
                            JOIN product_category c ON c.id = p.category_id
                            WHERE s.id IN (:skuIds)
                              AND s.status = 'ENABLED'
                              AND s.deleted_at IS NULL
                              AND p.status = 'ON_SALE'
                              AND p.deleted_at IS NULL
                              AND p.purged_at IS NULL
                              AND c.status = 'ENABLED'
                            """)
                    .param("skuIds", skuIds)
                    .query((rs, rowNum) -> new SkuReference(
                            rs.getLong("sku_id"),
                            rs.getLong("spu_id")))
                    .list()
                    .forEach(row -> visibleSkuSpuIds.put(row.skuId(), row.spuId()));
            if (!visibleSkuSpuIds.keySet().containsAll(skuIds)) {
                throw validationFailed();
            }
        }
        for (NormalizedEvent event : events) {
            if (event.skuId() != null && event.spuId() != null
                    && !event.spuId().equals(visibleSkuSpuIds.get(event.skuId()))) {
                throw validationFailed();
            }
        }
    }

    private static Long appUserId(AuthenticatedPrincipal principal) {
        return principal != null && principal.kind() == TokenKind.APP ? principal.subjectId() : null;
    }

    private static void requireUuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw validationFailed();
        }
    }

    private static String safePath(String value) {
        String result = safe(value, 160);
        if (result.contains("?") || result.contains("#")) {
            throw validationFailed();
        }
        return result;
    }

    private static String safe(String value, int maxLength) {
        String result = value == null ? "" : value.trim();
        if (result.length() > maxLength || result.indexOf('\0') >= 0) {
            throw validationFailed();
        }
        return result;
    }

    private static String digest(NormalizedEvent event) {
        String canonical = String.join("\u001f",
                event.clientEventId(), event.visitorId(), event.sessionId(), event.type().name(),
                event.occurredAt().toString(), event.pagePath(), event.sourcePage(), event.entryScene(),
                event.searchKeyword(), event.checkoutSource(), String.valueOf(event.spuId()),
                String.valueOf(event.skuId()), String.valueOf(event.quantity()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private enum InsertResult { ACCEPTED, DUPLICATE }

    private record StoredEvent(String payloadDigest, String eventSource) {
    }

    private record SkuReference(Long skuId, Long spuId) {
    }

    private record NormalizedEvent(
            String clientEventId,
            String visitorId,
            String sessionId,
            AnalyticsEventType type,
            Instant occurredAt,
            String pagePath,
            String sourcePage,
            String entryScene,
            String searchKeyword,
            String checkoutSource,
            Long spuId,
            Long skuId,
            Integer quantity
    ) {
    }
}
