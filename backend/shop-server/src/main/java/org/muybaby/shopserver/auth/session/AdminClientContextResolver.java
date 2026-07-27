package org.muybaby.shopserver.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.security.web.ClientIpResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class AdminClientContextResolver {

    public static final String DEVICE_ID_HEADER = "X-Device-Id";

    private static final int MAX_USER_AGENT_LENGTH = 512;
    private static final Pattern ACCEPTED_DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final ClientIpResolver clientIpResolver;

    public AdminClientContextResolver(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    public AdminClientContext resolve(HttpServletRequest request) {
        String suppliedDeviceId = normalize(request.getHeader(DEVICE_ID_HEADER));
        String stableDeviceId = ACCEPTED_DEVICE_ID.matcher(suppliedDeviceId).matches()
                ? suppliedDeviceId
                : UUID.randomUUID().toString();
        return new AdminClientContext(
                sha256(stableDeviceId),
                clientIpResolver.resolve(request),
                truncate(normalize(request.getHeader("User-Agent")), MAX_USER_AGENT_LENGTH)
        );
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
