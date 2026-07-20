package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentNotificationRouteProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Builds per-payment and per-refund callback URLs without exposing configuration identities. */
@Service
public class PaymentNotificationRouteService {

    private static final int TOKEN_BYTES = 24;
    private static final int MAX_NOTIFY_URL_LENGTH = 255;
    private static final String ROUTE_VALIDATION_TOKEN = "A".repeat(32);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PaymentNotificationRouteProperties properties;

    public PaymentNotificationRouteService(PaymentNotificationRouteProperties properties) {
        this.properties = properties;
    }

    /** Returns null while new route issuance is disabled; existing persisted tokens remain usable. */
    public String issueToken() {
        if (!properties.enabled()) {
            return null;
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String payNotifyUrl(String baseUrl, String routeToken) {
        return routedUrl(baseUrl, routeToken);
    }

    public String refundNotifyUrl(String baseUrl, String routeToken) {
        return routedUrl(baseUrl, routeToken);
    }

    /** Validates that a configured base URL can safely carry a future opaque route token. */
    public void validateRoutedBaseUrl(String baseUrl) {
        routedUrl(baseUrl, ROUTE_VALIDATION_TOKEN);
    }

    public String requireRouteToken(String routeToken) {
        if (!StringUtils.hasText(routeToken)) {
            throw validationFailure();
        }
        if (!routeToken.equals(routeToken.trim()) || !TOKEN_PATTERN.matcher(routeToken).matches()) {
            throw validationFailure();
        }
        return routeToken;
    }

    private String routedUrl(String baseUrl, String routeToken) {
        if (!StringUtils.hasText(routeToken)) {
            return baseUrl;
        }
        String normalizedBase = normalizeBaseUrl(baseUrl);
        String normalizedToken = requireRouteToken(routeToken);
        String routed = normalizedBase + "/r/" + normalizedToken;
        if (routed.length() > MAX_NOTIFY_URL_LENGTH) {
            throw validationFailure();
        }
        return routed;
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            throw validationFailure();
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(uri.getHost())
                    || !StringUtils.hasText(uri.getRawPath())
                    || "/".equals(uri.getRawPath())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || normalized.length() > MAX_NOTIFY_URL_LENGTH) {
                throw validationFailure();
            }
            return normalized;
        } catch (URISyntaxException ex) {
            throw validationFailure();
        }
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
