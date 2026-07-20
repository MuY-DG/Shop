package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AdminProductParameterDefinitionResponse;
import org.muybaby.shopserver.product.dto.AdminSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AppProductParameterValueResponse;
import org.muybaby.shopserver.product.dto.AppSpuListItemResponse;
import org.muybaby.shopserver.product.dto.ProductPageRequest;
import org.muybaby.shopserver.product.service.AppProductService;
import org.muybaby.shopserver.product.service.ProductParameterService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(ProductQueryCountTest.CountingDataSourceConfiguration.class)
@Transactional
class ProductQueryCountTest {

    @Autowired
    private AppProductService appProductService;

    @Autowired
    private ProductParameterService productParameterService;

    @Autowired
    private ProductReadMapper productReadMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SqlCounter sqlCounter;

    @Test
    void appProductPageUsesConstantQueriesAndPreservesParameterSnapshotSemantics() {
        long categoryId = 8_100_000L;
        long selectParameterId = 8_100_100L;
        long detailOnlyParameterId = 8_100_101L;
        insertCategory(categoryId, "Query Count App Category");
        insertParameter(selectParameterId, "QC_SELECT", "选择参数", "MULTI_SELECT", true, true, 0);
        insertOption(8_100_200L, selectParameterId, "FIRST", "第一项", 1, 0);
        insertOption(8_100_201L, selectParameterId, "SECOND", "第二项", 2, 1);
        insertParameter(detailOnlyParameterId, "QC_DETAIL_ONLY", "详情参数", "TEXT", false, true, 1);

        long firstSpuId = 8_101_000L;
        insertPublishedSpuWithSku(firstSpuId, 8_102_000L, categoryId, 0);
        insertParameterValue(firstSpuId, selectParameterId, "", "[\"SECOND\",\"UNKNOWN\",\"FIRST\"]");
        insertParameterValue(firstSpuId, detailOnlyParameterId, "仅详情可见", "[]");

        sqlCounter.reset();
        PageResult<AppSpuListItemResponse> single = appProductService.page(
                new ProductPageRequest(categoryId, null, 1L, 50L)
        );
        int singleProductQueries = sqlCounter.count();

        assertThat(single.records()).hasSize(1);
        assertParameterSnapshot(single.records().getFirst().parameters().getFirst());
        assertThat(single.records().getFirst().parameters())
                .extracting(AppProductParameterValueResponse::parameterCode)
                .containsExactly("QC_SELECT");

        for (int index = 1; index < 20; index++) {
            long spuId = firstSpuId + index;
            insertPublishedSpuWithSku(spuId, 8_102_000L + index, categoryId, index);
            insertParameterValue(spuId, selectParameterId, "", "[\"SECOND\",\"UNKNOWN\",\"FIRST\"]");
        }

        sqlCounter.reset();
        PageResult<AppSpuListItemResponse> many = appProductService.page(
                new ProductPageRequest(categoryId, null, 1L, 50L)
        );
        int manyProductQueries = sqlCounter.count();

        assertThat(many.records()).hasSize(20);
        assertThat(singleProductQueries).isEqualTo(4);
        assertThat(manyProductQueries).isEqualTo(singleProductQueries);
        assertThat(many.records()).allSatisfy(record -> assertParameterSnapshot(record.parameters().getFirst()));

        List<AppProductParameterValueResponse> detailParameters =
                productParameterService.displayValuesBySpuIds(List.of(firstSpuId), false).get(firstSpuId);
        assertThat(detailParameters)
                .extracting(AppProductParameterValueResponse::parameterCode)
                .containsExactly("QC_SELECT", "QC_DETAIL_ONLY");
    }

    @Test
    void adminProductDetailUsesConstantQueriesForSkuAndSpecTreeSize() {
        long categoryId = 8_200_000L;
        long spuId = 8_201_000L;
        long groupId = 8_202_000L;
        long redValueId = 8_203_000L;
        long blueValueId = 8_203_001L;
        insertCategory(categoryId, "Query Count Admin Category");
        insertSpu(spuId, categoryId, "Query Count Admin SPU", "DRAFT", "MULTI", 0);
        jdbcClient.sql("""
                        insert into product_spu_spec_group
                            (id, spu_id, group_key, name, image_enabled, sort_order)
                        values (:id, :spuId, 'color', '颜色', true, 0)
                        """)
                .param("id", groupId)
                .param("spuId", spuId)
                .update();
        insertSpecValue(redValueId, groupId, "red", "红色", 0);
        insertSpecValue(blueValueId, groupId, "blue", "蓝色", 1);

        long firstSkuId = 8_204_000L;
        insertSku(firstSkuId, spuId, 0);
        insertSkuSpecValue(firstSkuId, blueValueId);
        insertSkuSpecValue(firstSkuId, redValueId);

        sqlCounter.reset();
        AdminSpuDetailResponse single = productReadMapper.adminSpuDetail(spuId);
        int singleSkuQueries = sqlCounter.count();

        assertThat(single.skus()).hasSize(1);
        assertThat(single.skus().getFirst().specValueKeys()).containsExactly("red", "blue");
        assertThat(single.specGroups()).hasSize(1);
        assertThat(single.specGroups().getFirst().values())
                .extracting("valueKey")
                .containsExactly("red", "blue");

        for (int index = 1; index < 20; index++) {
            long skuId = firstSkuId + index;
            insertSku(skuId, spuId, index);
            insertSkuSpecValue(skuId, redValueId);
        }

        sqlCounter.reset();
        AdminSpuDetailResponse many = productReadMapper.adminSpuDetail(spuId);
        int manySkuQueries = sqlCounter.count();

        assertThat(many.skus()).hasSize(20);
        assertThat(singleSkuQueries).isEqualTo(9);
        assertThat(manySkuQueries).isEqualTo(singleSkuQueries);
    }

    @Test
    void parameterDefinitionsHydrateBindingsAndOptionsWithConstantQueries() {
        long categoryId = 8_300_000L;
        insertCategory(categoryId, "Query Count Definition Category");
        insertDefinitionWithBindingAndOption(8_301_000L, 8_302_000L, categoryId, 0);

        sqlCounter.reset();
        List<AdminProductParameterDefinitionResponse> single = productParameterService.definitions(null, false);
        int singleDefinitionQueries = sqlCounter.count();

        assertThat(single).hasSize(1);
        assertThat(single.getFirst().categoryIds()).containsExactly(categoryId);
        assertThat(single.getFirst().options()).extracting("optionCode").containsExactly("OPTION_0");

        for (int index = 1; index < 20; index++) {
            insertDefinitionWithBindingAndOption(
                    8_301_000L + index,
                    8_302_000L + index,
                    categoryId,
                    index
            );
        }

        sqlCounter.reset();
        List<AdminProductParameterDefinitionResponse> many = productParameterService.definitions(null, false);
        int manyDefinitionQueries = sqlCounter.count();

        assertThat(many).hasSize(20);
        assertThat(singleDefinitionQueries).isEqualTo(3);
        assertThat(manyDefinitionQueries).isEqualTo(singleDefinitionQueries);
        assertThat(many).allSatisfy(definition -> {
            assertThat(definition.categoryIds()).containsExactly(categoryId);
            assertThat(definition.options()).hasSize(1);
        });
    }

    private void assertParameterSnapshot(AppProductParameterValueResponse parameter) {
        assertThat(parameter.parameterCode()).isEqualTo("QC_SELECT");
        assertThat(parameter.displayText()).isEqualTo("第二项、UNKNOWN、第一项");
        assertThat(parameter.selectedOptions())
                .extracting("optionCode")
                .containsExactly("SECOND", "FIRST");
    }

    private void insertCategory(long categoryId, String name) {
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, icon, sort_order, status)
                        values (:id, 0, :name, '', 0, 'ENABLED')
                        """)
                .param("id", categoryId)
                .param("name", name)
                .update();
    }

    private void insertPublishedSpuWithSku(long spuId, long skuId, long categoryId, int sortOrder) {
        insertSpu(spuId, categoryId, "Query Count App SPU " + spuId, "ON_SALE", "SINGLE", sortOrder);
        insertSku(skuId, spuId, sortOrder);
    }

    private void insertSpu(
            long spuId,
            long categoryId,
            String title,
            String status,
            String specType,
            int sortOrder
    ) {
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, subtitle, main_image, spec_type,
                             selling_points, detail_html, sort_order, status)
                        values
                            (:id, :categoryId, :title, '', 'https://example.test/query-count.png', :specType,
                             '批量,查询', '<p>detail</p>', :sortOrder, :status)
                        """)
                .param("id", spuId)
                .param("categoryId", categoryId)
                .param("title", title)
                .param("specType", specType)
                .param("sortOrder", sortOrder)
                .param("status", status)
                .update();
    }

    private void insertSku(long skuId, long spuId, int sortOrder) {
        jdbcClient.sql("""
                        insert into product_sku
                            (id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, image, status, is_default, combination_key, sort_order)
                        values
                            (:id, :spuId, :skuCode, '{}', :specText, 1000, 1200,
                             10, '', 'ENABLED', :defaultSelected, :combinationKey, :sortOrder)
                        """)
                .param("id", skuId)
                .param("spuId", spuId)
                .param("skuCode", "QUERY-COUNT-SKU-" + skuId)
                .param("specText", "规格-" + skuId)
                .param("defaultSelected", sortOrder == 0)
                .param("combinationKey", "combination-" + skuId)
                .param("sortOrder", sortOrder)
                .update();
    }

    private void insertParameter(
            long parameterId,
            String code,
            String name,
            String valueType,
            boolean cardVisible,
            boolean detailVisible,
            int sortOrder
    ) {
        jdbcClient.sql("""
                        insert into product_parameter_definition
                            (id, parameter_code, parameter_name, value_type, unit, description,
                             required_value, filterable, card_visible, detail_visible, sort_order, status)
                        values
                            (:id, :code, :name, :valueType, '', '', false, false,
                             :cardVisible, :detailVisible, :sortOrder, 'ENABLED')
                        """)
                .param("id", parameterId)
                .param("code", code)
                .param("name", name)
                .param("valueType", valueType)
                .param("cardVisible", cardVisible)
                .param("detailVisible", detailVisible)
                .param("sortOrder", sortOrder)
                .update();
    }

    private void insertOption(
            long optionId,
            long parameterId,
            String code,
            String label,
            Integer displayLevel,
            int sortOrder
    ) {
        jdbcClient.sql("""
                        insert into product_parameter_option
                            (id, parameter_id, option_code, option_label, display_level, sort_order)
                        values (:id, :parameterId, :code, :label, :displayLevel, :sortOrder)
                        """)
                .param("id", optionId)
                .param("parameterId", parameterId)
                .param("code", code)
                .param("label", label)
                .param("displayLevel", displayLevel)
                .param("sortOrder", sortOrder)
                .update();
    }

    private void insertParameterValue(long spuId, long parameterId, String textValue, String optionCodesJson) {
        jdbcClient.sql("""
                        insert into product_spu_parameter_value
                            (spu_id, parameter_id, text_value, option_codes_json)
                        values (:spuId, :parameterId, :textValue, :optionCodesJson)
                        """)
                .param("spuId", spuId)
                .param("parameterId", parameterId)
                .param("textValue", textValue)
                .param("optionCodesJson", optionCodesJson)
                .update();
    }

    private void insertSpecValue(long valueId, long groupId, String key, String name, int sortOrder) {
        jdbcClient.sql("""
                        insert into product_spu_spec_value
                            (id, group_id, value_key, value_name, image, sort_order)
                        values (:id, :groupId, :key, :name, '', :sortOrder)
                        """)
                .param("id", valueId)
                .param("groupId", groupId)
                .param("key", key)
                .param("name", name)
                .param("sortOrder", sortOrder)
                .update();
    }

    private void insertSkuSpecValue(long skuId, long specValueId) {
        jdbcClient.sql("""
                        insert into product_sku_spec_value (sku_id, spec_value_id)
                        values (:skuId, :specValueId)
                        """)
                .param("skuId", skuId)
                .param("specValueId", specValueId)
                .update();
    }

    private void insertDefinitionWithBindingAndOption(
            long parameterId,
            long optionId,
            long categoryId,
            int sortOrder
    ) {
        insertParameter(
                parameterId,
                "QC_DEFINITION_" + sortOrder,
                "批量定义" + sortOrder,
                "SINGLE_SELECT",
                true,
                true,
                sortOrder
        );
        jdbcClient.sql("""
                        insert into product_category_parameter (category_id, parameter_id)
                        values (:categoryId, :parameterId)
                        """)
                .param("categoryId", categoryId)
                .param("parameterId", parameterId)
                .update();
        insertOption(
                optionId,
                parameterId,
                "OPTION_" + sortOrder,
                "选项" + sortOrder,
                sortOrder,
                sortOrder
        );
    }

    static final class SqlCounter {
        private final AtomicInteger count = new AtomicInteger();

        int count() {
            return count.get();
        }

        void increment() {
            count.incrementAndGet();
        }

        void reset() {
            count.set(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingDataSourceConfiguration {

        @Bean
        SqlCounter sqlCounter() {
            return new SqlCounter();
        }

        @Bean
        @Primary
        CountingDataSource dataSource(DataSourceProperties properties, SqlCounter sqlCounter) {
            return new CountingDataSource(properties.initializeDataSourceBuilder().build(), sqlCounter);
        }
    }

    static final class CountingDataSource extends AbstractDataSource implements AutoCloseable {
        private final DataSource delegate;
        private final SqlCounter sqlCounter;

        private CountingDataSource(DataSource delegate, SqlCounter sqlCounter) {
            this.delegate = delegate;
            this.sqlCounter = sqlCounter;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return countingConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return countingConnection(delegate.getConnection(username, password));
        }

        private Connection countingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        String methodName = method.getName();
                        if ("prepareStatement".equals(methodName)
                                || "prepareCall".equals(methodName)
                                || "createStatement".equals(methodName)) {
                            sqlCounter.increment();
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                    }
            );
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
