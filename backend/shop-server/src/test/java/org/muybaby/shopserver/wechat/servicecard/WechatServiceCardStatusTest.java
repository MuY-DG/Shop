package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WechatServiceCardStatusTest {

    @Test
    void official2001TransitionTableIsFrozenExactly() {
        assertPrevious(WechatServiceCardStatus.USER_PAID, 7);
        assertPrevious(WechatServiceCardStatus.WAITING_SHIPMENT, 1, 7);
        assertPrevious(WechatServiceCardStatus.PARTIALLY_SHIPPED, 1, 2, 3, 7);
        assertPrevious(WechatServiceCardStatus.SHIPPED, 1, 2, 3, 7);
        assertPrevious(WechatServiceCardStatus.RESHIPPED, 3, 4, 7);
        assertPrevious(WechatServiceCardStatus.SIGNED, 4, 5, 7);
        assertPrevious(WechatServiceCardStatus.AFTER_SALE, 1, 2, 3, 4, 5, 6);
        assertPrevious(WechatServiceCardStatus.TRANSACTION_SUCCEEDED, 1, 2, 3, 4, 5, 6, 7);
        assertPrevious(WechatServiceCardStatus.AFTER_SALE_ENDED, 7);
        assertPrevious(WechatServiceCardStatus.CANCELLED, 1, 2, 7);
        assertPrevious(WechatServiceCardStatus.AFTER_SALE_CLOSED, 7);
    }

    @Test
    void onlyStatusThreeCanRepeatAndOnlyOfficialTerminalStatesAreTerminal() {
        for (WechatServiceCardStatus status : WechatServiceCardStatus.values()) {
            assertThat(status.canFollow(status))
                    .as("same-state transition for %s", status)
                    .isEqualTo(status == WechatServiceCardStatus.PARTIALLY_SHIPPED);
            assertThat(status.terminal())
                    .isEqualTo(status.code() >= 8);
        }
    }

    private void assertPrevious(WechatServiceCardStatus target, int... allowed) {
        java.util.Set<Integer> expected = java.util.Arrays.stream(allowed)
                .boxed().collect(java.util.stream.Collectors.toSet());
        for (WechatServiceCardStatus previous : WechatServiceCardStatus.values()) {
            assertThat(target.canFollow(previous))
                    .as("transition %s -> %s", previous.code(), target.code())
                    .isEqualTo(expected.contains(previous.code()));
        }
    }
}
