package org.muybaby.shopserver.logistics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum DeliveryMode {
    UNIFIED(1),
    SPLIT(2);

    private final int value;

    DeliveryMode(int value) {
        this.value = value;
    }

    @JsonValue
    public int value() {
        return value;
    }

    @JsonCreator
    public static DeliveryMode fromValue(int value) {
        return Arrays.stream(values())
                .filter(item -> item.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported delivery mode: " + value));
    }
}
