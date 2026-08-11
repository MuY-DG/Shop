package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WechatServiceCardAdminRuntimeService {

    private final WechatServiceCardRuntimeSettingService runtimeSettingService;
    private final WechatServiceCardAdminReadService readService;

    public WechatServiceCardAdminRuntimeService(
            WechatServiceCardRuntimeSettingService runtimeSettingService,
            WechatServiceCardAdminReadService readService
    ) {
        this.runtimeSettingService = runtimeSettingService;
        this.readService = readService;
    }

    /**
     * The returned operational snapshot and the persisted switch change are one transaction.
     * A status-query failure therefore cannot produce a 500 response after silently committing
     * a different runtime state.
     */
    @Transactional
    public AdminWechatServiceCardStatusResponse update(
            AdminWechatServiceCardRuntimeUpdateRequest request,
            Long operatorId
    ) {
        runtimeSettingService.update(request, operatorId);
        return readService.status();
    }
}
