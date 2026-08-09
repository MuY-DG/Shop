package org.muybaby.shopserver.logistics.waybill.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressMode;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressParcel;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressSender;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ElectronicWaybillContextResponse(
        WechatExpressMode mode,
        boolean canCreate,
        List<String> blockers,
        WechatExpressSender sender,
        ElectronicWaybillReceiverResponse receiver,
        WechatExpressParcel defaultParcel,
        ElectronicWaybillAttemptResponse currentAttempt,
        List<ElectronicWaybillSandboxActionResponse> sandboxActions
) {
}
