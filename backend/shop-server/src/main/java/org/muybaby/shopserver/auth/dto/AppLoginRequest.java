package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AppLoginRequest(
        @NotBlank String code,
        String privacyPolicyVersion,
        Boolean privacyPolicyAccepted,
        String miniProgramEnv
) {

    public AppLoginRequest(String code) {
        this(code, null, null, null);
    }
}
