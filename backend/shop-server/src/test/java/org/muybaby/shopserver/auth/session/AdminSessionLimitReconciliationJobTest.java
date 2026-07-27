package org.muybaby.shopserver.auth.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSessionLimitReconciliationJobTest {

    @Test
    void retriesFailedUsersOnTheNextRunWithoutBlockingOtherUsers() {
        AdminSessionLimitReconciler reconciler = mock(AdminSessionLimitReconciler.class);
        AdminSessionLimitReconciliationJob job =
                new AdminSessionLimitReconciliationJob(reconciler);
        when(reconciler.limitedUserIds()).thenReturn(List.of(101L, 102L));
        doThrow(new IllegalStateException("redis unavailable"))
                .doNothing()
                .when(reconciler)
                .reconcileUser(101L);

        job.reconcileSessionLimits();
        job.reconcileSessionLimits();

        verify(reconciler, times(2)).reconcileUser(101L);
        verify(reconciler, times(2)).reconcileUser(102L);
    }
}
