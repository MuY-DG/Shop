package org.muybaby.shopserver.security.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "shop.security.client-ip")
public record ClientIpProperties(
        List<String> trustedProxyCidrs,
        Integer maxForwardedHops,
        Integer maxForwardedHeaderLength
) {

    public List<String> effectiveTrustedProxyCidrs() {
        return trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()
                ? List.of("127.0.0.0/8", "::1/128")
                : List.copyOf(trustedProxyCidrs);
    }

    public int effectiveMaxForwardedHops() {
        return maxForwardedHops == null || maxForwardedHops < 1 ? 20 : maxForwardedHops;
    }

    public int effectiveMaxForwardedHeaderLength() {
        return maxForwardedHeaderLength == null || maxForwardedHeaderLength < 1
                ? 2_048
                : maxForwardedHeaderLength;
    }
}
