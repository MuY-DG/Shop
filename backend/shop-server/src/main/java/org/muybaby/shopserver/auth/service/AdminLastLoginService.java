package org.muybaby.shopserver.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AdminLastLoginService {

    private static final Logger log = LoggerFactory.getLogger(AdminLastLoginService.class);
    private static final long FAILURE_WARNING_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final AdminLastLoginWriter writer;
    private final Clock clock;
    private final AtomicLong lastFailureWarningAt = new AtomicLong();

    @Autowired
    public AdminLastLoginService(AdminLastLoginWriter writer) {
        this(writer, Clock.systemUTC());
    }

    AdminLastLoginService(AdminLastLoginWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    public void updateBestEffort(long userId) {
        try {
            writer.update(userId, LocalDateTime.now(clock));
        } catch (RuntimeException ex) {
            warnFailure(ex);
        }
    }

    private void warnFailure(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastFailureWarningAt.get();
        if (now - previous < FAILURE_WARNING_INTERVAL_MILLIS
                || !lastFailureWarningAt.compareAndSet(previous, now)) {
            return;
        }
        log.warn("Admin last-login persistence failed: {}",
                exception.getClass().getSimpleName());
    }
}
