package org.muybaby.shopserver.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.order.CheckoutSource;
import org.muybaby.shopserver.order.OrderStatusGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CommerceFulfillmentSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void v10CreatesAddressCarrierDigestAndShipmentColumns() {
        Integer addressColumns = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'user_address'
                        """)
                .query(Integer.class)
                .single();
        assertThat(addressColumns).isEqualTo(11);

        Integer carrierColumns = jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'wechat_delivery_company'
                        """)
                .query(Integer.class)
                .single();
        assertThat(carrierColumns).isEqualTo(4);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'shop_order'
                          and column_name = 'checkout_request_digest'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        Integer receiverAddressLength = jdbcClient.sql("""
                        select character_maximum_length
                        from information_schema.columns
                        where table_name = 'shop_order'
                          and column_name = 'receiver_address'
                        """)
                .query(Integer.class)
                .single();
        assertThat(receiverAddressLength).isEqualTo(512);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'order_shipment'
                          and column_name in (
                            'logistics_type', 'delivery_mode', 'item_desc',
                            'express_company_code', 'express_company_name',
                            'consignor_contact', 'receiver_contact',
                            'upload_time', 'last_attempt_at', 'wechat_provider_mode'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(10);
    }

    @Test
    void enumContractsUseOfficialValuesAndJsonShapes() throws Exception {
        assertThat(LogisticsType.EXPRESS.value()).isEqualTo(1);
        assertThat(LogisticsType.LOCAL_DELIVERY.value()).isEqualTo(2);
        assertThat(LogisticsType.VIRTUAL.value()).isEqualTo(3);
        assertThat(LogisticsType.PICKUP.value()).isEqualTo(4);
        assertThat(DeliveryMode.UNIFIED.value()).isEqualTo(1);

        assertThat(objectMapper.writeValueAsString(LogisticsType.EXPRESS)).isEqualTo("1");
        assertThat(objectMapper.readValue("4", LogisticsType.class)).isSameAs(LogisticsType.PICKUP);
        assertThat(objectMapper.writeValueAsString(DeliveryMode.UNIFIED)).isEqualTo("1");
        assertThat(objectMapper.readValue("1", DeliveryMode.class)).isSameAs(DeliveryMode.UNIFIED);

        assertThat(CheckoutSource.values()).containsExactly(CheckoutSource.CART, CheckoutSource.DIRECT);
        assertThat(OrderStatusGroup.values()).containsExactly(
                OrderStatusGroup.ALL,
                OrderStatusGroup.UNPAID,
                OrderStatusGroup.TO_SHIP,
                OrderStatusGroup.TO_RECEIVE,
                OrderStatusGroup.COMPLETED
        );
        assertThat(WechatProviderMode.values()).containsExactly(
                WechatProviderMode.REAL,
                WechatProviderMode.MOCK,
                WechatProviderMode.DISABLED,
                WechatProviderMode.UNKNOWN
        );
        assertThat(WechatShippingUploadStatus.values()).containsExactly(
                WechatShippingUploadStatus.SKIPPED,
                WechatShippingUploadStatus.UPLOADING,
                WechatShippingUploadStatus.UPLOADED,
                WechatShippingUploadStatus.FAILED,
                WechatShippingUploadStatus.UNAVAILABLE,
                WechatShippingUploadStatus.UNKNOWN
        );
    }
}
