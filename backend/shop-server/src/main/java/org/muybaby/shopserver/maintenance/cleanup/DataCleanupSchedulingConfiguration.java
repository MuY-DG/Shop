package org.muybaby.shopserver.maintenance.cleanup;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class DataCleanupSchedulingConfiguration {

    public static final String APPLICATION_SCHEDULER_BEAN_NAME = "taskScheduler";
    public static final String SCHEDULER_BEAN_NAME = "dataCleanupTaskScheduler";

    @Bean(name = APPLICATION_SCHEDULER_BEAN_NAME)
    @Primary
    ThreadPoolTaskScheduler applicationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("application-scheduling-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(name = SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler dataCleanupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // One thread runs cleanup work and the second keeps its database lease alive.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("data-cleanup-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
