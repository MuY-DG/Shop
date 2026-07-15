package org.muybaby.shopserver.customerservice;

public record CustomerServiceTransferChangedEvent(
        Long requestId,
        Long conversationId,
        Long fromAdminUserId,
        Long toAdminUserId,
        String changeType
) {
}
