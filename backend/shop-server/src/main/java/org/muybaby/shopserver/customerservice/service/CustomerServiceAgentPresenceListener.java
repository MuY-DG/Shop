package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.customerservice.CustomerServiceAgentAvailableEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CustomerServiceAgentPresenceListener {

    private final CustomerServiceService customerServiceService;

    public CustomerServiceAgentPresenceListener(CustomerServiceService customerServiceService) {
        this.customerServiceService = customerServiceService;
    }

    @EventListener
    public void handleAvailable(CustomerServiceAgentAvailableEvent event) {
        customerServiceService.handleAgentPresenceAvailable(event.adminUserId());
    }
}
