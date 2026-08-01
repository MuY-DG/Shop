package org.muybaby.shopserver.maintenance.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigUpdateRequest;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupTaskUpdateRequest;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminDataCleanupConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataCleanupConfigService configService;

    @Test
    void endpointsAllowWritersToReadButStillProtectUpdates() throws Exception {
        String readToken = token(List.of("data-cleanup:config:read"));
        String writeToken = token(List.of("data-cleanup:config:write"));

        mockMvc.perform(get("/admin/data-cleanup/config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/data-cleanup/config")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/data-cleanup/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.tasks.length()").value(5));

        DataCleanupConfigResponse current = configService.current();
        String body = objectMapper.writeValueAsString(new DataCleanupConfigUpdateRequest(
                current.revision(),
                current.tasks().stream()
                        .map(task -> new DataCleanupTaskUpdateRequest(
                                task.taskCode(),
                                task.enabled(),
                                task.retentionDays(),
                                task.batchSize(),
                                task.cronExpression(),
                                task.batchIntervalSeconds(),
                                task.uploadPendingGraceMinutes()
                        ))
                        .toList()
        ));

        mockMvc.perform(put("/admin/data-cleanup/config")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/data-cleanup/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.tasks.length()").value(5));
    }

    @Test
    void rejectsInvalidPayloadAndReturnsConflictForAStaleRevision() throws Exception {
        String writeToken = token(List.of("data-cleanup:config:write"));
        mockMvc.perform(put("/admin/data-cleanup/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"tasks\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        DataCleanupConfigResponse current = configService.current();
        DataCleanupConfigUpdateRequest stale = new DataCleanupConfigUpdateRequest(
                current.revision() + 1,
                current.tasks().stream()
                        .map(task -> new DataCleanupTaskUpdateRequest(
                                task.taskCode(),
                                task.enabled(),
                                task.retentionDays(),
                                task.batchSize(),
                                task.cronExpression(),
                                task.batchIntervalSeconds(),
                                task.uploadPendingGraceMinutes()
                        ))
                        .toList()
        );
        mockMvc.perform(put("/admin/data-cleanup/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.DATA_CLEANUP_CONFIG_CONFLICT.code()));
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }
}
