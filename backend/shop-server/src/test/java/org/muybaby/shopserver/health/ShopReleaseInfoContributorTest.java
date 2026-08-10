package org.muybaby.shopserver.health;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.info.BuildProperties;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopReleaseInfoContributorTest {

    @Test
    void exposesOnlyReleaseIdentityAndCurrentMigration() {
        Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("time", "2026-08-10T12:34:56Z");
        properties.setProperty("gitSha", "0123456789ab");

        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("91"));
        MigrationInfoService migrationInfoService = mock(MigrationInfoService.class);
        when(migrationInfoService.current()).thenReturn(migration);
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenReturn(migrationInfoService);

        Info.Builder builder = new Info.Builder();
        new ShopReleaseInfoContributor(new BuildProperties(properties), flyway).contribute(builder);

        assertThat(builder.build().getDetails()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "gitSha", "0123456789ab",
                "buildTime", "2026-08-10T12:34:56Z",
                "version", "1.2.3",
                "flywayVersion", "91"
        ));
    }
}
