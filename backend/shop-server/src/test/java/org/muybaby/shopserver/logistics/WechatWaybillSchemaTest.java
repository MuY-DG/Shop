package org.muybaby.shopserver.logistics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WechatWaybillSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationAddsStructuredOrderReceiverSnapshotWithoutGuessingHistoricalAddress() {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where lower(table_name) = 'shop_order'
                          and lower(column_name) in (
                              'receiver_province', 'receiver_city', 'receiver_district',
                              'receiver_detail_address', 'receiver_location_name', 'receiver_doorplate'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(6);

        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            receiver_name, receiver_phone, receiver_address)
                        values (
                            198301, 'WAYBILL-LEGACY-ADDRESS', 1, 'PAID', 'CART', 'waybill-legacy-address',
                            '历史收货人', '13800138000', '北京市北京市朝阳区历史路1号')
                        """)
                .update();

        Map<String, Object> snapshot = jdbcClient.sql("""
                        select receiver_address, receiver_province, receiver_city, receiver_district,
                               receiver_detail_address, receiver_location_name, receiver_doorplate
                        from shop_order
                        where id = 198301
                        """)
                .query()
                .singleRow();
        assertThat(snapshot.get("RECEIVER_ADDRESS")).isEqualTo("北京市北京市朝阳区历史路1号");
        assertThat(snapshot.get("RECEIVER_PROVINCE")).isEqualTo("");
        assertThat(snapshot.get("RECEIVER_CITY")).isEqualTo("");
        assertThat(snapshot.get("RECEIVER_DISTRICT")).isEqualTo("");
        assertThat(snapshot.get("RECEIVER_DETAIL_ADDRESS")).isEqualTo("");
        assertThat(snapshot.get("RECEIVER_LOCATION_NAME")).isEqualTo("");
        assertThat(snapshot.get("RECEIVER_DOORPLATE")).isEqualTo("");
    }

    @Test
    void migrationCreatesDisabledExpressSettingAndWaybillTables() {
        Map<String, Object> setting = jdbcClient.sql("""
                        select mode, message_enabled, sender_name, sender_mobile,
                               delivery_id, biz_id, service_type, service_name,
                               default_weight_kg, default_length_cm,
                               default_width_cm, default_height_cm, revision
                        from wechat_express_setting
                        where id = 1
                        """)
                .query()
                .singleRow();

        assertThat(setting.get("MODE")).isEqualTo("DISABLED");
        assertThat(setting.get("MESSAGE_ENABLED")).isEqualTo(false);
        assertThat(setting.get("SENDER_NAME")).isEqualTo("");
        assertThat(setting.get("SENDER_MOBILE")).isEqualTo("");
        assertThat(setting.get("DELIVERY_ID")).isEqualTo("");
        assertThat(setting.get("BIZ_ID")).isEqualTo("");
        assertThat(setting.get("SERVICE_TYPE")).isNull();
        assertThat(setting.get("SERVICE_NAME")).isEqualTo("");
        assertThat(setting.get("DEFAULT_WEIGHT_KG")).isEqualTo(new BigDecimal("1.000"));
        assertThat(setting.get("DEFAULT_LENGTH_CM")).isEqualTo(new BigDecimal("20.00"));
        assertThat(setting.get("DEFAULT_WIDTH_CM")).isEqualTo(new BigDecimal("15.00"));
        assertThat(setting.get("DEFAULT_HEIGHT_CM")).isEqualTo(new BigDecimal("10.00"));
        assertThat(setting.get("REVISION")).isEqualTo(0L);

        Map<String, Object> serviceTypeColumn = jdbcClient.sql("""
                        select is_nullable, column_default
                        from information_schema.columns
                        where lower(table_name) = 'wechat_express_setting'
                          and lower(column_name) = 'service_type'
                        """)
                .query()
                .singleRow();
        assertThat(serviceTypeColumn.get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(serviceTypeColumn.get("COLUMN_DEFAULT")).isNull();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where lower(table_name) in (
                            'wechat_express_setting',
                            'order_electronic_waybill',
                            'shipment_waybill_registration'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(3);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(index_name) in (
                            'uk_order_electronic_waybill_provider_order',
                            'uk_order_electronic_waybill_attempt',
                            'uk_order_electronic_waybill_idempotency',
                            'idx_order_electronic_waybill_order_status',
                            'idx_order_electronic_waybill_status_attempt',
                            'uk_order_shipment_electronic_waybill',
                            'uk_shipment_waybill_registration_shipment',
                            'idx_shipment_waybill_registration_status_claim'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(8);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.table_constraints
                        where lower(constraint_name) in (
                            'chk_wechat_express_setting_singleton',
                            'chk_wechat_express_setting_mode',
                            'chk_order_electronic_waybill_mode',
                            'chk_order_electronic_waybill_status',
                            'chk_order_electronic_waybill_pending_operation',
                            'chk_order_electronic_waybill_parcel',
                            'chk_shipment_waybill_registration_kind',
                            'chk_shipment_waybill_registration_status',
                            'chk_order_shipment_source'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(9);
    }

    @Test
    void migrationAddsWaybillMenuSixPermissionsAndSuperAssignments() {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id = 502
                          and parent_id = 830
                          and name = 'OrderLogisticsConfig'
                          and path = 'logistics-config'
                          and component = '/order/logistics-config'
                          and enabled = true
                        """)
                .query(Integer.class)
                .single()).isOne();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where (id = 8301 and auth_mark = 'logistics:express:config:read')
                           or (id = 8302 and auth_mark = 'logistics:express:config:write')
                           or (id = 8303 and auth_mark = 'order:waybill:manage')
                           or (id = 8304 and auth_mark = 'order:waybill:print')
                           or (id = 8305 and auth_mark = 'order:waybill:test')
                           or (id = 8306 and auth_mark = 'order:shipping:registration:retry')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(6);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_menu_permission
                        where (menu_id = 502 and permission_id in (8301, 8302))
                           or (menu_id = 501 and permission_id in (8303, 8304, 8305, 8306))
                        """)
                .query(Integer.class)
                .single()).isEqualTo(6);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_item.code = 'R_SUPER'
                          and role_permission.permission_id in (8301, 8302, 8303, 8304, 8305, 8306)
                        """)
                .query(Integer.class)
                .single()).isEqualTo(6);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu role_menu
                        join admin_role role_item on role_item.id = role_menu.role_id
                        where role_item.code = 'R_SUPER'
                          and role_menu.menu_id = 502
                        """)
                .query(Integer.class)
                .single()).isOne();
    }
}
