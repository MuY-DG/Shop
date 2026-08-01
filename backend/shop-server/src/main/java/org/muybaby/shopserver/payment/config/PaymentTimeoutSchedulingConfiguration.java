package org.muybaby.shopserver.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PaymentTimeoutSchedulingConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock paymentTimeoutClock() {
        return Clock.systemUTC();
    }
}
