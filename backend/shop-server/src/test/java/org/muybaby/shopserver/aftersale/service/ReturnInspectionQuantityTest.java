package org.muybaby.shopserver.aftersale.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.muybaby.shopserver.aftersale.dto.AdminReturnInspectionItemRequest;
import org.muybaby.shopserver.aftersale.dto.AfterSaleItemResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReturnInspectionQuantityTest {

    private static final long AFTER_SALE_ID = 7_231_001L;
    private static final long FIRST_ITEM_ID = 7_231_011L;
    private static final long SECOND_ITEM_ID = 7_231_012L;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AfterSaleV2WorkflowService workflow;

    @Autowired
    private AfterSaleV2ReadService readService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedApprovedReturn() {
        jdbc.sql("""
                insert into shop_order (id, order_no, user_id, status, idempotency_key, checkout_request_digest)
                values (7231000, 'RETURN-RECEIVED-QUANTITY', 7231099, 'SHIPPED', 'received-quantity',
                        'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff')
                """).update();
        jdbc.sql("""
                insert into order_item
                    (id, order_id, sku_id, spu_id, product_title, sku_code, quantity, line_amount_cent)
                values (7231011, 7231000, 7231021, 7231031, '退回商品A', 'RECEIPT-A', 2, 2000),
                       (7231012, 7231000, 7231022, 7231032, '退回商品B', 'RECEIPT-B', 1, 1000)
                """).update();
        jdbc.sql("""
                insert into after_sale_request
                    (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                     requested_amount_cent, approved_amount_cent)
                values (7231001, 'AS-RECEIVED-QUANTITY', 7231000, 7231099, 'RETURN_REFUND',
                        'WAITING_INSPECTION', '退货', 3000, 3000)
                """).update();
        jdbc.sql("""
                insert into after_sale_item
                    (id, after_sale_id, order_item_id, sku_id, order_quantity_snapshot,
                     paid_amount_basis_cent, requested_quantity, approved_quantity,
                     requested_amount_cent, approved_amount_cent)
                values (7231041, 7231001, 7231011, 7231021, 2, 2000, 2, 2, 2000, 2000),
                       (7231042, 7231001, 7231012, 7231022, 1, 1000, 1, 1, 1000, 1000)
                """).update();
        jdbc.sql("""
                insert into after_sale_return
                    (after_sale_id, return_address_id, contact_name, contact_phone, detail_address,
                     user_shipped_at, merchant_received_at)
                values (7231001, 7231051, '售后仓', '13800138000', '测试路1号', current_timestamp, current_timestamp)
                """).update();
    }

    @Test
    void damagedGoodsRemainReceivedEvenWhenTheyCannotBeRestocked() throws Exception {
        assertThat(items()).allMatch(item -> item.receivedQuantity() == null);
        var request = objectMapper.readValue("""
                {"orderItemId":7231011,"receivedQuantity":1,"restockQuantity":0}
                """, AdminReturnInspectionItemRequest.class);

        var plan = inspect(List.of(request));

        assertThat(plan.approvedAmountCent()).isEqualTo(3000L);
        assertThat(items().getFirst().receivedQuantity()).isEqualTo(1);
        assertThat(items().getFirst().restockQuantity()).isZero();
        // Omitted items in an explicit inspection are neither received nor restocked.
        assertThat(items().get(1).receivedQuantity()).isZero();
        assertThat(items().get(1).restockQuantity()).isZero();
        var json = objectMapper.readTree(objectMapper.writeValueAsString(items().getFirst()));
        assertThat(json.path("receivedQuantity").asInt()).isEqualTo(1);
        assertThat(json.path("restockQuantity").asInt()).isZero();
    }

    @Test
    void legacyItemWithoutReceivedQuantityDefaultsToApprovedQuantity() {
        inspect(List.of(new AdminReturnInspectionItemRequest(FIRST_ITEM_ID, 1)));

        assertThat(items().getFirst().receivedQuantity()).isEqualTo(2);
        assertThat(items().getFirst().restockQuantity()).isEqualTo(1);
        assertThat(items().get(1).receivedQuantity()).isZero();
    }

    @Test
    void legacyInspectionWithoutAnItemListKeepsTheAllReceivedDefault() {
        inspect(null);

        assertThat(items()).extracting(AfterSaleItemResponse::receivedQuantity).containsExactly(2, 1);
        assertThat(items()).extracting(AfterSaleItemResponse::restockQuantity).containsExactly(2, 1);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "2,0", "0,1", "1,-1"})
    void invalidSecondItemDoesNotPartiallyWriteTheFirstItem(int received, int restock) {
        var failure = catchThrowableOfType(() -> inspect(List.of(
                new AdminReturnInspectionItemRequest(FIRST_ITEM_ID, 1, 1),
                new AdminReturnInspectionItemRequest(SECOND_ITEM_ID, restock, received))), BusinessException.class);

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertUninspected();
    }

    @Test
    void unknownOrDuplicateItemsAreRejectedBeforeWritingAnyReceipt() {
        var valid = new AdminReturnInspectionItemRequest(FIRST_ITEM_ID, 1, 1);
        for (var invalid : List.of(valid, new AdminReturnInspectionItemRequest(7_231_999L, 0, 0))) {
            var failure = catchThrowableOfType(() -> inspect(List.of(valid, invalid)), BusinessException.class);
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertUninspected();
        }
    }

    private AfterSaleV2WorkflowService.ApprovalPlan inspect(List<AdminReturnInspectionItemRequest> inspection) {
        return workflow.applyInspectionAcceptanceLocked(AFTER_SALE_ID, inspection, "验收记录", 1L, LocalDateTime.now());
    }

    private void assertUninspected() {
        assertThat(items()).allMatch(item -> item.receivedQuantity() == null && item.restockQuantity() == 0);
        assertThat(jdbc.sql("select inspection_result from after_sale_return where after_sale_id = :id")
                .param("id", AFTER_SALE_ID).query(String.class).single()).isEmpty();
    }

    private List<AfterSaleItemResponse> items() {
        return readService.decorate(new AfterSaleResponse(
                AFTER_SALE_ID, "AS-RECEIVED-QUANTITY", 7_231_000L, "RETURN-RECEIVED-QUANTITY", 7_231_099L,
                "测试用户", "RETURN_REFUND", "WAITING_INSPECTION", "退货", "", 3000L, 3000L,
                "", 1L, null, LocalDateTime.now(), List.of(), List.of(), null)).items();
    }
}
