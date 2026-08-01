package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingCapabilityState;
import org.muybaby.shopserver.logistics.dto.WechatDeliveryCompanyResponse;
import org.muybaby.shopserver.logistics.dto.WechatShippingCapabilityResponse;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class WechatShippingCatalogService {

    private static final Logger log = LoggerFactory.getLogger(WechatShippingCatalogService.class);

    private final JdbcClient jdbcClient;
    private final ShippingProperties shippingProperties;
    private final WechatShippingProvider shippingProvider;
    private final TransactionTemplate transactionTemplate;

    public WechatShippingCatalogService(
            JdbcClient jdbcClient,
            ShippingProperties shippingProperties,
            WechatShippingProvider shippingProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.shippingProperties = shippingProperties;
        this.shippingProvider = shippingProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public WechatShippingCapabilityResponse capability(AuthenticatedPrincipal principal) {
        requireAdmin(principal);
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (!shippingProperties.isUploadEnabled()) {
            return new WechatShippingCapabilityResponse(
                    false,
                    WechatProviderMode.DISABLED,
                    WechatShippingCapabilityState.UNAVAILABLE,
                    null,
                    "UPLOAD_DISABLED",
                    "WeChat shipping upload is disabled",
                    checkedAt
            );
        }

        WechatProviderMode providerMode = safeProviderMode();
        if (providerMode == WechatProviderMode.MOCK) {
            return mockCapability(checkedAt);
        }

        WechatShippingCapabilityResult result;
        try {
            result = shippingProvider.queryCapability();
        } catch (RuntimeException ex) {
            log.warn("WeChat shipping capability lookup failed: exception={}", ex.getClass().getSimpleName());
            result = WechatShippingCapabilityResult.unknown(
                    "CAPABILITY_LOOKUP_FAILED", "WeChat shipping capability is unknown"
            );
        }
        if (result == null) {
            result = WechatShippingCapabilityResult.unknown(
                    "AMBIGUOUS_RESPONSE", "WeChat shipping capability is unknown"
            );
        }
        return new WechatShippingCapabilityResponse(
                true,
                providerMode,
                result.state(),
                result.tradeManaged(),
                result.errorCode(),
                result.errorMessage(),
                checkedAt
        );
    }

    public List<WechatDeliveryCompanyResponse> list(AuthenticatedPrincipal principal) {
        requireAdmin(principal);
        return listEnabled();
    }

    public List<WechatDeliveryCompanyResponse> sync(AuthenticatedPrincipal principal) {
        requireAdmin(principal);

        List<WechatDeliveryCompanyResult> fetched;
        try {
            fetched = shippingProvider.getDeliveryCompanies();
        } catch (RuntimeException ex) {
            log.warn("WeChat delivery company lookup failed: exception={}", ex.getClass().getSimpleName());
            throw new IllegalStateException("WeChat delivery company lookup failed");
        }
        List<WechatDeliveryCompanyResult> companies = normalize(fetched);
        LocalDateTime syncedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        transactionTemplate.executeWithoutResult(status -> synchronizeCompanies(companies, syncedAt));
        return listEnabled();
    }

    private WechatShippingCapabilityResponse mockCapability(OffsetDateTime checkedAt) {
        return new WechatShippingCapabilityResponse(
                true,
                WechatProviderMode.MOCK,
                WechatShippingCapabilityState.UNAVAILABLE,
                null,
                "MOCK_PROVIDER",
                "Mock provider cannot confirm WeChat shipping capability",
                checkedAt
        );
    }

    private WechatProviderMode safeProviderMode() {
        WechatProviderMode mode = shippingProvider.mode();
        return mode == null ? WechatProviderMode.UNKNOWN : mode;
    }

    private List<WechatDeliveryCompanyResult> normalize(List<WechatDeliveryCompanyResult> fetched) {
        if (fetched == null) {
            throw new IllegalStateException("WeChat delivery company lookup failed");
        }
        Map<String, WechatDeliveryCompanyResult> unique = new LinkedHashMap<>();
        for (WechatDeliveryCompanyResult item : fetched) {
            if (item != null
                    && StringUtils.hasText(item.deliveryId())
                    && StringUtils.hasText(item.deliveryName())) {
                String id = item.deliveryId().trim();
                unique.put(id, new WechatDeliveryCompanyResult(id, item.deliveryName().trim()));
            }
        }
        if (!fetched.isEmpty() && unique.isEmpty()) {
            throw new IllegalStateException("WeChat delivery company lookup failed");
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(WechatDeliveryCompanyResult::deliveryId))
                .toList();
    }

    private void synchronizeCompanies(List<WechatDeliveryCompanyResult> companies, LocalDateTime syncedAt) {
        for (WechatDeliveryCompanyResult company : companies) {
            int updated = updateCompany(company, syncedAt);
            if (updated == 0) {
                try {
                    insertCompany(company, syncedAt);
                } catch (DuplicateKeyException ex) {
                    updateCompany(company, syncedAt);
                }
            }
        }

        Set<String> fetchedIds = companies.stream()
                .map(WechatDeliveryCompanyResult::deliveryId)
                .collect(Collectors.toUnmodifiableSet());
        List<String> cachedIds = jdbcClient.sql("""
                        select delivery_id
                        from wechat_delivery_company
                        order by delivery_id
                        """)
                .query(String.class)
                .list();
        for (String cachedId : cachedIds) {
            if (!fetchedIds.contains(cachedId)) {
                jdbcClient.sql("""
                                update wechat_delivery_company
                                set enabled = false
                                where delivery_id = :deliveryId
                                """)
                        .param("deliveryId", cachedId)
                        .update();
            }
        }
    }

    private int updateCompany(WechatDeliveryCompanyResult company, LocalDateTime syncedAt) {
        return jdbcClient.sql("""
                        update wechat_delivery_company
                        set delivery_name = :deliveryName,
                            enabled = true,
                            synced_at = :syncedAt
                        where delivery_id = :deliveryId
                        """)
                .param("deliveryId", company.deliveryId())
                .param("deliveryName", company.deliveryName())
                .param("syncedAt", syncedAt)
                .update();
    }

    private void insertCompany(WechatDeliveryCompanyResult company, LocalDateTime syncedAt) {
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values (:deliveryId, :deliveryName, true, :syncedAt)
                        """)
                .param("deliveryId", company.deliveryId())
                .param("deliveryName", company.deliveryName())
                .param("syncedAt", syncedAt)
                .update();
    }

    private List<WechatDeliveryCompanyResponse> listEnabled() {
        return jdbcClient.sql("""
                        select delivery_id, delivery_name, synced_at
                        from wechat_delivery_company
                        where enabled = true
                        order by delivery_name, delivery_id
                        """)
                .query(this::mapCompany)
                .list();
    }

    private WechatDeliveryCompanyResponse mapCompany(ResultSet rs, int rowNum) throws SQLException {
        return new WechatDeliveryCompanyResponse(
                rs.getString("delivery_id"),
                rs.getString("delivery_name"),
                rs.getObject("synced_at", LocalDateTime.class)
        );
    }

    private Long requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }
}
