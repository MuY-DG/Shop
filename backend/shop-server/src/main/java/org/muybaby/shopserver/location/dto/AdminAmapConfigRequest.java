package org.muybaby.shopserver.location.dto;

public record AdminAmapConfigRequest(
        Boolean enabled,
        String miniProgramKey
) {
}
