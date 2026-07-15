package org.muybaby.shopserver.customerservice.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustomerServiceTransferTimeoutJob {

    private final CustomerServiceService customerServiceService;

    public CustomerServiceTransferTimeoutJob(CustomerServiceService customerServiceService) {
        this.customerServiceService = customerServiceService;
    }

    @Scheduled(
            fixedDelayString = "${shop.customer-service.transfer-timeout-check-delay:5s}",
            initialDelayString = "${shop.customer-service.transfer-timeout-check-initial-delay:5s}"
    )
    public void expireTransferRequests() {
        customerServiceService.expireTransferRequests();
    }
}
