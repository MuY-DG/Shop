package org.muybaby.shopserver.customerservice;

public record CustomerServiceChangedEvent(
        Long conversationId,
        Long appUserId,
        String changeType,
        Long messageId
) {
}
