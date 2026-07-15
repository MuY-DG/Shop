package org.muybaby.shopserver.user.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.CouponScopeType;
import org.muybaby.shopserver.coupon.CouponTemplateStatus;
import org.muybaby.shopserver.coupon.CouponType;
import org.muybaby.shopserver.coupon.DiscountType;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.user.dto.AdminCouponIssueRequest;
import org.muybaby.shopserver.user.dto.AdminCouponIssueResponse;
import org.muybaby.shopserver.user.dto.AdminCustomerQueryRequest;
import org.muybaby.shopserver.user.dto.AdminCustomerResponse;
import org.muybaby.shopserver.user.dto.AdminDirectCouponIssueRequest;
import org.muybaby.shopserver.user.dto.AdminIssuableCouponTemplateResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class AdminCustomerService {

    private static final String ENABLED_USER_STATUS = "ENABLED";
    private static final String PUBLIC_DISTRIBUTION_MODE = "PUBLIC";
    private static final String DIRECT_DISTRIBUTION_MODE = "DIRECT";
    private static final String ADMIN_ISSUE_SOURCE = "ADMIN_ISSUE";
    private static final String ADMIN_DIRECT_SOURCE = "ADMIN_DIRECT";
    private static final String DEFAULT_STRATEGY_KEY = "coupon.amount-off.v1";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public AdminCustomerService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public PageResult<AdminCustomerResponse> page(AdminCustomerQueryRequest query) {
        AdminCustomerQueryRequest normalized = query == null
                ? new AdminCustomerQueryRequest(null, null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        String keyword = normalizeKeyword(normalized.keyword());
        String keywordPattern = "%" + keyword + "%";
        Long keywordUserId = parseUserId(keyword);
        String status = normalizeStatus(normalized.status());
        LocalDateTime now = LocalDateTime.now();

        Long total = jdbcClient.sql("""
                        select count(*)
                        from app_user u
                        where (
                            :keyword = ''
                            or lower(u.nickname) like lower(:keywordPattern)
                            or u.phone_number like :keywordPattern
                            or (:keywordUserId is not null and u.id = :keywordUserId)
                        )
                          and (:status = '' or u.status = :status)
                        """)
                .param("keyword", keyword)
                .param("keywordPattern", keywordPattern)
                .param("keywordUserId", keywordUserId)
                .param("status", status)
                .query(Long.class)
                .single();

        List<AdminCustomerResponse> records = jdbcClient.sql("""
                        select u.id, u.nickname, u.phone_number, u.phone_authorized, u.status,
                               u.last_login_at, u.created_at, u.updated_at,
                               (select count(*)
                                from user_coupon uc
                                where uc.user_id = u.id) as coupon_total_count,
                               (select count(*)
                                from user_coupon uc
                                where uc.user_id = u.id
                                  and uc.status = :claimedStatus
                                  and uc.valid_start_at <= :now
                                  and uc.valid_end_at >= :now) as coupon_available_count,
                               (select count(*)
                                from user_coupon uc
                                where uc.user_id = u.id
                                  and uc.status = :usedStatus) as coupon_used_count
                        from app_user u
                        where (
                            :keyword = ''
                            or lower(u.nickname) like lower(:keywordPattern)
                            or u.phone_number like :keywordPattern
                            or (:keywordUserId is not null and u.id = :keywordUserId)
                        )
                          and (:status = '' or u.status = :status)
                        order by u.created_at desc, u.id desc
                        limit :size offset :offset
                        """)
                .param("keyword", keyword)
                .param("keywordPattern", keywordPattern)
                .param("keywordUserId", keywordUserId)
                .param("status", status)
                .param("claimedStatus", UserCouponStatus.CLAIMED.name())
                .param("usedStatus", UserCouponStatus.USED.name())
                .param("now", now)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapCustomer)
                .list();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    public List<AdminIssuableCouponTemplateResponse> issuableCouponTemplates(Long userId) {
        requireEnabledCustomer(userId, false);
        LocalDateTime now = LocalDateTime.now();
        return jdbcClient.sql("""
                        select t.id, t.name, t.description, t.coupon_type, t.discount_type,
                               t.threshold_cent, t.discount_cent, t.scope_type, t.scope_value,
                               greatest(t.total_stock - t.claimed_count, 0) as stock_remaining,
                               t.per_user_limit,
                               (select count(*)
                                from user_coupon uc
                                where uc.user_id = :userId
                                  and uc.template_id = t.id) as user_claim_count,
                               t.valid_start_at, t.valid_end_at
                        from coupon_template t
                        where t.status = :enabledStatus
                          and t.distribution_mode = :distributionMode
                          and t.valid_start_at <= :now
                          and t.valid_end_at >= :now
                          and t.claimed_count < t.total_stock
                          and (select count(*)
                               from user_coupon uc
                               where uc.user_id = :userId
                                 and uc.template_id = t.id) < t.per_user_limit
                          and (
                              (t.scope_type = :allScope and t.scope_value = '')
                              or (
                                  t.scope_type = :productScope
                                  and exists (
                                      select 1
                                      from product_spu_coupon pc
                                      join product_spu s on s.id = pc.spu_id
                                      where pc.coupon_template_id = t.id
                                        and s.id = cast(t.scope_value as decimal(19, 0))
                                        and s.deleted_at is null
                                        and s.purged_at is null
                                  )
                              )
                          )
                        order by t.sort_order asc, t.valid_end_at asc, t.id desc
                        """)
                .param("userId", userId)
                .param("enabledStatus", CouponTemplateStatus.ENABLED.name())
                .param("distributionMode", PUBLIC_DISTRIBUTION_MODE)
                .param("now", now)
                .param("allScope", CouponScopeType.ALL.name())
                .param("productScope", CouponScopeType.PRODUCT.name())
                .query(this::mapIssuableTemplate)
                .list();
    }

    @Transactional
    public AdminCouponIssueResponse issueCoupon(
            Long operatorAdminUserId,
            Long userId,
            AdminCouponIssueRequest request
    ) {
        requireEnabledCustomer(userId, true);
        LocalDateTime issuedAt = LocalDateTime.now();
        CouponTemplateRow preview = findActiveTemplate(request.templateId(), issuedAt, false)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));
        validateScopeAndLockProduct(preview);
        CouponTemplateRow template = findActiveTemplate(request.templateId(), issuedAt, true)
                .filter(locked -> Objects.equals(locked.scopeType(), preview.scopeType())
                        && Objects.equals(locked.scopeValue(), preview.scopeValue()))
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));

        Integer userClaimCount = jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where user_id = :userId
                          and template_id = :templateId
                        """)
                .param("userId", userId)
                .param("templateId", template.id())
                .query(Integer.class)
                .single();
        if (userClaimCount != null && userClaimCount >= template.perUserLimit()) {
            throw new BusinessException(ErrorCode.COUPON_CLAIM_LIMIT_REACHED);
        }

        Long userCouponId = insertUserCoupon(userId, template, issuedAt);
        int stockUpdated = jdbcClient.sql("""
                        update coupon_template
                        set claimed_count = claimed_count + 1,
                            updated_at = :issuedAt
                        where id = :templateId
                          and claimed_count < total_stock
                        """)
                .param("issuedAt", issuedAt)
                .param("templateId", template.id())
                .update();
        if (stockUpdated != 1) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }

        jdbcClient.sql("""
                        insert into coupon_claim_record
                            (template_id, user_id, user_coupon_id, claimed_at,
                             issue_source, issued_by_admin_user_id, issue_note)
                        values
                            (:templateId, :userId, :userCouponId, :issuedAt,
                             :issueSource, :operatorAdminUserId, :issueNote)
                        """)
                .param("templateId", template.id())
                .param("userId", userId)
                .param("userCouponId", userCouponId)
                .param("issuedAt", issuedAt)
                .param("issueSource", ADMIN_ISSUE_SOURCE)
                .param("operatorAdminUserId", operatorAdminUserId)
                .param("issueNote", normalizeNote(request.note()))
                .update();

        return new AdminCouponIssueResponse(
                userCouponId,
                template.id(),
                template.name(),
                UserCouponStatus.CLAIMED.name(),
                template.validStartAt(),
                template.validEndAt(),
                issuedAt
        );
    }

    @Transactional
    public AdminCouponIssueResponse createDirectCoupon(
            Long operatorAdminUserId,
            Long userId,
            AdminDirectCouponIssueRequest request
    ) {
        requireEnabledCustomer(userId, true);
        LocalDateTime issuedAt = LocalDateTime.now();
        DirectCouponDefinition coupon = validateDirectCoupon(request, issuedAt);
        Long templateId = insertDirectTemplate(userId, coupon);
        CouponTemplateRow template = new CouponTemplateRow(
                templateId,
                coupon.name(),
                coupon.couponType().name(),
                DiscountType.AMOUNT_OFF.name(),
                coupon.thresholdCent(),
                coupon.discountCent(),
                CouponScopeType.ALL.name(),
                "",
                1,
                coupon.validStartAt(),
                coupon.validEndAt()
        );
        Long userCouponId = insertUserCoupon(userId, template, issuedAt);
        insertClaimAudit(
                templateId,
                userId,
                userCouponId,
                issuedAt,
                ADMIN_DIRECT_SOURCE,
                operatorAdminUserId,
                coupon.note()
        );

        return new AdminCouponIssueResponse(
                userCouponId,
                templateId,
                coupon.name(),
                UserCouponStatus.CLAIMED.name(),
                coupon.validStartAt(),
                coupon.validEndAt(),
                issuedAt
        );
    }

    private void requireEnabledCustomer(Long userId, boolean lock) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.APP_USER_UNAVAILABLE);
        }
        String lockClause = lock ? " for update" : "";
        boolean exists = jdbcClient.sql("""
                        select id
                        from app_user
                        where id = :userId
                          and status = :status
                        """ + lockClause)
                .param("userId", userId)
                .param("status", ENABLED_USER_STATUS)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new BusinessException(ErrorCode.APP_USER_UNAVAILABLE);
        }
    }

    private Optional<CouponTemplateRow> findActiveTemplate(
            Long templateId,
            LocalDateTime now,
            boolean lock
    ) {
        if (templateId == null || templateId <= 0) {
            return Optional.empty();
        }
        String lockClause = lock ? " for update" : "";
        return jdbcClient.sql("""
                        select id, name, coupon_type, discount_type, threshold_cent, discount_cent,
                               scope_type, scope_value, per_user_limit, valid_start_at, valid_end_at
                        from coupon_template
                        where id = :templateId
                          and distribution_mode = :distributionMode
                          and status = :status
                          and valid_start_at <= :now
                          and valid_end_at >= :now
                          and claimed_count < total_stock
                        """ + lockClause)
                .param("templateId", templateId)
                .param("distributionMode", PUBLIC_DISTRIBUTION_MODE)
                .param("status", CouponTemplateStatus.ENABLED.name())
                .param("now", now)
                .query(this::mapTemplateRow)
                .optional();
    }

    private DirectCouponDefinition validateDirectCoupon(
            AdminDirectCouponIssueRequest request,
            LocalDateTime issuedAt
    ) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String name = request.name() == null ? "" : request.name().trim();
        String description = request.description() == null ? "" : request.description().trim();
        String note = normalizeNote(request.note());
        if (!StringUtils.hasText(name) || name.length() > 80
                || description.length() > 255 || note.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        CouponType couponType;
        try {
            couponType = CouponType.valueOf(request.couponType());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Long thresholdCent = request.thresholdCent();
        Long discountCent = request.discountCent();
        if (thresholdCent == null || thresholdCent < 0L || discountCent == null || discountCent <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (couponType == CouponType.NO_THRESHOLD && thresholdCent != 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (couponType == CouponType.MIN_SPEND
                && (thresholdCent <= 0L || discountCent >= thresholdCent)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        LocalDateTime validStartAt = request.validStartAt();
        LocalDateTime validEndAt = request.validEndAt();
        if (validStartAt == null || validEndAt == null
                || !validStartAt.isBefore(validEndAt) || !validEndAt.isAfter(issuedAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new DirectCouponDefinition(
                name,
                description,
                couponType,
                thresholdCent,
                discountCent,
                validStartAt,
                validEndAt,
                note
        );
    }

    private Long insertDirectTemplate(Long userId, DirectCouponDefinition coupon) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into coupon_template (
                            name, description, coupon_type, discount_type,
                            threshold_cent, discount_cent, scope_type, scope_value,
                            strategy_key, total_stock, claimed_count, per_user_limit,
                            valid_start_at, valid_end_at, status, sort_order,
                            distribution_mode, audience_user_id
                        )
                        values (
                            :name, :description, :couponType, :discountType,
                            :thresholdCent, :discountCent, :scopeType, '',
                            :strategyKey, 1, 1, 1,
                            :validStartAt, :validEndAt, :status, 0,
                            :distributionMode, :audienceUserId
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("name", coupon.name())
                        .addValue("description", coupon.description())
                        .addValue("couponType", coupon.couponType().name())
                        .addValue("discountType", DiscountType.AMOUNT_OFF.name())
                        .addValue("thresholdCent", coupon.thresholdCent())
                        .addValue("discountCent", coupon.discountCent())
                        .addValue("scopeType", CouponScopeType.ALL.name())
                        .addValue("strategyKey", DEFAULT_STRATEGY_KEY)
                        .addValue("validStartAt", coupon.validStartAt())
                        .addValue("validEndAt", coupon.validEndAt())
                        .addValue("status", CouponTemplateStatus.DISABLED.name())
                        .addValue("distributionMode", DIRECT_DISTRIBUTION_MODE)
                        .addValue("audienceUserId", userId),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void insertClaimAudit(
            Long templateId,
            Long userId,
            Long userCouponId,
            LocalDateTime issuedAt,
            String issueSource,
            Long operatorAdminUserId,
            String issueNote
    ) {
        jdbcClient.sql("""
                        insert into coupon_claim_record
                            (template_id, user_id, user_coupon_id, claimed_at,
                             issue_source, issued_by_admin_user_id, issue_note)
                        values
                            (:templateId, :userId, :userCouponId, :issuedAt,
                             :issueSource, :operatorAdminUserId, :issueNote)
                        """)
                .param("templateId", templateId)
                .param("userId", userId)
                .param("userCouponId", userCouponId)
                .param("issuedAt", issuedAt)
                .param("issueSource", issueSource)
                .param("operatorAdminUserId", operatorAdminUserId)
                .param("issueNote", issueNote)
                .update();
    }

    private void validateScopeAndLockProduct(CouponTemplateRow template) {
        if (CouponScopeType.ALL.name().equals(template.scopeType())) {
            if (!StringUtils.hasText(template.scopeValue())) {
                return;
            }
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
        if (!CouponScopeType.PRODUCT.name().equals(template.scopeType())
                || !StringUtils.hasText(template.scopeValue())) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }

        long spuId;
        try {
            spuId = Long.parseLong(template.scopeValue().trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
        boolean activeProduct = jdbcClient.sql("""
                        select id
                        from product_spu
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        for update
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .optional()
                .isPresent();
        Integer bindingCount = jdbcClient.sql("""
                        select count(*)
                        from product_spu_coupon
                        where spu_id = :spuId
                          and coupon_template_id = :templateId
                        """)
                .param("spuId", spuId)
                .param("templateId", template.id())
                .query(Integer.class)
                .single();
        if (spuId <= 0 || !activeProduct || bindingCount == null || bindingCount != 1) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
    }

    private Long insertUserCoupon(Long userId, CouponTemplateRow template, LocalDateTime issuedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into user_coupon (
                            user_id, template_id, template_name, coupon_type, discount_type,
                            threshold_cent, discount_cent, scope_type, scope_value,
                            valid_start_at, valid_end_at, status, claimed_at
                        )
                        values (
                            :userId, :templateId, :templateName, :couponType, :discountType,
                            :thresholdCent, :discountCent, :scopeType, :scopeValue,
                            :validStartAt, :validEndAt, :status, :issuedAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("templateId", template.id())
                        .addValue("templateName", template.name())
                        .addValue("couponType", template.couponType())
                        .addValue("discountType", template.discountType())
                        .addValue("thresholdCent", template.thresholdCent())
                        .addValue("discountCent", template.discountCent())
                        .addValue("scopeType", template.scopeType())
                        .addValue("scopeValue", template.scopeValue())
                        .addValue("validStartAt", template.validStartAt())
                        .addValue("validEndAt", template.validEndAt())
                        .addValue("status", UserCouponStatus.CLAIMED.name())
                        .addValue("issuedAt", issuedAt),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private AdminCustomerResponse mapCustomer(ResultSet rs, int rowNum) throws SQLException {
        return new AdminCustomerResponse(
                rs.getLong("id"),
                rs.getString("nickname"),
                rs.getString("phone_number"),
                rs.getBoolean("phone_authorized"),
                rs.getString("status"),
                rs.getInt("coupon_total_count"),
                rs.getInt("coupon_available_count"),
                rs.getInt("coupon_used_count"),
                rs.getObject("last_login_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private AdminIssuableCouponTemplateResponse mapIssuableTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new AdminIssuableCouponTemplateResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getInt("stock_remaining"),
                rs.getInt("per_user_limit"),
                rs.getInt("user_claim_count"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class)
        );
    }

    private CouponTemplateRow mapTemplateRow(ResultSet rs, int rowNum) throws SQLException {
        return new CouponTemplateRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getInt("per_user_limit"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class)
        );
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return "";
        }
        String normalized = keyword.trim();
        if (normalized.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private Long parseUserId(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        try {
            long userId = Long.parseLong(keyword);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "";
        }
        String normalized = status.trim().toUpperCase();
        if (!"ENABLED".equals(normalized) && !"DISABLED".equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String normalizeNote(String note) {
        return StringUtils.hasText(note) ? note.trim() : "";
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Failed to retrieve generated key");
    }

    private record CouponTemplateRow(
            Long id,
            String name,
            String couponType,
            String discountType,
            Long thresholdCent,
            Long discountCent,
            String scopeType,
            String scopeValue,
            Integer perUserLimit,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt
    ) {
    }

    private record DirectCouponDefinition(
            String name,
            String description,
            CouponType couponType,
            Long thresholdCent,
            Long discountCent,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt,
            String note
    ) {
    }
}
