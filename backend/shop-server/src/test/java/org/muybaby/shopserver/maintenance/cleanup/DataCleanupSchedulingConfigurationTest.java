package org.muybaby.shopserver.maintenance.cleanup;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class DataCleanupSchedulingConfigurationTest {

    @Test
    void keepsApplicationAndCleanupSchedulingOnSeparatePools() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             DataCleanupSchedulingConfiguration.class)) {
            ThreadPoolTaskScheduler application = context.getBean(
                    DataCleanupSchedulingConfiguration.APPLICATION_SCHEDULER_BEAN_NAME,
                    ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler cleanup = context.getBean(
                    DataCleanupSchedulingConfiguration.SCHEDULER_BEAN_NAME,
                    ThreadPoolTaskScheduler.class);
            assertThat(application).isNotSameAs(cleanup);
            assertThat(context.getBean(TaskScheduler.class)).isSameAs(application);
            assertThat(application.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(cleanup.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        }
    }
}
