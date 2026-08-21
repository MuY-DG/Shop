package org.muybaby.shopserver.accountcancellation.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.muybaby.shopserver.accountcancellation.dto.AccountCancellationEligibilityResponse;
import org.muybaby.shopserver.accountcancellation.dto.AccountCancellationRequest;
import org.muybaby.shopserver.accountcancellation.dto.AccountCancellationResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.compliance.LegalDocumentType;
import org.muybaby.shopserver.compliance.dto.LegalDocumentResponse;
import org.muybaby.shopserver.compliance.service.LegalDocumentService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.service.StorageService;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.user.service.AppUserService;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatMiniProgramClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccountCancellationService {

    private static final String DELETED_DATA_CATEGORIES =
            "账号登录标识,昵称与头像,授权手机号,收货地址,购物车,商品收藏,浏览记录,未使用优惠券";
    private static final String RETAINED_DATA_CATEGORIES =
            "已完成订单,支付与退款,售后与客服,商品评价,安全与审计记录";

    private final JdbcClient jdbcClient;
    private final AppUserService appUserService;
    private final WechatMiniProgramClient wechatClient;
    private final LegalDocumentService legalDocumentService;
    private final StorageService storageService;
    private final TransactionOperations transaction;

    public AccountCancellationService(
            JdbcClient jdbcClient,
            AppUserService appUserService,
            WechatMiniProgramClient wechatClient,
            LegalDocumentService legalDocumentService,
            StorageService storageService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.appUserService = appUserService;
        this.wechatClient = wechatClient;
        this.legalDocumentService = legalDocumentService;
        this.storageService = storageService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public AccountCancellationEligibilityResponse eligibility(AuthenticatedPrincipal principal) {
        Long userId = requireAppUser(principal);
        appUserService.requireEnabledUser(userId);
        return eligibilityFor(userId);
    }

    public AccountCancellationResponse cancel(
            AuthenticatedPrincipal principal,
            AccountCancellationRequest request
    ) {
        Long userId = requireAppUser(principal);
        if (request == null || !Boolean.TRUE.equals(request.noticeAcknowledged())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String wechatCode = requireText(request.wechatCode(), 128);
        AppUser currentUser = appUserService.requireEnabledUser(userId);
        WechatCodeSession codeSession = wechatClient.code2Session(wechatCode);
        if (codeSession == null || !Objects.equals(currentUser.openid(), codeSession.openid())) {
            throw new BusinessException(ErrorCode.ACCOUNT_CANCELLATION_IDENTITY_MISMATCH);
        }

        CancellationResult result = transaction.execute(status -> {
            LegalDocumentResponse notice = legalDocumentService.requireAcknowledgedCurrent(
                    LegalDocumentType.ACCOUNT_CANCELLATION_NOTICE,
                    request.noticeVersion(),
                    request.noticeContentSha256()
            );
            AppUser lockedUser = appUserService.requireEnabledUserForUpdate(userId);
            if (!Objects.equals(lockedUser.openid(), codeSession.openid())) {
                throw new BusinessException(ErrorCode.ACCOUNT_CANCELLATION_IDENTITY_MISMATCH);
            }

            lockActiveCommerceRows(userId);
            AccountCancellationEligibilityResponse eligibility = eligibilityFor(userId);
            if (!eligibility.eligible()) {
                throw new BusinessException(ErrorCode.ACCOUNT_CANCELLATION_ACTIVE_OBLIGATIONS);
            }

            deleteDisposableAccountData(userId);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int updated = jdbcClient.sql("""
                            update app_user
                            set openid = :cancelledOpenid,
                                unionid = null,
                                nickname = '',
                                avatar_url = null,
                                phone_number = null,
                                phone_country_code = null,
                                phone_authorized = false,
                                phone_authorized_at = null,
                                status = 'CANCELLED',
                                auth_version = auth_version + 1,
                                cancelled_at = :completedAt,
                                updated_at = :completedAt
                            where id = :userId
                              and status = 'ENABLED'
                              and auth_version = :authVersion
                            """)
                    .param("cancelledOpenid", cancellationPlaceholder())
                    .param("completedAt", now)
                    .param("userId", userId)
                    .param("authVersion", lockedUser.authVersion())
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
            }

            long cancellationId = IdWorker.getId();
            jdbcClient.sql("""
                            insert into app_user_account_cancellation (
                                id, user_id, legal_document_revision_id,
                                notice_version, notice_content_sha256,
                                channel, mini_program_env, identity_verified_at,
                                deleted_data_categories, retained_data_categories,
                                completed_at, created_at
                            ) values (
                                :id, :userId, :noticeId,
                                :noticeVersion, :noticeContentSha256,
                                'WECHAT_MINIPROGRAM', :miniProgramEnv, :completedAt,
                                :deletedDataCategories, :retainedDataCategories,
                                :completedAt, :completedAt
                            )
                            """)
                    .param("id", cancellationId)
                    .param("userId", userId)
                    .param("noticeId", notice.id())
                    .param("noticeVersion", notice.version())
                    .param("noticeContentSha256", notice.contentSha256())
                    .param("miniProgramEnv", request.miniProgramEnv())
                    .param("deletedDataCategories", DELETED_DATA_CATEGORIES)
                    .param("retainedDataCategories", RETAINED_DATA_CATEGORIES)
                    .param("completedAt", now)
                    .update();
            return new CancellationResult(cancellationId, now, lockedUser.avatarUrl());
        });
        if (result == null) {
            throw new IllegalStateException("Account cancellation transaction returned no result");
        }
        storageService.cleanupReplacedUserAvatar(userId, result.avatarUrl(), null);
        return new AccountCancellationResponse(result.cancellationId(), result.completedAt());
    }

    private AccountCancellationEligibilityResponse eligibilityFor(Long userId) {
        long activeOrders = count("""
                select count(*) from shop_order
                where user_id = :userId
                  and status not in ('CLOSED', 'COMPLETED', 'REFUNDED')
                """, userId);
        long activePayments = count("""
                select count(*)
                from payment_order payment
                join shop_order order_entry on order_entry.id = payment.order_id
                where order_entry.user_id = :userId
                  and payment.status not in ('PAID', 'CLOSED')
                """, userId);
        long activeRefunds = count("""
                select count(*)
                from refund_order refund
                join shop_order order_entry on order_entry.id = refund.order_id
                where order_entry.user_id = :userId
                  and not (
                      refund.status = 'SUCCESS'
                      or (refund.status = 'FAILED' and refund.callback_status = 'CLOSED')
                  )
                """, userId);
        long activeAfterSales = count("""
                select count(*) from after_sale_request
                where user_id = :userId
                  and status not in ('REJECTED', 'RETURN_REJECTED', 'CANCELLED', 'REFUNDED')
                """, userId);
        return new AccountCancellationEligibilityResponse(
                activeOrders + activePayments + activeRefunds + activeAfterSales == 0,
                activeOrders,
                activePayments,
                activeRefunds,
                activeAfterSales
        );
    }

    private void deleteDisposableAccountData(Long userId) {
        jdbcClient.sql("delete from cart_item where user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("delete from user_product_favorite where user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("delete from user_product_browse_history where user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("delete from user_address where user_id = :userId")
                .param("userId", userId).update();
        jdbcClient.sql("""
                        delete from coupon_claim_record
                        where user_id = :userId
                          and user_coupon_id in (
                              select coupon.id
                              from user_coupon coupon
                              where coupon.user_id = :userId
                                and coupon.status in ('CLAIMED', 'EXPIRED')
                                and not exists (
                                    select 1 from shop_order order_entry
                                    where order_entry.user_coupon_id = coupon.id
                                )
                          )
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        delete from user_coupon
                        where user_id = :userId
                          and status in ('CLAIMED', 'EXPIRED')
                          and not exists (
                              select 1 from shop_order order_entry
                              where order_entry.user_coupon_id = user_coupon.id
                          )
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        update product_review
                        set anonymous = true, updated_at = current_timestamp
                        where user_id = :userId and anonymous = false
                        """)
                .param("userId", userId)
                .update();
    }

    private void lockActiveCommerceRows(Long userId) {
        lock("""
                select id from shop_order
                where user_id = :userId
                  and status not in ('CLOSED', 'COMPLETED', 'REFUNDED')
                order by id for update
                """, userId);
        lock("""
                select id from after_sale_request
                where user_id = :userId
                  and status not in ('REJECTED', 'RETURN_REJECTED', 'CANCELLED', 'REFUNDED')
                order by id for update
                """, userId);
        lock("""
                select id from payment_order
                where order_id in (select id from shop_order where user_id = :userId)
                  and status not in ('PAID', 'CLOSED')
                order by order_id, id for update
                """, userId);
        lock("""
                select id from refund_order
                where order_id in (select id from shop_order where user_id = :userId)
                  and not (
                      status = 'SUCCESS'
                      or (status = 'FAILED' and callback_status = 'CLOSED')
                  )
                order by order_id, id for update
                """, userId);
    }

    private void lock(String sql, Long userId) {
        jdbcClient.sql(sql).param("userId", userId).query(Long.class).list();
    }

    private long count(String sql, Long userId) {
        return jdbcClient.sql(sql).param("userId", userId).query(Long.class).single();
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private String requireText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String cancellationPlaceholder() {
        return "cancelled_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record CancellationResult(Long cancellationId, LocalDateTime completedAt, String avatarUrl) {
    }
}
