package org.muybaby.shopserver.logistics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum LogisticsType {
    EXPRESS(1),
    LOCAL_DELIVERY(2),
    VIRTUAL(3),
    PICKUP(4);

    private final int value;

    LogisticsType(int value) {
        this.value = value;
    }

    @JsonValue
    public int value() {
        return value;
    }

    @JsonCreator
    public static LogisticsType fromValue(int value) {
        return Arrays.stream(values())
                .filter(item -> item.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported logistics type: " + value));
    }
}
