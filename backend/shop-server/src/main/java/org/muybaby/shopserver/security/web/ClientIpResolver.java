package org.muybaby.shopserver.security.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

@Component
public class ClientIpResolver {

    private final List<Subnet> trustedProxies;
    private final int maxForwardedHops;
    private final int maxForwardedHeaderLength;

    public ClientIpResolver(ClientIpProperties properties) {
        this.trustedProxies = properties.effectiveTrustedProxyCidrs().stream()
                .map(Subnet::parse)
                .toList();
        this.maxForwardedHops = properties.effectiveMaxForwardedHops();
        this.maxForwardedHeaderLength = properties.effectiveMaxForwardedHeaderLength();
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
        if (forwardedFor.length() > maxForwardedHeaderLength) {
            return remoteAddress;
        }

        String[] values = forwardedFor.split(",", -1);
        if (values.length > maxForwardedHops) {
            return remoteAddress;
        }
        String leftmost = remoteAddress;
        for (int index = values.length - 1; index >= 0; index--) {
            String normalized = normalizeLiteral(values[index]);
            if (normalized == null) {
                return remoteAddress;
            }
            if (!isTrusted(normalized)) {
                return normalized;
            }
            leftmost = normalized;
        }
        return leftmost;
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
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.contains(":")) {
            return parseIpv4Literal(value);
        }
        if (!value.matches("[0-9a-fA-F:]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static InetAddress parseIpv4Literal(String value) {
        if (!value.matches("[0-9.]+")) {
            return null;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        try {
            for (int index = 0; index < octets.length; index++) {
                if (octets[index].isEmpty() || octets[index].length() > 3) {
                    return null;
                }
                int octet = Integer.parseInt(octets[index]);
                if (octet < 0 || octet > 255) {
                    return null;
                }
                address[index] = (byte) octet;
            }
            return InetAddress.getByAddress(address);
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
            int prefix = parts.length == 1 ? bitLength : parsePrefix(parts[1], value);
            if (prefix < 0 || prefix > bitLength) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix: " + value);
            }
            byte[] network = address.getAddress().clone();
            mask(network, prefix);
            return new Subnet(network, prefix);
        }

        private static int parsePrefix(String prefix, String value) {
            try {
                return Integer.parseInt(prefix);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix: " + value, ex);
            }
        }

        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress().clone();
            if (candidate.length != network.length) {
                return false;
            }
            mask(candidate, prefixLength);
            return Arrays.equals(network, candidate);
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
