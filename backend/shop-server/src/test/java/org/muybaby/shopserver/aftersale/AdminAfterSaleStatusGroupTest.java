package org.muybaby.shopserver.aftersale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAfterSaleStatusGroupTest {

    @Test
    void keepsReviewReturnInspectionRefundAndClosedGroupsSemanticallySeparate() {
        assertThat(AdminAfterSaleStatusGroup.PENDING_REVIEW.statuses())
                .containsExactly(AfterSaleStatus.REQUESTED.name());
        assertThat(AdminAfterSaleStatusGroup.RETURN_PROCESSING.statuses())
                .containsExactly(
                        AfterSaleStatus.WAITING_RETURN.name(),
                        AfterSaleStatus.RETURNING.name());
        assertThat(AdminAfterSaleStatusGroup.PENDING_INSPECTION.statuses())
                .containsExactly(AfterSaleStatus.WAITING_INSPECTION.name());
        assertThat(AdminAfterSaleStatusGroup.REFUNDING.statuses())
                .containsExactly(AfterSaleStatus.APPROVED.name(), AfterSaleStatus.REFUNDING.name());
        assertThat(AdminAfterSaleStatusGroup.REJECTED.statuses())
                .containsExactly(
                        AfterSaleStatus.REJECTED.name(),
                        AfterSaleStatus.RETURN_REJECTED.name(),
                        AfterSaleStatus.CANCELLED.name());

        List<String> operationalGroups = List.of(
                AdminAfterSaleStatusGroup.PENDING_REVIEW,
                AdminAfterSaleStatusGroup.RETURN_PROCESSING,
                AdminAfterSaleStatusGroup.PENDING_INSPECTION,
                AdminAfterSaleStatusGroup.REFUNDING,
                AdminAfterSaleStatusGroup.REFUNDED,
                AdminAfterSaleStatusGroup.REJECTED,
                AdminAfterSaleStatusGroup.REFUND_FAILED
        ).stream().flatMap(group -> group.statuses().stream()).toList();
        assertThat(operationalGroups)
                .containsExactlyInAnyOrderElementsOf(AdminAfterSaleStatusGroup.ALL.statuses());
    }
}
