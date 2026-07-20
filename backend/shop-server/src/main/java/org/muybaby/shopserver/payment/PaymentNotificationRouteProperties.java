package org.muybaby.shopserver.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls issuing opaque, per-operation callback routes.
 *
 * <p>The routed handlers are always registered. Keep issuance disabled during the first rolling
 * deployment, then enable it only after every instance can serve the routed paths.</p>
 */
@ConfigurationProperties(prefix = "shop.pay.notification-route")
public record PaymentNotificationRouteProperties(boolean enabled) {
}
