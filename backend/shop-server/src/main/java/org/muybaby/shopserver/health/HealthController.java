package org.muybaby.shopserver.health;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/app/health")
    public ApiResponse<HealthStatus> health() {
        return ApiResponse.success(new HealthStatus("UP", "shop-server"));
    }

    public record HealthStatus(String status, String service) {
    }
}
