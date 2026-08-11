package org.muybaby.shopserver.wechat.servicecard;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class WechatServiceCardRepairUnit {

    private final WechatServiceCardOutboxService outboxService;

    public WechatServiceCardRepairUnit(WechatServiceCardOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void repair(long orderId, LocalDateTime observedAt) {
        outboxService.onOrderFact(orderId, observedAt);
    }
}
