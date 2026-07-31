package org.muybaby.shopserver.customerservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public final class CustomerServiceManagementDtos {

    private CustomerServiceManagementDtos() {
    }

    public record ManagedUserResponse(
            @JsonStringId Long adminUserId,
            String username,
            String serviceName,
            String serviceAvatar,
            boolean online,
            boolean manager,
            LocalDateTime boundAt
    ) {
    }

    public record GuestUserResponse(
            @JsonStringId Long adminUserId,
            String username,
            String displayName,
            String avatar
    ) {
    }

    public record ManagedUserCreateRequest(
            @Size(max = 64) String serviceName
    ) {
    }

    public record ManagedUserNameUpdateRequest(
            @NotBlank @Size(max = 64) String serviceName
    ) {
    }

    public record ManagedUserManagerUpdateRequest(
            @NotNull Boolean manager
    ) {
    }

    public record RoutingAgentResponse(
            @JsonStringId Long adminUserId,
            String username,
            String serviceName,
            boolean online,
            Integer maxActiveConversations,
            int calculatedWeight,
            double calculatedWeightPercent
    ) {
    }

    public record CustomerServiceConfigResponse(
            String defaultServiceName,
            String avatar,
            Long avatarFileId,
            String assignmentStrategy,
            boolean stickyAgentEnabled,
            int stickyWindowHours,
            List<RoutingAgentResponse> routingAgents
    ) {
    }

    public record RoutingAgentUpdateRequest(
            @NotNull @Min(1) Long adminUserId,
            @Min(1) @Max(1000) Integer maxActiveConversations
    ) {
    }

    public record CustomerServiceRoutingUpdateRequest(
            @NotBlank
            @Pattern(regexp = "LEAST_LOADED|ROUND_ROBIN|WEIGHTED")
            String assignmentStrategy,
            @NotNull Boolean stickyAgentEnabled,
            @NotNull @Min(1) @Max(720) Integer stickyWindowHours,
            @NotNull List<@Valid RoutingAgentUpdateRequest> agents
    ) {
    }

    public record CustomerServiceIdentityUpdateRequest(
            @NotBlank @Size(max = 64) String defaultServiceName,
            @Min(1) Long avatarFileId
    ) {
    }
}
