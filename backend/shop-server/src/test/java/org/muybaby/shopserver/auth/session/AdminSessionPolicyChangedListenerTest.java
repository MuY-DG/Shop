package org.muybaby.shopserver.auth.session;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminSessionPolicyChangedListenerTest {

    @Test
    void limitChangeUsesTheCurrentDatabasePolicyInsteadOfTheEventSnapshot() {
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AdminSessionLimitReconciler reconciler = mock(AdminSessionLimitReconciler.class);
        AdminSessionPolicyChangedListener listener =
                new AdminSessionPolicyChangedListener(opaqueTokenService, reconciler);

        listener.onPolicyChanged(new AdminSessionPolicyChangedEvent(101L, false, 1));

        verify(reconciler).reconcileUser(101L);
        verifyNoInteractions(opaqueTokenService);
    }

    @Test
    void revokeAllStillRevokesTheEntireSubjectImmediately() {
        OpaqueTokenService opaqueTokenService = mock(OpaqueTokenService.class);
        AdminSessionLimitReconciler reconciler = mock(AdminSessionLimitReconciler.class);
        AdminSessionPolicyChangedListener listener =
                new AdminSessionPolicyChangedListener(opaqueTokenService, reconciler);

        listener.onPolicyChanged(new AdminSessionPolicyChangedEvent(101L, true, 3));

        verify(opaqueTokenService).revokeSubjectSessions(TokenKind.ADMIN, 101L);
        verifyNoInteractions(reconciler);
    }
}
