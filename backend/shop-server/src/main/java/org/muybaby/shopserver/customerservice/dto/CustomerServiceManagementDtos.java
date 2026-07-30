package org.muybaby.shopserver.customerservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public final class CustomerServiceManagementDtos {

    private CustomerServiceManagementDtos() {
    }

    public record ManagedUserResponse(
            @JsonStringId Long adminUserId,
            String username,
            String displayName,
            String adminAvatar,
            String status,
            boolean agent,
            boolean manager,
            String serviceName,
            String serviceNameOverride,
            String serviceAvatar,
            boolean online,
            String workStatus,
            int activeConversationCount,
            int maxActiveConversations,
            int routingWeight,
            LocalDateTime updatedAt
    ) {
    }

    public record ManagedUserUpdateRequest(
            @NotNull Boolean agent,
            @NotNull Boolean manager,
            @Size(max = 64) String serviceNameOverride,
            @NotNull @Min(1) @Max(1000) Integer maxActiveConversations,
            @NotNull @Min(1) @Max(1000) Integer routingWeight
    ) {
    }

    public record CustomerServiceConfigResponse(
            String defaultServiceName,
            String avatar,
            boolean autoAssignEnabled,
            String assignmentStrategy,
            boolean stickyAgentEnabled,
            int stickyWindowHours,
            @JsonStringId Long updatedBy,
            LocalDateTime updatedAt
    ) {
    }

    public record CustomerServiceConfigUpdateRequest(
            @NotBlank @Size(max = 64) String defaultServiceName,
            @Size(max = 255) String avatar,
            @NotNull Boolean autoAssignEnabled,
            @NotBlank
            @Pattern(regexp = "LEAST_LOADED|ROUND_ROBIN|WEIGHTED")
            String assignmentStrategy,
            @NotNull Boolean stickyAgentEnabled,
            @NotNull @Min(1) @Max(720) Integer stickyWindowHours
    ) {
    }
}
