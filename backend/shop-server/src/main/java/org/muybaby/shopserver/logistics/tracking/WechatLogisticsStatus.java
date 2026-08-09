package org.muybaby.shopserver.logistics.tracking;

public enum WechatLogisticsStatus {
    NOT_FOUND(0, "未揽收或暂未查到"),
    PICKED_UP(1, "已揽件"),
    IN_TRANSIT(2, "运输中"),
    OUT_FOR_DELIVERY(3, "派件中"),
    SIGNED(4, "已签收"),
    EXCEPTION(5, "物流异常"),
    SIGNED_BY_OTHER(6, "已代签收");

    private final int code;
    private final String displayText;

    WechatLogisticsStatus(int code, String displayText) {
        this.code = code;
        this.displayText = displayText;
    }

    public int code() {
        return code;
    }

    public String displayText() {
        return displayText;
    }

    public static WechatLogisticsStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (WechatLogisticsStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
