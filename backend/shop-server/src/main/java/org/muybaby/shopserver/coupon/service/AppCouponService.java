package org.muybaby.shopserver.coupon.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.CouponScopeType;
import org.muybaby.shopserver.coupon.CouponTemplateStatus;
import org.muybaby.shopserver.coupon.DiscountType;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.coupon.dto.AppClaimableCouponResponse;
import org.muybaby.shopserver.coupon.dto.AppUserCouponResponse;
import org.muybaby.shopserver.coupon.dto.AvailableCouponItemResponse;
import org.muybaby.shopserver.coupon.dto.AvailableCouponRequest;
import org.muybaby.shopserver.coupon.dto.AvailableCouponResponse;
import org.muybaby.shopserver.promotion.CheckoutContext;
import org.muybaby.shopserver.promotion.CheckoutItem;
import org.muybaby.shopserver.promotion.CouponCandidate;
import org.muybaby.shopserver.promotion.CouponDiscountCalculator;
import org.muybaby.shopserver.promotion.DiscountResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AppCouponService {

    private static final String CATEGORY_ENABLED = "ENABLED";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final CouponDiscountCalculator couponDiscountCalculator = new CouponDiscountCalculator();

    public AppCouponService(JdbcClient jdbcClient, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public List<AppClaimableCouponResponse> claimable(AuthenticatedPrincipal principal) {
        Long userId = requireAppUser(principal);
        LocalDateTime now = LocalDateTime.now();
        return jdbcClient.sql("""
                        select t.id,
                               t.name,
                               t.description,
                               t.coupon_type,
                               t.threshold_cent,
                               t.discount_cent,
                               t.valid_start_at,
                               t.valid_end_at,
                               t.total_stock,
                               t.claimed_count,
                               t.per_user_limit,
                               (select count(*) from user_coupon uc where uc.user_id = :userId and uc.template_id = t.id) as user_claim_count
                        from coupon_template t
                        where t.status = :status
                          and t.valid_start_at <= :now
                          and t.valid_end_at >= :now
                        order by
                          case
                            when t.claimed_count < t.total_stock
                             and (select count(*) from user_coupon uc where uc.user_id = :userId and uc.template_id = t.id) < t.per_user_limit
                            then 0 else 1
                          end,
                          t.sort_order asc,
                          t.id desc
                        """)
                .param("userId", userId)
                .param("status", CouponTemplateStatus.ENABLED.name())
                .param("now", now)
                .query(this::mapClaimableCoupon)
                .list();
    }

    @Transactional
    public AppUserCouponResponse claim(AuthenticatedPrincipal principal, Long templateId) {
        Long userId = requireAppUser(principal);
        CouponTemplateRow template = findActiveTemplateForUpdate(templateId, LocalDateTime.now())
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));

        Integer userClaimCount = jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where user_id = :userId
                          and template_id = :templateId
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
        if (userClaimCount != null && userClaimCount >= template.perUserLimit()) {
            throw new BusinessException(ErrorCode.COUPON_CLAIM_LIMIT_REACHED);
        }

        Long userCouponId = insertUserCoupon(userId, template);
        int updatedRows = jdbcClient.sql("""
                        update coupon_template
                        set claimed_count = claimed_count + 1,
                            updated_at = :updatedAt
                        where id = :templateId
                        """)
                .param("updatedAt", LocalDateTime.now())
                .param("templateId", templateId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }

        jdbcClient.sql("""
                        insert into coupon_claim_record (template_id, user_id, user_coupon_id, claimed_at)
                        values (:templateId, :userId, :userCouponId, :claimedAt)
                        """)
                .param("templateId", templateId)
                .param("userId", userId)
                .param("userCouponId", userCouponId)
                .param("claimedAt", LocalDateTime.now())
                .update();

        return findUserCoupon(userId, userCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));
    }

    public List<AppUserCouponResponse> mine(AuthenticatedPrincipal principal, String status) {
        Long userId = requireAppUser(principal);
        String normalizedStatus = normalizeStatus(status);
        return jdbcClient.sql("""
                        select id, template_id, template_name, coupon_type, threshold_cent, discount_cent,
                               scope_type, status, valid_start_at, valid_end_at, claimed_at
                        from user_coupon
                        where user_id = :userId
                          and (:status is null or status = :status)
                        order by claimed_at desc, id desc
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .query(this::mapAppUserCoupon)
                .list();
    }

    public AvailableCouponResponse available(AuthenticatedPrincipal principal, AvailableCouponRequest request) {
        Long userId = requireAppUser(principal);
        List<Long> cartItemIds = request == null || request.cartItemIds() == null ? List.of() : request.cartItemIds();
        List<CartCouponRow> cartRows = findEligibleCartRows(userId, cartItemIds);
        CheckoutContext context = new CheckoutContext(
                userId,
                cartRows.stream()
                        .map(row -> new CheckoutItem(row.skuId(), row.spuId(), row.lineAmountCent(), row.quantity()))
                        .toList()
        );
        long cartAmountCent = context.totalAmountCent();

        List<AvailableCouponCandidate> evaluated = findAvailableUserCoupons(userId, LocalDateTime.now()).stream()
                .map(candidate -> new AvailableCouponCandidate(candidate, couponDiscountCalculator.calculate(context, candidate.toPromotionCandidate())))
                .sorted(Comparator
                        .comparing((AvailableCouponCandidate value) -> Boolean.FALSE.equals(value.discountResult().available()))
                        .thenComparing((AvailableCouponCandidate value) -> value.discountResult().discountAmountCent(), Comparator.reverseOrder())
                        .thenComparing(value -> value.userCoupon().validEndAt())
                        .thenComparing(value -> value.userCoupon().userCouponId()))
                .toList();

        List<AvailableCouponItemResponse> coupons = evaluated.stream()
                .map(this::toAvailableCouponItemResponse)
                .toList();
        Optional<AvailableCouponCandidate> best = evaluated.stream()
                .filter(value -> Boolean.TRUE.equals(value.discountResult().available()))
                .findFirst();
        long bestDiscountCent = best.map(value -> value.discountResult().discountAmountCent()).orElse(0L);
        Long bestUserCouponId = best.map(value -> value.userCoupon().userCouponId()).orElse(null);

        return new AvailableCouponResponse(
                cartAmountCent,
                bestUserCouponId,
                bestDiscountCent,
                Math.max(cartAmountCent - bestDiscountCent, 0L),
                coupons
        );
    }

    private Optional<CouponTemplateRow> findActiveTemplateForUpdate(Long templateId, LocalDateTime now) {
        return jdbcClient.sql("""
                        select id, name, coupon_type, discount_type, threshold_cent, discount_cent,
                               scope_type, scope_value, total_stock, claimed_count, per_user_limit,
                               valid_start_at, valid_end_at
                        from coupon_template
                        where id = :templateId
                          and status = :status
                          and valid_start_at <= :now
                          and valid_end_at >= :now
                          and claimed_count < total_stock
                        for update
                        """)
                .param("templateId", templateId)
                .param("status", CouponTemplateStatus.ENABLED.name())
                .param("now", now)
                .query(this::mapCouponTemplate)
                .optional();
    }

    private Long insertUserCoupon(Long userId, CouponTemplateRow template) {
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
                            :validStartAt, :validEndAt, :status, :claimedAt
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
                        .addValue("claimedAt", LocalDateTime.now()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private Optional<AppUserCouponResponse> findUserCoupon(Long userId, Long userCouponId) {
        return jdbcClient.sql("""
                        select id, template_id, template_name, coupon_type, threshold_cent, discount_cent,
                               scope_type, status, valid_start_at, valid_end_at, claimed_at
                        from user_coupon
                        where id = :userCouponId
                          and user_id = :userId
                        """)
                .param("userCouponId", userCouponId)
                .param("userId", userId)
                .query(this::mapAppUserCoupon)
                .optional();
    }

    private List<CartCouponRow> findEligibleCartRows(Long userId, List<Long> cartItemIds) {
        String filterSql = cartItemIds.isEmpty() ? "" : " and ci.id in (:cartItemIds)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId);
        if (!cartItemIds.isEmpty()) {
            params.addValue("cartItemIds", cartItemIds);
        }
        return namedParameterJdbcTemplate.query("""
                        select ci.id as cart_item_id,
                               ci.sku_id,
                               k.spu_id,
                               ci.quantity,
                               k.price_cent,
                               k.price_cent * ci.quantity as line_amount_cent
                        from cart_item ci
                        join product_sku k on k.id = ci.sku_id
                        join product_spu s on s.id = k.spu_id
                        join product_category c on c.id = s.category_id
                        where ci.user_id = :userId
                          and k.status = 'ENABLED'
                          and s.status = 'ON_SALE'
                          and c.status = 'ENABLED'
                          and k.stock_available >= ci.quantity
                        """ + filterSql,
                params,
                this::mapCartCouponRow);
    }

    private List<UserCouponAvailableRow> findAvailableUserCoupons(Long userId, LocalDateTime now) {
        return jdbcClient.sql("""
                        select id, template_id, template_name, coupon_type, discount_type,
                               threshold_cent, discount_cent, scope_type, scope_value, valid_end_at
                        from user_coupon
                        where user_id = :userId
                          and status = :status
                          and valid_start_at <= :now
                          and valid_end_at >= :now
                          and scope_type = :scopeType
                        """)
                .param("userId", userId)
                .param("status", UserCouponStatus.CLAIMED.name())
                .param("now", now)
                .param("scopeType", CouponScopeType.ALL.name())
                .query(this::mapUserCouponAvailableRow)
                .list();
    }

    private AppClaimableCouponResponse mapClaimableCoupon(ResultSet rs, int rowNum) throws SQLException {
        int totalStock = rs.getInt("total_stock");
        int claimedCount = rs.getInt("claimed_count");
        int perUserLimit = rs.getInt("per_user_limit");
        int userClaimCount = rs.getInt("user_claim_count");
        boolean outOfStock = claimedCount >= totalStock;
        boolean claimLimitReached = userClaimCount >= perUserLimit;
        boolean claimable = !outOfStock && !claimLimitReached;
        String unavailableReason = null;
        if (outOfStock) {
            unavailableReason = "OUT_OF_STOCK";
        } else if (claimLimitReached) {
            unavailableReason = "CLAIM_LIMIT_REACHED";
        }
        return new AppClaimableCouponResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("coupon_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class),
                claimedCount,
                perUserLimit,
                claimable,
                unavailableReason
        );
    }

    private AppUserCouponResponse mapAppUserCoupon(ResultSet rs, int rowNum) throws SQLException {
        return new AppUserCouponResponse(
                rs.getLong("id"),
                rs.getLong("template_id"),
                rs.getString("template_name"),
                rs.getString("coupon_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("status"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class),
                rs.getObject("claimed_at", LocalDateTime.class)
        );
    }

    private CouponTemplateRow mapCouponTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new CouponTemplateRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getInt("total_stock"),
                rs.getInt("claimed_count"),
                rs.getInt("per_user_limit"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class)
        );
    }

    private CartCouponRow mapCartCouponRow(ResultSet rs, int rowNum) throws SQLException {
        return new CartCouponRow(
                rs.getLong("cart_item_id"),
                rs.getLong("sku_id"),
                rs.getLong("spu_id"),
                rs.getInt("quantity"),
                rs.getLong("price_cent"),
                rs.getLong("line_amount_cent")
        );
    }

    private UserCouponAvailableRow mapUserCouponAvailableRow(ResultSet rs, int rowNum) throws SQLException {
        return new UserCouponAvailableRow(
                rs.getLong("id"),
                rs.getLong("template_id"),
                rs.getString("template_name"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getObject("valid_end_at", LocalDateTime.class)
        );
    }

    private AvailableCouponItemResponse toAvailableCouponItemResponse(AvailableCouponCandidate candidate) {
        UserCouponAvailableRow userCoupon = candidate.userCoupon();
        DiscountResult discountResult = candidate.discountResult();
        return new AvailableCouponItemResponse(
                userCoupon.userCouponId(),
                userCoupon.templateId(),
                userCoupon.name(),
                userCoupon.couponType(),
                userCoupon.thresholdCent(),
                userCoupon.discountCent(),
                discountResult.discountAmountCent(),
                discountResult.available(),
                discountResult.unavailableReason(),
                userCoupon.validEndAt()
        );
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return UserCouponStatus.valueOf(status.trim()).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
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
            Integer totalStock,
            Integer claimedCount,
            Integer perUserLimit,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt
    ) {
    }

    private record CartCouponRow(
            Long cartItemId,
            Long skuId,
            Long spuId,
            Integer quantity,
            Long priceCent,
            Long lineAmountCent
    ) {
    }

    private record UserCouponAvailableRow(
            Long userCouponId,
            Long templateId,
            String name,
            String couponType,
            String discountType,
            Long thresholdCent,
            Long discountCent,
            String scopeType,
            String scopeValue,
            LocalDateTime validEndAt
    ) {
        private CouponCandidate toPromotionCandidate() {
            return new CouponCandidate(
                    userCouponId,
                    templateId,
                    name,
                    couponType,
                    discountType,
                    thresholdCent,
                    discountCent,
                    scopeType,
                    scopeValue
            );
        }
    }

    private record AvailableCouponCandidate(
            UserCouponAvailableRow userCoupon,
            DiscountResult discountResult
    ) {
    }
}
