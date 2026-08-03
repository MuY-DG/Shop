package org.muybaby.shopserver.customerservice;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.PresenceResponse;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/customer-service")
public class AppCustomerServicePresenceController {

    private final CustomerServiceService customerServiceService;

    public AppCustomerServicePresenceController(CustomerServiceService customerServiceService) {
        this.customerServiceService = customerServiceService;
    }

    @GetMapping("/presence")
    public ApiResponse<PresenceResponse> presence() {
        return ApiResponse.success(new PresenceResponse(customerServiceService.isOnline()));
    }
}
