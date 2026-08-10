package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.dto.MerchantReturnAddressRequest;
import org.muybaby.shopserver.aftersale.dto.MerchantReturnAddressResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MerchantReturnAddressService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public MerchantReturnAddressService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public List<MerchantReturnAddressResponse> list(AuthenticatedPrincipal principal) {
        requireAdmin(principal);
        return jdbcClient.sql("""
                        select id, contact_name, contact_phone, province, city, district,
                               detail_address, enabled, default_slot, version, created_at, updated_at
                        from merchant_return_address
                        order by default_slot desc, enabled desc, updated_at desc, id desc
                        """)
                .query((rs, rowNum) -> new MerchantReturnAddressResponse(
                        rs.getLong("id"), rs.getString("contact_name"),
                        rs.getString("contact_phone"), rs.getString("province"),
                        rs.getString("city"), rs.getString("district"),
                        rs.getString("detail_address"), rs.getBoolean("enabled"),
                        rs.getObject("default_slot", Integer.class) != null,
                        rs.getLong("version"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)))
                .list();
    }

    @Transactional
    public MerchantReturnAddressResponse create(
            AuthenticatedPrincipal principal,
            MerchantReturnAddressRequest request
    ) {
        long adminId = requireAdmin(principal);
        AddressInput input = validate(request);
        lockAddresses();
        boolean makeDefault = input.enabled()
                && (input.defaultAddress() || !hasEnabledDefault());
        if (makeDefault) {
            clearDefault();
        }
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into merchant_return_address (
                            contact_name, contact_phone, province, city, district, detail_address,
                            enabled, default_slot, version, created_by, updated_by, created_at, updated_at
                        ) values (
                            :contactName, :contactPhone, :province, :city, :district, :detailAddress,
                            :enabled, :defaultSlot, 0, :adminId, :adminId, :now, :now
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("contactName", input.contactName())
                        .addValue("contactPhone", input.contactPhone())
                        .addValue("province", input.province())
                        .addValue("city", input.city())
                        .addValue("district", input.district())
                        .addValue("detailAddress", input.detailAddress())
                        .addValue("enabled", input.enabled())
                        .addValue("defaultSlot", makeDefault && input.enabled() ? 1 : null)
                        .addValue("adminId", adminId)
                        .addValue("now", now),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        ensureEnabledDefault();
        return require(key.longValue());
    }

    @Transactional
    public MerchantReturnAddressResponse update(
            AuthenticatedPrincipal principal,
            long addressId,
            MerchantReturnAddressRequest request
    ) {
        long adminId = requireAdmin(principal);
        AddressInput input = validate(request);
        if (request.version() == null || request.version() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        lockAddresses();
        require(addressId);
        if (input.enabled() && input.defaultAddress()) {
            clearDefault();
        }
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                        update merchant_return_address
                        set contact_name = :contactName,
                            contact_phone = :contactPhone,
                            province = :province,
                            city = :city,
                            district = :district,
                            detail_address = :detailAddress,
                            enabled = :enabled,
                            default_slot = :defaultSlot,
                            version = version + 1,
                            updated_by = :adminId,
                            updated_at = :now
                        where id = :id and version = :version
                        """)
                .param("contactName", input.contactName())
                .param("contactPhone", input.contactPhone())
                .param("province", input.province())
                .param("city", input.city())
                .param("district", input.district())
                .param("detailAddress", input.detailAddress())
                .param("enabled", input.enabled())
                .param("defaultSlot", input.enabled() && input.defaultAddress() ? 1 : null)
                .param("adminId", adminId)
                .param("now", now)
                .param("id", addressId)
                .param("version", request.version())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        ensureEnabledDefault();
        return require(addressId);
    }

    @Transactional
    public void disable(AuthenticatedPrincipal principal, long addressId) {
        long adminId = requireAdmin(principal);
        lockAddresses();
        require(addressId);
        int updated = jdbcClient.sql("""
                        update merchant_return_address
                        set enabled = false, default_slot = null, version = version + 1,
                            updated_by = :adminId, updated_at = :now
                        where id = :id
                        """)
                .param("adminId", adminId)
                .param("now", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("id", addressId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        ensureEnabledDefault();
    }

    AddressSnapshot requireEnabled(long addressId) {
        return jdbcClient.sql("""
                        select id, contact_name, contact_phone, province, city, district, detail_address
                        from merchant_return_address
                        where id = :id and enabled = true
                        """)
                .param("id", addressId)
                .query((rs, rowNum) -> new AddressSnapshot(
                        rs.getLong("id"), rs.getString("contact_name"),
                        rs.getString("contact_phone"), rs.getString("province"),
                        rs.getString("city"), rs.getString("district"),
                        rs.getString("detail_address")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private MerchantReturnAddressResponse require(long addressId) {
        return jdbcClient.sql("""
                        select id, contact_name, contact_phone, province, city, district,
                               detail_address, enabled, default_slot, version, created_at, updated_at
                        from merchant_return_address where id = :id
                        """)
                .param("id", addressId)
                .query((rs, rowNum) -> new MerchantReturnAddressResponse(
                        rs.getLong("id"), rs.getString("contact_name"),
                        rs.getString("contact_phone"), rs.getString("province"),
                        rs.getString("city"), rs.getString("district"),
                        rs.getString("detail_address"), rs.getBoolean("enabled"),
                        rs.getObject("default_slot", Integer.class) != null,
                        rs.getLong("version"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private AddressInput validate(MerchantReturnAddressRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new AddressInput(
                text(request.contactName(), 64, true),
                text(request.contactPhone(), 32, true),
                text(request.province(), 64, false),
                text(request.city(), 64, false),
                text(request.district(), 64, false),
                text(request.detailAddress(), 255, true),
                !Boolean.FALSE.equals(request.enabled()),
                Boolean.TRUE.equals(request.defaultAddress()));
    }

    private String text(String value, int max, boolean required) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max || required && !StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private long requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private void lockAddresses() {
        jdbcClient.sql("select id from merchant_return_address order by id for update")
                .query(Long.class)
                .list();
    }

    private boolean hasEnabledDefault() {
        return jdbcClient.sql("""
                        select count(*) from merchant_return_address
                        where enabled = true and default_slot = 1
                        """)
                .query(Long.class)
                .single() > 0;
    }

    private void clearDefault() {
        jdbcClient.sql("""
                        update merchant_return_address
                        set default_slot = null, version = version + 1,
                            updated_at = current_timestamp
                        where default_slot = 1
                        """)
                .update();
    }

    private void ensureEnabledDefault() {
        if (hasEnabledDefault()) {
            return;
        }
        Long replacement = jdbcClient.sql("""
                        select id from merchant_return_address
                        where enabled = true order by updated_at desc, id desc limit 1
                        """)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (replacement != null) {
            jdbcClient.sql("""
                            update merchant_return_address
                            set default_slot = 1, version = version + 1,
                                updated_at = current_timestamp
                            where id = :id
                            """)
                    .param("id", replacement)
                    .update();
        }
    }

    record AddressSnapshot(
            long id,
            String contactName,
            String contactPhone,
            String province,
            String city,
            String district,
            String detailAddress
    ) {
    }

    private record AddressInput(
            String contactName,
            String contactPhone,
            String province,
            String city,
            String district,
            String detailAddress,
            boolean enabled,
            boolean defaultAddress
    ) {
    }
}
