package org.muybaby.shopserver.admin.rbac.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminUserUpdateRequest(
        @NotBlank @Size(max = 64) String displayName,
        @NotBlank @Email @Size(max = 128) String email,
        @Size(max = 72) String password,
        @Size(max = 255) String avatar,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @NotEmpty List<Long> roleIds
) {
}
