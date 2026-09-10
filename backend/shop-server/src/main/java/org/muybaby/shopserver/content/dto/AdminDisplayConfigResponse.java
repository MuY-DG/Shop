package org.muybaby.shopserver.content.dto;

import java.time.LocalDateTime;

public record AdminDisplayConfigResponse(String displayName, long version, LocalDateTime updatedAt) {
}
