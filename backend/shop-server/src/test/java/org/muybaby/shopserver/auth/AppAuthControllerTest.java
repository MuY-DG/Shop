package org.muybaby.shopserver.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.InMemoryTokenStore;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenGrant;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppAuthControllerTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private InMemoryTokenStore tokenStore;

    @Test
    void appLoginExchangesCodeAndIssuesAppToken() throws Exception {
        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("apr_")))
                .andExpect(jsonPath("$.data.expiresIn").value(604800))
                .andExpect(jsonPath("$.data.user.nickname", startsWith("用户")))
                .andExpect(jsonPath("$.data.user.openidMasked").value("test****code"))
                .andExpect(jsonPath("$.data.user.phoneAuthorized").value(false))
                .andExpect(jsonPath("$.data.user.phoneNumberMasked").doesNotExist());
    }

    @Test
    void loginMeAndPhoneReturnTheSameProfileShape() throws Exception {
        AppSession login = login("profile-consistency");

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(Long.toString(login.userId())))
                .andExpect(jsonPath("$.data.nickname").value(login.nickname()))
                .andExpect(jsonPath("$.data.openidMasked").value("test****ency"))
                .andExpect(jsonPath("$.data.phoneAuthorized").value(false))
                .andExpect(jsonPath("$.data.phoneNumberMasked").doesNotExist());

        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", bearer(login.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"test-phone-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(Long.toString(login.userId())))
                .andExpect(jsonPath("$.data.nickname").value(login.nickname()))
                .andExpect(jsonPath("$.data.openidMasked").value("test****ency"))
                .andExpect(jsonPath("$.data.phoneAuthorized").value(true))
                .andExpect(jsonPath("$.data.phoneNumberMasked").value("138****5678"))
                .andExpect(content().string(not(containsString("13812345678"))));
    }

    @Test
    void appUserCanUpdateNicknameAndReadItFromLaterProfiles() throws Exception {
        AppSession login = login("nickname-profile-user");

        mockMvc.perform(put("/app/users/me")
                        .header("Authorization", bearer(login.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  山茶花用户  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(Long.toString(login.userId())))
                .andExpect(jsonPath("$.data.nickname").value("山茶花用户"));

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("山茶花用户"));

        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"nickname-profile-user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.nickname").value("山茶花用户"));
    }

    @Test
    void appUserCanUploadWechatChosenAvatarAndReadItFromLaterProfiles() throws Exception {
        AppSession login = login("avatar-profile-user");

        MvcResult uploadResult = mockMvc.perform(multipart("/app/users/me/avatar")
                        .file(new MockMultipartFile("file", "wechat-avatar.png", "image/png", TINY_PNG))
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl", startsWith("http://localhost:8080/files/public/")))
                .andReturn();
        String avatarUrl = read(uploadResult, "/data/avatarUrl").asText();

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(avatarUrl));

        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"avatar-profile-user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.avatarUrl").value(avatarUrl));
    }

    @Test
    void appUserCanSaveWechatAvatarUrlWithoutUploadingAFile() throws Exception {
        AppSession login = login("avatar-url-profile-user");
        String avatarUrl = "https://thirdwx.qlogo.cn/mmopen/vi_32/wechat-avatar/132";

        mockMvc.perform(put("/app/users/me/avatar")
                        .header("Authorization", bearer(login.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("avatarUrl", avatarUrl))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(avatarUrl));

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(avatarUrl));

        mockMvc.perform(put("/app/users/me/avatar")
                        .header("Authorization", bearer(login.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\":\"https://images.example.test/avatar.png\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void nicknameUpdateRequiresAppTokenAndValidNickname() throws Exception {
        AppSession login = login("nickname-validation-user");

        mockMvc.perform(put("/app/users/me")
                        .header("Authorization", bearer(login.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"单\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(put("/app/users/me")
                        .header("Authorization", bearer(adminLoginAndExtractToken("token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"后台管理员\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesOnceAndLogoutRevokesTheNewSession() throws Exception {
        AppSession login = login("refresh-once");
        MvcResult refreshed = mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", login.refreshToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("apr_")))
                .andExpect(jsonPath("$.data.user.userId").value(Long.toString(login.userId())))
                .andReturn();

        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", login.refreshToken()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        String newAccess = read(refreshed, "/data/token").asText();
        mockMvc.perform(post("/app/auth/logout")
                        .header("Authorization", bearer(newAccess)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(newAccess)))
                .andExpect(status().isUnauthorized());

        String newRefresh = read(refreshed, "/data/refreshToken").asText();
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", newRefresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void phoneAuthorizationPersistsIntoLaterLoginAndMeWithoutLeakingFullNumber() throws Exception {
        AppSession firstLogin = login("persistent-phone");
        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", bearer(firstLogin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"test-phone-code\"}"))
                .andExpect(status().isOk());

        MvcResult laterLoginResult = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"persistent-phone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.userId").value(Long.toString(firstLogin.userId())))
                .andExpect(jsonPath("$.data.user.phoneAuthorized").value(true))
                .andExpect(jsonPath("$.data.user.phoneNumberMasked").value("138****5678"))
                .andExpect(content().string(not(containsString("13812345678"))))
                .andReturn();

        String laterAccess = read(laterLoginResult, "/data/token").asText();
        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(laterAccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(Long.toString(firstLogin.userId())))
                .andExpect(jsonPath("$.data.phoneAuthorized").value(true))
                .andExpect(jsonPath("$.data.phoneNumberMasked").value("138****5678"))
                .andExpect(content().string(not(containsString("13812345678"))));
    }

    @Test
    void refreshRejectsExpiredWrongKindAndMalformedTokensAsAuthenticationRequired() throws Exception {
        String expiredToken = "apr_expired-token";
        TokenSession expiredSession = TokenSession.app(99L, "expired", Instant.now());
        tokenStore.saveFamily(expiredSession.sessionId(), List.of(new TokenGrant(
                tokenKey(TokenKind.APP, "refresh", expiredToken),
                expiredSession,
                Duration.ZERO
        )));

        assertRefreshUnauthorized(expiredToken);
        assertRefreshUnauthorized(adminLoginAndExtractRefreshToken());
        assertRefreshUnauthorized("not-a-refresh-token");
    }

    @Test
    void blankNullAndMissingRefreshTokensAreAuthenticationRequired() throws Exception {
        for (String requestBody : List.of(
                "{}",
                "{\"refreshToken\":null}",
                "{\"refreshToken\":\"\"}",
                "{\"refreshToken\":\"   \"}"
        )) {
            mockMvc.perform(post("/app/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(100001));
        }
    }

    @Test
    void unparseableRefreshJsonRemainsBadRequest() throws Exception {
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacySessionWithoutFamilyIndexIsRevokedThroughMarker() throws Exception {
        AppSession login = login("legacy-session");
        TokenSession session = opaqueTokenService.lookupAccessToken(login.token(), TokenKind.APP).orElseThrow();
        removeSessionIndex(session.sessionId());

        mockMvc.perform(post("/app/auth/logout")
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(login.token())))
                .andExpect(status().isUnauthorized());
        assertRefreshUnauthorized(login.refreshToken());
        org.assertj.core.api.Assertions.assertThat(tokenStore.isSessionRevoked(session.sessionId())).isTrue();
    }

    @Test
    void adminTokenCannotAuthorizeAppPhoneApi() throws Exception {
        String adminToken = adminLoginAndExtractToken("token");

        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-phone-code"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private AppSession login(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk())
                .andReturn();
        return new AppSession(
                read(result, "/data/token").asText(),
                read(result, "/data/refreshToken").asText(),
                read(result, "/data/user/userId").asLong(),
                read(result, "/data/user/nickname").asText()
        );
    }

    private void assertRefreshUnauthorized(String refreshToken) throws Exception {
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    private String adminLoginAndExtractRefreshToken() throws Exception {
        return adminLoginAndExtractToken("refreshToken");
    }

    private String adminLoginAndExtractToken(String field) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return read(result, "/data/" + field).asText();
    }

    private JsonNode read(MvcResult result, String pointer) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at(pointer);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String tokenKey(TokenKind kind, String type, String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return "shop:auth:" + kind.namespace() + ":" + type + ":" + HexFormat.of().formatHex(digest);
    }

    @SuppressWarnings("unchecked")
    private void removeSessionIndex(String sessionId) throws Exception {
        Field field = InMemoryTokenStore.class.getDeclaredField("sessionKeys");
        field.setAccessible(true);
        ((Map<String, ?>) field.get(tokenStore)).remove(sessionId);
    }

    private record AppSession(String token, String refreshToken, long userId, String nickname) {
    }
}
