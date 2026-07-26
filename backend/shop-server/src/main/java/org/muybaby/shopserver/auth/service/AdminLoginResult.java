package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.auth.dto.LoginTokenResponse;

public record AdminLoginResult(
        LoginTokenResponse tokens,
        Long adminUserId,
        String username
) {
}
