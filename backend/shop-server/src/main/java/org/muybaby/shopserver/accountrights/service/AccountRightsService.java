package org.muybaby.shopserver.accountrights.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.muybaby.shopserver.accountrights.AccountRightsAdminAction;
import org.muybaby.shopserver.accountrights.AccountRightsRequestStatus;
import org.muybaby.shopserver.accountrights.AccountRightsRequestType;
import org.muybaby.shopserver.accountrights.dto.AccountRightsAuditResponse;
import org.muybaby.shopserver.accountrights.dto.AccountRightsRequestDetailResponse;
import org.muybaby.shopserver.accountrights.dto.AccountRightsRequestResponse;
import org.muybaby.shopserver.accountrights.dto.AccountRightsVersionRequest;
import org.muybaby.shopserver.accountrights.dto.AdminAccountRightsQuery;
import org.muybaby.shopserver.accountrights.dto.AdminAccountRightsTransitionRequest;
import org.muybaby.shopserver.accountrights.dto.AppAccountRightsSubmitRequest;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.user.service.AppUserService;
import org.muybaby.shopserver.wechat.WechatCodeSession;
import org.muybaby.shopserver.wechat.WechatMiniProgramClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccountRightsService {

    private static final int ACTIVE_REQUEST_KEY = 1;
    private static final int MAX_USER_HISTORY = 100;

    private final JdbcClient jdbcClient;
    private final AppUserService appUserService;
    private final WechatMiniProgramClient wechatClient;
    private final TransactionOperations transaction;

    public AccountRightsService(
            JdbcClient jdbcClient,
            AppUserService appUserService,
            WechatMiniProgramClient wechatClient,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.appUserService = appUserService;
        this.wechatClient = wechatClient;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public AccountRightsRequestResponse submit(
            AuthenticatedPrincipal principal,
            AppAccountRightsSubmitRequest request
    ) {
        Long userId = requirePrincipal(principal, TokenKind.APP);
        if (request == null || request.requestType() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        AccountRightsRequestType requestType = request.requestType();
        String requestNote = normalizeOptionalText(request.requestNote(), 1000);
        String verifiedOpenid = verifyIdentityIfRequired(userId, requestType, request.wechatCode());
        LocalDateTime identityVerifiedAt = verifiedOpenid == null ? null : utcNow();

        try {
            AccountRightsRequestResponse response = transaction.execute(status -> {
                AppUser lockedUser = appUserService.requireEnabledUserForUpdate(userId);
                if (verifiedOpenid != null && !Objects.equals(lockedUser.openid(), verifiedOpenid)) {
                    throw new BusinessException(ErrorCode.ACCOUNT_CANCELLATION_IDENTITY_MISMATCH);
                }
                if (hasActiveRequest(userId)) {
                    throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_CONFLICT);
                }

                long requestId = IdWorker.getId();
                LocalDateTime now = utcNow();
                jdbcClient.sql("""
                                insert into app_user_rights_request (
                                    id, user_id, request_type, status, active_request_key,
                                    request_note, identity_verified_at, created_at, updated_at
                                ) values (
                                    :id, :userId, :requestType, 'PENDING', 1,
                                    :requestNote, :identityVerifiedAt, :createdAt, :updatedAt
                                )
                                """)
                        .param("id", requestId)
                        .param("userId", userId)
                        .param("requestType", requestType.name())
                        .param("requestNote", requestNote)
                        .param("identityVerifiedAt", identityVerifiedAt, Types.TIMESTAMP)
                        .param("createdAt", now)
                        .param("updatedAt", now)
                        .update();
                insertAudit(
                        requestId, "SUBMITTED", "APP_USER", userId,
                        "", AccountRightsRequestStatus.PENDING.name(), "", "", List.of(), now
                );
                return requireRequest(requestId);
            });
            if (response == null) {
                throw new IllegalStateException("Account-rights submit transaction returned no result");
            }
            return response;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_CONFLICT);
        }
    }

    public List<AccountRightsRequestResponse> listForUser(AuthenticatedPrincipal principal) {
        Long userId = requirePrincipal(principal, TokenKind.APP);
        return jdbcClient.sql(baseRequestSelect() + """
                        where request.user_id = :userId
                        order by request.created_at desc, request.id desc
                        limit :limit
                        """)
                .param("userId", userId)
                .param("limit", MAX_USER_HISTORY)
                .query(this::mapRequest)
                .list();
    }

    public AccountRightsRequestDetailResponse detailForUser(
            AuthenticatedPrincipal principal,
            Long requestId
    ) {
        Long userId = requirePrincipal(principal, TokenKind.APP);
        AccountRightsRequestResponse request = findRequest(requestId)
                .filter(candidate -> userId.equals(candidate.userId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_UNAVAILABLE));
        return new AccountRightsRequestDetailResponse(request, audits(requestId));
    }

    public AccountRightsRequestResponse withdraw(
            AuthenticatedPrincipal principal,
            Long requestId,
            AccountRightsVersionRequest versionRequest
    ) {
        Long userId = requirePrincipal(principal, TokenKind.APP);
        long expectedVersion = requireVersion(versionRequest == null ? null : versionRequest.version());
        AccountRightsRequestResponse response = transaction.execute(status -> {
            AccountRightsRequestResponse current = requireOwnedRequestForUpdate(requestId, userId);
            if (current.version() != expectedVersion
                    || !AccountRightsRequestStatus.PENDING.name().equals(current.status())) {
                throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
            }
            LocalDateTime now = utcNow();
            int updated = jdbcClient.sql("""
                            update app_user_rights_request
                            set status = 'WITHDRAWN',
                                active_request_key = null,
                                withdrawn_at = :now,
                                version = version + 1,
                                updated_at = :now
                            where id = :id
                              and user_id = :userId
                              and status = 'PENDING'
                              and version = :version
                            """)
                    .param("now", now)
                    .param("id", requestId)
                    .param("userId", userId)
                    .param("version", expectedVersion)
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
            }
            insertAudit(
                    requestId, "WITHDRAWN", "APP_USER", userId,
                    current.status(), AccountRightsRequestStatus.WITHDRAWN.name(),
                    "", "", List.of(), now
            );
            return requireRequest(requestId);
        });
        if (response == null) {
            throw new IllegalStateException("Account-rights withdrawal transaction returned no result");
        }
        return response;
    }

    public PageResult<AccountRightsRequestResponse> pageForAdmin(AdminAccountRightsQuery query) {
        AdminAccountRightsQuery safeQuery = query == null
                ? new AdminAccountRightsQuery(null, null, null, null, null)
                : query;
        long current = safeQuery.pageCurrent();
        long size = safeQuery.pageSize();
        long offset;
        try {
            offset = Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String requestType = normalizeEnumFilter(safeQuery.requestType(), AccountRightsRequestType.class);
        String requestStatus = normalizeEnumFilter(safeQuery.status(), AccountRightsRequestStatus.class);
        Long userId = safeQuery.userId();
        if (userId != null && userId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Long total = jdbcClient.sql("""
                        select count(*)
                        from app_user_rights_request request
                        where (:hasUserId = false or request.user_id = :userId)
                          and (:hasType = false or request.request_type = :requestType)
                          and (:hasStatus = false or request.status = :requestStatus)
                        """)
                .param("hasUserId", userId != null)
                .param("userId", userId, Types.BIGINT)
                .param("hasType", StringUtils.hasText(requestType))
                .param("requestType", requestType)
                .param("hasStatus", StringUtils.hasText(requestStatus))
                .param("requestStatus", requestStatus)
                .query(Long.class)
                .single();

        List<AccountRightsRequestResponse> records = jdbcClient.sql(baseRequestSelect() + """
                        where (:hasUserId = false or request.user_id = :userId)
                          and (:hasType = false or request.request_type = :requestType)
                          and (:hasStatus = false or request.status = :requestStatus)
                        order by request.created_at desc, request.id desc
                        limit :size offset :offset
                        """)
                .param("hasUserId", userId != null)
                .param("userId", userId, Types.BIGINT)
                .param("hasType", StringUtils.hasText(requestType))
                .param("requestType", requestType)
                .param("hasStatus", StringUtils.hasText(requestStatus))
                .param("requestStatus", requestStatus)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapRequest)
                .list();
        return PageResult.of(records, total, current, size);
    }

    public AccountRightsRequestDetailResponse detailForAdmin(Long requestId) {
        AccountRightsRequestResponse request = requireRequest(requestId);
        return new AccountRightsRequestDetailResponse(request, audits(requestId));
    }

    public AccountRightsRequestResponse adminTransition(
            AuthenticatedPrincipal principal,
            Long requestId,
            AccountRightsAdminAction action,
            AdminAccountRightsTransitionRequest transitionRequest
    ) {
        Long adminId = requirePrincipal(principal, TokenKind.ADMIN);
        if (action == null || transitionRequest == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long expectedVersion = requireVersion(transitionRequest.version());
        String reason = requireText(transitionRequest.reason(), 500);
        String retentionExplanation = requireText(transitionRequest.retentionExplanation(), 1000);
        List<String> retainedCategories = normalizeCategories(transitionRequest.retainedDataCategories());

        AccountRightsRequestResponse response = transaction.execute(status -> {
            AccountRightsRequestResponse snapshot = requireRequest(requestId);
            AppUser lockedUser = null;
            AccountRightsRequestType type = AccountRightsRequestType.valueOf(snapshot.requestType());
            if (action == AccountRightsAdminAction.COMPLETE && type.changesStoredIdentity()) {
                lockedUser = appUserService.requireUserForUpdate(snapshot.userId());
            }

            AccountRightsRequestResponse current = requireRequestForUpdate(requestId);
            if (current.version() != expectedVersion) {
                throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
            }
            AccountRightsRequestStatus targetStatus = targetStatus(action, current.status());
            LocalDateTime now = utcNow();

            if (action == AccountRightsAdminAction.COMPLETE && type.changesStoredIdentity()) {
                lockCommerceRows(current.userId());
                assertNoActiveCommerceObligations(current.userId());
                applyIdentityCompletion(lockedUser, type, now);
            }

            String retainedCategoriesValue = encodeCategories(retainedCategories);
            Integer activeKey = isActive(targetStatus) ? ACTIVE_REQUEST_KEY : null;
            int updated = jdbcClient.sql("""
                            update app_user_rights_request
                            set status = :targetStatus,
                                active_request_key = :activeKey,
                                review_reason = :reason,
                                retention_explanation = :retentionExplanation,
                                retained_data_categories = :retainedCategories,
                                reviewed_by = :reviewedBy,
                                reviewed_at = :now,
                                approved_at = case when :targetStatus = 'APPROVED' then :now else approved_at end,
                                rejected_at = case when :targetStatus = 'REJECTED' then :now else rejected_at end,
                                completed_at = case when :targetStatus = 'COMPLETED' then :now else completed_at end,
                                version = version + 1,
                                updated_at = :now
                            where id = :id
                              and status = :currentStatus
                              and version = :version
                            """)
                    .param("targetStatus", targetStatus.name())
                    .param("activeKey", activeKey, Types.SMALLINT)
                    .param("reason", reason)
                    .param("retentionExplanation", retentionExplanation)
                    .param("retainedCategories", retainedCategoriesValue)
                    .param("reviewedBy", adminId)
                    .param("now", now)
                    .param("id", requestId)
                    .param("currentStatus", current.status())
                    .param("version", expectedVersion)
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
            }
            insertAudit(
                    requestId, auditAction(action), "ADMIN", adminId,
                    current.status(), targetStatus.name(), reason, retentionExplanation,
                    retainedCategories, now
            );
            return requireRequest(requestId);
        });
        if (response == null) {
            throw new IllegalStateException("Account-rights admin transaction returned no result");
        }
        return response;
    }

    private String verifyIdentityIfRequired(
            Long userId,
            AccountRightsRequestType requestType,
            String wechatCode
    ) {
        if (requestType != AccountRightsRequestType.ACCOUNT_CANCELLATION) {
            if (StringUtils.hasText(wechatCode)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return null;
        }
        String normalizedCode = requireText(wechatCode, 128);
        AppUser currentUser = appUserService.requireEnabledUser(userId);
        WechatCodeSession codeSession = wechatClient.code2Session(normalizedCode);
        if (codeSession == null || !Objects.equals(currentUser.openid(), codeSession.openid())) {
            throw new BusinessException(ErrorCode.ACCOUNT_CANCELLATION_IDENTITY_MISMATCH);
        }
        return codeSession.openid();
    }

    private void assertNoActiveCommerceObligations(Long userId) {
        if (count("""
                select count(*)
                from shop_order
                where user_id = :userId
                  and status not in ('CLOSED', 'COMPLETED', 'REFUNDED')
                """, userId) > 0
                || count("""
                select count(*)
                from payment_order payment
                join shop_order order_entry on order_entry.id = payment.order_id
                where order_entry.user_id = :userId
                  and payment.status not in ('PAID', 'CLOSED')
                """, userId) > 0
                || count("""
                select count(*)
                from after_sale_request after_sale
                where after_sale.user_id = :userId
                  and after_sale.status not in (
                      'REJECTED', 'RETURN_REJECTED', 'CANCELLED', 'REFUNDED'
                  )
                """, userId) > 0
                || count("""
                select count(*)
                from refund_order refund
                join shop_order order_entry on order_entry.id = refund.order_id
                where order_entry.user_id = :userId
                  and not (
                      refund.status = 'SUCCESS'
                      or (refund.status = 'FAILED' and refund.callback_status = 'CLOSED')
                  )
                """, userId) > 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_CANCELLATION_ACTIVE_OBLIGATIONS);
        }
    }

    private void lockCommerceRows(Long userId) {
        jdbcClient.sql("""
                        select id
                        from shop_order
                        where user_id = :userId
                          and status not in ('CLOSED', 'COMPLETED', 'REFUNDED')
                        order by id
                        for update
                        """)
                .param("userId", userId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select id
                        from after_sale_request
                        where user_id = :userId
                          and status not in (
                              'REJECTED', 'RETURN_REJECTED', 'CANCELLED', 'REFUNDED'
                          )
                        order by id
                        for update
                        """)
                .param("userId", userId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select id
                        from payment_order
                        where order_id in (
                            select id from shop_order where user_id = :userId
                        )
                          and status not in ('PAID', 'CLOSED')
                        order by order_id, id
                        for update
                        """)
                .param("userId", userId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select id
                        from refund_order
                        where order_id in (
                            select id from shop_order where user_id = :userId
                        )
                          and not (
                              status = 'SUCCESS'
                              or (status = 'FAILED' and callback_status = 'CLOSED')
                          )
                        order by order_id, id
                        for update
                        """)
                .param("userId", userId)
                .query(Long.class)
                .list();
    }

    private long count(String sql, Long userId) {
        return jdbcClient.sql(sql)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private void applyIdentityCompletion(
            AppUser user,
            AccountRightsRequestType requestType,
            LocalDateTime completedAt
    ) {
        if (user == null) {
            throw new BusinessException(ErrorCode.APP_USER_UNAVAILABLE);
        }
        if (requestType == AccountRightsRequestType.ACCOUNT_CANCELLATION) {
            if ("CANCELLED".equals(user.status())) {
                throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
            }
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
                              and auth_version = :authVersion
                            """)
                    .param("cancelledOpenid", cancellationPlaceholder())
                    .param("completedAt", completedAt)
                    .param("userId", user.id())
                    .param("authVersion", user.authVersion())
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
            }
            return;
        }

        int updated = jdbcClient.sql("""
                        update app_user
                        set unionid = null,
                            nickname = '',
                            avatar_url = null,
                            phone_number = null,
                            phone_country_code = null,
                            phone_authorized = false,
                            phone_authorized_at = null,
                            auth_version = auth_version + 1,
                            updated_at = :completedAt
                        where id = :userId
                          and auth_version = :authVersion
                        """)
                .param("completedAt", completedAt)
                .param("userId", user.id())
                .param("authVersion", user.authVersion())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
        }
    }

    private AccountRightsRequestStatus targetStatus(AccountRightsAdminAction action, String currentStatus) {
        AccountRightsRequestStatus current;
        try {
            current = AccountRightsRequestStatus.valueOf(currentStatus);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
        }
        if (action == AccountRightsAdminAction.REVIEW
                && current == AccountRightsRequestStatus.PENDING) {
            return AccountRightsRequestStatus.IN_REVIEW;
        }
        if (action == AccountRightsAdminAction.APPROVE
                && current == AccountRightsRequestStatus.IN_REVIEW) {
            return AccountRightsRequestStatus.APPROVED;
        }
        if (action == AccountRightsAdminAction.REJECT
                && (current == AccountRightsRequestStatus.PENDING
                || current == AccountRightsRequestStatus.IN_REVIEW)) {
            return AccountRightsRequestStatus.REJECTED;
        }
        if (action == AccountRightsAdminAction.COMPLETE
                && current == AccountRightsRequestStatus.APPROVED) {
            return AccountRightsRequestStatus.COMPLETED;
        }
        throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_STATE_CONFLICT);
    }

    private boolean isActive(AccountRightsRequestStatus status) {
        return status == AccountRightsRequestStatus.PENDING
                || status == AccountRightsRequestStatus.IN_REVIEW
                || status == AccountRightsRequestStatus.APPROVED;
    }

    private String auditAction(AccountRightsAdminAction action) {
        return switch (action) {
            case REVIEW -> "REVIEWED";
            case APPROVE -> "APPROVED";
            case REJECT -> "REJECTED";
            case COMPLETE -> "COMPLETED";
        };
    }

    private void insertAudit(
            Long requestId,
            String action,
            String actorType,
            Long actorId,
            String fromStatus,
            String toStatus,
            String reason,
            String retentionExplanation,
            List<String> retainedCategories,
            LocalDateTime createdAt
    ) {
        jdbcClient.sql("""
                        insert into app_user_rights_request_audit (
                            id, request_id, action, actor_type, actor_id,
                            from_status, to_status, reason, retention_explanation,
                            retained_data_categories, created_at
                        ) values (
                            :id, :requestId, :action, :actorType, :actorId,
                            :fromStatus, :toStatus, :reason, :retentionExplanation,
                            :retainedCategories, :createdAt
                        )
                        """)
                .param("id", IdWorker.getId())
                .param("requestId", requestId)
                .param("action", action)
                .param("actorType", actorType)
                .param("actorId", actorId, Types.BIGINT)
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("reason", reason)
                .param("retentionExplanation", retentionExplanation)
                .param("retainedCategories", encodeCategories(retainedCategories))
                .param("createdAt", createdAt)
                .update();
    }

    private boolean hasActiveRequest(Long userId) {
        return jdbcClient.sql("""
                        select count(*)
                        from app_user_rights_request
                        where user_id = :userId
                          and active_request_key = 1
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single() > 0;
    }

    private AccountRightsRequestResponse requireOwnedRequestForUpdate(Long requestId, Long userId) {
        Long lockedRequestId = jdbcClient.sql("""
                        select id
                        from app_user_rights_request
                        where id = :id and user_id = :userId
                        for update
                        """)
                .param("id", requirePositiveId(requestId))
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_UNAVAILABLE));
        return requireRequest(lockedRequestId);
    }

    private AccountRightsRequestResponse requireRequestForUpdate(Long requestId) {
        Long lockedRequestId = jdbcClient.sql("""
                        select id
                        from app_user_rights_request
                        where id = :id
                        for update
                        """)
                .param("id", requirePositiveId(requestId))
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_UNAVAILABLE));
        return requireRequest(lockedRequestId);
    }

    private AccountRightsRequestResponse requireRequest(Long requestId) {
        return findRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_UNAVAILABLE));
    }

    private java.util.Optional<AccountRightsRequestResponse> findRequest(Long requestId) {
        return jdbcClient.sql(baseRequestSelect() + "where request.id = :id")
                .param("id", requirePositiveId(requestId))
                .query(this::mapRequest)
                .optional();
    }

    private List<AccountRightsAuditResponse> audits(Long requestId) {
        return jdbcClient.sql("""
                        select id, action, actor_type, actor_id, from_status, to_status,
                               reason, retention_explanation, retained_data_categories, created_at
                        from app_user_rights_request_audit
                        where request_id = :requestId
                        order by created_at, id
                        """)
                .param("requestId", requestId)
                .query(this::mapAudit)
                .list();
    }

    private String baseRequestSelect() {
        return """
                select request.id,
                       request.user_id,
                       app_user.nickname as user_nickname,
                       app_user.status as user_status,
                       request.request_type,
                       request.status,
                       request.request_note,
                       request.identity_verified_at,
                       request.review_reason,
                       request.retention_explanation,
                       request.retained_data_categories,
                       request.reviewed_by,
                       request.reviewed_at,
                       request.approved_at,
                       request.rejected_at,
                       request.withdrawn_at,
                       request.completed_at,
                       request.version,
                       request.created_at,
                       request.updated_at
                from app_user_rights_request request
                join app_user on app_user.id = request.user_id
                """;
    }

    private AccountRightsRequestResponse mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRightsRequestResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("user_nickname"),
                rs.getString("user_status"),
                rs.getString("request_type"),
                rs.getString("status"),
                rs.getString("request_note"),
                rs.getObject("identity_verified_at", LocalDateTime.class),
                rs.getString("review_reason"),
                rs.getString("retention_explanation"),
                decodeCategories(rs.getString("retained_data_categories")),
                rs.getObject("reviewed_by", Long.class),
                rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("rejected_at", LocalDateTime.class),
                rs.getObject("withdrawn_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private AccountRightsAuditResponse mapAudit(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRightsAuditResponse(
                rs.getLong("id"),
                rs.getString("action"),
                rs.getString("actor_type"),
                rs.getObject("actor_id", Long.class),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("reason"),
                rs.getString("retention_explanation"),
                decodeCategories(rs.getString("retained_data_categories")),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private List<String> normalizeCategories(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = requireText(value, 64);
            boolean invalid = item.indexOf(',') >= 0 || item.codePoints().anyMatch(Character::isISOControl);
            if (invalid) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            normalized.add(item);
        }
        if (normalized.size() > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return List.copyOf(normalized);
    }

    private String encodeCategories(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private List<String> decodeCategories(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        List<String> categories = new ArrayList<>();
        for (String item : value.split(",")) {
            if (StringUtils.hasText(item)) {
                categories.add(item.trim());
            }
        }
        return List.copyOf(categories);
    }

    private <E extends Enum<E>> String normalizeEnumFilter(String value, Class<E> type) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            Enum.valueOf(type, normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Long requirePrincipal(AuthenticatedPrincipal principal, TokenKind kind) {
        if (principal == null || principal.kind() != kind || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private long requireVersion(Long version) {
        if (version == null || version < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return version;
    }

    private Long requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_RIGHTS_REQUEST_UNAVAILABLE);
        }
        return id;
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

    private String normalizeOptionalText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return requireText(value, maxLength);
    }

    private String cancellationPlaceholder() {
        return "cancelled_" + UUID.randomUUID().toString().replace("-", "");
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }
}
