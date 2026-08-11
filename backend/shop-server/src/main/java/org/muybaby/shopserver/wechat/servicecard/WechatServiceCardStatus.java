package org.muybaby.shopserver.wechat.servicecard;

import java.util.Map;
import java.util.Set;

public enum WechatServiceCardStatus {
    USER_PAID(1, false),
    WAITING_SHIPMENT(2, false),
    PARTIALLY_SHIPPED(3, false),
    SHIPPED(4, false),
    RESHIPPED(5, false),
    SIGNED(6, false),
    AFTER_SALE(7, false),
    TRANSACTION_SUCCEEDED(8, true),
    AFTER_SALE_ENDED(9, true),
    CANCELLED(10, true),
    AFTER_SALE_CLOSED(11, true);

    private static final Map<Integer, Set<Integer>> PREVIOUS = Map.ofEntries(
            Map.entry(1, Set.of(7)),
            Map.entry(2, Set.of(1, 7)),
            Map.entry(3, Set.of(1, 2, 3, 7)),
            Map.entry(4, Set.of(1, 2, 3, 7)),
            Map.entry(5, Set.of(3, 4, 7)),
            Map.entry(6, Set.of(4, 5, 7)),
            Map.entry(7, Set.of(1, 2, 3, 4, 5, 6)),
            Map.entry(8, Set.of(1, 2, 3, 4, 5, 6, 7)),
            Map.entry(9, Set.of(7)),
            Map.entry(10, Set.of(1, 2, 7)),
            Map.entry(11, Set.of(7))
    );

    private final int code;
    private final boolean terminal;

    WechatServiceCardStatus(int code, boolean terminal) {
        this.code = code;
        this.terminal = terminal;
    }

    public int code() {
        return code;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean activationAllowed() {
        return code == 1 || code == 2;
    }

    public boolean canFollow(WechatServiceCardStatus previous) {
        return previous != null && PREVIOUS.get(code).contains(previous.code);
    }

    public static WechatServiceCardStatus fromCode(int code) {
        for (WechatServiceCardStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported WeChat 2001 status: " + code);
    }
}
