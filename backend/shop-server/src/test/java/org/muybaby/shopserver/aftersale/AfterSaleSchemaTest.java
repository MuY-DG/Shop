package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AfterSaleSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void afterSaleEvidenceAndRefundTablesAcceptRepresentativeRows() {
        assertThat(OrderStatus.valueOf("REFUNDING").name()).isEqualTo("REFUNDING");
        assertThat(StorageFileUsageType.valueOf("AFTER_SALE_EVIDENCE")).isSameAs(StorageFileUsageType.AFTER_SALE_EVIDENCE);
        assertThat(ErrorCode.valueOf("WECHAT_REFUND_FAILED")).isSameAs(ErrorCode.WECHAT_REFUND_FAILED);

        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, paid_at, refunding_at)
                        values
                            (19301, 'AFTER-SALE-SCHEMA-ORDER', 1, 'REFUNDING', 'CART', 'after-sale-schema-order',
                             6980, 6980, 0, 0, 6980, 6980, current_timestamp, current_timestamp)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, folder_id, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, width, height,
                             alt_text, tags_json, public_url, status, uploaded_by_type, uploaded_by_id,
                             upload_context_type, upload_context_id, expires_at)
                        values
                            (19302, 'ATTACHMENT', 'IMAGE', null, 'PRIVATE', 'LOCAL', '',
                             'private/schema/refund.png', 'refund.png', 'image/png', 'png', 68,
                             'refund-evidence-schema', 1, 1, '', null, null, 'ACTIVE', 'APP', 1,
                             'ORDER', 19301, dateadd('HOUR', 24, current_timestamp))
                        """)
                .update();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status, reason, description,
                             requested_amount_cent, approved_amount_cent, audit_note, reviewed_by, reviewed_at)
                        values
                            (19303, 'ASFIX19303', 19301, 1, 'REFUND_ONLY', 'REFUNDING', '不想要了',
                             'schema after sale', 6980, 6980, 'schema approved', 1, current_timestamp)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into after_sale_evidence
                            (id, after_sale_id, file_id, sort_order)
                        values
                            (19304, 19303, 19302, 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             notify_url, refund_notify_url, enabled, status)
                        values
                            (19305, 'After Sale Schema Config', 'wx-schema-app', '1900000001',
                             'schema-serial', 'ciphertext-placeholder',
                             'https://example.test/wxpay/pay/notify',
                             'https://example.test/wxpay/refund/notify', true, 'ACTIVE')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, out_trade_no, prepay_id, transaction_id,
                             payer_openid, status, amount_cent, expires_at, paid_at)
                        values
                            (19306, 19301, 19305, 'AFTER-SALE-SCHEMA-TRADE', 'prepay-after-sale',
                             'transaction-after-sale', 'openid-schema', 'PAID', 6980,
                             dateadd('MINUTE', 15, current_timestamp), current_timestamp)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id, out_refund_no, refund_id, provider_reason,
                             refund_amount_cent, status, callback_status, callback_digest, requested_at)
                        values
                            (19307, 19303, 19301, 19306, 'AFTER-SALE-SCHEMA-REFUND',
                             'refund-schema', '', 6980, 'PROCESSING', 'ACCEPTED',
                             'refund-callback-digest-schema', current_timestamp)
                        """)
                .update();

        Integer refundCount = jdbcClient.sql("""
                        select count(*)
                        from refund_order ro
                        join after_sale_request asr on asr.id = ro.after_sale_id
                        join after_sale_evidence ase on ase.after_sale_id = asr.id
                        where ro.out_refund_no = 'AFTER-SALE-SCHEMA-REFUND'
                          and ro.provider_reason = ''
                          and asr.status = 'REFUNDING'
                          and ase.file_id = 19302
                        """)
                .query(Integer.class)
                .single();

        assertThat(refundCount).isEqualTo(1);
    }

    @Test
    void tradeMenuContainsOrderAndAfterSaleWithoutChangingPermissionOwnership() {
        Integer tradeMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 830
                          and parent_id is null
                          and name = 'Trade'
                          and path = '/trade'
                          and title = '交易管理'
                          and enabled = true
                        """)
                .query(Integer.class)
                .single();
        Integer childMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where parent_id = 830
                          and (
                            (id = 501 and path = 'orders' and component = '/order/list')
                            or (id = 821 and path = 'after-sales' and component = '/aftersale/list')
                          )
                        """)
                .query(Integer.class)
                .single();
        Integer disabledLegacyParentCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id in (500, 820)
                          and enabled = false
                          and visible = false
                        """)
                .query(Integer.class)
                .single();
        Integer superRoleMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu
                        where role_id = 1
                          and menu_id in (830, 501, 821)
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission mp
                        join admin_permission p on p.id = mp.permission_id
                        where (mp.menu_id = 501 and p.auth_mark like 'order:%')
                           or (mp.menu_id = 821 and p.auth_mark like 'aftersale:%')
                        """)
                .query(Integer.class)
                .single();

        assertThat(tradeMenuCount).isEqualTo(1);
        assertThat(childMenuCount).isEqualTo(2);
        assertThat(disabledLegacyParentCount).isEqualTo(2);
        assertThat(superRoleMenuCount).isEqualTo(3);
        assertThat(permissionCount).isEqualTo(6);
    }
}
