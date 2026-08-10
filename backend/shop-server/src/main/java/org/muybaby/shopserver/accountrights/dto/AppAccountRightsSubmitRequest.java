package org.muybaby.shopserver.accountrights.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.accountrights.AccountRightsRequestType;

public record AppAccountRightsSubmitRequest(
        @NotNull AccountRightsRequestType requestType,
        @Size(max = 1000) String requestNote,
        @Size(max = 128) String wechatCode
) {
}
