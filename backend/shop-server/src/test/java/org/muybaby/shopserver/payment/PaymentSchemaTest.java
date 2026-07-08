package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaymentSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void paymentTablesAcceptConfigOrderAndCallbackRows() {
        assertThat(OrderStatus.valueOf("PAYING").name()).isEqualTo("PAYING");
        assertThat(StockChangeType.valueOf("ORDER_CONFIRM").name()).isEqualTo("ORDER_CONFIRM");
        assertThat(StorageFileUsageType.valueOf("PAYMENT_CONFIG_CERT")).isSameAs(StorageFileUsageType.PAYMENT_CONFIG_CERT);
        assertThat(ErrorCode.valueOf("PAYMENT_PENDING")).isSameAs(ErrorCode.PAYMENT_PENDING);

        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent)
                        values
                            (19101, 'PAY-SCHEMA-CREATED', 1, 'CREATED', 'CART', 'pay-schema-created',
                             3980, 3980, 0, 0, 3980, 0),
                            (19102, 'PAY-SCHEMA-PAYING', 1, 'PAYING', 'CART', 'pay-schema-paying',
                             3980, 3980, 0, 0, 3980, 0)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (19103, 'Schema Config', 'wx-schema-app', '1900000001', 'schema-serial',
                             'ciphertext-placeholder', null, null, 'PUBLIC_KEY',
                             'PUB_KEY_ID_SCHEMA', null, 'https://example.test/wxpay/pay/notify',
                             'https://example.test/wxpay/refund/notify', true, 'ACTIVE')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, out_trade_no, prepay_id, transaction_id,
                             payer_openid, status, amount_cent, request_digest, callback_digest,
                             expires_at, paid_at)
                        values
                            (19104, 19102, 19103, 'PAY-SCHEMA-OUT-TRADE', 'prepay-schema',
                             'transaction-schema', 'openid-schema', 'PAID', 3980,
                             'request-digest-schema', 'callback-digest-schema',
                             dateadd('MINUTE', 15, current_timestamp), current_timestamp)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into payment_callback_log
                            (id, callback_type, notify_id, out_trade_no, transaction_id, event_type,
                             resource_digest, raw_body_sha256, status)
                        values
                            (19105, 'PAY', 'notify-schema', 'PAY-SCHEMA-OUT-TRADE',
                             'transaction-schema', 'TRANSACTION.SUCCESS', 'resource-digest-schema',
                             'raw-body-sha256-schema', 'SUCCESS')
                        """)
                .update();

        Integer payingOrderCount = jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where status in ('CREATED', 'PAYING')
                          and id in (19101, 19102)
                        """)
                .query(Integer.class)
                .single();
        Integer paymentRowCount = jdbcClient.sql("""
                        select count(*)
                        from payment_order po
                        join payment_config pc on pc.id = po.payment_config_id
                        join payment_callback_log pcl on pcl.out_trade_no = po.out_trade_no
                        where po.id = 19104
                          and pc.enabled = true
                          and pcl.status = 'SUCCESS'
                        """)
                .query(Integer.class)
                .single();

        assertThat(payingOrderCount).isEqualTo(2);
        assertThat(paymentRowCount).isEqualTo(1);
    }

    @Test
    void paymentConfigMenuAndPermissionsAreSeeded() {
        Integer menuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id in (800, 801)
                          and path in ('/payment', 'config')
                          and component in ('/index/index', '/payment/config')
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where id in (8001, 8002, 8003)
                          and auth_mark in ('payment:config:read', 'payment:config:write', 'payment:config:enable')
                        """)
                .query(Integer.class)
                .single();

        assertThat(menuCount).isEqualTo(2);
        assertThat(permissionCount).isEqualTo(3);
    }
}
