package org.muybaby.shopserver.auth.dto;

public record PhoneAuthorizeResponse(boolean phoneAuthorized, String phoneNumberMasked) {
}
