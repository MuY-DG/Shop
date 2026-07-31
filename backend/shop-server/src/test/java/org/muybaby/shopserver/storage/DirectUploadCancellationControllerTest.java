package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.log.service.AdminSystemLogRecorder;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.aftersale.AppAfterSaleController;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.analytics.AppUserDailyActivityService;
import org.muybaby.shopserver.auth.service.AppAuthService;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.customerservice.AdminCustomerServiceController;
import org.muybaby.shopserver.customerservice.AppCustomerServiceImageUploadController;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.operation.service.OperationsStatisticsService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.security.PathTokenKindResolver;
import org.muybaby.shopserver.security.web.ClientIpResolver;
import org.muybaby.shopserver.storage.service.DirectUploadService;
import org.muybaby.shopserver.storage.service.StorageService;
import org.muybaby.shopserver.user.AppUserController;
import org.muybaby.shopserver.user.service.AppUserAvatarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        AdminAssetController.class,
        AdminCustomerServiceController.class,
        AppCustomerServiceImageUploadController.class,
        AppUserController.class,
        AppAfterSaleController.class
})
@AutoConfigureMockMvc(addFilters = false)
class DirectUploadCancellationControllerTest {

    private static final AuthenticatedPrincipal ADMIN =
            new AuthenticatedPrincipal(
                    TokenKind.ADMIN,
                    11L,
                    "cancel-admin",
                    List.of("R_SUPER"),
                    List.of(
                            "asset:upload",
                            "customer-service:message:send"
                    )
            );
    private static final AuthenticatedPrincipal APP =
            new AuthenticatedPrincipal(
                    TokenKind.APP,
                    22L,
                    "cancel-app",
                    List.of(),
                    List.of()
            );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DirectUploadService directUploadService;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private CustomerServiceService customerServiceService;

    @MockitoBean
    private OperationsStatisticsService operationsStatisticsService;

    @MockitoBean
    private AppAuthService appAuthService;

    @MockitoBean
    private AppUserAvatarService appUserAvatarService;

    @MockitoBean
    private AppAfterSaleService appAfterSaleService;

    @MockitoBean
    private PathTokenKindResolver pathTokenKindResolver;

    @MockitoBean
    private OpaqueTokenService opaqueTokenService;

    @MockitoBean
    private AdminRbacService adminRbacService;

    @MockitoBean
    private AppUserDailyActivityService appUserDailyActivityService;

    @MockitoBean
    private AdminSystemLogRecorder adminSystemLogRecorder;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allDirectUploadResourcesExposeStandardCancellationEndpoints()
            throws Exception {
        authenticate(
                ADMIN,
                "asset:upload",
                "customer-service:message:send"
        );
        mockMvc.perform(delete(
                        "/admin/assets/upload-sessions/{uploadId}",
                        "library-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(directUploadService).cancelLibrary(
                ADMIN, "library-upload");

        mockMvc.perform(delete(
                        "/admin/customer-service/conversations/{conversationId}"
                                + "/images/upload-sessions/{uploadId}",
                        33L,
                        "admin-chat-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(customerServiceService).cancelImageUploadSessionFromAdmin(
                ADMIN, 33L, "admin-chat-upload");

        authenticate(APP);
        mockMvc.perform(delete(
                        "/app/users/me/avatar/upload-sessions/{uploadId}",
                        "avatar-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(appUserAvatarService).cancelDirectUploadSession(
                APP, "avatar-upload");

        mockMvc.perform(delete(
                        "/app/orders/{orderId}/after-sale-evidence"
                                + "/upload-sessions/{uploadId}",
                        44L,
                        "after-sale-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(appAfterSaleService).cancelEvidenceUploadSession(
                APP, 44L, "after-sale-upload");

        mockMvc.perform(delete(
                        "/app/customer-service/images"
                                + "/upload-sessions/{uploadId}",
                        "app-chat-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(customerServiceService).cancelImageUploadSessionFromApp(
                APP, "app-chat-upload");
    }

    private void authenticate(
            AuthenticatedPrincipal principal,
            String... authorities
    ) {
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }
}
