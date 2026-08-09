package org.muybaby.shopserver.logistics.waybill.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

@Service
public class WechatExpressConfigService {

    public static final String SANDBOX_DELIVERY_ID = "TEST";
    public static final String SANDBOX_DELIVERY_NAME = "微信官方测试运力";
    public static final String SANDBOX_BIZ_ID = "test_biz_id";
    public static final int SANDBOX_SERVICE_TYPE = 1;
    public static final String SANDBOX_SERVICE_NAME = "test_service_name";

    private static final long CONFIG_ID = 1L;
    private static final BigDecimal MAX_WEIGHT_EXCLUSIVE = new BigDecimal("10000000");
    private static final BigDecimal MAX_DIMENSION_EXCLUSIVE = new BigDecimal("100000000");

    private final JdbcClient jdbcClient;

    public WechatExpressConfigService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public WechatExpressConfigResponse current() {
        return toResponse(requireSetting(false));
    }

    @Transactional(readOnly = true)
    public WechatExpressEffectiveConfig effectiveConfig() {
        return toEffectiveConfig(requireSetting(false));
    }

    @Transactional
    public WechatExpressConfigResponse update(
            WechatExpressConfigUpdateRequest request,
            Long updatedBy
    ) {
        requireRequestShape(request);
        SettingRow persisted = requireSetting(true);
        if (persisted.revision() != request.revision()) {
            throw new BusinessException(ErrorCode.WECHAT_EXPRESS_CONFIG_CONFLICT);
        }

        NormalizedUpdate update = normalize(request, persisted.bizId());
        validate(update);
        int updated = jdbcClient.sql("""
                        update wechat_express_setting
                        set mode = :mode,
                            message_enabled = :messageEnabled,
                            sender_name = :senderName,
                            sender_mobile = :senderMobile,
                            sender_company = :senderCompany,
                            sender_province = :senderProvince,
                            sender_city = :senderCity,
                            sender_district = :senderDistrict,
                            sender_detail_address = :senderDetailAddress,
                            delivery_id = :deliveryId,
                            delivery_name = :deliveryName,
                            biz_id = :bizId,
                            service_type = :serviceType,
                            service_name = :serviceName,
                            default_weight_kg = :defaultWeightKg,
                            default_length_cm = :defaultLengthCm,
                            default_width_cm = :defaultWidthCm,
                            default_height_cm = :defaultHeightCm,
                            revision = revision + 1,
                            updated_by = :updatedBy,
                            updated_at = current_timestamp
                        where id = :id
                          and revision = :revision
                        """)
                .param("mode", update.mode().name())
                .param("messageEnabled", update.messageEnabled())
                .param("senderName", update.sender().name())
                .param("senderMobile", update.sender().mobile())
                .param("senderCompany", update.sender().company())
                .param("senderProvince", update.sender().province())
                .param("senderCity", update.sender().city())
                .param("senderDistrict", update.sender().district())
                .param("senderDetailAddress", update.sender().detailAddress())
                .param("deliveryId", update.account().deliveryId())
                .param("deliveryName", update.account().deliveryName())
                .param("bizId", update.account().bizId())
                .param("serviceType", update.account().serviceType())
                .param("serviceName", update.account().serviceName())
                .param("defaultWeightKg", update.parcel().weightKg())
                .param("defaultLengthCm", update.parcel().lengthCm())
                .param("defaultWidthCm", update.parcel().widthCm())
                .param("defaultHeightCm", update.parcel().heightCm())
                .param("updatedBy", updatedBy)
                .param("id", CONFIG_ID)
                .param("revision", request.revision())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.WECHAT_EXPRESS_CONFIG_CONFLICT);
        }
        return toResponse(requireSetting(false));
    }

    private void requireRequestShape(WechatExpressConfigUpdateRequest request) {
        if (request == null
                || request.revision() == null
                || request.revision() < 0
                || request.mode() == null
                || request.messageEnabled() == null
                || request.sender() == null
                || request.production() == null
                || request.production().clearBizId() == null
                || request.defaultParcel() == null) {
            throw validationFailure();
        }
    }

    private NormalizedUpdate normalize(WechatExpressConfigUpdateRequest request, String persistedBizId) {
        WechatExpressSender sender = new WechatExpressSender(
                trimToEmpty(request.sender().name()),
                trimToEmpty(request.sender().mobile()),
                trimToEmpty(request.sender().company()),
                trimToEmpty(request.sender().province()),
                trimToEmpty(request.sender().city()),
                trimToEmpty(request.sender().district()),
                trimToEmpty(request.sender().detailAddress())
        );
        WechatExpressProductionUpdate production = request.production();
        String submittedBizId = trimToEmpty(production.bizId());
        String bizId;
        if (Boolean.TRUE.equals(production.clearBizId())) {
            bizId = "";
        } else if (StringUtils.hasText(submittedBizId)) {
            bizId = submittedBizId;
        } else {
            bizId = trimToEmpty(persistedBizId);
        }
        WechatExpressAccount account = new WechatExpressAccount(
                trimToEmpty(production.deliveryId()),
                trimToEmpty(production.deliveryName()),
                bizId,
                production.serviceType(),
                trimToEmpty(production.serviceName())
        );
        return new NormalizedUpdate(
                request.mode(),
                request.messageEnabled(),
                sender,
                account,
                request.defaultParcel()
        );
    }

    private void validate(NormalizedUpdate update) {
        WechatExpressSender sender = update.sender();
        WechatExpressAccount account = update.account();
        if (!validLength(sender.name(), 64)
                || !validLength(sender.mobile(), 32)
                || !validLength(sender.company(), 64)
                || !validLength(sender.province(), 64)
                || !validLength(sender.city(), 64)
                || !validLength(sender.district(), 64)
                || !validLength(sender.detailAddress(), 512)
                || !validLength(account.deliveryId(), 128)
                || !validLength(account.deliveryName(), 128)
                || !validLength(account.bizId(), 128)
                || !validLength(account.serviceName(), 128)
                || account.serviceType() != null && account.serviceType() < 0
                || !validParcel(update.parcel())) {
            throw validationFailure();
        }
        if (update.mode() != WechatExpressMode.DISABLED && !completeSender(sender)) {
            throw validationFailure();
        }
        if (update.mode() == WechatExpressMode.PRODUCTION
                && (!StringUtils.hasText(account.deliveryId())
                || !StringUtils.hasText(account.deliveryName())
                || !StringUtils.hasText(account.bizId())
                || account.serviceType() == null
                || !StringUtils.hasText(account.serviceName()))) {
            throw validationFailure();
        }
    }

    private boolean completeSender(WechatExpressSender sender) {
        return StringUtils.hasText(sender.name())
                && StringUtils.hasText(sender.mobile())
                && StringUtils.hasText(sender.province())
                && StringUtils.hasText(sender.city())
                && StringUtils.hasText(sender.district())
                && StringUtils.hasText(sender.detailAddress());
    }

    private boolean validParcel(WechatExpressParcel parcel) {
        return parcel != null
                && parcel.count() == 1
                && validDecimal(parcel.weightKg(), 3, MAX_WEIGHT_EXCLUSIVE)
                && validDecimal(parcel.lengthCm(), 2, MAX_DIMENSION_EXCLUSIVE)
                && validDecimal(parcel.widthCm(), 2, MAX_DIMENSION_EXCLUSIVE)
                && validDecimal(parcel.heightCm(), 2, MAX_DIMENSION_EXCLUSIVE);
    }

    private boolean validDecimal(BigDecimal value, int scale, BigDecimal maximumExclusive) {
        if (value == null || value.signum() <= 0 || value.compareTo(maximumExclusive) >= 0) {
            return false;
        }
        try {
            value.setScale(scale, RoundingMode.UNNECESSARY);
            return true;
        } catch (ArithmeticException ex) {
            return false;
        }
    }

    private boolean validLength(String value, int maximum) {
        return value != null && value.length() <= maximum;
    }

    private SettingRow requireSetting(boolean forUpdate) {
        String suffix = forUpdate ? " for update" : "";
        return jdbcClient.sql("""
                        select mode,
                               message_enabled,
                               sender_name,
                               sender_mobile,
                               sender_company,
                               sender_province,
                               sender_city,
                               sender_district,
                               sender_detail_address,
                               delivery_id,
                               delivery_name,
                               biz_id,
                               service_type,
                               service_name,
                               default_weight_kg,
                               default_length_cm,
                               default_width_cm,
                               default_height_cm,
                               revision,
                               updated_at
                        from wechat_express_setting
                        where id = :id
                        """ + suffix)
                .param("id", CONFIG_ID)
                .query(this::mapSetting)
                .optional()
                .orElseThrow(() -> new IllegalStateException("WeChat express setting seed is missing"));
    }

    private SettingRow mapSetting(ResultSet rs, int rowNum) throws SQLException {
        return new SettingRow(
                WechatExpressMode.valueOf(rs.getString("mode")),
                rs.getBoolean("message_enabled"),
                new WechatExpressSender(
                        rs.getString("sender_name"),
                        rs.getString("sender_mobile"),
                        rs.getString("sender_company"),
                        rs.getString("sender_province"),
                        rs.getString("sender_city"),
                        rs.getString("sender_district"),
                        rs.getString("sender_detail_address")
                ),
                rs.getString("delivery_id"),
                rs.getString("delivery_name"),
                rs.getString("biz_id"),
                rs.getObject("service_type", Integer.class),
                rs.getString("service_name"),
                new WechatExpressParcel(
                        1,
                        rs.getBigDecimal("default_weight_kg"),
                        rs.getBigDecimal("default_length_cm"),
                        rs.getBigDecimal("default_width_cm"),
                        rs.getBigDecimal("default_height_cm")
                ),
                rs.getLong("revision"),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private WechatExpressConfigResponse toResponse(SettingRow row) {
        Integer productionServiceType = configuredProduction(row) ? row.serviceType() : null;
        WechatExpressAccountResponse production = new WechatExpressAccountResponse(
                row.deliveryId(),
                row.deliveryName(),
                maskBizId(row.bizId()),
                productionServiceType,
                row.serviceName()
        );
        WechatExpressEffectiveConfig effective = toEffectiveConfig(row);
        WechatExpressAccount effectiveAccount = effective.account();
        String effectiveBizId = row.mode() == WechatExpressMode.SANDBOX
                ? effectiveAccount.bizId()
                : maskBizId(effectiveAccount.bizId());
        return new WechatExpressConfigResponse(
                row.mode(),
                row.messageEnabled(),
                row.sender(),
                production,
                new WechatExpressAccountResponse(
                        effectiveAccount.deliveryId(),
                        effectiveAccount.deliveryName(),
                        effectiveBizId,
                        effectiveAccount.serviceType(),
                        effectiveAccount.serviceName()
                ),
                row.parcel(),
                row.revision(),
                row.updatedAt()
        );
    }

    private WechatExpressEffectiveConfig toEffectiveConfig(SettingRow row) {
        WechatExpressAccount account = switch (row.mode()) {
            case DISABLED -> new WechatExpressAccount("", "", "", null, "");
            case SANDBOX -> new WechatExpressAccount(
                    SANDBOX_DELIVERY_ID,
                    SANDBOX_DELIVERY_NAME,
                    SANDBOX_BIZ_ID,
                    SANDBOX_SERVICE_TYPE,
                    SANDBOX_SERVICE_NAME
            );
            case PRODUCTION -> new WechatExpressAccount(
                    row.deliveryId(),
                    row.deliveryName(),
                    row.bizId(),
                    row.serviceType(),
                    row.serviceName()
            );
        };
        return new WechatExpressEffectiveConfig(
                row.mode(),
                row.messageEnabled(),
                row.sender(),
                account,
                row.parcel(),
                row.revision()
        );
    }

    private boolean configuredProduction(SettingRow row) {
        return StringUtils.hasText(row.deliveryId())
                || StringUtils.hasText(row.deliveryName())
                || StringUtils.hasText(row.bizId())
                || row.serviceType() != null
                || StringUtils.hasText(row.serviceName());
    }

    private String maskBizId(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        int starCount = value.length() > 10 ? 6 : 3;
        return value.substring(0, 2)
                + "*".repeat(starCount)
                + value.substring(value.length() - 2);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record NormalizedUpdate(
            WechatExpressMode mode,
            boolean messageEnabled,
            WechatExpressSender sender,
            WechatExpressAccount account,
            WechatExpressParcel parcel
    ) {
    }

    private record SettingRow(
            WechatExpressMode mode,
            boolean messageEnabled,
            WechatExpressSender sender,
            String deliveryId,
            String deliveryName,
            String bizId,
            Integer serviceType,
            String serviceName,
            WechatExpressParcel parcel,
            long revision,
            LocalDateTime updatedAt
    ) {
    }
}
