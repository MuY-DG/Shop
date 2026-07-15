package org.muybaby.shopserver.coupon.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.dto.AdminCouponClaimQueryRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponClaimResponse;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateQueryRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class CouponReadMapper {

    private static final String PUBLIC_DISTRIBUTION_MODE = "PUBLIC";
    private static final String DIRECT_DISTRIBUTION_MODE = "DIRECT";
    private static final Set<String> DISTRIBUTION_MODES = Set.of(
            PUBLIC_DISTRIBUTION_MODE,
            DIRECT_DISTRIBUTION_MODE
    );
    private static final Set<String> ISSUE_SOURCES = Set.of(
            "SELF_CLAIM",
            "ADMIN_ISSUE",
            "ADMIN_DIRECT"
    );
    private static final Set<String> USER_COUPON_STATUSES = Set.of(
            "CLAIMED",
            "LOCKED",
            "USED",
            "EXPIRED"
    );

    private final JdbcClient jdbcClient;

    public CouponReadMapper(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<AdminCouponTemplateResponse> adminTemplatePage(AdminCouponTemplateQueryRequest query) {
        AdminCouponTemplateQueryRequest normalizedQuery = query == null
                ? new AdminCouponTemplateQueryRequest(null, null, null, null, null)
                : query;
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;

        String nameLike = StringUtils.hasText(normalizedQuery.name()) ? "%" + normalizedQuery.name().trim() + "%" : null;
        String status = StringUtils.hasText(normalizedQuery.status()) ? normalizedQuery.status().trim() : null;
        String distributionMode = normalizeOptionalFilter(
                normalizedQuery.distributionMode(),
                DISTRIBUTION_MODES
        );

        Long total = jdbcClient.sql("""
                        select count(*)
                        from coupon_template t
                        where (:distributionMode = '' or t.distribution_mode = :distributionMode)
                          and (:nameLike is null or t.name like :nameLike)
                          and (:status is null or t.status = :status)
                        """)
                .param("nameLike", nameLike)
                .param("status", status)
                .param("distributionMode", distributionMode)
                .query(Long.class)
                .single();

        List<AdminCouponTemplateResponse> records = jdbcClient.sql("""
                        select t.id, t.name, t.description, t.coupon_type, t.discount_type,
                               t.threshold_cent, t.discount_cent, t.scope_type, t.scope_value,
                               t.strategy_key, t.total_stock, t.claimed_count,
                               greatest(t.total_stock - t.claimed_count, 0) as stock_remaining,
                               t.per_user_limit, t.valid_start_at, t.valid_end_at, t.status,
                               t.sort_order, t.created_at, t.updated_at,
                               t.distribution_mode, t.audience_user_id,
                               u.nickname as audience_nickname,
                               u.phone_number as audience_phone_number
                        from coupon_template t
                        left join app_user u on u.id = t.audience_user_id
                        where (:distributionMode = '' or t.distribution_mode = :distributionMode)
                          and (:nameLike is null or t.name like :nameLike)
                          and (:status is null or t.status = :status)
                        order by t.sort_order asc, t.id desc
                        limit :limit offset :offset
                        """)
                .param("nameLike", nameLike)
                .param("status", status)
                .param("distributionMode", distributionMode)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAdminCouponTemplate)
                .list();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    public PageResult<AdminCouponClaimResponse> adminClaimPage(AdminCouponClaimQueryRequest query) {
        AdminCouponClaimQueryRequest normalizedQuery = query == null
                ? new AdminCouponClaimQueryRequest(null, null, null, null, null, null, null)
                : query;
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;
        String templateNameLike = likePattern(normalizedQuery.templateName());
        String userKeyword = normalizeText(normalizedQuery.userKeyword());
        String userKeywordLike = "%" + userKeyword + "%";
        Long userKeywordId = parsePositiveLong(userKeyword);
        String distributionMode = normalizeOptionalFilter(
                normalizedQuery.distributionMode(),
                DISTRIBUTION_MODES
        );
        String issueSource = normalizeOptionalFilter(normalizedQuery.issueSource(), ISSUE_SOURCES);
        String status = normalizeOptionalFilter(normalizedQuery.status(), USER_COUPON_STATUSES);

        Long total = jdbcClient.sql("""
                        select count(*)
                        from coupon_claim_record cr
                        join coupon_template t on t.id = cr.template_id
                        join user_coupon uc on uc.id = cr.user_coupon_id
                        join app_user u on u.id = cr.user_id
                        where (:templateNameLike is null or lower(t.name) like lower(:templateNameLike))
                          and (
                              :userKeyword = ''
                              or lower(u.nickname) like lower(:userKeywordLike)
                              or u.phone_number like :userKeywordLike
                              or (:userKeywordId is not null and u.id = :userKeywordId)
                          )
                          and (:distributionMode = '' or t.distribution_mode = :distributionMode)
                          and (:issueSource = '' or cr.issue_source = :issueSource)
                          and (:status = '' or uc.status = :status)
                        """)
                .param("templateNameLike", templateNameLike)
                .param("userKeyword", userKeyword)
                .param("userKeywordLike", userKeywordLike)
                .param("userKeywordId", userKeywordId)
                .param("distributionMode", distributionMode)
                .param("issueSource", issueSource)
                .param("status", status)
                .query(Long.class)
                .single();

        List<AdminCouponClaimResponse> records = jdbcClient.sql("""
                        select cr.id, cr.template_id, t.name as template_name,
                               t.distribution_mode,
                               cr.user_id, u.nickname as user_nickname,
                               u.phone_number as user_phone_number,
                               cr.user_coupon_id, uc.coupon_type, uc.discount_type,
                               uc.threshold_cent, uc.discount_cent, uc.scope_type, uc.scope_value,
                               uc.status as user_coupon_status,
                               uc.valid_start_at, uc.valid_end_at,
                               uc.used_order_id, uc.used_at,
                               cr.issue_source, cr.issued_by_admin_user_id,
                               au.display_name as operator_display_name,
                               cr.issue_note, cr.claimed_at
                        from coupon_claim_record cr
                        join coupon_template t on t.id = cr.template_id
                        join user_coupon uc on uc.id = cr.user_coupon_id
                        join app_user u on u.id = cr.user_id
                        left join admin_user au on au.id = cr.issued_by_admin_user_id
                        where (:templateNameLike is null or lower(t.name) like lower(:templateNameLike))
                          and (
                              :userKeyword = ''
                              or lower(u.nickname) like lower(:userKeywordLike)
                              or u.phone_number like :userKeywordLike
                              or (:userKeywordId is not null and u.id = :userKeywordId)
                          )
                          and (:distributionMode = '' or t.distribution_mode = :distributionMode)
                          and (:issueSource = '' or cr.issue_source = :issueSource)
                          and (:status = '' or uc.status = :status)
                        order by cr.claimed_at desc, cr.id desc
                        limit :limit offset :offset
                        """)
                .param("templateNameLike", templateNameLike)
                .param("userKeyword", userKeyword)
                .param("userKeywordLike", userKeywordLike)
                .param("userKeywordId", userKeywordId)
                .param("distributionMode", distributionMode)
                .param("issueSource", issueSource)
                .param("status", status)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAdminCouponClaim)
                .list();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    private AdminCouponTemplateResponse mapAdminCouponTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new AdminCouponTemplateResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getString("strategy_key"),
                rs.getInt("total_stock"),
                rs.getInt("claimed_count"),
                rs.getInt("stock_remaining"),
                rs.getInt("per_user_limit"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getString("distribution_mode"),
                rs.getObject("audience_user_id", Long.class),
                rs.getString("audience_nickname"),
                rs.getString("audience_phone_number")
        );
    }

    private AdminCouponClaimResponse mapAdminCouponClaim(ResultSet rs, int rowNum) throws SQLException {
        return new AdminCouponClaimResponse(
                rs.getLong("id"),
                rs.getLong("template_id"),
                rs.getString("template_name"),
                rs.getString("distribution_mode"),
                rs.getLong("user_id"),
                rs.getString("user_nickname"),
                rs.getString("user_phone_number"),
                rs.getLong("user_coupon_id"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getString("user_coupon_status"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class),
                rs.getObject("used_order_id", Long.class),
                rs.getObject("used_at", LocalDateTime.class),
                rs.getString("issue_source"),
                rs.getObject("issued_by_admin_user_id", Long.class),
                rs.getString("operator_display_name"),
                rs.getString("issue_note"),
                rs.getObject("claimed_at", LocalDateTime.class)
        );
    }

    private String likePattern(String value) {
        String normalized = normalizeText(value);
        return normalized.isEmpty() ? null : "%" + normalized + "%";
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String normalizeOptionalFilter(String value, Set<String> allowedValues) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase();
        if (!allowedValues.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private Long parsePositiveLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
