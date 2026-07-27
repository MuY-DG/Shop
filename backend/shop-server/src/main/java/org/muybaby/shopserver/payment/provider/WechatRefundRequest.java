package org.muybaby.shopserver.payment.provider;

import java.nio.charset.StandardCharsets;

public record WechatRefundRequest(
        String outTradeNo,
        String transactionId,
        String outRefundNo,
        long refundAmountCent,
        long totalAmountCent,
        String reason,
        String notifyUrl
) {

    private static final int MAX_REASON_UTF8_BYTES = 80;

    public WechatRefundRequest {
        reason = providerSafeReason(reason);
    }

    /**
     * WeChat limits the displayed refund reason to 80 UTF-8 bytes and accepts only the
     * one-to-three-byte UTF-8 subset. Canonicalizing in the request value object guarantees that an
     * idempotent retry reconstructs exactly the same provider parameter.
     */
    public static String providerSafeReason(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        StringBuilder result = new StringBuilder();
        int byteCount = 0;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            String part = codePoint > Character.MAX_VALUE
                    ? "?"
                    : new String(Character.toChars(codePoint));
            int partBytes = part.getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + partBytes > MAX_REASON_UTF8_BYTES) {
                break;
            }
            result.append(part);
            byteCount += partBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }
}
