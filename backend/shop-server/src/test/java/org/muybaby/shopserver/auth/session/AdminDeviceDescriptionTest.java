package org.muybaby.shopserver.auth.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDeviceDescriptionTest {

    @Test
    void identifiesDesktopChromeOnMac() {
        AdminDeviceDescription description = AdminDeviceDescription.fromUserAgent(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        + "AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"
        );

        assertThat(description).isEqualTo(new AdminDeviceDescription("Mac", "Chrome", "macOS"));
    }

    @Test
    void identifiesMobileSafariOnIphone() {
        AdminDeviceDescription description = AdminDeviceDescription.fromUserAgent(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) "
                        + "AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1"
        );

        assertThat(description).isEqualTo(new AdminDeviceDescription("iPhone", "Safari", "iOS"));
    }
}
