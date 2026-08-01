package org.muybaby.shopserver.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.Granularity;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ReportQuery;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TradeStatisticsReport;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.TrafficStatisticsReport;
import org.muybaby.shopserver.operation.query.TrafficStatisticsQueryRepository;
import org.muybaby.shopserver.operation.query.CommerceTrendQueryRepository;
import org.muybaby.shopserver.operation.service.OperationsStatisticsService;
import org.muybaby.shopserver.storage.AssetModelMigrationTest;
import org.muybaby.shopserver.user.service.AdminCustomerService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.muybaby.shopserver.support.MigrationTestSupport.latestMigrationVersion;

@Testcontainers
class CommerceFulfillmentMySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> CLEAN_MYSQL = mysql("fulfillment_clean");

    @Container
    private static final MySQLContainer<?> UPGRADE_MYSQL = mysql("fulfillment_upgrade");

    @Test
    void cleanMySqlSchemaMigratesFromV1ToLatest() throws SQLException {
        CommerceFulfillmentMigrationTest.migrateToLatest(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());

        Flyway flyway = Flyway.configure()
                .dataSource(CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword())
                .placeholders(CommerceFulfillmentMigrationTest.safeSeedPlaceholders())
                .load();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        AssetModelMigrationTest.assertFinalAssetSchema(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        assertAdminCustomerCouponQueryIsCollationSafe();

        try (Connection connection = DriverManager.getConnection(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                     from information_schema.columns
                     where table_schema = database()
                       and table_name = 'order_shipment'
                       and column_name in (
                         'logistics_type', 'delivery_mode', 'item_desc',
                         'express_company_code', 'express_company_name',
                         'consignor_contact', 'receiver_contact',
                         'upload_time', 'last_attempt_at', 'wechat_provider_mode'
                       )
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(10);
        }
    }

    @Test
    void environmentPaymentSnapshotInsertIsAppendOnlyOnMySql() {
        CommerceFulfillmentMigrationTest.migrateToLatest(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword()));
        String fingerprint = "a".repeat(64);

        insertEnvironmentSnapshot(jdbcClient, fingerprint, "wx-original", "v1:original-ciphertext");
        insertEnvironmentSnapshot(jdbcClient, fingerprint, "wx-replacement", "v1:replacement-ciphertext");

        assertThat(jdbcClient.sql("""
                        select app_id
                        from payment_config_snapshot
                        where fingerprint = :fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .query(String.class)
                .single()).isEqualTo("wx-original");
        assertThat(jdbcClient.sql("""
                        select api_v3_key_ciphertext
                        from payment_config_snapshot
                        where fingerprint = :fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .query(String.class)
                .single()).isEqualTo("v1:original-ciphertext");
    }

    private void insertEnvironmentSnapshot(
            JdbcClient jdbcClient,
            String fingerprint,
            String appId,
            String apiV3KeyCiphertext
    ) {
        jdbcClient.sql("""
                        insert into payment_config_snapshot
                            (fingerprint, config_source, config_name, app_id, mch_id,
                             merchant_serial_no, api_v3_key_ciphertext, private_key_pem_ciphertext,
                             notify_url, refund_notify_url, verify_mode, wechat_public_key_id,
                             wechat_public_key_pem_ciphertext)
                        values
                            (:fingerprint, 'ENV', 'MySQL snapshot', :appId, 'mch-snapshot',
                             'serial-snapshot', :apiV3KeyCiphertext, 'v1:private-ciphertext',
                             'https://example.test/wxpay/pay/notify',
                             'https://example.test/wxpay/refund/notify', 'PUBLIC_KEY',
                             'wechat-public-key-id', 'v1:public-ciphertext')
                        on duplicate key update fingerprint = fingerprint
                        """)
                .param("fingerprint", fingerprint)
                .param("appId", appId)
                .param("apiV3KeyCiphertext", apiV3KeyCiphertext)
                .update();
    }

    private void assertAdminCustomerCouponQueryIsCollationSafe() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                CLEAN_MYSQL.getJdbcUrl(), CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        alter table coupon_template
                        modify scope_value varchar(255)
                        character set utf8mb4 collate utf8mb4_unicode_ci not null default ''
                        """)
                .update();
        jdbcClient.sql("""
                        insert into app_user (id, openid, nickname, status)
                        values (990001, 'mysql-customer-query', 'MySQL customer query', 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, status)
                        values (990001, 0, 'MySQL customer query', 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, selling_points, detail_html, status)
                        values
                            (990001, 990001, 'MySQL customer query', '', '', 'ON_SALE')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status)
                        values
                            (990001, 'MySQL customer query', 'MIN_SPEND', 'AMOUNT_OFF', 1000, 100,
                             'PRODUCT', '990001', 10, 0, 1,
                             current_timestamp - interval 1 day,
                             current_timestamp + interval 1 day,
                             'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_coupon (spu_id, coupon_template_id)
                        values (990001, 990001)
                        """)
                .update();

        AdminCustomerService service = new AdminCustomerService(
                jdbcClient,
                new NamedParameterJdbcTemplate(dataSource));

        assertThatCode(() -> service.issuableCouponTemplates(990001L))
                .doesNotThrowAnyException();
        assertThat(service.issuableCouponTemplates(990001L))
                .extracting("id")
                .containsExactly(990001L);
    }

    @Test
    void populatedMySqlSchemaMigratesThroughV16AndTheV17StorageCleanBreak() throws SQLException {
        String jdbcUrl = UPGRADE_MYSQL.getJdbcUrl();
        String username = UPGRADE_MYSQL.getUsername();
        String password = UPGRADE_MYSQL.getPassword();

        CommerceFulfillmentMigrationTest.migrateToV9(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.seedLegacyShipment(jdbcUrl, username, password);
        AssetModelMigrationTest.migrateToV16(jdbcUrl, username, password);
        AssetModelMigrationTest.seedLegacyStorageBindings(jdbcUrl, username, password);
        seedLegacyPhoneAuthorization(jdbcUrl, username, password);
        AssetModelMigrationTest.migrateToLatest(jdbcUrl, username, password);

        CommerceFulfillmentMigrationTest.assertMigratedLegacyShipment(jdbcUrl, username, password);
        CommerceFulfillmentMigrationTest.assertMigratedLegacyProduct(jdbcUrl, username, password);
        AssetModelMigrationTest.assertFinalAssetSchema(jdbcUrl, username, password);
        AssetModelMigrationTest.assertLegacyBindingsWereCleared(jdbcUrl, username, password);
        assertLegacyPhoneAuthorizationWasSnapshotted(jdbcUrl, username, password);
    }

    @Test
    void operationsStatisticsUseShanghaiDayBoundariesForUtcMySqlTimestamps() {
        String jdbcUrl = CLEAN_MYSQL.getJdbcUrl();
        CommerceFulfillmentMigrationTest.migrateToLatest(
                jdbcUrl, CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                jdbcUrl, CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                        insert into app_user (id, openid, status, created_at, updated_at)
                        values (990101, 'mysql-operations-timezone', 'ENABLED',
                                timestamp '2029-12-01 00:00:00', timestamp '2029-12-01 00:00:00')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (990111, 'MYSQL-OPS-BEFORE', 990101, 'PAID', 'mysql-ops-before',
                             100, 100, timestamp '2029-12-31 15:59:59',
                             timestamp '2029-12-31 15:59:59', timestamp '2029-12-31 15:59:59'),
                            (990112, 'MYSQL-OPS-START', 990101, 'PAID', 'mysql-ops-start',
                             200, 200, timestamp '2029-12-31 16:00:00',
                             timestamp '2029-12-31 16:00:00', timestamp '2029-12-31 16:00:00'),
                            (990113, 'MYSQL-OPS-END', 990101, 'PAID', 'mysql-ops-end',
                             300, 300, timestamp '2030-01-01 15:59:59',
                             timestamp '2030-01-01 15:59:59', timestamp '2030-01-01 15:59:59'),
                            (990114, 'MYSQL-OPS-AFTER', 990101, 'PAID', 'mysql-ops-after',
                             400, 400, timestamp '2030-01-01 16:00:00',
                             timestamp '2030-01-01 16:00:00', timestamp '2030-01-01 16:00:00')
                        """)
                .update();

        TradeStatisticsReport report = operationsStatisticsService(jdbcClient).tradeStatistics(
                new ReportQuery(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1), Granularity.HOUR)
        );

        assertThat(report.meta().timezone()).isEqualTo("Asia/Shanghai");
        assertThat(report.summary().get("createdOrderCount").value()).isEqualTo(2);
        assertThat(report.summary().get("paidOrderCount").value()).isEqualTo(2);
        assertThat(report.summary().get("paidAmountCent").value()).isEqualTo(500);
        assertThat(report.hourlyOrders().data())
                .filteredOn(point -> point.value() > 0)
                .extracting("label", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("00:00", 1L),
                        org.assertj.core.groups.Tuple.tuple("23:00", 1L)
                );
    }

    @Test
    void trafficStatisticsReadMySqlTimestampsWithoutReportingAuthenticationFailure() {
        String jdbcUrl = CLEAN_MYSQL.getJdbcUrl();
        CommerceFulfillmentMigrationTest.migrateToLatest(
                jdbcUrl, CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword());
        JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
                jdbcUrl, CLEAN_MYSQL.getUsername(), CLEAN_MYSQL.getPassword()));
        jdbcClient.sql("""
                        insert into analytics_event
                            (id, client_event_id, payload_digest, visitor_id, session_id,
                             event_source, event_type, page_path, occurred_at, received_at, business_date)
                        values
                            (990301, 'mysql-traffic-home', 'mysql-traffic-home-digest',
                             'mysql-traffic-visitor', 'mysql-traffic-session',
                             'CLIENT', 'PAGE_VIEW', '/pages/home/home',
                             timestamp '2030-02-01 04:00:00', timestamp '2030-02-01 04:00:01',
                             date '2030-02-01'),
                            (990302, 'mysql-traffic-product', 'mysql-traffic-product-digest',
                             'mysql-traffic-visitor', 'mysql-traffic-session',
                             'CLIENT', 'PRODUCT_VIEW', '/pages/product/detail/detail',
                             timestamp '2030-02-01 04:01:00', timestamp '2030-02-01 04:01:01',
                             date '2030-02-01'),
                            (990303, 'mysql-traffic-late-page', 'mysql-traffic-late-page-digest',
                             'mysql-traffic-visitor-2', 'mysql-traffic-session-2',
                             'CLIENT', 'PAGE_VIEW', '/pages/other',
                             timestamp '2030-02-01 15:59:59', timestamp '2030-02-01 15:59:59',
                             date '2030-02-01'),
                            (990304, 'mysql-traffic-late-search', 'mysql-traffic-late-search-digest',
                             'mysql-traffic-visitor', 'mysql-traffic-session-3',
                             'CLIENT', 'SEARCH', '/pages/search',
                             timestamp '2030-02-01 15:59:59', timestamp '2030-02-01 15:59:59',
                             date '2030-02-01')
                        """)
                .update();

        TrafficStatisticsReport report = operationsStatisticsService(jdbcClient).trafficStatistics(
                new ReportQuery(LocalDate.of(2030, 2, 1), LocalDate.of(2030, 2, 1), Granularity.HOUR)
        );

        assertThat(report.summary().get("pageViewCount").value()).isEqualTo(2);
        assertThat(report.summary().get("visitorCount").value()).isEqualTo(2);
        assertThat(report.summary().get("sessionCount").value()).isEqualTo(3);
        assertThat(report.trend().data().stream()
                .filter(series -> series.key().equals("pageViewCount"))
                .findFirst()
                .orElseThrow()
                .points())
                .filteredOn(point -> point.value() > 0)
                .extracting("label", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2030-02-01 12:00", 1L),
                        org.assertj.core.groups.Tuple.tuple("2030-02-01 23:00", 1L));
        assertThat(report.trend().data().stream()
                .filter(series -> series.key().equals("visitorCount"))
                .findFirst()
                .orElseThrow()
                .points())
                .filteredOn(point -> point.value() > 0)
                .extracting("label", "value")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2030-02-01 12:00", 1L),
                        org.assertj.core.groups.Tuple.tuple("2030-02-01 23:00", 2L));
        assertThat(report.funnel().data())
                .extracting("key", "users")
                .startsWith(
                        org.assertj.core.groups.Tuple.tuple("homeVisit", 1L),
                        org.assertj.core.groups.Tuple.tuple("productView", 1L)
                );
    }

    private void seedLegacyPhoneAuthorization(String jdbcUrl, String username, String password) {
        JdbcClient.create(new DriverManagerDataSource(jdbcUrl, username, password))
                .sql("""
                        insert into app_user (id, openid, phone_number, phone_authorized, status)
                        values (990201, 'mysql-legacy-phone-auth', '13800009902', true, 'ENABLED')
                        """)
                .update();
    }

    private void assertLegacyPhoneAuthorizationWasSnapshotted(
            String jdbcUrl,
            String username,
            String password
    ) {
        assertThat(JdbcClient.create(new DriverManagerDataSource(jdbcUrl, username, password))
                .sql("select phone_authorized_at from app_user where id = 990201")
                .query(java.time.LocalDateTime.class)
                .single()).isNotNull();
    }

    private static MySQLContainer<?> mysql(String databaseName) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(databaseName)
                .withUsername("shop_test")
                .withPassword("shop_test")
                .withEnv("TZ", "UTC")
                .withUrlParam("serverTimezone", "UTC");
    }

    private OperationsStatisticsService operationsStatisticsService(JdbcClient jdbcClient) {
        return new OperationsStatisticsService(
                jdbcClient,
                new CommerceTrendQueryRepository(jdbcClient),
                new TrafficStatisticsQueryRepository(jdbcClient));
    }
}
