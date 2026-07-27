package org.muybaby.shopserver.user.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.user.address.service.AppAddressService;
import org.muybaby.shopserver.user.address.service.OwnedAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppAddressControllerTest {

    private static final String AUTHORIZATION = "Authorization";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AppAddressService appAddressService;

    @BeforeEach
    void clearAddresses() {
        jdbcClient.sql("delete from user_address").update();
    }

    @Test
    void addressApisRequireAppAuthentication() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(get("/app/addresses"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/app/addresses")
                        .header(AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyListAndFirstCreateTrimPersistAndFormatAddress() throws Exception {
        LoggedInApp user = appLogin("test-login-code");

        mockMvc.perform(get("/app/addresses")
                        .header(AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        Map<String, Object> request = validAddress("  张三  ", false);
        request.put("receiverPhone", "  13800138000  ");
        request.put("province", "  北京市  ");
        request.put("city", " 北京市 ");
        request.put("district", "  朝阳区 ");
        request.put("detailAddress", "  火锅路1号  ");
        request.put("locationName", "  朝阳火锅店  ");
        request.put("doorplate", "  3栋201  ");

        String response = mockMvc.perform(post("/app/addresses")
                        .header(AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.receiverName").value("张三"))
                .andExpect(jsonPath("$.data.receiverPhone").value("13800138000"))
                .andExpect(jsonPath("$.data.province").value("北京市"))
                .andExpect(jsonPath("$.data.city").value("北京市"))
                .andExpect(jsonPath("$.data.district").value("朝阳区"))
                .andExpect(jsonPath("$.data.detailAddress").value("火锅路1号"))
                .andExpect(jsonPath("$.data.locationName").value("朝阳火锅店"))
                .andExpect(jsonPath("$.data.doorplate").value("3栋201"))
                .andExpect(jsonPath("$.data.formattedAddress")
                        .value("北京市北京市朝阳区火锅路1号 朝阳火锅店 3栋201"))
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long addressId = objectMapper.readTree(response).path("data").path("id").asLong();
        Map<String, Object> persisted = jdbcClient.sql("""
                        select receiver_name, receiver_phone, province, city, district,
                               detail_address, location_name, doorplate
                        from user_address
                        where id = :addressId and user_id = :userId
                        """)
                .param("addressId", addressId)
                .param("userId", user.userId())
                .query()
                .singleRow();
        assertThat(persisted.get("RECEIVER_NAME")).isEqualTo("张三");
        assertThat(persisted.get("RECEIVER_PHONE")).isEqualTo("13800138000");
        assertThat(persisted.get("DETAIL_ADDRESS")).isEqualTo("火锅路1号");
        assertThat(persisted.get("LOCATION_NAME")).isEqualTo("朝阳火锅店");
        assertThat(persisted.get("DOORPLATE")).isEqualTo("3栋201");
    }

    @Test
    void explicitDefaultMovesAtomicallyAndListOrdersDefaultThenNewest() throws Exception {
        LoggedInApp user = appLogin("test-login-code");
        long first = createAddress(user.token(), "张三", false);
        long second = createAddress(user.token(), "李四", false);
        long third = createAddress(user.token(), "王五", true);

        mockMvc.perform(get("/app/addresses")
                        .header(AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(Long.toString(third)))
                .andExpect(jsonPath("$.data[0].isDefault").value(true))
                .andExpect(jsonPath("$.data[1].id").value(Long.toString(second)))
                .andExpect(jsonPath("$.data[2].id").value(Long.toString(first)));

        mockMvc.perform(post("/app/addresses/{id}/default", second)
                        .header(AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(second)))
                .andExpect(jsonPath("$.data.isDefault").value(true));

        getAddress(user.token(), first)
                .andExpect(jsonPath("$.data.isDefault").value(false));
        getAddress(user.token(), third)
                .andExpect(jsonPath("$.data.isDefault").value(false));
        mockMvc.perform(get("/app/addresses")
                        .header(AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(Long.toString(second)))
                .andExpect(jsonPath("$.data[1].id").value(Long.toString(third)))
                .andExpect(jsonPath("$.data[2].id").value(Long.toString(first)));
    }

    @Test
    void updatingCurrentDefaultWithFalseKeepsItDefault() throws Exception {
        LoggedInApp user = appLogin("test-login-code");
        long first = createAddress(user.token(), "张三", false);
        createAddress(user.token(), "李四", false);
        Map<String, Object> request = validAddress("  张三更新  ", false);
        request.put("detailAddress", "  火锅路88号  ");

        mockMvc.perform(put("/app/addresses/{id}", first)
                        .header(AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiverName").value("张三更新"))
                .andExpect(jsonPath("$.data.detailAddress").value("火锅路88号"))
                .andExpect(jsonPath("$.data.isDefault").value(true));

        assertExactlyOneDefault(user.userId(), first);
    }

    @Test
    void updatingSecondAddressWithTrueMovesDefaultAndListMatchesResponse() throws Exception {
        LoggedInApp user = appLogin("test-login-code");
        long first = createAddress(user.token(), "张三", false);
        long second = createAddress(user.token(), "李四", false);
        Map<String, Object> request = validAddress("  李四更新  ", true);
        request.put("detailAddress", "  火锅路99号  ");

        mockMvc.perform(put("/app/addresses/{id}", second)
                        .header(AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(second)))
                .andExpect(jsonPath("$.data.receiverName").value("李四更新"))
                .andExpect(jsonPath("$.data.detailAddress").value("火锅路99号"))
                .andExpect(jsonPath("$.data.isDefault").value(true));

        getAddress(user.token(), first)
                .andExpect(jsonPath("$.data.isDefault").value(false));
        mockMvc.perform(get("/app/addresses")
                        .header(AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(Long.toString(second)))
                .andExpect(jsonPath("$.data[0].receiverName").value("李四更新"))
                .andExpect(jsonPath("$.data[0].detailAddress").value("火锅路99号"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true))
                .andExpect(jsonPath("$.data[1].id").value(Long.toString(first)))
                .andExpect(jsonPath("$.data[1].isDefault").value(false));
        assertExactlyOneDefault(user.userId(), second);
    }

    @Test
    void deletingDefaultPromotesOldestRemainingAndReturnsEmptyEnvelope() throws Exception {
        LoggedInApp user = appLogin("test-login-code");
        long first = createAddress(user.token(), "张三", true);
        long second = createAddress(user.token(), "李四", false);
        long third = createAddress(user.token(), "王五", false);

        mockMvc.perform(delete("/app/addresses/{id}", first)
                        .header(AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());

        getAddress(user.token(), second)
                .andExpect(jsonPath("$.data.isDefault").value(true));
        getAddress(user.token(), third)
                .andExpect(jsonPath("$.data.isDefault").value(false));
        assertExactlyOneDefault(user.userId(), second);
    }

    @Test
    void anotherUserCannotEnumerateAnOwnedAddressAcrossOperations() throws Exception {
        LoggedInApp userA = appLogin("test-login-code");
        LoggedInApp userB = appLogin("second-login-code");
        long addressId = createAddress(userA.token(), "张三", true);
        String body = objectMapper.writeValueAsString(validAddress("李四", true));

        assertHidden(get("/app/addresses/{id}", addressId), userB.token(), null);
        assertHidden(put("/app/addresses/{id}", addressId), userB.token(), body);
        assertHidden(delete("/app/addresses/{id}", addressId), userB.token(), null);
        assertHidden(post("/app/addresses/{id}/default", addressId), userB.token(), null);
        assertHidden(get("/app/addresses/{id}", Long.MAX_VALUE), userA.token(), null);

        assertThatThrownBy(() -> appAddressService.requireOwnedForUpdate(userB.userId(), addressId))
                .isInstanceOf(BusinessException.class);
        OwnedAddress owned = appAddressService.requireOwnedForUpdate(userA.userId(), addressId);
        assertThat(owned.id()).isEqualTo(addressId);
        assertThat(owned.userId()).isEqualTo(userA.userId());
        assertThat(owned.receiverName()).isEqualTo("张三");
        assertThat(owned.receiverPhone()).isEqualTo("13800138000");
        assertThat(owned.formattedAddress()).isEqualTo("北京市北京市朝阳区火锅路张三号");
    }

    @Test
    void trimmedValidationRejectsBlankFieldsAndLengthOverflow() throws Exception {
        LoggedInApp user = appLogin("test-login-code");
        List<Map<String, Object>> invalidRequests = List.of(
                changed(validAddress("张三", false), "receiverName", "   "),
                changed(validAddress("张三", false), "receiverName", "姓".repeat(65)),
                changed(validAddress("张三", false), "receiverPhone", "1".repeat(33)),
                changed(validAddress("张三", false), "province", "   "),
                changed(validAddress("张三", false), "city", "\t"),
                changed(validAddress("张三", false), "district", "  "),
                changed(validAddress("张三", false), "detailAddress", "\n"),
                changed(validAddress("张三", false), "locationName", "地".repeat(129)),
                changed(validAddress("张三", false), "doorplate", "门".repeat(129))
        );

        for (Map<String, Object> request : invalidRequests) {
            mockMvc.perform(post("/app/addresses")
                            .header(AUTHORIZATION, bearer(user.token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(100400));
        }

        Long count = jdbcClient.sql("select count(*) from user_address where user_id = :userId")
                .param("userId", user.userId())
                .query(Long.class)
                .single();
        assertThat(count).isZero();
    }

    private long createAddress(String token, String receiverName, boolean isDefault) throws Exception {
        String response = mockMvc.perform(post("/app/addresses")
                        .header(AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validAddress(receiverName, isDefault))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private ResultActions getAddress(String token, long addressId) throws Exception {
        return mockMvc.perform(get("/app/addresses/{id}", addressId)
                        .header(AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private void assertHidden(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token,
            String body
    ) throws Exception {
        request.header(AUTHORIZATION, bearer(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    private void assertExactlyOneDefault(long userId, long expectedDefaultId) {
        List<Map<String, Object>> rows = jdbcClient.sql("""
                        select id, is_default
                        from user_address
                        where user_id = :userId
                        order by id
                        """)
                .param("userId", userId)
                .query()
                .listOfRows();
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().filter(row -> Boolean.TRUE.equals(row.get("IS_DEFAULT"))).count())
                .isEqualTo(1);
        assertThat(rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("IS_DEFAULT")))
                .map(row -> ((Number) row.get("ID")).longValue())
                .toList())
                .containsExactly(expectedDefaultId);
    }

    private Map<String, Object> validAddress(String receiverName, boolean isDefault) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("receiverName", receiverName);
        request.put("receiverPhone", "13800138000");
        request.put("province", "北京市");
        request.put("city", "北京市");
        request.put("district", "朝阳区");
        request.put("detailAddress", "火锅路" + receiverName.trim() + "号");
        request.put("isDefault", isDefault);
        return request;
    }

    private Map<String, Object> changed(Map<String, Object> source, String key, Object value) {
        source.put(key, value);
        return source;
    }

    private LoggedInApp appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new LoggedInApp(
                data.path("user").path("userId").asLong(),
                data.path("token").asText()
        );
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

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoggedInApp(long userId, String token) {
    }
}
