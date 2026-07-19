package org.muybaby.shopserver.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Component
public class AnalyticsClientIpResolver {

    private final List<Subnet> trustedProxies;

    public AnalyticsClientIpResolver(AnalyticsRateLimitProperties properties) {
        this.trustedProxies = properties.effectiveTrustedProxyCidrs().stream()
                .map(Subnet::parse)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalizeLiteral(request.getRemoteAddr());
        if (remoteAddress == null || !isTrusted(remoteAddress)) {
            return remoteAddress == null ? "unknown" : remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }
        String[] values = forwardedFor.split(",");
        List<String> chain = new ArrayList<>(values.length);
        for (String value : values) {
            String normalized = normalizeLiteral(value);
            if (normalized == null) {
                return remoteAddress;
            }
            chain.add(normalized);
        }
        for (int index = chain.size() - 1; index >= 0; index--) {
            String candidate = chain.get(index);
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return chain.isEmpty() ? remoteAddress : chain.get(0);
    }

    private boolean isTrusted(String address) {
        InetAddress inetAddress = parseLiteral(address);
        return inetAddress != null && trustedProxies.stream().anyMatch(subnet -> subnet.contains(inetAddress));
    }

    private static String normalizeLiteral(String value) {
        InetAddress address = parseLiteral(value == null ? null : value.trim());
        return address == null ? null : address.getHostAddress();
    }

    private static InetAddress parseLiteral(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private record Subnet(byte[] network, int prefixLength) {

        private static Subnet parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Trusted proxy CIDR cannot be blank");
            }
            String[] parts = value.trim().split("/", -1);
            InetAddress address = parseLiteral(parts[0]);
            if (address == null || parts.length > 2) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + value);
            }
            int bitLength = address.getAddress().length * 8;
            int prefix = parts.length == 1 ? bitLength : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > bitLength) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix: " + value);
            }
            byte[] network = address.getAddress().clone();
            mask(network, prefix);
            return new Subnet(network, prefix);
        }

        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress().clone();
            if (candidate.length != network.length) {
                return false;
            }
            mask(candidate, prefixLength);
            return java.util.Arrays.equals(network, candidate);
        }

        private static void mask(byte[] value, int prefixLength) {
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            if (remainingBits > 0 && fullBytes < value.length) {
                value[fullBytes] &= (byte) (0xff << (8 - remainingBits));
                fullBytes++;
            }
            for (int index = fullBytes; index < value.length; index++) {
                value[index] = 0;
            }
        }
    }
}
