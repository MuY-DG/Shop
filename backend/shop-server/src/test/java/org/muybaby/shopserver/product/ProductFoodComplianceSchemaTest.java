package org.muybaby.shopserver.product;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.support.MigrationTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductFoodComplianceSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationCreatesFoodDisclosureSchemaWithNonFoodDefaults() {
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (9890001, 0, 'Food Schema Category', '', 1, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu (
                            id, category_id, title, subtitle, main_image,
                            selling_points, detail_html, sort_order, status
                        ) values (
                            9890002, 9890001, 'Food Schema SPU', '',
                            'https://assets.example.test/v89-main.png', '', '', 1, 'DRAFT'
                        )
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_sku (
                            id, spu_id, sku_code, spec_json, spec_text,
                            price_cent, original_price_cent, stock_available,
                            weight_gram, image, status, sort_order, combination_key
                        ) values (
                            9890003, 9890002, 'FOOD-SCHEMA-SKU', '{}', '默认规格',
                            1000, 1200, 10, 100, '', 'ENABLED', 1, 'FOOD-SCHEMA-SKU'
                        )
                        """)
                .update();

        assertThat(columnExists("product_spu", "compliance_type")).isTrue();
        assertThat(columnExists("product_sku", "net_content_text")).isTrue();
        assertThat(columnCount("product_food_disclosure")).isEqualTo(14);
        assertThat(columnCount("product_food_disclosure_label")).isEqualTo(6);
        assertThat(jdbcClient.sql("select compliance_type from product_spu where id = 9890002")
                .query(String.class)
                .single()).isEqualTo(ProductComplianceType.NON_FOOD.name());
        assertThat(jdbcClient.sql("select net_content_text from product_sku where id = 9890003")
                .query(String.class)
                .single()).isEmpty();
        assertThat(StorageUsageOwnerType.valueOf("PRODUCT_FOOD_DISCLOSURE"))
                .isEqualTo(StorageUsageOwnerType.PRODUCT_FOOD_DISCLOSURE);
        assertThat(StorageFileUsageType.valueOf("PRODUCT_FOOD_LABEL"))
                .isEqualTo(StorageFileUsageType.PRODUCT_FOOD_LABEL);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update product_spu set compliance_type = 'UNCLASSIFIED' where id = 9890002
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationConvertsLegacyUnclassifiedProductsWithoutChangingFoodOrLifecycleState() {
        String jdbcUrl = "jdbc:h2:mem:product_compliance_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target("8")
                .load()
                .migrate();
        JdbcClient legacyJdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        legacyJdbc.sql("""
                        insert into product_spu
                            (id, category_id, title, selling_points, detail_html,
                             compliance_type, status, deleted_at, purged_at)
                        values
                            (9890011, 1, 'Legacy draft', '', '', 'UNCLASSIFIED', 'DRAFT', null, null),
                            (9890012, 1, 'Legacy recycled', '', '', 'UNCLASSIFIED', 'OFF_SALE',
                                current_timestamp, null),
                            (9890013, 1, 'Legacy purged', '', '', 'UNCLASSIFIED', 'OFF_SALE',
                                current_timestamp, current_timestamp),
                            (9890014, 1, 'Existing food', '', '', 'FOOD', 'ON_SALE', null, null),
                            (9890015, 1, 'Existing non-food', '', '', 'NON_FOOD', 'DRAFT', null, null)
                        """).update();
        var originalLifecycle = legacyJdbc.sql("""
                        select id, status, deleted_at, purged_at from product_spu order by id
                        """).query().listOfRows();

        MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", "");

        assertThat(legacyJdbc.sql("select compliance_type from product_spu order by id")
                .query(String.class).list())
                .containsExactly("NON_FOOD", "NON_FOOD", "NON_FOOD", "FOOD", "NON_FOOD");
        assertThat(legacyJdbc.sql("""
                        select id, status, deleted_at, purged_at from product_spu order by id
                        """).query().listOfRows())
                .isEqualTo(originalLifecycle);
        assertThatThrownBy(() -> legacyJdbc.sql("""
                        update product_spu set compliance_type = 'UNCLASSIFIED' where id = 9890011
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = :tableName
                          and lower(column_name) = :columnName
                        """)
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }

    private int columnCount(String tableName) {
        return jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = :tableName
                        """)
                .param("tableName", tableName)
                .query(Integer.class)
                .single();
    }
}
