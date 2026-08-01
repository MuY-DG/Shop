package org.muybaby.shopserver.maintenance.cleanup.dto;

import java.util.List;

public record DataCleanupConfigResponse(
        long revision,
        List<DataCleanupTaskResponse> tasks
) {
}
