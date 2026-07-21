package org.muybaby.shopserver.location.service;

import org.muybaby.shopserver.location.config.AmapRuntimeConfigService;
import org.muybaby.shopserver.location.config.ResolvedAmapConfig;
import org.muybaby.shopserver.location.dto.AppAmapConfigResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AppLocationService {

    private final AmapRuntimeConfigService configService;

    public AppLocationService(AmapRuntimeConfigService configService) {
        this.configService = configService;
    }

    public AppAmapConfigResponse clientConfig() {
        ResolvedAmapConfig config = configService.effective();
        if (!config.enabled() || !StringUtils.hasText(config.miniProgramKey())) {
            return new AppAmapConfigResponse(false, "");
        }
        return new AppAmapConfigResponse(true, config.miniProgramKey());
    }
}
