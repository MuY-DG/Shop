package org.muybaby.shopserver.finance.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.api.JsonStringId;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchDetailResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationResolutionAuditResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminTradeBillEntryResponse;

import java.lang.reflect.RecordComponent;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceReconciliationDtoSerializationTest {

    private static final long LARGE_ID = 9_007_199_254_740_993L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposedDatabaseIdsAreStringsWhileVersionsAndAmountsRemainNumbers() throws Exception {
        AdminReconciliationDifferenceResponse difference =
                new AdminReconciliationDifferenceResponse(
                        LARGE_ID, LARGE_ID - 1, "key", "AMOUNT_MISMATCH", "CRITICAL", "OPEN",
                        "transaction", "trade", "refund", "out-refund", LARGE_ID - 2,
                        LARGE_ID - 4, LARGE_ID - 5,
                        101L, 99L, "SUCCESS", "PAID", 7L, "", "", LARGE_ID - 3,
                        null, null, null, "b".repeat(64), 123L, true, false);

        JsonNode json = objectMapper.valueToTree(difference);
        assertThat(json.path("id").isTextual()).isTrue();
        assertThat(json.path("batchId").isTextual()).isTrue();
        assertThat(json.path("orderId").isTextual()).isTrue();
        assertThat(json.path("paymentOrderId").isTextual()).isTrue();
        assertThat(json.path("refundOrderId").isTextual()).isTrue();
        assertThat(json.path("resolvedBy").isTextual()).isTrue();
        assertThat(json.path("version").isNumber()).isTrue();
        assertThat(json.path("providerAmountCent").isNumber()).isTrue();
        assertThat(json.path("localAmountCent").isNumber()).isTrue();
    }

    @Test
    void everyDeclaredFinanceIdComponentCarriesJsonStringId() {
        Map<Class<?>, String[]> expectedIds = Map.of(
                AdminReconciliationBatchResponse.class, new String[]{"id", "requestedBy"},
                AdminReconciliationBatchDetailResponse.class, new String[]{"id", "requestedBy"},
                AdminTradeBillEntryResponse.class, new String[]{"id", "batchId"},
                AdminReconciliationDifferenceResponse.class,
                new String[]{"id", "batchId", "orderId", "paymentOrderId", "refundOrderId", "resolvedBy"},
                AdminReconciliationResolutionAuditResponse.class,
                new String[]{"id", "differenceId", "operatorId"}
        );

        expectedIds.forEach((type, names) -> {
            Map<String, RecordComponent> components = java.util.Arrays.stream(type.getRecordComponents())
                    .collect(java.util.stream.Collectors.toMap(RecordComponent::getName, value -> value));
            for (String name : names) {
                assertThat(components.get(name).isAnnotationPresent(JsonStringId.class))
                        .as(type.getSimpleName() + "." + name)
                        .isTrue();
            }
        });
    }
}
