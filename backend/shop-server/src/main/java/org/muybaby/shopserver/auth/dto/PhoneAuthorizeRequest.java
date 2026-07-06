package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record PhoneAuthorizeRequest(@NotBlank String code) {
}
