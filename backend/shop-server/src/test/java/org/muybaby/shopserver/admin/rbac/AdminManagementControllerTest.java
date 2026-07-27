package org.muybaby.shopserver.admin.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminUserAndRoleManagementUseRealRbacTables() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/admin/system/users")
                        .param("current", "1")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].username").value("Super"))
                .andExpect(jsonPath("$.data.records[0].maxSessions").value(0))
                .andExpect(jsonPath("$.data.records[0].roleCodes", containsInAnyOrder("R_SUPER")));

        long roleId = createRole(token, "R_SUPPORT");

        mockMvc.perform(put("/admin/system/roles/{roleId}/grants", roleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":[100,101,200,201],"permissionIds":[1000]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/system/roles/{roleId}/grants", roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuIds", containsInAnyOrder(100, 101, 200, 201)))
                .andExpect(jsonPath("$.data.permissionIds", containsInAnyOrder(1000)));

        String createUserResponse = mockMvc.perform(post("/admin/system/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"Support",
                                  "displayName":"Support User",
                                  "email":"support@shop.local",
                                  "password":"123456",
                                  "avatar":"",
                                  "roleIds":[%d],
                                  "maxSessions":2
                                }
                                """.formatted(roleId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long userId = objectMapper.readTree(createUserResponse).path("data").asLong();

        String filteredUsersResponse = mockMvc.perform(get("/admin/system/users")
                        .param("username", "support")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].maxSessions").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode returnedRoleIds = objectMapper.readTree(filteredUsersResponse)
                .at("/data/records/0/roleIds");
        assertThat(returnedRoleIds.isArray()).isTrue();
        assertThat(returnedRoleIds.size()).isEqualTo(1);
        assertThat(returnedRoleIds.get(0).isIntegralNumber()).isTrue();
        assertThat(returnedRoleIds.get(0).longValue()).isEqualTo(roleId);

        mockMvc.perform(delete("/admin/system/roles/{roleId}", roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(110005));

        mockMvc.perform(delete("/admin/system/users/{userId}", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Support","password":"123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));

        mockMvc.perform(delete("/admin/system/users/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(110006));
    }

    @Test
    void roleGrantRejectsMissingParentMenusAndPermissionsWithoutOwningMenus() throws Exception {
        String token = loginAndExtractToken();
        long roleId = createRole(token, "R_GRANT_VALIDATION");

        mockMvc.perform(put("/admin/system/roles/{roleId}/grants", roleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":[100,101],"permissionIds":[]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/system/roles/{roleId}/grants", roleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":[101],"permissionIds":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(110007));

        mockMvc.perform(put("/admin/system/roles/{roleId}/grants", roleId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":[100,101],"permissionIds":[1000]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(110007));

        mockMvc.perform(get("/admin/system/roles/{roleId}/grants", roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuIds", containsInAnyOrder(100, 101)))
                .andExpect(jsonPath("$.data.permissionIds").isEmpty());
    }

    private long createRole(String token, String code) throws Exception {
        String response = mockMvc.perform(post("/admin/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Support","description":"Support role","enabled":true}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String loginAndExtractToken() throws Exception {
        return loginAndExtractToken("Super", "123456");
    }

    private String loginAndExtractToken(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "userName", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return data.path("token").asText();
    }
}
