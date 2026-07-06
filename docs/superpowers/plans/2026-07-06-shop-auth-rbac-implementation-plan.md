# Shop Authentication And RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Milestone 1 authentication and RBAC for the Spring Boot backend, Art Design Pro admin console, and WeChat mini program shell.

**Architecture:** Use Spring Security with opaque access and refresh tokens stored by hash in Redis, with a memory token store for tests. Admin and mini program identities use separate token prefixes, Redis namespaces, principals, and security path rules so an admin token never authenticates `/app/**` and an app token never authenticates `/admin/**`. RBAC is backend-driven: the database stores admin users, roles, menus, and permission marks, and the admin menu API returns Art Design Pro route records with `meta.authList`.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security, MyBatis-Plus, Flyway, MySQL/H2, Redis, BCrypt, Art Design Pro, Vue 3, TypeScript, Vite, native WeChat mini program TypeScript.

---

## Scope Boundary

This plan covers only Milestone 1 from `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`.

Included:

- Spring Security token model.
- Admin username/password login API.
- Admin current user API.
- Backend-driven Art Design Pro menu and route API.
- Admin user, role, permission, and menu model.
- Mini program silent login API using `wx.login` code exchange.
- Optional phone authorization API using a WeChat phone code.
- Admin token and app token isolation.
- Redis/session/token storage strategy with test memory store.
- Backend, admin, mini program tests and smoke checks.

Excluded:

- Product, inventory, cart, coupon, order, payment, shipment, after-sale, and refund behavior.
- Admin CRUD screens for users, roles, menus, and permissions.
- WeChat Pay callback security.
- WeChat platform callback verification.

## References

- Product design: `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- Prior foundation plan: `docs/superpowers/plans/2026-07-06-shop-foundation-implementation-plan.md`
- Art Design Pro permission mode: https://www.artd.pro/docs/zh/guide/in-depth/permission.html
- Art Design Pro route and menu shape: https://www.artd.pro/docs/zh/guide/essentials/route.html
- WeChat mini program login API: https://developers.weixin.qq.com/miniprogram/dev/api/open-api/login/wx.login.html
- WeChat code2Session API: https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html
- WeChat phone number API: https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-info/phone-number/getPhoneNumber.html

## API Contracts

All APIs use the existing envelope:

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

Admin login:

```http
POST /admin/auth/login
Content-Type: application/json

{
  "userName": "Super",
  "password": "123456"
}
```

```json
{
  "token": "adm_access_example",
  "refreshToken": "adm_refresh_example",
  "expiresIn": 7200
}
```

Admin current user:

```http
GET /admin/auth/current-user
Authorization: Bearer adm_access_example
```

```json
{
  "userId": 1,
  "userName": "Super",
  "email": "super@shop.local",
  "avatar": "",
  "roles": ["R_SUPER"],
  "buttons": ["system:user:create", "system:user:update", "system:role:update"]
}
```

Admin menu tree:

```http
GET /admin/system/menus
Authorization: Bearer adm_access_example
```

```json
[
  {
    "id": 100,
    "name": "Dashboard",
    "path": "/dashboard",
    "component": "/index/index",
    "meta": {
      "title": "menus.dashboard.title",
      "icon": "ri:dashboard-line",
      "keepAlive": false,
      "authList": []
    },
    "children": [
      {
        "id": 101,
        "name": "Console",
        "path": "console",
        "component": "/dashboard/console",
        "meta": {
          "title": "menus.dashboard.console",
          "icon": "ri:dashboard-line",
          "keepAlive": false,
          "authList": []
        },
        "children": []
      }
    ]
  }
]
```

Mini program silent login:

```http
POST /app/auth/login
Content-Type: application/json

{
  "code": "wx-login-code"
}
```

```json
{
  "token": "app_access_example",
  "refreshToken": "app_refresh_example",
  "expiresIn": 604800,
  "user": {
    "userId": 1,
    "openidMasked": "oabc****xyz",
    "phoneAuthorized": false
  }
}
```

Optional phone authorization:

```http
POST /app/auth/phone
Authorization: Bearer app_access_example
Content-Type: application/json

{
  "code": "wx-phone-code"
}
```

```json
{
  "phoneAuthorized": true,
  "phoneNumberMasked": "138****5678"
}
```

## Token And Session Strategy

Use opaque bearer tokens instead of JWT:

```text
adm_<43+ url-safe random chars>  admin access token
adr_<43+ url-safe random chars>  admin refresh token
app_<43+ url-safe random chars>  app access token
apr_<43+ url-safe random chars>  app refresh token
```

Store only SHA-256 hashes of token values:

```text
shop:auth:admin:access:<sha256>   TTL 2h
shop:auth:admin:refresh:<sha256>  TTL 7d
shop:auth:app:access:<sha256>     TTL 7d
shop:auth:app:refresh:<sha256>    TTL 30d
```

Session payload:

```json
{
  "sessionId": "uuid",
  "kind": "ADMIN",
  "subjectId": 1,
  "subjectName": "Super",
  "roles": ["R_SUPER"],
  "permissions": ["system:user:create"],
  "issuedAt": "2026-07-06T12:00:00Z",
  "expiresAt": "2026-07-06T14:00:00Z"
}
```

Security rules:

- `/admin/auth/login`, `/app/auth/login`, `/app/health`, `/actuator/health`, and `/actuator/info` are public.
- `/admin/**` requires an `ADMIN` token.
- `/app/**` requires an `APP` token.
- `/wxpay/**` and `/wechat/**` remain unauthenticated by user token; later payment/platform plans add signature verification filters.
- Missing, expired, malformed, or wrong-kind tokens return HTTP 401 with `ErrorCode.AUTHENTICATION_REQUIRED`.
- Authenticated admin users missing method-level permission return HTTP 403 with `ErrorCode.PERMISSION_DENIED`.

## File Structure

Planned files and responsibilities:

```text
backend/shop-server/src/main/java/org/muybaby/shopserver/
  auth/
    AdminAuthController.java              Admin login/current-user/refresh endpoints.
    AppAuthController.java                Mini program login/phone/refresh endpoints.
    dto/
      AdminLoginRequest.java
      LoginTokenResponse.java
      RefreshTokenRequest.java
      CurrentAdminUserResponse.java
      AppLoginRequest.java
      AppLoginResponse.java
      AppUserSummary.java
      PhoneAuthorizeRequest.java
      PhoneAuthorizeResponse.java
    service/
      AdminAuthService.java               Admin credential verification and response assembly.
      AppAuthService.java                 WeChat login and phone authorization workflow.
  auth/token/
    TokenKind.java                        ADMIN or APP.
    TokenPair.java                        Issued token values and TTL seconds.
    TokenSession.java                     Serializable authenticated session.
    TokenStore.java                       Storage abstraction.
    InMemoryTokenStore.java               Test token store.
    RedisTokenStore.java                  Redis-backed token store.
    OpaqueTokenService.java               Token generation, hashing, issue, lookup, refresh.
    TokenProperties.java                  TTL and store configuration.
  security/
    ApiAccessDeniedHandler.java           JSON 403 response.
    ApiAuthenticationEntryPoint.java      JSON 401 response.
    AuthenticatedPrincipal.java           Principal used by controllers and method security.
    PathTokenKindResolver.java            Maps request path to required token kind.
    TokenAuthentication.java              Spring Authentication implementation.
    TokenAuthenticationFilter.java        Bearer token filter.
    SecurityConfig.java                   Spring Security chain and method security.
  admin/rbac/
    AdminMenuController.java              Backend menu API.
    dto/
      AdminRouteResponse.java
      AdminRouteMetaResponse.java
      AdminRouteAuthResponse.java
    entity/
      AdminUser.java
      AdminRole.java
      AdminMenu.java
      AdminPermission.java
    mapper/
      AdminUserMapper.java
      AdminRoleMapper.java
      AdminMenuMapper.java
      AdminPermissionMapper.java
    service/
      AdminRbacService.java               Role, permission, and menu queries.
      AdminMenuRouteService.java          Art Design Pro menu tree assembly.
  user/
    entity/AppUser.java
    mapper/AppUserMapper.java
    service/AppUserService.java
  wechat/
    WechatMiniProgramClient.java          Port for WeChat APIs.
    WechatMiniProgramProperties.java
    WechatCodeSession.java
    WechatPhoneInfo.java
    RestWechatMiniProgramClient.java
    MockWechatMiniProgramClient.java
  common/error/ErrorCode.java             Add auth and WeChat login error codes.

backend/shop-server/src/main/resources/
  application.yaml                        Auth and WeChat config defaults.
  application-dev.yaml                    Redis and WeChat mock settings.
  db/migration/V2__auth_rbac.sql          Auth, RBAC, app user schema and seed data.

backend/shop-server/src/test/java/org/muybaby/shopserver/
  auth/
    AdminAuthControllerTest.java
    AppAuthControllerTest.java
  auth/token/
    OpaqueTokenServiceTest.java
    InMemoryTokenStoreTest.java
  security/
    PathTokenKindResolverTest.java
    TokenAuthenticationFilterTest.java
    SecurityConfigTest.java
  admin/rbac/
    AdminMenuControllerTest.java
    AdminMenuRouteServiceTest.java
  wechat/
    MockWechatMiniProgramClientTest.java

admin/src/
  api/auth.ts                             Point admin auth APIs at `/admin/auth/*`.
  api/system-manage.ts                    Point menu API at `/admin/system/menus`.
  types/api/api.d.ts                      Match backend auth response contracts.
  utils/http/index.ts                     Send `Authorization: Bearer <token>`.

miniprogram/
  app.ts                                  Restore app token from storage on launch.
  services/auth.ts                        Silent login and phone authorization calls.
  types/api.ts                            Auth response types.
  utils/request.ts                        Preserve bearer token behavior.
  pages/profile/profile.ts                Trigger silent login and optional phone auth.
  pages/profile/profile.wxml              Add phone auth button binding.
```

## Task 1: Token Model And Store

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenKind.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenPair.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenSession.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenStore.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/InMemoryTokenStore.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/RedisTokenStore.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/OpaqueTokenService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenProperties.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token/OpaqueTokenServiceTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token/InMemoryTokenStoreTest.java`
- Modify: `backend/shop-server/src/main/resources/application.yaml`
- Modify: `backend/shop-server/src/test/resources/application-test.yaml`

- [ ] **Step 1: Write failing tests for token prefixes, lookup, expiry, and wrong-kind isolation**

Create `OpaqueTokenServiceTest`:

```java
package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTokenStore tokenStore = new InMemoryTokenStore(clock);
    private final TokenProperties properties = new TokenProperties(
            Duration.ofHours(2),
            Duration.ofDays(7),
            Duration.ofDays(7),
            Duration.ofDays(30)
    );
    private final OpaqueTokenService tokenService = new OpaqueTokenService(tokenStore, properties, clock);

    @Test
    void issueAdminTokensWithAdminPrefixesAndLookupSessionByAccessToken() {
        TokenSession session = TokenSession.admin(1L, "Super", List.of("R_SUPER"), List.of("system:user:create"), clock.instant());

        TokenPair pair = tokenService.issue(TokenKind.ADMIN, session);

        assertThat(pair.accessToken()).startsWith("adm_");
        assertThat(pair.refreshToken()).startsWith("adr_");
        assertThat(pair.expiresIn()).isEqualTo(7200);
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.ADMIN)).contains(session);
    }

    @Test
    void issueAppTokensWithAppPrefixesAndRejectAdminLookup() {
        TokenSession session = TokenSession.app(9L, "openid-user", clock.instant());

        TokenPair pair = tokenService.issue(TokenKind.APP, session);

        assertThat(pair.accessToken()).startsWith("app_");
        assertThat(pair.refreshToken()).startsWith("apr_");
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.ADMIN)).isEmpty();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).contains(session);
    }
}
```

Create `InMemoryTokenStoreTest`:

```java
package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenStoreTest {

    @Test
    void expiredSessionsAreNotReturned() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = TokenSession.admin(1L, "Super", List.of("R_SUPER"), List.of(), clock.instant());

        store.save("shop:auth:admin:access:hash", session, Duration.ZERO);

        assertThat(store.find("shop:auth:admin:access:hash")).isEmpty();
    }
}
```

- [ ] **Step 2: Run token tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='OpaqueTokenServiceTest,InMemoryTokenStoreTest' test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
```

- [ ] **Step 3: Implement token types and store abstraction**

Create `TokenKind`:

```java
package org.muybaby.shopserver.auth.token;

public enum TokenKind {
    ADMIN("adm_", "adr_", "admin"),
    APP("app_", "apr_", "app");

    private final String accessPrefix;
    private final String refreshPrefix;
    private final String namespace;

    TokenKind(String accessPrefix, String refreshPrefix, String namespace) {
        this.accessPrefix = accessPrefix;
        this.refreshPrefix = refreshPrefix;
        this.namespace = namespace;
    }

    public String accessPrefix() {
        return accessPrefix;
    }

    public String refreshPrefix() {
        return refreshPrefix;
    }

    public String namespace() {
        return namespace;
    }
}
```

Create `TokenSession`:

```java
package org.muybaby.shopserver.auth.token;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TokenSession(
        String sessionId,
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> roles,
        List<String> permissions,
        Instant issuedAt
) {
    public static TokenSession admin(Long userId, String username, List<String> roles, List<String> permissions, Instant issuedAt) {
        return new TokenSession(UUID.randomUUID().toString(), TokenKind.ADMIN, userId, username, List.copyOf(roles), List.copyOf(permissions), issuedAt);
    }

    public static TokenSession app(Long userId, String openidMasked, Instant issuedAt) {
        return new TokenSession(UUID.randomUUID().toString(), TokenKind.APP, userId, openidMasked, List.of(), List.of(), issuedAt);
    }
}
```

Create `TokenPair`:

```java
package org.muybaby.shopserver.auth.token;

public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
}
```

Create `TokenStore`:

```java
package org.muybaby.shopserver.auth.token;

import java.time.Duration;
import java.util.Optional;

public interface TokenStore {
    void save(String key, TokenSession session, Duration ttl);

    Optional<TokenSession> find(String key);

    void delete(String key);
}
```

- [ ] **Step 4: Implement in-memory and Redis stores**

Create `InMemoryTokenStore`:

```java
package org.muybaby.shopserver.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "memory")
public class InMemoryTokenStore implements TokenStore {

    private final Clock clock;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();

    public InMemoryTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void save(String key, TokenSession session, Duration ttl) {
        sessions.put(key, new StoredSession(session, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<TokenSession> find(String key) {
        StoredSession stored = sessions.get(key);
        if (stored == null || !stored.expiresAt().isAfter(clock.instant())) {
            sessions.remove(key);
            return Optional.empty();
        }
        return Optional.of(stored.session());
    }

    @Override
    public void delete(String key) {
        sessions.remove(key);
    }

    private record StoredSession(TokenSession session, Instant expiresAt) {
    }
}
```

Create `RedisTokenStore`:

```java
package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "redis", matchIfMissing = true)
public class RedisTokenStore implements TokenStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisTokenStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String key, TokenSession session, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(session), ttl);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to save token session", ex);
        }
    }

    @Override
    public Optional<TokenSession> find(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, TokenSession.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read token session", ex);
        }
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
```

- [ ] **Step 5: Implement token service and properties**

Create `TokenProperties`:

```java
package org.muybaby.shopserver.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.auth")
public record TokenProperties(
        Duration adminAccessTtl,
        Duration adminRefreshTtl,
        Duration appAccessTtl,
        Duration appRefreshTtl
) {
}
```

Create `OpaqueTokenService`:

```java
package org.muybaby.shopserver.auth.token;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class OpaqueTokenService {

    private static final String KEY_PREFIX = "shop:auth:";

    private final TokenStore tokenStore;
    private final TokenProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public OpaqueTokenService(TokenStore tokenStore, TokenProperties properties, Clock clock) {
        this.tokenStore = tokenStore;
        this.properties = properties;
        this.clock = clock;
    }

    public TokenPair issue(TokenKind kind, TokenSession session) {
        Duration accessTtl = accessTtl(kind);
        Duration refreshTtl = refreshTtl(kind);
        String accessToken = kind.accessPrefix() + randomTokenBody();
        String refreshToken = kind.refreshPrefix() + randomTokenBody();
        tokenStore.save(key(kind, "access", accessToken), session, accessTtl);
        tokenStore.save(key(kind, "refresh", refreshToken), session, refreshTtl);
        return new TokenPair(accessToken, refreshToken, accessTtl.toSeconds());
    }

    public Optional<TokenSession> lookupAccessToken(String token, TokenKind requiredKind) {
        if (token == null || !token.startsWith(requiredKind.accessPrefix())) {
            return Optional.empty();
        }
        return tokenStore.find(key(requiredKind, "access", token))
                .filter(session -> session.kind() == requiredKind);
    }

    private Duration accessTtl(TokenKind kind) {
        return kind == TokenKind.ADMIN ? properties.adminAccessTtl() : properties.appAccessTtl();
    }

    private Duration refreshTtl(TokenKind kind) {
        return kind == TokenKind.ADMIN ? properties.adminRefreshTtl() : properties.appRefreshTtl();
    }

    private String key(TokenKind kind, String tokenType, String token) {
        return KEY_PREFIX + kind.namespace() + ":" + tokenType + ":" + sha256(token);
    }

    private String randomTokenBody() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
```

- [ ] **Step 6: Add auth config defaults**

Modify `application.yaml`:

```yaml
shop:
  api:
    success-code: 200
    success-message: success
  auth:
    token-store: redis
    admin-access-ttl: 2h
    admin-refresh-ttl: 7d
    app-access-ttl: 7d
    app-refresh-ttl: 30d
```

Modify `application-test.yaml`:

```yaml
shop:
  auth:
    token-store: memory
    admin-access-ttl: 2h
    admin-refresh-ttl: 7d
    app-access-ttl: 7d
    app-refresh-ttl: 30d
```

- [ ] **Step 7: Run token tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='OpaqueTokenServiceTest,InMemoryTokenStoreTest' test
```

Expected:

```text
BUILD SUCCESS
```

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token backend/shop-server/src/main/resources/application.yaml backend/shop-server/src/test/resources/application-test.yaml
git commit -m "feat: add opaque token session model"
```

## Task 2: Auth And RBAC Database Model

**Files:**

- Create: `backend/shop-server/src/main/resources/db/migration/V2__auth_rbac.sql`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/entity/AdminUser.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/entity/AdminRole.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/entity/AdminMenu.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/entity/AdminPermission.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/mapper/AdminUserMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/mapper/AdminRoleMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/mapper/AdminMenuMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/mapper/AdminPermissionMapper.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/user/entity/AppUser.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/user/mapper/AppUserMapper.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminRbacSchemaTest.java`
- Modify: `backend/shop-server/src/test/resources/application-test.yaml`

- [ ] **Step 1: Write a failing schema and seed test**

Create `AdminRbacSchemaTest`:

```java
package org.muybaby.shopserver.admin.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminRbacSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedSuperAdminHasBcryptPasswordAndSystemMenus() {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where username = 'Super'")
                .query(String.class)
                .single();

        Integer menuCount = jdbcClient.sql("select count(*) from admin_menu where enabled = true")
                .query(Integer.class)
                .single();

        assertThat(passwordEncoder.matches("123456", passwordHash)).isTrue();
        assertThat(menuCount).isGreaterThanOrEqualTo(5);
    }
}
```

- [ ] **Step 2: Enable Flyway for tests and run the schema test**

Modify `application-test.yaml` so Flyway migrations create the H2 schema:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminRbacSchemaTest test
```

Expected:

```text
Table "ADMIN_USER" not found
```

- [ ] **Step 3: Add V2 migration**

Create `V2__auth_rbac.sql`:

```sql
CREATE TABLE admin_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    email VARCHAR(128) NOT NULL,
    avatar VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

CREATE TABLE admin_role (
    id BIGINT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_role_code UNIQUE (code)
);

CREATE TABLE admin_permission (
    id BIGINT PRIMARY KEY,
    auth_mark VARCHAR(128) NOT NULL,
    title VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_admin_permission_auth_mark UNIQUE (auth_mark)
);

CREATE TABLE admin_menu (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT NULL,
    name VARCHAR(64) NOT NULL,
    path VARCHAR(128) NOT NULL,
    component VARCHAR(128) NOT NULL,
    title VARCHAR(128) NOT NULL,
    icon VARCHAR(64) NOT NULL DEFAULT '',
    sort_order INT NOT NULL DEFAULT 0,
    keep_alive BOOLEAN NOT NULL DEFAULT FALSE,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admin_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE admin_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE admin_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE admin_menu_permission (
    menu_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (menu_id, permission_id)
);

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY,
    openid VARCHAR(128) NOT NULL,
    unionid VARCHAR(128) NULL,
    phone_number VARCHAR(32) NULL,
    phone_country_code VARCHAR(16) NULL,
    phone_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_openid UNIQUE (openid)
);

CREATE INDEX idx_admin_menu_parent_sort ON admin_menu(parent_id, sort_order);
CREATE INDEX idx_app_user_phone ON app_user(phone_number);

INSERT INTO admin_user (id, username, password_hash, display_name, email, status)
VALUES
    (1, 'Super', '$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i', 'Super Admin', 'super@shop.local', 'ENABLED');

INSERT INTO admin_role (id, code, name, description, enabled)
VALUES
    (1, 'R_SUPER', 'Super Admin', 'Full system access', TRUE),
    (2, 'R_ADMIN', 'Admin', 'Shop operator access', TRUE);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (1001, 'system:user:create', 'Create admin user'),
    (1002, 'system:user:update', 'Update admin user'),
    (1003, 'system:user:disable', 'Disable admin user'),
    (1101, 'system:role:create', 'Create role'),
    (1102, 'system:role:update', 'Update role'),
    (1103, 'system:role:assign', 'Assign role permissions'),
    (1201, 'system:menu:update', 'Update menu');

INSERT INTO admin_menu (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (100, NULL, 'Dashboard', '/dashboard', '/index/index', 'menus.dashboard.title', 'ri:dashboard-line', 10, FALSE, TRUE, TRUE),
    (101, 100, 'Console', 'console', '/dashboard/console', 'menus.dashboard.console', 'ri:dashboard-line', 11, FALSE, TRUE, TRUE),
    (200, NULL, 'System', '/system', '/index/index', 'menus.system.title', 'ri:settings-3-line', 90, FALSE, TRUE, TRUE),
    (201, 200, 'User', 'user', '/system/user', 'menus.system.user', 'ri:user-line', 91, TRUE, TRUE, TRUE),
    (202, 200, 'Role', 'role', '/system/role', 'menus.system.role', 'ri:admin-line', 92, TRUE, TRUE, TRUE),
    (203, 200, 'Menu', 'menu', '/system/menu', 'menus.system.menu', 'ri:menu-line', 93, TRUE, TRUE, TRUE);

INSERT INTO admin_user_role (user_id, role_id)
VALUES (1, 1);

INSERT INTO admin_role_menu (role_id, menu_id)
VALUES
    (1, 100), (1, 101), (1, 200), (1, 201), (1, 202), (1, 203),
    (2, 100), (2, 101);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 1001), (1, 1002), (1, 1003), (1, 1101), (1, 1102), (1, 1103), (1, 1201);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (201, 1001), (201, 1002), (201, 1003),
    (202, 1101), (202, 1102), (202, 1103),
    (203, 1201);
```

- [ ] **Step 4: Add entity and mapper classes**

Create entity classes with MyBatis-Plus table names. Example for `AdminUser`:

```java
package org.muybaby.shopserver.admin.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_user")
public record AdminUser(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String email,
        String avatar,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean enabled() {
        return "ENABLED".equals(status);
    }
}
```

Create mappers:

```java
package org.muybaby.shopserver.admin.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.muybaby.shopserver.admin.rbac.entity.AdminUser;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
```

Apply the same pattern to `AdminRole`, `AdminMenu`, `AdminPermission`, and `AppUser`.

- [ ] **Step 5: Run schema test and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminRbacSchemaTest test
```

Expected:

```text
BUILD SUCCESS
```

Commit:

```bash
git add backend/shop-server/src/main/resources/db/migration/V2__auth_rbac.sql backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac backend/shop-server/src/main/java/org/muybaby/shopserver/user backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminRbacSchemaTest.java backend/shop-server/src/test/resources/application-test.yaml
git commit -m "feat: add auth rbac schema"
```

## Task 3: Spring Security Token Authentication

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/ApiAuthenticationEntryPoint.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/ApiAccessDeniedHandler.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/AuthenticatedPrincipal.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/PathTokenKindResolver.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/TokenAuthentication.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/TokenAuthenticationFilter.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`
- Modify: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/security/PathTokenKindResolverTest.java`
- Modify: `backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java`

- [ ] **Step 1: Write failing tests for path-kind resolution and security responses**

Create `PathTokenKindResolverTest`:

```java
package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;

import static org.assertj.core.api.Assertions.assertThat;

class PathTokenKindResolverTest {

    private final PathTokenKindResolver resolver = new PathTokenKindResolver();

    @Test
    void resolvesAdminAndAppProtectedPaths() {
        assertThat(resolver.resolve("/admin/auth/current-user")).contains(TokenKind.ADMIN);
        assertThat(resolver.resolve("/app/auth/phone")).contains(TokenKind.APP);
    }

    @Test
    void publicAndCallbackPathsDoNotUseUserTokens() {
        assertThat(resolver.resolve("/admin/auth/login")).isEmpty();
        assertThat(resolver.resolve("/app/auth/login")).isEmpty();
        assertThat(resolver.resolve("/wxpay/notify")).isEmpty();
        assertThat(resolver.resolve("/wechat/events")).isEmpty();
    }
}
```

Update `SecurityConfigTest` with JSON response assertions:

```java
@Test
void adminApisReturnJsonUnauthorizedEnvelope() throws Exception {
    mockMvc.perform(get("/admin/probe"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", is(100001)))
            .andExpect(jsonPath("$.msg", is("Authentication required")));
}
```

- [ ] **Step 2: Run security tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='PathTokenKindResolverTest,SecurityConfigTest' test
```

Expected:

```text
cannot find symbol: class PathTokenKindResolver
```

- [ ] **Step 3: Add error codes and JSON security handlers**

Update `ErrorCode`:

```java
INVALID_CREDENTIALS(100002, "Invalid username or password"),
TOKEN_EXPIRED(100004, "Token expired"),
WECHAT_LOGIN_FAILED(100101, "WeChat login failed"),
WECHAT_PHONE_FAILED(100102, "WeChat phone authorization failed"),
```

Create `ApiAuthenticationEntryPoint`:

```java
package org.muybaby.shopserver.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ErrorCode errorCode = ErrorCode.AUTHENTICATION_REQUIRED;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode.code(), errorCode.message()));
    }
}
```

Create `ApiAccessDeniedHandler` using `ErrorCode.PERMISSION_DENIED` and HTTP 403.

- [ ] **Step 4: Implement path resolver and authentication filter**

Create `PathTokenKindResolver`:

```java
package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PathTokenKindResolver {

    public Optional<TokenKind> resolve(String path) {
        if (path.equals("/admin/auth/login") || path.equals("/app/auth/login")) {
            return Optional.empty();
        }
        if (path.startsWith("/wxpay/") || path.startsWith("/wechat/")) {
            return Optional.empty();
        }
        if (path.startsWith("/admin/")) {
            return Optional.of(TokenKind.ADMIN);
        }
        if (path.startsWith("/app/") && !path.equals("/app/health")) {
            return Optional.of(TokenKind.APP);
        }
        return Optional.empty();
    }
}
```

Create `AuthenticatedPrincipal` and `TokenAuthentication`:

```java
package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenKind;

import java.util.List;

public record AuthenticatedPrincipal(
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> roles,
        List<String> permissions
) {
}
```

```java
package org.muybaby.shopserver.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class TokenAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    public TokenAuthentication(AuthenticatedPrincipal principal) {
        super(principal.permissions().stream().map(SimpleGrantedAuthority::new).toList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }
}
```

Create `TokenAuthenticationFilter`:

```java
package org.muybaby.shopserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final PathTokenKindResolver pathTokenKindResolver;
    private final OpaqueTokenService tokenService;

    public TokenAuthenticationFilter(PathTokenKindResolver pathTokenKindResolver, OpaqueTokenService tokenService) {
        this.pathTokenKindResolver = pathTokenKindResolver;
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        pathTokenKindResolver.resolve(request.getRequestURI()).ifPresent(kind -> {
            String token = bearerToken(request);
            tokenService.lookupAccessToken(token, kind)
                    .map(this::authentication)
                    .ifPresent(authentication -> SecurityContextHolder.getContext().setAuthentication(authentication));
        });
        filterChain.doFilter(request, response);
    }

    private TokenAuthentication authentication(TokenSession session) {
        return new TokenAuthentication(new AuthenticatedPrincipal(
                session.kind(),
                session.subjectId(),
                session.subjectName(),
                session.roles(),
                session.permissions()
        ));
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length());
    }
}
```

- [ ] **Step 5: Wire Spring Security**

Update `SecurityConfig`:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(TokenProperties.class)
public class SecurityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenAuthenticationFilter tokenAuthenticationFilter,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/admin/auth/login", "/app/auth/login").permitAll()
                        .requestMatchers("/app/health", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/wxpay/**", "/wechat/**").permitAll()
                        .requestMatchers("/admin/**", "/app/**").authenticated()
                        .anyRequest().permitAll())
                .build();
    }
}
```

- [ ] **Step 6: Run security tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='PathTokenKindResolverTest,SecurityConfigTest' test
```

Expected:

```text
BUILD SUCCESS
```

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/security backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java backend/shop-server/src/test/java/org/muybaby/shopserver/security
git commit -m "feat: enforce token authentication by api namespace"
```

## Task 4: Admin Login And Current User APIs

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/AdminAuthController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AdminLoginRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/LoginTokenResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/CurrentAdminUserResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/service/AdminAuthService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/service/AdminRbacService.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/auth/AdminAuthControllerTest.java`

- [ ] **Step 1: Write failing admin auth API tests**

Create `AdminAuthControllerTest`:

```java
package org.muybaby.shopserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginReturnsAdminTokenPair() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("adr_")))
                .andExpect(jsonPath("$.data.expiresIn").value(7200));
    }

    @Test
    void loginRejectsBadPassword() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"bad"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    void currentUserReturnsRolesAndButtons() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.userName").value("Super"))
                .andExpect(jsonPath("$.data.roles", containsInAnyOrder("R_SUPER")))
                .andExpect(jsonPath("$.data.buttons", containsInAnyOrder(
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:menu:update"
                )));
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.substring(response.indexOf("adm_"), response.indexOf("\",\"refreshToken"));
    }
}
```

- [ ] **Step 2: Run admin auth tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminAuthControllerTest test
```

Expected:

```text
Status expected:<200> but was:<404>
```

- [ ] **Step 3: Implement DTOs**

Create `AdminLoginRequest`:

```java
package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminLoginRequest(
        @NotBlank String userName,
        @NotBlank String password
) {
}
```

Create `LoginTokenResponse`:

```java
package org.muybaby.shopserver.auth.dto;

public record LoginTokenResponse(String token, String refreshToken, long expiresIn) {
}
```

Create `CurrentAdminUserResponse`:

```java
package org.muybaby.shopserver.auth.dto;

import java.util.List;

public record CurrentAdminUserResponse(
        Long userId,
        String userName,
        String email,
        String avatar,
        List<String> roles,
        List<String> buttons
) {
}
```

- [ ] **Step 4: Implement RBAC query service**

Create `AdminRbacService` methods:

```java
public Optional<AdminUser> findEnabledUserByUsername(String username);

public Optional<AdminUser> findEnabledUserById(Long userId);

public List<String> roleCodesByUserId(Long userId);

public List<String> permissionMarksByUserId(Long userId);
```

Use `JdbcClient` for join queries because the join shape is small and explicit:

```java
public List<String> roleCodesByUserId(Long userId) {
    return jdbcClient.sql("""
            select r.code
            from admin_role r
            join admin_user_role ur on ur.role_id = r.id
            where ur.user_id = :userId and r.enabled = true
            order by r.id
            """)
            .param("userId", userId)
            .query(String.class)
            .list();
}
```

Permission query:

```java
select distinct p.auth_mark
from admin_permission p
join admin_role_permission rp on rp.permission_id = p.id
join admin_user_role ur on ur.role_id = rp.role_id
join admin_role r on r.id = ur.role_id
where ur.user_id = :userId and r.enabled = true
order by p.auth_mark
```

- [ ] **Step 5: Implement admin auth service and controller**

Create `AdminAuthService`:

```java
package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.admin.rbac.entity.AdminUser;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.dto.CurrentAdminUserResponse;
import org.muybaby.shopserver.auth.dto.LoginTokenResponse;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
public class AdminAuthService {

    private final AdminRbacService rbacService;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenService tokenService;
    private final Clock clock;

    public AdminAuthService(AdminRbacService rbacService, PasswordEncoder passwordEncoder, OpaqueTokenService tokenService, Clock clock) {
        this.rbacService = rbacService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    public LoginTokenResponse login(AdminLoginRequest request) {
        AdminUser user = rbacService.findEnabledUserByUsername(request.userName())
                .filter(found -> passwordEncoder.matches(request.password(), found.passwordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        List<String> roles = rbacService.roleCodesByUserId(user.id());
        List<String> permissions = rbacService.permissionMarksByUserId(user.id());
        TokenPair pair = tokenService.issue(TokenKind.ADMIN, TokenSession.admin(user.id(), user.username(), roles, permissions, clock.instant()));
        return new LoginTokenResponse(pair.accessToken(), pair.refreshToken(), pair.expiresIn());
    }

    public CurrentAdminUserResponse currentUser(AuthenticatedPrincipal principal) {
        AdminUser user = rbacService.findEnabledUserById(principal.subjectId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        return new CurrentAdminUserResponse(
                user.id(),
                user.username(),
                user.email(),
                user.avatar(),
                principal.roles(),
                principal.permissions()
        );
    }
}
```

Create `AdminAuthController`:

```java
package org.muybaby.shopserver.auth;

import jakarta.validation.Valid;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.dto.CurrentAdminUserResponse;
import org.muybaby.shopserver.auth.dto.LoginTokenResponse;
import org.muybaby.shopserver.auth.service.AdminAuthService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginTokenResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return ApiResponse.success(adminAuthService.login(request));
    }

    @GetMapping("/current-user")
    public ApiResponse<CurrentAdminUserResponse> currentUser(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(adminAuthService.currentUser(principal));
    }
}
```

- [ ] **Step 6: Run admin auth tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminAuthControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/auth backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/service/AdminRbacService.java backend/shop-server/src/test/java/org/muybaby/shopserver/auth/AdminAuthControllerTest.java
git commit -m "feat: add admin login and current user api"
```

## Task 5: Backend-Driven Art Design Pro Menu API

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/AdminMenuController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/dto/AdminRouteResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/dto/AdminRouteMetaResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/dto/AdminRouteAuthResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac/service/AdminMenuRouteService.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminMenuControllerTest.java`

- [ ] **Step 1: Write failing menu API tests**

Create `AdminMenuControllerTest`:

```java
package org.muybaby.shopserver.admin.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void menuApiReturnsArtDesignProRouteTreeWithAuthList() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/admin/system/menus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].path").value("/dashboard"))
                .andExpect(jsonPath("$.data[0].component").value("/index/index"))
                .andExpect(jsonPath("$.data[1].path").value("/system"))
                .andExpect(jsonPath("$.data[1].children[0].path").value("user"))
                .andExpect(jsonPath("$.data[1].children[0].meta.authList[*].authMark", hasItem("system:user:create")));
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.substring(response.indexOf("adm_"), response.indexOf("\",\"refreshToken"));
    }
}
```

- [ ] **Step 2: Run menu tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminMenuControllerTest test
```

Expected:

```text
Status expected:<200> but was:<404>
```

- [ ] **Step 3: Implement route DTOs**

Create route response records:

```java
package org.muybaby.shopserver.admin.rbac.dto;

import java.util.List;

public record AdminRouteResponse(
        Long id,
        String name,
        String path,
        String component,
        AdminRouteMetaResponse meta,
        List<AdminRouteResponse> children
) {
}
```

```java
package org.muybaby.shopserver.admin.rbac.dto;

import java.util.List;

public record AdminRouteMetaResponse(
        String title,
        String icon,
        boolean keepAlive,
        List<AdminRouteAuthResponse> authList
) {
}
```

```java
package org.muybaby.shopserver.admin.rbac.dto;

public record AdminRouteAuthResponse(Long id, String title, String authMark) {
}
```

- [ ] **Step 4: Implement menu route service**

Create `AdminMenuRouteService` that:

- Loads enabled menus granted to the current admin roles.
- Loads button permissions per menu.
- Builds a stable tree ordered by `sort_order`, then `id`.
- Returns child paths exactly as stored, so Art Design Pro normalizes nested paths.
- Filters absent-page menu rows by `enabled = true` in SQL.

Core SQL:

```sql
select distinct m.*
from admin_menu m
join admin_role_menu rm on rm.menu_id = m.id
join admin_user_role ur on ur.role_id = rm.role_id
join admin_role r on r.id = ur.role_id
where ur.user_id = :userId
  and r.enabled = true
  and m.enabled = true
  and m.visible = true
order by m.sort_order, m.id
```

Auth list SQL:

```sql
select p.id, p.title, p.auth_mark
from admin_permission p
join admin_menu_permission mp on mp.permission_id = p.id
join admin_role_permission rp on rp.permission_id = p.id
join admin_user_role ur on ur.role_id = rp.role_id
where ur.user_id = :userId and mp.menu_id = :menuId
order by p.id
```

- [ ] **Step 5: Implement menu controller**

Create `AdminMenuController`:

```java
package org.muybaby.shopserver.admin.rbac;

import org.muybaby.shopserver.admin.rbac.dto.AdminRouteResponse;
import org.muybaby.shopserver.admin.rbac.service.AdminMenuRouteService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/system")
public class AdminMenuController {

    private final AdminMenuRouteService menuRouteService;

    public AdminMenuController(AdminMenuRouteService menuRouteService) {
        this.menuRouteService = menuRouteService;
    }

    @GetMapping("/menus")
    public ApiResponse<List<AdminRouteResponse>> menus(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(menuRouteService.routesForUser(principal.subjectId()));
    }
}
```

- [ ] **Step 6: Run menu tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AdminMenuControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/admin/rbac backend/shop-server/src/test/java/org/muybaby/shopserver/admin/rbac/AdminMenuControllerTest.java
git commit -m "feat: add backend menu route api"
```

## Task 6: Mini Program Silent Login And Phone Authorization APIs

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/AppAuthController.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppLoginRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppLoginResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppUserSummary.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/PhoneAuthorizeRequest.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/PhoneAuthorizeResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/auth/service/AppAuthService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/user/service/AppUserService.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/WechatMiniProgramClient.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/WechatMiniProgramProperties.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/WechatCodeSession.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/WechatPhoneInfo.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/MockWechatMiniProgramClient.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/wechat/RestWechatMiniProgramClient.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/auth/AppAuthControllerTest.java`
- Modify: `backend/shop-server/src/main/resources/application.yaml`
- Modify: `backend/shop-server/src/main/resources/application-dev.yaml`
- Modify: `backend/shop-server/src/test/resources/application-test.yaml`

- [ ] **Step 1: Write failing app auth API tests**

Create `AppAuthControllerTest`:

```java
package org.muybaby.shopserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
                .andExpect(jsonPath("$.data.user.openidMasked").value("test****code"))
                .andExpect(jsonPath("$.data.user.phoneAuthorized").value(false));
    }

    @Test
    void phoneAuthorizationRequiresAppTokenAndStoresMaskedPhone() throws Exception {
        String token = appLoginAndExtractToken();

        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-phone-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneAuthorized").value(true))
                .andExpect(jsonPath("$.data.phoneNumberMasked").value("138****5678"));
    }

    @Test
    void adminTokenCannotAuthorizeAppPhoneApi() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-phone-code"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.substring(response.indexOf("app_"), response.indexOf("\",\"refreshToken"));
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.substring(response.indexOf("adm_"), response.indexOf("\",\"refreshToken"));
    }
}
```

- [ ] **Step 2: Run app auth tests and verify they fail**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAuthControllerTest test
```

Expected:

```text
Status expected:<200> but was:<404>
```

- [ ] **Step 3: Add WeChat config and client port**

Add config:

```yaml
shop:
  wechat:
    mini-program:
      app-id: ${WECHAT_MINI_PROGRAM_APP_ID:}
      app-secret: ${WECHAT_MINI_PROGRAM_APP_SECRET:}
      mock-enabled: false
```

In `application-dev.yaml` and `application-test.yaml`:

```yaml
shop:
  wechat:
    mini-program:
      mock-enabled: true
```

Create client records:

```java
package org.muybaby.shopserver.wechat;

public record WechatCodeSession(String openid, String unionid, String sessionKey) {
}
```

```java
package org.muybaby.shopserver.wechat;

public record WechatPhoneInfo(String phoneNumber, String purePhoneNumber, String countryCode) {
}
```

Create port:

```java
package org.muybaby.shopserver.wechat;

public interface WechatMiniProgramClient {
    WechatCodeSession code2Session(String code);

    WechatPhoneInfo getPhoneNumber(String code);
}
```

Create mock client:

```java
@Component
@ConditionalOnProperty(name = "shop.wechat.mini-program.mock-enabled", havingValue = "true")
public class MockWechatMiniProgramClient implements WechatMiniProgramClient {
    @Override
    public WechatCodeSession code2Session(String code) {
        return new WechatCodeSession("test-openid-" + code, null, "test-session-key");
    }

    @Override
    public WechatPhoneInfo getPhoneNumber(String code) {
        return new WechatPhoneInfo("13812345678", "13812345678", "86");
    }
}
```

- [ ] **Step 4: Implement app user service**

Create `AppUserService` with methods:

```java
public AppUser upsertByOpenid(WechatCodeSession session);

public AppUser markPhoneAuthorized(Long userId, WechatPhoneInfo phoneInfo);
```

Use SQL upsert logic compatible with MySQL mode H2:

```java
AppUser existing = findByOpenid(session.openid()).orElse(null);
if (existing != null) {
    jdbcClient.sql("update app_user set unionid = :unionid, last_login_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP where id = :id")
            .param("unionid", session.unionid())
            .param("id", existing.id())
            .update();
    return findById(existing.id()).orElseThrow();
}
Long id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
jdbcClient.sql("""
        insert into app_user (id, openid, unionid, status, last_login_at)
        values (:id, :openid, :unionid, 'ENABLED', CURRENT_TIMESTAMP)
        """)
        .param("id", id)
        .param("openid", session.openid())
        .param("unionid", session.unionid())
        .update();
return findById(id).orElseThrow();
```

- [ ] **Step 5: Implement app auth service and controller**

Create `AppAuthService`:

```java
public AppLoginResponse login(AppLoginRequest request) {
    WechatCodeSession codeSession = wechatClient.code2Session(request.code());
    AppUser user = appUserService.upsertByOpenid(codeSession);
    TokenPair pair = tokenService.issue(TokenKind.APP, TokenSession.app(user.id(), maskOpenid(user.openid()), clock.instant()));
    return new AppLoginResponse(
            pair.accessToken(),
            pair.refreshToken(),
            pair.expiresIn(),
            new AppUserSummary(user.id(), maskOpenid(user.openid()), user.phoneAuthorized())
    );
}

public PhoneAuthorizeResponse authorizePhone(Long userId, PhoneAuthorizeRequest request) {
    WechatPhoneInfo phoneInfo = wechatClient.getPhoneNumber(request.code());
    AppUser updated = appUserService.markPhoneAuthorized(userId, phoneInfo);
    return new PhoneAuthorizeResponse(updated.phoneAuthorized(), maskPhone(updated.phoneNumber()));
}
```

Create `AppAuthController`:

```java
@RestController
@RequestMapping("/app/auth")
public class AppAuthController {

    private final AppAuthService appAuthService;

    public AppAuthController(AppAuthService appAuthService) {
        this.appAuthService = appAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<AppLoginResponse> login(@Valid @RequestBody AppLoginRequest request) {
        return ApiResponse.success(appAuthService.login(request));
    }

    @PostMapping("/phone")
    public ApiResponse<PhoneAuthorizeResponse> phone(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody PhoneAuthorizeRequest request
    ) {
        return ApiResponse.success(appAuthService.authorizePhone(principal.subjectId(), request));
    }
}
```

- [ ] **Step 6: Run app auth tests and commit**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=AppAuthControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

Commit:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/auth backend/shop-server/src/main/java/org/muybaby/shopserver/user backend/shop-server/src/main/java/org/muybaby/shopserver/wechat backend/shop-server/src/main/resources/application.yaml backend/shop-server/src/main/resources/application-dev.yaml backend/shop-server/src/test/resources/application-test.yaml backend/shop-server/src/test/java/org/muybaby/shopserver/auth/AppAuthControllerTest.java
git commit -m "feat: add mini program auth api"
```

## Task 7: Admin Frontend API Alignment

**Files:**

- Modify: `admin/src/api/auth.ts`
- Modify: `admin/src/api/system-manage.ts`
- Modify: `admin/src/types/api/api.d.ts`
- Modify: `admin/src/utils/http/index.ts`

- [ ] **Step 1: Update admin auth API paths**

Change `admin/src/api/auth.ts`:

```ts
import request from '@/utils/http'

export function fetchLogin(params: Api.Auth.LoginParams) {
  return request.post<Api.Auth.LoginResponse>({
    url: '/admin/auth/login',
    data: params
  })
}

export function fetchGetUserInfo() {
  return request.get<Api.Auth.UserInfo>({
    url: '/admin/auth/current-user'
  })
}
```

- [ ] **Step 2: Update menu API path**

Change `admin/src/api/system-manage.ts` menu function:

```ts
export function fetchGetMenuList() {
  return request.get<AppRouteRecord[]>({
    url: '/admin/system/menus'
  })
}
```

- [ ] **Step 3: Send bearer token**

Change the request interceptor in `admin/src/utils/http/index.ts`:

```ts
const { accessToken } = useUserStore()
if (accessToken) request.headers.set('Authorization', `Bearer ${accessToken}`)
```

- [ ] **Step 4: Align auth response types**

Update `Api.Auth.LoginResponse`:

```ts
interface LoginResponse {
  token: string
  refreshToken: string
  expiresIn: number
}
```

Keep `Api.Auth.UserInfo` fields unchanged because they already match the backend current-user contract.

- [ ] **Step 5: Build admin and commit**

Run:

```bash
cd admin
pnpm build
```

Expected:

```text
vite build
```

Commit:

```bash
git add admin/src/api/auth.ts admin/src/api/system-manage.ts admin/src/types/api/api.d.ts admin/src/utils/http/index.ts
git commit -m "feat: align admin auth api with backend"
```

## Task 8: Mini Program Client Auth Flow

**Files:**

- Create: `miniprogram/services/auth.ts`
- Modify: `miniprogram/app.ts`
- Modify: `miniprogram/types/api.ts`
- Modify: `miniprogram/pages/profile/profile.ts`
- Modify: `miniprogram/pages/profile/profile.wxml`

- [ ] **Step 1: Add auth response types**

Modify `miniprogram/types/api.ts`:

```ts
export interface AppUserSummary {
  userId: number;
  openidMasked: string;
  phoneAuthorized: boolean;
}

export interface AppLoginResponse {
  token: string;
  refreshToken: string;
  expiresIn: number;
  user: AppUserSummary;
}

export interface PhoneAuthorizeResponse {
  phoneAuthorized: boolean;
  phoneNumberMasked: string;
}
```

- [ ] **Step 2: Create mini program auth service**

Create `miniprogram/services/auth.ts`:

```ts
import { request } from "../utils/request";
import type { AppLoginResponse, PhoneAuthorizeResponse } from "../types/api";

const TOKEN_STORAGE_KEY = "shop_app_token";
const REFRESH_TOKEN_STORAGE_KEY = "shop_app_refresh_token";

export function restoreStoredToken(): string {
  const token = wx.getStorageSync(TOKEN_STORAGE_KEY);
  return typeof token === "string" ? token : "";
}

export async function silentLogin(): Promise<AppLoginResponse> {
  const loginResult = await wxLogin();
  const response = await request<AppLoginResponse>({
    url: "/app/auth/login",
    method: "POST",
    auth: false,
    data: {
      code: loginResult.code
    }
  });
  wx.setStorageSync(TOKEN_STORAGE_KEY, response.token);
  wx.setStorageSync(REFRESH_TOKEN_STORAGE_KEY, response.refreshToken);
  getApp<{ globalData: { token: string } }>().globalData.token = response.token;
  return response;
}

export async function authorizePhone(code: string): Promise<PhoneAuthorizeResponse> {
  return request<PhoneAuthorizeResponse>({
    url: "/app/auth/phone",
    method: "POST",
    data: { code }
  });
}

function wxLogin(): Promise<WechatMiniprogram.LoginSuccessCallbackResult> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: resolve,
      fail: reject
    });
  });
}
```

- [ ] **Step 3: Restore token on app launch**

Modify `app.ts`:

```ts
import { restoreStoredToken } from "./services/auth";

App<IAppOption>({
  globalData: {
    apiBaseUrl: "http://localhost:8080",
    token: restoreStoredToken()
  },
  onLaunch() {
    wx.getSystemInfo({
      success: (info) => {
        this.globalData.systemInfo = info;
      }
    });
  }
});
```

- [ ] **Step 4: Wire profile silent login and phone authorization**

Update `profile.ts` page data and methods:

```ts
import { authorizePhone, silentLogin } from "../../services/auth";

Page({
  data: {
    loginStatus: "Not logged in",
    phoneStatus: "Phone not authorized"
  },
  async onShow() {
    const response = await silentLogin();
    this.setData({
      loginStatus: `Logged in as ${response.user.openidMasked}`,
      phoneStatus: response.user.phoneAuthorized ? "Phone authorized" : "Phone not authorized"
    });
  },
  async onGetPhoneNumber(event: WechatMiniprogram.ButtonGetPhoneNumber) {
    if (!event.detail.code) {
      this.setData({ phoneStatus: "Phone authorization cancelled" });
      return;
    }
    const response = await authorizePhone(event.detail.code);
    this.setData({
      phoneStatus: response.phoneAuthorized ? response.phoneNumberMasked : "Phone not authorized"
    });
  }
});
```

Update `profile.wxml`:

```xml
<view class="profile-page">
  <view class="profile-row">{{loginStatus}}</view>
  <view class="profile-row">{{phoneStatus}}</view>
  <button open-type="getPhoneNumber" bindgetphonenumber="onGetPhoneNumber">Authorize Phone</button>
</view>
```

- [ ] **Step 5: Run mini program typecheck and commit**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected:

```text
tsc
```

Commit:

```bash
git add miniprogram/app.ts miniprogram/services/auth.ts miniprogram/types/api.ts miniprogram/pages/profile/profile.ts miniprogram/pages/profile/profile.wxml
git commit -m "feat: add mini program login client"
```

## Task 9: Full Verification And Smoke Checks

**Files:**

- Modify: `docs/dev-setup.md`

- [ ] **Step 1: Run focused backend tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest='OpaqueTokenServiceTest,InMemoryTokenStoreTest,PathTokenKindResolverTest,SecurityConfigTest,AdminRbacSchemaTest,AdminAuthControllerTest,AdminMenuControllerTest,AppAuthControllerTest' test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 2: Run full backend test suite**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: Run admin build**

Run:

```bash
cd admin
pnpm build
```

Expected:

```text
vite build
```

- [ ] **Step 4: Run mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected:

```text
tsc
```

- [ ] **Step 5: Start backend and run smoke HTTP checks**

Start backend in one terminal:

```bash
cd backend/shop-server
SPRING_PROFILES_ACTIVE=test ./mvnw spring-boot:run
```

In another terminal, run admin login:

```bash
curl -s -X POST http://localhost:8080/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userName":"Super","password":"123456"}'
```

Expected response contains:

```json
{"code":200,"msg":"success","data":{"token":"adm_
```

Run app login:

```bash
curl -s -X POST http://localhost:8080/app/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"code":"test-login-code"}'
```

Expected response contains:

```json
{"code":200,"msg":"success","data":{"token":"app_
```

Run wrong-token smoke check with an app token against admin current user:

```bash
curl -i http://localhost:8080/admin/auth/current-user \
  -H "Authorization: Bearer ${APP_TOKEN}"
```

Expected:

```text
HTTP/1.1 401
```

- [ ] **Step 6: Document auth smoke checks**

Add to `docs/dev-setup.md`:

````markdown
## Authentication Smoke Checks

Backend test profile uses an in-memory token store and mock WeChat mini program client.

```bash
cd backend/shop-server
SPRING_PROFILES_ACTIVE=test ./mvnw spring-boot:run
```

Admin login:

```bash
curl -s -X POST http://localhost:8080/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userName":"Super","password":"123456"}'
```

Mini program login:

```bash
curl -s -X POST http://localhost:8080/app/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"code":"test-login-code"}'
```

Admin and app tokens are intentionally isolated. An `app_` token must receive HTTP 401 on `/admin/**`, and an `adm_` token must receive HTTP 401 on `/app/**`.
````

- [ ] **Step 7: Final status commit**

Run:

```bash
git add docs/dev-setup.md
git commit -m "docs: add auth smoke checks"
```

## Requirement Coverage Check

- Spring Security token model: Tasks 1 and 3.
- Admin login API: Task 4.
- Admin current user API: Task 4.
- Backend-driven Art Design Pro menu/route API: Task 5.
- Admin role/permission/menu model: Task 2.
- Mini program silent login API: Task 6.
- Optional phone authorization API: Tasks 6 and 8.
- Admin token and app token isolation: Tasks 1, 3, 6, and 9.
- Redis/session/token storage strategy: Task 1.
- Tests and smoke checks: Tasks 1 through 9.

## Execution Notes

- Execute tasks in order. Later tasks depend on the token model, schema, and security chain.
- Keep commits small as written in each task.
- Do not return menu routes for pages that do not exist in `admin/src/views`; Art Design Pro backend mode dynamically registers returned components.
- Use `shop.wechat.mini-program.mock-enabled=true` in dev/test until real WeChat credentials are configured.
- The seed `Super` user password is `123456`; the stored value is BCrypt and is validated by `AdminRbacSchemaTest`.
