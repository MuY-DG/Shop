package org.muybaby.shopserver.logistics;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shipmentTableAcceptsRepresentativeRow() {
        assertThat(OrderStatus.valueOf("SHIPPED").name()).isEqualTo("SHIPPED");
        assertThat(OrderStatus.valueOf("COMPLETED").name()).isEqualTo("COMPLETED");
        assertThat(ErrorCode.valueOf("WECHAT_SHIPPING_UPLOAD_FAILED")).isSameAs(ErrorCode.WECHAT_SHIPPING_UPLOAD_FAILED);

        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, paid_at, shipped_at)
                        values
                            (19201, 'SHIP-SCHEMA-ORDER', 1, 'SHIPPED', 'CART', 'ship-schema-order',
                             5980, 5980, 0, 0, 5980, 5980, current_timestamp, current_timestamp)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into order_shipment
                            (id, order_id, express_company_name, tracking_no, shipment_note, status,
                             logistics_type, delivery_mode, item_desc,
                             wechat_upload_status, wechat_error_code, wechat_error_message,
                             retry_count, shipped_at, wechat_uploaded_at)
                        values
                            (19202, 19201, '顺丰速运', 'SF1234567890', 'schema shipment',
                             'SHIPPED', 1, 1, 'Shipment Item',
                             'UPLOADED', '', '', 0, current_timestamp, current_timestamp)
                        """)
                .update();

        Integer shipmentCount = jdbcClient.sql("""
                        select count(*)
                        from order_shipment
                        where order_id = 19201
                          and tracking_no = 'SF1234567890'
                          and shipment_source = 'MANUAL'
                          and electronic_waybill_id is null
                          and logistics_type = 1
                          and delivery_mode = 1
                          and item_desc = 'Shipment Item'
                          and wechat_upload_status = 'UPLOADED'
                        """)
                .query(Integer.class)
                .single();

        assertThat(shipmentCount).isEqualTo(1);
    }

    @Test
    void reliableWechatDeliveryColumnsSupportPendingAndTokenFencedWork() {
        assertThat(WechatShippingUploadStatus.valueOf("PENDING"))
                .isEqualTo(WechatShippingUploadStatus.PENDING);

        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, paid_at, shipped_at)
                        values
                            (19221, 'RELIABLE-SHIP-SCHEMA', 1, 'SHIPPED', 'CART',
                             'reliable-ship-schema', 5980, 5980, 0, 0, 5980, 5980,
                             current_timestamp, current_timestamp)
                        """).update();
        jdbcClient.sql("""
                        insert into order_shipment
                            (id, order_id, express_company_name, tracking_no, shipment_note, status,
                             logistics_type, delivery_mode, item_desc,
                             wechat_provider_mode, wechat_upload_status,
                             wechat_error_code, wechat_error_message, retry_count,
                             wechat_upload_next_action_at, shipped_at)
                        values
                            (19222, 19221, null, null, '', 'SHIPPED',
                             4, 1, 'Reliable shipment', 'REAL', 'PENDING', '', '', 0,
                             current_timestamp, current_timestamp)
                        """).update();

        var row = jdbcClient.sql("""
                        select wechat_upload_status,
                               wechat_upload_claim_token,
                               wechat_upload_claimed_at,
                               wechat_upload_attempt_count,
                               wechat_upload_not_uploaded_observations,
                               wechat_upload_last_reconciled_at
                        from order_shipment
                        where id = 19222
                        """).query().singleRow();

        assertThat(row.get("wechat_upload_status")).isEqualTo("PENDING");
        assertThat(row.get("wechat_upload_claim_token")).isNull();
        assertThat(row.get("wechat_upload_claimed_at")).isNull();
        assertThat(row.get("wechat_upload_attempt_count")).isEqualTo(0);
        assertThat(row.get("wechat_upload_not_uploaded_observations")).isEqualTo(0);
        assertThat(row.get("wechat_upload_last_reconciled_at")).isNull();
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(table_name) = 'order_shipment'
                          and lower(index_name) = 'idx_order_shipment_wechat_delivery_due'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void nonExpressShipmentAllowsNullCarrierAndTrackingNumber() {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, paid_at, shipped_at)
                        values
                            (19211, 'NON-EXPRESS-SCHEMA-ORDER', 1, 'SHIPPED', 'CART',
                             'non-express-schema-order', 5980, 5980, 0, 0, 5980, 5980,
                             current_timestamp, current_timestamp)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into order_shipment
                            (id, order_id, express_company_name, tracking_no, shipment_note, status,
                             logistics_type, delivery_mode, item_desc,
                             wechat_upload_status, wechat_error_code, wechat_error_message,
                             retry_count, shipped_at, wechat_uploaded_at)
                        values
                            (19212, 19211, null, null, 'local delivery', 'SHIPPED',
                             2, 1, 'Shipment Item', 'SKIPPED', '', '', 0,
                             current_timestamp, null)
                        """)
                .update();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_shipment
                        where order_id = 19211
                          and logistics_type = 2
                          and express_company_name is null
                          and tracking_no is null
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void shipmentPermissionsAreAttachedToOrderListMenu() {
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where id in (8101, 8102)
                          and auth_mark in ('order:ship', 'order:shipping:retry')
                        """)
                .query(Integer.class)
                .single();
        Integer menuPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission
                        where menu_id = 501
                          and permission_id in (8101, 8102)
                        """)
                .query(Integer.class)
                .single();

        assertThat(permissionCount).isEqualTo(2);
        assertThat(menuPermissionCount).isEqualTo(2);
    }
}
