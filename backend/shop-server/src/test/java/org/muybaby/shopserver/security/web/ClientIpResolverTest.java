package org.muybaby.shopserver.security.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void usesForwardedChainOnlyWhenImmediatePeerIsTrusted() {
        ClientIpResolver resolver = resolver(20, 2_048);
        MockHttpServletRequest trusted = request("127.0.0.1", "198.51.100.25, 10.0.0.8");
        MockHttpServletRequest untrusted = request("203.0.113.10", "198.51.100.25");

        assertThat(resolver.resolve(trusted)).isEqualTo("198.51.100.25");
        assertThat(resolver.resolve(untrusted)).isEqualTo("203.0.113.10");
    }

    @Test
    void invalidOrOversizedForwardedChainFallsBackToDirectPeer() {
        ClientIpResolver resolver = resolver(2, 30);

        assertThat(resolver.resolve(request("127.0.0.1", "198.51.100.1, invalid")))
                .isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", "198.51.100.1, 10.0.0.1, 10.0.0.2")))
                .isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", "198.51.100.12345678901234567890")))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void normalizesIpv6LiteralsAndFallsBackForUnknownRemoteAddress() {
        ClientIpResolver resolver = resolver(20, 2_048);

        assertThat(resolver.resolve(request("::1", "2001:db8::25"))).isEqualTo("2001:db8:0:0:0:0:0:25");
        assertThat(resolver.resolve(request("not-an-ip", null))).isEqualTo("unknown");
    }

    @Test
    void rejectsHostnameShapedValuesAndStopsAtFirstUntrustedHopFromTheRight() {
        ClientIpResolver resolver = resolver(20, 2_048);

        assertThat(resolver.resolve(request("127.0.0.1", "face"))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", "dead.cafe"))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", "face, 198.51.100.25")))
                .isEqualTo("198.51.100.25");
    }

    private ClientIpResolver resolver(int maxHops, int maxLength) {
        return new ClientIpResolver(new ClientIpProperties(
                List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8"),
                maxHops,
                maxLength));
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
