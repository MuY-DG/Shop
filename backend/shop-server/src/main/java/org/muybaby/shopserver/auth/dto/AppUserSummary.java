package org.muybaby.shopserver.auth.dto;

public record AppUserSummary(Long userId, String openidMasked, boolean phoneAuthorized) {
}
