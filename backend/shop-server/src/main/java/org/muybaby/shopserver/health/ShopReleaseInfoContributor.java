package org.muybaby.shopserver.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ShopReleaseInfoContributor implements InfoContributor {

    private static final String UNKNOWN = "unknown";

    private final BuildProperties buildProperties;
    private final Flyway flyway;

    public ShopReleaseInfoContributor(BuildProperties buildProperties, Flyway flyway) {
        this.buildProperties = buildProperties;
        this.flyway = flyway;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("gitSha", valueOrUnknown(buildProperties.get("gitSha")))
                .withDetail("buildTime", buildTime())
                .withDetail("version", valueOrUnknown(buildProperties.getVersion()))
                .withDetail("flywayVersion", flywayVersion());
    }

    private String buildTime() {
        Instant time = buildProperties.getTime();
        return time == null ? UNKNOWN : time.toString();
    }

    private String flywayVersion() {
        MigrationInfo current = flyway.info().current();
        return current == null || current.getVersion() == null
                ? UNKNOWN
                : current.getVersion().getVersion();
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
