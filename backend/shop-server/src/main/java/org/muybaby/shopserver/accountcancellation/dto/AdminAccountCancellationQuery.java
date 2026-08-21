package org.muybaby.shopserver.accountcancellation.dto;

public record AdminAccountCancellationQuery(
        Long current,
        Long size,
        Long userId,
        String miniProgramEnv
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
