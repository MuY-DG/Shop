package org.muybaby.shopserver.auth.login;

import java.time.Duration;

public interface AdminLoginAttemptStore {

    Duration lockedFor(String key);

    Duration recordFailure(String key, int failureLimit, Duration failureWindow, Duration lockDuration);

    void clear(String key);
}
