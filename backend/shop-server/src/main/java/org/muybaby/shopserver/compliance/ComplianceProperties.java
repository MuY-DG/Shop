package org.muybaby.shopserver.compliance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "shop.compliance")
public record ComplianceProperties(
        @DefaultValue("false") boolean privacyConsentRequired
) {
}
