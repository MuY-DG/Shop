package org.muybaby.shopserver.user.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.dto.AppUserOverviewResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class AppUserOverviewService {

    private final JdbcClient jdbcClient;
    private final CustomerServiceService customerServiceService;

    public AppUserOverviewService(
            JdbcClient jdbcClient,
            CustomerServiceService customerServiceService
    ) {
        this.jdbcClient = jdbcClient;
        this.customerServiceService = customerServiceService;
    }

    @Transactional(readOnly = true)
    public AppUserOverviewResponse overview(AuthenticatedPrincipal principal) {
        long userId = requireAppUser(principal);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        OverviewCounts counts = jdbcClient.sql("""
                        select
                          (select count(*)
                           from user_coupon coupon
                           where coupon.user_id = :userId
                             and coupon.status = 'CLAIMED'
                             and coupon.valid_start_at <= :now
                             and coupon.valid_end_at >= :now) as available_coupon_count,
                          (select count(*)
                           from user_product_favorite favorite
                           where favorite.user_id = :userId) as favorite_count,
                          (select count(*)
                           from user_product_browse_history browse_history
                           where browse_history.user_id = :userId) as browse_history_count,
                          (select count(*)
                           from shop_order user_order
                           where user_order.user_id = :userId
                             and user_order.app_deleted_at is null
                             and user_order.status in ('CREATED', 'PAYING')) as unpaid_order_count,
                          (select count(*)
                           from shop_order user_order
                           where user_order.user_id = :userId
                             and user_order.app_deleted_at is null
                             and user_order.status = 'PAID') as to_ship_order_count,
                          (select count(*)
                           from shop_order user_order
                           where user_order.user_id = :userId
                             and user_order.app_deleted_at is null
                             and user_order.status = 'SHIPPED') as to_receive_order_count,
                          (select count(*)
                           from shop_order user_order
                           where user_order.user_id = :userId
                             and user_order.app_deleted_at is null
                             and user_order.status = 'COMPLETED'
                             and user_order.completed_at is not null
                             and exists (
                               select 1
                               from order_item pending_item
                               where pending_item.order_id = user_order.id
                                 and not exists (
                                   select 1
                                   from product_review review
                                   where review.source_order_item_id = pending_item.id
                                 )
                                 and exists (
                                   select 1
                                   from product_spu pending_product
                                   where pending_product.id = pending_item.spu_id
                                     and pending_product.purged_at is null
                                 )
                             )) as to_review_order_count,
                          (select count(*)
                           from after_sale_request after_sale
                           where after_sale.user_id = :userId
                             and after_sale.status in (
                               'REQUESTED', 'APPROVED', 'REFUNDING', 'REFUND_FAILED'
                             )) as active_after_sale_count,
                          (select coalesce(sum(conversation.app_unread_count), 0)
                           from customer_service_conversation conversation
                           where conversation.app_user_id = :userId) as customer_service_unread_count
                        """)
                .param("userId", userId)
                .param("now", now)
                .query((rs, rowNum) -> new OverviewCounts(
                        rs.getLong("available_coupon_count"),
                        rs.getLong("favorite_count"),
                        rs.getLong("browse_history_count"),
                        rs.getLong("unpaid_order_count"),
                        rs.getLong("to_ship_order_count"),
                        rs.getLong("to_receive_order_count"),
                        rs.getLong("to_review_order_count"),
                        rs.getLong("active_after_sale_count"),
                        rs.getLong("customer_service_unread_count")
                ))
                .single();
        boolean customerServiceOnline = customerServiceService.isOnline();
        return new AppUserOverviewResponse(
                counts.availableCouponCount(),
                counts.favoriteCount(),
                counts.browseHistoryCount(),
                counts.unpaidOrderCount(),
                counts.toShipOrderCount(),
                counts.toReceiveOrderCount(),
                counts.toReviewOrderCount(),
                counts.activeAfterSaleCount(),
                counts.customerServiceUnreadCount(),
                customerServiceOnline
        );
    }

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private record OverviewCounts(
            long availableCouponCount,
            long favoriteCount,
            long browseHistoryCount,
            long unpaidOrderCount,
            long toShipOrderCount,
            long toReceiveOrderCount,
            long toReviewOrderCount,
            long activeAfterSaleCount,
            long customerServiceUnreadCount
    ) {
    }
}
