package org.muybaby.shopserver.accountcancellation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountCancellationRequest(
        @NotBlank @Size(max = 128) String wechatCode,
        @NotBlank @Pattern(regexp = "[0-9A-Za-z._-]{1,40}") String noticeVersion,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String noticeContentSha256,
        @AssertTrue Boolean noticeAcknowledged,
        @NotBlank @Pattern(regexp = "develop|trial|release") String miniProgramEnv
) {
}
