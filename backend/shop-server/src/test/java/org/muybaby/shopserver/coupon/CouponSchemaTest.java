package org.muybaby.shopserver.coupon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CouponSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void couponTablesAndMarketingMenuExist() {
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (5001, 'Schema coupon', 'schema test', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, 500,
                             'ALL', '', 'coupon.amount-off.v1', 10, 0, 1,
                             current_timestamp, dateadd('DAY', 7, current_timestamp), 'DISABLED', 10)
                        """)
                .update();

        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value, valid_start_at,
                             valid_end_at, status, claimed_at)
                        values
                            (7001, 1, 5001, 'Schema coupon', 'NO_THRESHOLD', 'AMOUNT_OFF',
                             0, 500, 'ALL', '', current_timestamp,
                             dateadd('DAY', 7, current_timestamp), 'CLAIMED', current_timestamp)
                        """)
                .update();

        jdbcClient.sql("""
                        insert into coupon_claim_record (id, template_id, user_id, user_coupon_id, claimed_at)
                        values (8001, 5001, 1, 7001, current_timestamp)
                        """)
                .update();

        Integer marketingMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where id in (400, 401)
                          and path in ('/marketing', 'coupon')
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'coupon:template:create',
                            'coupon:template:update',
                            'coupon:template:enable',
                            'coupon:template:disable'
                        )
                        """)
                .query(Integer.class)
                .single();

        assertThat(marketingMenuCount).isEqualTo(2);
        assertThat(permissionCount).isEqualTo(4);
    }
}
