package org.muybaby.shopserver.logistics.service;

import org.springframework.stereotype.Component;

@Component
public class ShipmentContactMasker {

    private static final String MASK_PREFIX = "*******";

    public String mask(String contact) {
        if (contact == null) {
            return null;
        }
        String digits = contact.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return null;
        }
        return MASK_PREFIX + digits.substring(digits.length() - 4);
    }
}
