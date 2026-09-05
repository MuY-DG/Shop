package org.muybaby.shopserver.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

class AfterSaleFulfillmentMigrationTest {

    @Test
    void v19ClassifiesLegacyRefundsOnlyWhenPreShipmentEvidenceIsComplete() {
        String jdbcUrl = "jdbc:h2:mem:v19_history_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        MigrationTestSupport.migrateToVersion(jdbcUrl, "sa", "", "18");
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        AfterSaleFulfillmentMigrationTestSupport.insertLegacyRows(jdbc);

        MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", "");

        AfterSaleFulfillmentMigrationTestSupport.assertConservativeHistory(jdbc);
    }
}
