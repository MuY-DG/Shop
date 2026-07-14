package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminProductSpecTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createReadAndRenameTemplateWhileKeepingStructureIdsAndKeys() throws Exception {
        String token = adminLoginAndExtractToken();
        long templateId = createTemplate(token, createRequest("服装规格"));

        mockMvc.perform(get("/admin/product/spec-templates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(templateId))
                .andExpect(jsonPath("$.data[0].name").value("服装规格"))
                .andExpect(jsonPath("$.data[0].groupCount").value(2))
                .andExpect(jsonPath("$.data[0].valueCount").value(4));

        JsonNode detail = getDetail(token, templateId);
        long colorGroupId = detail.at("/groups/0/id").asLong();
        long redValueId = detail.at("/groups/0/values/0/id").asLong();
        long blueValueId = detail.at("/groups/0/values/1/id").asLong();
        long sizeGroupId = detail.at("/groups/1/id").asLong();
        long smallValueId = detail.at("/groups/1/values/0/id").asLong();
        long largeValueId = detail.at("/groups/1/values/1/id").asLong();

        String updateRequest = """
                {
                  "name":"服装规格更新",
                  "groups":[
                    {
                      "id":%d,"groupKey":"color","name":"颜色更新","imageEnabled":true,"sortOrder":10,
                      "values":[
                        {"id":%d,"valueKey":"red","valueName":"朱红","sortOrder":10},
                        {"id":%d,"valueKey":"blue","valueName":"海蓝","sortOrder":20}
                      ]
                    },
                    {
                      "id":%d,"groupKey":"size","name":"尺码更新","imageEnabled":false,"sortOrder":20,
                      "values":[
                        {"id":%d,"valueKey":"small","valueName":"小码","sortOrder":10},
                        {"id":%d,"valueKey":"large","valueName":"大码","sortOrder":20}
                      ]
                    }
                  ]
                }
                """.formatted(
                colorGroupId, redValueId, blueValueId,
                sizeGroupId, smallValueId, largeValueId
        );
        mockMvc.perform(put("/admin/product/spec-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest))
                .andExpect(status().isOk());

        JsonNode updated = getDetail(token, templateId);
        org.assertj.core.api.Assertions.assertThat(updated.path("name").asText()).isEqualTo("服装规格更新");
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/0/id").asLong()).isEqualTo(colorGroupId);
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/0/groupKey").asText()).isEqualTo("color");
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/0/imageEnabled").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/1/id").asLong()).isEqualTo(sizeGroupId);
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/1/groupKey").asText()).isEqualTo("size");
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/1/imageEnabled").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/0/values/1/id").asLong()).isEqualTo(blueValueId);
        org.assertj.core.api.Assertions.assertThat(updated.at("/groups/0/values/1/valueKey").asText()).isEqualTo("blue");

        String removedGroupRequest = """
                {
                  "name":"非法删除规格名",
                  "groups":[{
                    "id":%d,"groupKey":"size","name":"尺码","imageEnabled":true,"sortOrder":1,
                    "values":[
                      {"id":%d,"valueKey":"large","valueName":"大码","sortOrder":1},
                      {"id":%d,"valueKey":"small","valueName":"小码","sortOrder":2}
                    ]
                  }]
                }
                """.formatted(sizeGroupId, largeValueId, smallValueId);
        assertInvalidUpdate(token, templateId, removedGroupRequest);

        ObjectNode addedGroupRequest = (ObjectNode) objectMapper.readTree(updateRequest);
        ((ArrayNode) addedGroupRequest.path("groups")).add(objectMapper.readTree("""
                {"groupKey":"material","name":"材质","imageEnabled":false,"sortOrder":3,
                 "values":[{"valueKey":"cotton","valueName":"棉","sortOrder":1}]}
                """));
        assertInvalidUpdate(token, templateId, objectMapper.writeValueAsString(addedGroupRequest));

        ObjectNode removedValueRequest = (ObjectNode) objectMapper.readTree(updateRequest);
        ((ArrayNode) removedValueRequest.at("/groups/0/values")).remove(1);
        assertInvalidUpdate(token, templateId, objectMapper.writeValueAsString(removedValueRequest));

        ObjectNode addedValueRequest = (ObjectNode) objectMapper.readTree(updateRequest);
        ((ArrayNode) addedValueRequest.at("/groups/0/values")).add(objectMapper.readTree("""
                {"valueKey":"medium","valueName":"中码","sortOrder":3}
                """));
        assertInvalidUpdate(token, templateId, objectMapper.writeValueAsString(addedValueRequest));

        String changedKeyRequest = updateRequest.replace("\"groupKey\":\"size\"", "\"groupKey\":\"new-size\"");
        assertInvalidUpdate(token, templateId, changedKeyRequest);

        String changedImageGroupRequest = updateRequest.replace(
                "\"groupKey\":\"color\",\"name\":\"颜色更新\",\"imageEnabled\":true",
                "\"groupKey\":\"color\",\"name\":\"颜色更新\",\"imageEnabled\":false"
        );
        assertInvalidUpdate(token, templateId, changedImageGroupRequest);

        String changedSortRequest = updateRequest.replace(
                "\"groupKey\":\"color\",\"name\":\"颜色更新\",\"imageEnabled\":true,\"sortOrder\":10",
                "\"groupKey\":\"color\",\"name\":\"颜色更新\",\"imageEnabled\":true,\"sortOrder\":11"
        );
        assertInvalidUpdate(token, templateId, changedSortRequest);

        mockMvc.perform(post("/admin/product/spec-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestWithNoImageGroup()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    @Test
    void templateEndpointsRequireTheirMethodLevelAuthorities() throws Exception {
        String unrelatedToken = limitedAdminToken(List.of("product:sku:stock"));
        String spuUpdateToken = limitedAdminToken(List.of("product:spu:update"));
        String createToken = limitedAdminToken(List.of("product:spec-template:create"));
        String updateToken = limitedAdminToken(List.of("product:spec-template:update"));

        mockMvc.perform(get("/admin/product/spec-templates")
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        mockMvc.perform(get("/admin/product/spec-templates")
                        .header("Authorization", "Bearer " + spuUpdateToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/product/spec-templates")
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest("权限规格")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        long templateId = createTemplate(createToken, createRequest("权限规格"));

        mockMvc.perform(put("/admin/product/spec-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestForSingleTemplate(templateId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        JsonNode detail = getDetail(updateToken, templateId);
        long groupId = detail.at("/groups/0/id").asLong();
        long valueId = detail.at("/groups/0/values/0/id").asLong();
        String validUpdate = """
                {"name":"权限规格更新","groups":[{
                  "id":%d,"groupKey":"color","name":"颜色更新","imageEnabled":true,"sortOrder":0,
                  "values":[{"id":%d,"valueKey":"red","valueName":"红色更新","sortOrder":0}]
                }]}
                """.formatted(groupId, valueId);
        mockMvc.perform(put("/admin/product/spec-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdate))
                .andExpect(status().isOk());
    }

    @Test
    void multiSpecProductCanBeSavedAsIndependentTemplateSnapshot() throws Exception {
        String token = adminLoginAndExtractToken();
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, '另存模板分类', '', 0, 'ENABLED')
                        """).update();
        Long categoryId = jdbcClient.sql("select id from product_category where name = '另存模板分类'")
                .query(Long.class).single();
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html,
                             sort_order, status, spec_type, freight_template_id)
                        values
                            (:categoryId, '模板来源商品', '', 'cover.jpg', '', '', 0, 'DRAFT', 'MULTI', 1)
                        """).param("categoryId", categoryId).update();
        Long spuId = jdbcClient.sql("select id from product_spu where title = '模板来源商品'")
                .query(Long.class).single();
        jdbcClient.sql("""
                        insert into product_spu_spec_group
                            (spu_id, group_key, name, image_enabled, sort_order)
                        values (:spuId, 'color', '颜色', true, 0)
                        """).param("spuId", spuId).update();
        Long groupId = jdbcClient.sql("select id from product_spu_spec_group where spu_id = :spuId")
                .param("spuId", spuId).query(Long.class).single();
        jdbcClient.sql("""
                        insert into product_spu_spec_value
                            (group_id, value_key, value_name, image, sort_order)
                        values
                            (:groupId, 'red', '红色', 'red.jpg', 0),
                            (:groupId, 'blue', '蓝色', 'blue.jpg', 1)
                        """).param("groupId", groupId).update();

        String response = mockMvc.perform(post("/admin/product/spus/{spuId}/spec-template", spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"来源商品规格\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long templateId = objectMapper.readTree(response).path("data").asLong();

        mockMvc.perform(get("/admin/product/spec-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("来源商品规格"))
                .andExpect(jsonPath("$.data.groups[0].groupKey").value("color"))
                .andExpect(jsonPath("$.data.groups[0].values[0].valueKey").value("red"))
                .andExpect(jsonPath("$.data.groups[0].values[1].valueKey").value("blue"));
    }

    private long createTemplate(String token, String body) throws Exception {
        String response = mockMvc.perform(post("/admin/product/spec-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private void assertInvalidUpdate(String token, long templateId, String body) throws Exception {
        mockMvc.perform(put("/admin/product/spec-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    private JsonNode getDetail(String token, long templateId) throws Exception {
        String response = mockMvc.perform(get("/admin/product/spec-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private String createRequest(String name) {
        if ("权限规格".equals(name)) {
            return """
                    {"name":"权限规格","groups":[{
                      "groupKey":"color","name":"颜色","imageEnabled":true,"sortOrder":0,
                      "values":[{"valueKey":"red","valueName":"红色","sortOrder":0}]
                    }]}
                    """;
        }
        return """
                {
                  "name":"%s",
                  "groups":[
                    {
                      "groupKey":"color","name":"颜色","imageEnabled":true,"sortOrder":10,
                      "values":[
                        {"valueKey":"red","valueName":"红色","sortOrder":10},
                        {"valueKey":"blue","valueName":"蓝色","sortOrder":20}
                      ]
                    },
                    {
                      "groupKey":"size","name":"尺码","imageEnabled":false,"sortOrder":20,
                      "values":[
                        {"valueKey":"small","valueName":"小码","sortOrder":10},
                        {"valueKey":"large","valueName":"大码","sortOrder":20}
                      ]
                    }
                  ]
                }
                """.formatted(name);
    }

    private String createRequestWithNoImageGroup() {
        return """
                {"name":"无图片规格","groups":[{
                  "groupKey":"size","name":"尺码","imageEnabled":false,"sortOrder":0,
                  "values":[{"valueKey":"small","valueName":"小码","sortOrder":0}]
                }]}
                """;
    }

    private String updateRequestForSingleTemplate(long ignoredTemplateId) {
        return createRequest("权限规格更新");
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String limitedAdminToken(List<String> permissions) {
        return issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }
}
