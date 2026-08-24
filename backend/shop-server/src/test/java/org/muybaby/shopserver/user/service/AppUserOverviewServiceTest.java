package org.muybaby.shopserver.user.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.realtime.RealtimeConnectionPrincipal;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.dto.AppUserOverviewResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppUserOverviewServiceTest {

    private static final long USER_ID = 99101L;

    @Autowired
    private AppUserOverviewService appUserOverviewService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RealtimeSessionHub realtimeSessionHub;

    @Test
    void overviewAggregatesCurrentUserDataWithoutClearingCustomerServiceUnreadCount() {
        LocalDateTime now = LocalDateTime.now();
        insertUser();
        insertProducts();
        insertCoupons(now);
        insertPreferences(now);
        insertOrders(now);
        insertAfterSales(now);
        insertConversation(now);

        AppUserOverviewResponse overview = appUserOverviewService.overview(appPrincipal());

        assertThat(overview.availableCouponCount()).isEqualTo(1);
        assertThat(overview.favoriteCount()).isEqualTo(1);
        assertThat(overview.browseHistoryCount()).isEqualTo(1);
        assertThat(overview.unpaidOrderCount()).isEqualTo(2);
        assertThat(overview.toShipOrderCount()).isEqualTo(1);
        assertThat(overview.toReceiveOrderCount()).isEqualTo(1);
        assertThat(overview.toReviewOrderCount()).isEqualTo(1);
        assertThat(overview.activeAfterSaleCount()).isEqualTo(1);
        assertThat(overview.customerServiceUnreadCount()).isEqualTo(7);
        assertThat(overview.customerServiceOnline()).isFalse();
        assertThat(jdbcClient.sql("""
                        select app_unread_count
                        from customer_service_conversation
                        where app_user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query(Integer.class)
                .single()).isEqualTo(7);
    }

    @Test
    void overviewOnlineStatusRequiresManualAvailabilityAndLiveWorkspace() {
        insertUser();
        long adminUserId = insertAvailableCustomerServiceAgent();

        AppUserOverviewResponse disconnected = appUserOverviewService.overview(appPrincipal());
        assertThat(disconnected.customerServiceOnline()).isFalse();

        WebSocketSession session = customerServiceWorkspaceSession(adminUserId);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "overview-online-agent",
                List.of("customer-service:conversation:read")
        ));
        realtimeSessionHub.startCustomerServicePresence(session);

        AppUserOverviewResponse connected = appUserOverviewService.overview(appPrincipal());
        assertThat(connected.customerServiceOnline()).isTrue();

        jdbcClient.sql("""
                        update customer_service_agent_state
                        set work_status = 'OFFLINE'
                        where admin_user_id = :adminUserId
                        """)
                .param("adminUserId", adminUserId)
                .update();
        AppUserOverviewResponse offline = appUserOverviewService.overview(appPrincipal());
        assertThat(offline.customerServiceOnline()).isFalse();

        realtimeSessionHub.unregister(session);
    }

    @Test
    void overviewRequiresAnAppPrincipal() {
        assertThatThrownBy(() -> appUserOverviewService.overview(null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private void insertUser() {
        jdbcClient.sql("""
                        insert into app_user (id, openid, status)
                        values (:id, :openid, 'ENABLED')
                        """)
                .param("id", USER_ID)
                .param("openid", "overview-user-99101")
                .update();
    }

    private long insertAvailableCustomerServiceAgent() {
        String username = "overview-online-agent";
        jdbcClient.sql("""
                        insert into admin_user
                            (username, password_hash, display_name, email, avatar,
                             status, created_at, updated_at)
                        values
                            (:username, 'unused', '概览在线客服', :email, '',
                             'ENABLED', current_timestamp, current_timestamp)
                        """)
                .param("username", username)
                .param("email", username + "@shop.local")
                .update();
        long adminUserId = jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        select :adminUserId, id from admin_role where code = 'R_CUSTOMER_SERVICE'
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

    private WebSocketSession customerServiceWorkspaceSession(long adminUserId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("overview-customer-service-" + adminUserId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private void insertProducts() {
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (99111, 0, 'Overview Category', '', 1, 'ENABLED')
                        """).update();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, subtitle, main_image,
                             selling_points, detail_html, sort_order, status)
                        values
                            (99121, 99111, 'Overview Product', '', '', '', '', 1, 'ON_SALE')
                        """).update();
    }

    private void insertCoupons(LocalDateTime now) {
        insertCoupon(99131L, "CLAIMED", now.minusDays(1), now.plusDays(1));
        insertCoupon(99132L, "CLAIMED", now.minusDays(2), now.minusDays(1));
        insertCoupon(99133L, "LOCKED", now.minusDays(1), now.plusDays(1));
    }

    private void insertCoupon(
            long id,
            String status,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt
    ) {
        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             discount_cent, valid_start_at, valid_end_at, status)
                        values
                            (:id, :userId, :templateId, 'Overview Coupon', 'NO_THRESHOLD',
                             'AMOUNT_OFF', 500, :validStartAt, :validEndAt, :status)
                        """)
                .param("id", id)
                .param("userId", USER_ID)
                .param("templateId", id + 1000)
                .param("validStartAt", validStartAt)
                .param("validEndAt", validEndAt)
                .param("status", status)
                .update();
    }

    private void insertPreferences(LocalDateTime now) {
        jdbcClient.sql("""
                        insert into user_product_favorite (user_id, spu_id, created_at)
                        values (:userId, 99121, :now)
                        """)
                .param("userId", USER_ID)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into user_product_browse_history
                            (user_id, spu_id, first_viewed_at, last_viewed_at, view_count)
                        values (:userId, 99121, :now, :now, 3)
                        """)
                .param("userId", USER_ID)
                .param("now", now)
                .update();
    }

    private void insertOrders(LocalDateTime now) {
        insertOrder(99201L, "CREATED", null, null, now);
        insertOrder(99202L, "PAYING", null, null, now);
        insertOrder(99203L, "PAID", null, null, now);
        insertOrder(99204L, "SHIPPED", null, null, now);
        insertOrder(99205L, "COMPLETED", now, null, now);
        insertOrder(99206L, "CREATED", null, now, now);
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, sku_code,
                             quantity, original_price_cent, unit_price_cent,
                             line_original_amount_cent, line_amount_cent)
                        values
                            (99251, 99205, 99301, 99121, 'Overview Product', 'OVERVIEW-SKU',
                             1, 1000, 1000, 1000, 1000)
                        """).update();
    }

    private void insertOrder(
            long id,
            String status,
            LocalDateTime completedAt,
            LocalDateTime appDeletedAt,
            LocalDateTime now
    ) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             product_original_amount_cent, product_amount_cent,
                             coupon_discount_cent, freight_cent, payable_amount_cent,
                             paid_amount_cent, completed_at, app_deleted_at, created_at, updated_at)
                        values
                            (:id, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             1000, 1000, 0, 0, 1000, 1000,
                             :completedAt, :appDeletedAt, :now, :now)
                        """)
                .param("id", id)
                .param("orderNo", "OVERVIEW-" + id)
                .param("userId", USER_ID)
                .param("status", status)
                .param("idempotencyKey", "overview-" + id)
                .param("completedAt", completedAt)
                .param("appDeletedAt", appDeletedAt)
                .param("now", now)
                .update();
    }

    private void insertAfterSales(LocalDateTime now) {
        insertAfterSale(99401L, 99203L, "REQUESTED", now);
        insertAfterSale(99402L, 99204L, "REFUNDED", now);
    }

    private void insertAfterSale(long id, long orderId, String status, LocalDateTime now) {
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status,
                             reason, requested_amount_cent, created_at, updated_at)
                        values
                            (:id, :afterSaleNo, :orderId, :userId, 'REFUND_ONLY', :status,
                             'Overview test', 1000, :now, :now)
                        """)
                .param("id", id)
                .param("afterSaleNo", "AS-OVERVIEW-" + id)
                .param("orderId", orderId)
                .param("userId", USER_ID)
                .param("status", status)
                .param("now", now)
                .update();
    }

    private void insertConversation(LocalDateTime now) {
        jdbcClient.sql("""
                        insert into customer_service_conversation
                            (id, app_user_id, status, app_unread_count, admin_unread_count,
                             created_at, updated_at)
                        values (99501, :userId, 'ACTIVE', 7, 0, :now, :now)
                        """)
                .param("userId", USER_ID)
                .param("now", now)
                .update();
    }

    private AuthenticatedPrincipal appPrincipal() {
        return new AuthenticatedPrincipal(
                TokenKind.APP,
                USER_ID,
                "overview-user",
                List.of(),
                List.of()
        );
    }
}
