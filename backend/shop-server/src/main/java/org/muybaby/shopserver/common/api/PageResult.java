package org.muybaby.shopserver.common.api;

import java.util.List;

public record PageResult<T>(List<T> records, long total, long current, long size) {

    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        return new PageResult<>(records, total, current, size);
    }
}
