package org.muybaby.shopserver.wechat.servicecard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfig;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfigResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Component
public class WechatServiceCardPayloadFactory {

    private static final int MAX_PRODUCTS = 10;
    private static final int MAX_PRODUCT_NAME_CODE_POINTS = 40;
    private static final long UINT32_MAX = 4_294_967_295L;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final WechatServiceCardProperties properties;
    private final WechatServiceCardConfigResolver configResolver;
    private final WechatPlatformCredentialResolver credentialResolver;

    public WechatServiceCardPayloadFactory(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            WechatServiceCardProperties properties,
            WechatServiceCardConfigResolver configResolver,
            WechatPlatformCredentialResolver credentialResolver
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.configResolver = configResolver;
        this.credentialResolver = credentialResolver;
    }

    public PaymentSnapshot paidPayment(long orderId) {
        return jdbcClient.sql("""
                        select payment.id, payment.transaction_id, payment.payer_openid,
                               payment.amount_cent, payment.paid_at,
                               payment.payment_config_id, payment.payment_config_fingerprint,
                               coalesce(db_config.app_id, env_snapshot.app_id) as payment_app_id
                        from payment_order payment
                        left join payment_config db_config
                          on db_config.id = payment.payment_config_id
                        left join payment_config_snapshot env_snapshot
                          on payment.payment_config_id is null
                         and env_snapshot.fingerprint = payment.payment_config_fingerprint
                        where payment.order_id = :orderId
                          and payment.status = 'PAID'
                          and payment.transaction_id <> ''
                          and payment.payer_openid <> ''
                          and payment.paid_at is not null
                        order by payment.paid_at desc, payment.id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(this::mapPayment)
                .optional()
                .orElse(null);
    }

    public void validatePaymentMiniProgram(PaymentSnapshot payment) {
        String appId;
        try {
            appId = credentialResolver.resolve().appId();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Mini Program platform config is unavailable", ex);
        }
        if (payment == null || !StringUtils.hasText(appId)
                || !StringUtils.hasText(payment.paymentAppId())
                || !appId.trim().equals(payment.paymentAppId().trim())) {
            throw new IllegalStateException("Payment AppID does not match Mini Program AppID");
        }
    }

    public PayloadSnapshot build(
            long orderId,
            WechatServiceCardStatus status,
            boolean activation,
            PaymentSnapshot payment
    ) {
        return build(orderId, status, activation, payment, configResolver.resolve());
    }

    PayloadSnapshot build(
            long orderId,
            WechatServiceCardStatus status,
            boolean activation,
            PaymentSnapshot payment,
            WechatServiceCardConfig config
    ) {
        if (status == null || payment == null) {
            throw new IllegalArgumentException("WeChat 2001 payload context is required");
        }
        requireUint32(payment.amountCent(), "pay_amount", true);
        ObjectNode content = objectMapper.createObjectNode();
        content.put("cur_status", status.code());
        content.put("wxa_path_query", orderPath(orderId));
        if (status == WechatServiceCardStatus.USER_PAID
                || status == WechatServiceCardStatus.WAITING_SHIPMENT) {
            List<ItemSnapshot> items = items(orderId);
            if (items.isEmpty()) {
                throw new IllegalStateException("Order items are unavailable for WeChat 2001 activation");
            }
            long productCount = 0L;
            for (ItemSnapshot item : items) {
                requireUint32(item.quantity(), "count", false);
                requireUint32(item.unitPriceCent(), "single_price", true);
                productCount = Math.addExact(productCount, item.quantity());
                requireUint32(productCount, "product_count", false);
            }
            content.put("product_count", productCount);
            ArrayNode infoList = objectMapper.createArrayNode();
            for (ItemSnapshot item : items.stream().limit(MAX_PRODUCTS).toList()) {
                ObjectNode product = objectMapper.createObjectNode();
                product.put("product_img", productImage(item, config));
                product.put("product_name", truncate(item.productTitle(), MAX_PRODUCT_NAME_CODE_POINTS));
                product.put("product_path_query", productPath(item.spuId()));
                product.put("count", item.quantity());
                product.put("single_price", item.unitPriceCent());
                infoList.add(product);
            }
            ObjectNode productList = objectMapper.createObjectNode();
            productList.set("info_list", infoList);
            content.set("product_list", productList);
        }

        ObjectNode check = null;
        if (activation) {
            if (!status.activationAllowed()) {
                throw new IllegalArgumentException("Only status 1 or 2 can activate WeChat 2001");
            }
            check = objectMapper.createObjectNode();
            check.put("pay_amount", payment.amountCent());
            long payTime = payment.paidAt().toEpochSecond(ZoneOffset.UTC);
            requireUint32(payTime, "pay_time", false);
            check.put("pay_time", payTime);
            check.put("pay_channel", 0);
        }
        try {
            String contentJson = objectMapper.writeValueAsString(content);
            String checkJson = check == null ? null : objectMapper.writeValueAsString(check);
            int totalBytes = contentJson.getBytes(StandardCharsets.UTF_8).length
                    + (checkJson == null ? 0 : checkJson.getBytes(StandardCharsets.UTF_8).length);
            if (totalBytes > properties.maxPayloadBytes()) {
                throw new IllegalStateException("WeChat 2001 payload exceeds the configured safe limit");
            }
            return new PayloadSnapshot(contentJson, checkJson);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize WeChat 2001 payload", ex);
        }
    }

    private String productImage(ItemSnapshot item, WechatServiceCardConfig config) {
        Set<String> allowedHosts = config.allowedImageHosts();
        if (config.preferOrderSnapshotImages()) {
            for (String candidate : List.of(
                    nullToEmpty(item.displayImage()),
                    nullToEmpty(item.skuImage()),
                    nullToEmpty(item.mainImage()))) {
                if (WechatServiceCardProperties.validPublicImage(candidate, allowedHosts)) {
                    return candidate.trim();
                }
            }
        }
        if (!WechatServiceCardProperties.validPublicImage(
                config.fallbackProductImage(), allowedHosts)) {
            throw new IllegalStateException(
                    "A controlled public HTTPS fallback image is required for WeChat 2001"
            );
        }
        return config.fallbackProductImage();
    }

    private List<ItemSnapshot> items(long orderId) {
        return jdbcClient.sql("""
                        select spu_id, product_title, main_image, sku_image, display_image,
                               unit_price_cent, quantity
                        from order_item
                        where order_id = :orderId
                        order by id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ItemSnapshot(
                        rs.getLong("spu_id"),
                        rs.getString("product_title"),
                        rs.getString("main_image"),
                        rs.getString("sku_image"),
                        rs.getString("display_image"),
                        rs.getLong("unit_price_cent"),
                        rs.getInt("quantity")
                ))
                .list();
    }

    private PaymentSnapshot mapPayment(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentSnapshot(
                rs.getLong("id"),
                rs.getString("transaction_id"),
                rs.getString("payer_openid"),
                rs.getLong("amount_cent"),
                rs.getObject("paid_at", LocalDateTime.class),
                rs.getObject("payment_config_id", Long.class),
                rs.getString("payment_config_fingerprint"),
                rs.getString("payment_app_id")
        );
    }

    static String orderPath(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        return "pages/order/detail/detail?order_id=" + orderId;
    }

    static String productPath(long spuId) {
        if (spuId <= 0) {
            throw new IllegalArgumentException("spuId must be positive");
        }
        return "pages/product/detail/detail?id=" + spuId;
    }

    private static String truncate(String value, int maxCodePoints) {
        String source = value == null ? "" : value;
        StringBuilder sanitized = new StringBuilder(source.length());
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= source.length() || !Character.isLowSurrogate(source.charAt(index + 1))) {
                    index++;
                    continue;
                }
            } else if (Character.isLowSurrogate(current)) {
                index++;
                continue;
            }
            int codePoint = source.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint <= 0x1F || (codePoint >= 0x7F && codePoint <= 0x9F)) {
                continue;
            }
            sanitized.appendCodePoint(codePoint);
        }
        String normalized = sanitized.toString().trim();
        if (normalized.isEmpty()) {
            normalized = "商品";
        }
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= maxCodePoints) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxCodePoints));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void requireUint32(long value, String field, boolean allowZero) {
        long minimum = allowZero ? 0L : 1L;
        if (value < minimum || value > UINT32_MAX) {
            throw new IllegalArgumentException(field + " is outside the WeChat uint32 range");
        }
    }

    public record PaymentSnapshot(
            long paymentOrderId,
            String transactionId,
            String payerOpenid,
            long amountCent,
            LocalDateTime paidAt,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String paymentAppId
    ) {
    }

    public record PayloadSnapshot(String contentJson, String checkJson) {
    }

    private record ItemSnapshot(
            long spuId,
            String productTitle,
            String mainImage,
            String skuImage,
            String displayImage,
            long unitPriceCent,
            int quantity
    ) {
    }
}
