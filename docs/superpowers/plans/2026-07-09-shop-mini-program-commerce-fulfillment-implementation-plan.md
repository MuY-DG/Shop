# Shop Mini Program Commerce And Fulfillment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Complete durable app sessions, CART/DIRECT checkout, address snapshots, the mini program order center, all four WeChat logistics types, and scheduled timeout close while preserving the existing payment, refund, inventory, coupon, and storage flows.

**Architecture:** Extend the current Spring Boot modular monolith and native mini program with focused session, address, checkout-selection, fulfillment-gateway, and order-center units. Both checkout sources converge on one internal CheckoutSelection; local shipment and WeChat upload remain separate state transitions; all shared-file tasks execute sequentially and each task is independently tested, reviewed, and committed.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security, JdbcClient, Redis, Flyway, MySQL 8/H2 MySQL mode, Vue 3, TypeScript, Element Plus, native WeChat mini program TypeScript, TDesign MiniProgram, pnpm, tsx, node:test.

## Global Constraints

- The approved design is docs/superpowers/specs/2026-07-09-shop-mini-program-commerce-fulfillment-design.md.
- Do not reimplement foundation, auth/RBAC, product, cart, coupon, order/stock-lock, storage, WeChat Pay, base shipment, after-sale, or refund.
- The only new migration is backend/shop-server/src/main/resources/db/migration/V10__commerce_fulfillment.sql. Never modify applied V2, V6, V8, or V9.
- API envelopes remain { code, msg, data }; paged data remains { records, total, current, size }.
- Money remains integer cents.
- Backend, admin, and mini program OrderStatus values remain CREATED, PAYING, PAID, SHIPPED, COMPLETED, CLOSED, REFUNDING, REFUNDED.
- PAID is the waiting-to-ship state; do not add TO_SHIP to OrderStatus.
- CheckoutSource values are CART and DIRECT. Missing source is accepted only as compatibility CART; updated clients always send it.
- DIRECT checkout never creates, merges, updates, deletes, or clears cart rows.
- Submit requires an owned address and stores immutable receiver snapshots.
- LogisticsType numeric values are EXPRESS=1, LOCAL_DELIVERY=2, VIRTUAL=3, PICKUP=4.
- DeliveryMode is UNIFIED=1 only and the provider sends exactly one shipping_list item.
- Non-express payloads omit tracking_no, express_company, and contact keys.
- UPLOADED shipments reject ordinary retry.
- Local shipment remains SHIPPED when WeChat upload is skipped, unavailable, unknown, or failed.
- Test profile uses mock WeChat clients/providers; mock success is never reported as real WeChat success.
- Stateful Spring integration tests use rollback or deterministic cleanup; shared mock providers/properties are reset before each test. Concurrent tests use barriers, timeouts, joined executors, and final database assertions. Scheduler context tests always close their contexts/executors.
- Full openid, full authorized profile phone, access tokens, refresh tokens, login codes, phone codes, certificates, private keys, authorization headers, and request secrets must not appear in logs or committed files.
- Do not commit .env.local, target, node_modules, dist, upload roots, certificates, private keys, smoke screenshots, or tokens.
- Tasks touching V10, shared API types, AppOrderService, request recovery, admin order page, or mini program order pages run sequentially.
- Every task follows RED -> confirm expected failure -> GREEN -> focused verification -> spec review -> code review -> fix Critical/Important -> re-run covering tests -> re-review -> one task commit.
- Final accepted commits must be converged to /Users/muybaby/Project/Production/Shop main and the full verification matrix rerun there.

---

## File And Ownership Map

### Shared schema and enums

- Create backend/shop-server/src/main/resources/db/migration/V10__commerce_fulfillment.sql: sole schema owner for addresses, carrier cache, checkout digest, and shipment columns.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/order/CheckoutSource.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatusGroup.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/LogisticsType.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/DeliveryMode.java.
- Modify backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatShippingUploadStatus.java.

### App session

- Modify backend/shop-server/src/main/java/org/muybaby/shopserver/auth/AppAuthController.java.
- Modify backend/shop-server/src/main/java/org/muybaby/shopserver/auth/service/AppAuthService.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppUserProfile.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppSessionResponse.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/RefreshTokenRequest.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/auth/service/AppUserProfileMapper.java.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/user/AppUserController.java.
- Modify token-store and security files named in Task 2.
- Create miniprogram/services/session.ts and miniprogram/utils/http.ts.
- Modify miniprogram/services/auth.ts, miniprogram/utils/request.ts, miniprogram/services/storage.ts, miniprogram/app.ts, and profile files.

### Address and checkout

- Create backend/shop-server/src/main/java/org/muybaby/shopserver/user/address package for address DTOs, service, and controller.
- Create backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/CheckoutSelectionService.java.
- Modify existing app order DTOs, AppOrderService, and AppOrderController.
- Create miniprogram/services/address.ts and address pages.
- Modify product detail, cart, order preview, order service, app registration, and mini program API types.

### Order center

- Modify AppOrderService and AppOrderController only in Task 7 after Task 5 is committed.
- Modify AppAfterSaleService and AppAfterSaleController in Task 7.
- Create mini program after-sale list/detail pages and modify existing order/profile surfaces in Task 11.

### Fulfillment

- Extend the provider model and capability/carrier gateway in Task 8.
- Split local shipment persistence and upload coordination in Task 9.
- Modify admin API/types/order page only in Task 10.
- Modify mini program shipment types/order detail only in Task 11.

### Timeout and documentation

- Refactor PaymentTimeoutCloseService and add scheduler/properties in Task 12.
- Modify docs/dev-setup.md, docs/smoke-checks.md, and directly stale completion/readme documents only in Task 13.

---

### Task 1: V10 Commerce Fulfillment Schema And Shared Enums

**Goal:** Land the only migration and cross-surface backend enum foundations without changing runtime behavior.

**Files:**
- Modify: backend/shop-server/pom.xml
- Create: backend/shop-server/src/main/resources/db/migration/V10__commerce_fulfillment.sql
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/CheckoutSource.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatusGroup.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/LogisticsType.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/DeliveryMode.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatProviderMode.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatShippingUploadStatus.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/AdminShipmentService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AdminOrderService.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/fulfillment/CommerceFulfillmentSchemaTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/fulfillment/CommerceFulfillmentMigrationTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/fulfillment/CommerceFulfillmentMySqlMigrationTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/ShipmentSchemaTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/AdminShipmentControllerTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/AdminOrderControllerTest.java

**Interfaces:**
- Produces CheckoutSource.CART and CheckoutSource.DIRECT.
- Produces OrderStatusGroup.ALL, UNPAID, TO_SHIP, TO_RECEIVE, COMPLETED.
- Produces LogisticsType values with official numeric JSON values 1 through 4.
- Produces DeliveryMode.UNIFIED with numeric JSON value 1.
- Produces WechatProviderMode REAL, MOCK, DISABLED, UNKNOWN so persisted/admin evidence cannot conflate simulation with platform acceptance.
- Produces WechatShippingUploadStatus SKIPPED, UPLOADING, UPLOADED, FAILED, UNAVAILABLE, UNKNOWN.
- Produces user_address, wechat_delivery_company, shop_order.checkout_request_digest, and expanded order_shipment columns for all later tasks.

- [ ] **Step 1: Add failing migration and enum tests**

Create CommerceFulfillmentSchemaTest with assertions equivalent to:

~~~java
@SpringBootTest
@ActiveProfiles("test")
class CommerceFulfillmentSchemaTest {
    @Autowired JdbcClient jdbcClient;

    @Test
    void v10CreatesAddressCarrierDigestAndShipmentColumns() {
        Integer addressColumns = jdbcClient.sql("""
                select count(*)
                from information_schema.columns
                where table_name = 'user_address'
                """).query(Integer.class).single();
        assertThat(addressColumns).isEqualTo(11);

        assertThat(jdbcClient.sql("""
                select count(*)
                from information_schema.columns
                where table_name = 'shop_order'
                  and column_name = 'checkout_request_digest'
                """).query(Integer.class).single()).isEqualTo(1);

        Integer receiverAddressLength = jdbcClient.sql("""
                select character_maximum_length
                from information_schema.columns
                where table_name = 'shop_order'
                  and column_name = 'receiver_address'
                """).query(Integer.class).single();
        assertThat(receiverAddressLength).isEqualTo(512);

        assertThat(jdbcClient.sql("""
                select count(*)
                from information_schema.columns
                where table_name = 'order_shipment'
                  and column_name in (
                    'logistics_type', 'delivery_mode', 'item_desc',
                    'express_company_code', 'express_company_name',
                    'consignor_contact', 'receiver_contact',
                    'upload_time', 'last_attempt_at', 'wechat_provider_mode'
                  )
                """).query(Integer.class).single()).isEqualTo(10);
    }

    @Test
    void enumContractsUseOfficialValues() {
        assertThat(LogisticsType.EXPRESS.value()).isEqualTo(1);
        assertThat(LogisticsType.LOCAL_DELIVERY.value()).isEqualTo(2);
        assertThat(LogisticsType.VIRTUAL.value()).isEqualTo(3);
        assertThat(LogisticsType.PICKUP.value()).isEqualTo(4);
        assertThat(DeliveryMode.UNIFIED.value()).isEqualTo(1);
    }
}
~~~

Create CommerceFulfillmentMigrationTest using a dedicated H2 MySQL-mode URL. Migrate to target 9, insert one paid order and legacy order_shipment with express_company and tracking_no, then migrate to 10 and assert:

- logistics_type=1.
- delivery_mode=1.
- the old company text is in express_company_name.
- the old tracking number and shipment note are unchanged.
- express_company_code is null.
- item_desc is nonblank.
- wechat_provider_mode is UNKNOWN for the legacy row.

Add test-scoped org.testcontainers:junit-jupiter and org.testcontainers:mysql dependencies. CommerceFulfillmentMySqlMigrationTest uses the locally available mysql:8.0 image and two fresh schemas to prove both clean V1 -> V10 and populated V9 -> V10 paths with the same legacy assertions. Do not point this test at the developer's hotpot_shop schema and do not use Flyway clean on any persistent database.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='CommerceFulfillmentSchemaTest,CommerceFulfillmentMigrationTest,CommerceFulfillmentMySqlMigrationTest,ShipmentSchemaTest' test

Expected RED: compilation failure for missing enums and/or both H2 and MySQL Flyway assertions failing because V10 is absent. A skipped MySQL container test is not accepted as proof for this task.

- [ ] **Step 2: Implement V10 exactly once**

Create V10__commerce_fulfillment.sql with these concrete operations:

~~~sql
CREATE TABLE user_address (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    receiver_name VARCHAR(64) NOT NULL,
    receiver_phone VARCHAR(32) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_address_user_default
    ON user_address(user_id, is_default, id);

CREATE TABLE wechat_delivery_company (
    delivery_id VARCHAR(128) PRIMARY KEY,
    delivery_name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    synced_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_wechat_delivery_company_enabled_name
    ON wechat_delivery_company(enabled, delivery_name);

ALTER TABLE shop_order
    ADD COLUMN checkout_request_digest VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE shop_order
    MODIFY COLUMN receiver_address VARCHAR(512) NOT NULL DEFAULT '';

ALTER TABLE order_shipment
    RENAME COLUMN express_company TO express_company_name;

ALTER TABLE order_shipment
    MODIFY COLUMN express_company_name VARCHAR(128) NULL;

ALTER TABLE order_shipment
    MODIFY COLUMN tracking_no VARCHAR(80) NULL;

ALTER TABLE order_shipment
    ADD COLUMN logistics_type INT NOT NULL DEFAULT 1;

ALTER TABLE order_shipment
    ADD COLUMN delivery_mode INT NOT NULL DEFAULT 1;

ALTER TABLE order_shipment
    ADD COLUMN item_desc VARCHAR(240) NOT NULL DEFAULT '历史订单商品';

ALTER TABLE order_shipment
    ADD COLUMN express_company_code VARCHAR(128) NULL;

ALTER TABLE order_shipment
    ADD COLUMN consignor_contact VARCHAR(128) NULL;

ALTER TABLE order_shipment
    ADD COLUMN receiver_contact VARCHAR(128) NULL;

ALTER TABLE order_shipment
    ADD COLUMN upload_time VARCHAR(64) NULL;

ALTER TABLE order_shipment
    ADD COLUMN last_attempt_at TIMESTAMP NULL;

ALTER TABLE order_shipment
    ADD COLUMN wechat_provider_mode VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

UPDATE order_shipment
SET item_desc = COALESCE(
    (
        SELECT SUBSTRING(MIN(order_item.product_title), 1, 120)
        FROM order_item
        WHERE order_item.order_id = order_shipment.order_id
    ),
    '历史订单商品'
)
WHERE item_desc = '历史订单商品';
~~~

If H2 or MySQL rejects one ALTER form, change only V10 syntax to the equivalent form proven by CommerceFulfillmentMigrationTest and CommerceFulfillmentMySqlMigrationTest. Do not edit V8.

The item_desc storage width is 240 UTF-16 code units so H2 can store 120 supplementary Unicode code points; Task 9 still enforces the official maximum of 120 Unicode code points before persistence/upload. express_company_name is widened to 128 to hold a delivery_name snapshot without truncating the carrier directory contract.

shop_order.receiver_address is widened to 512 because the validated province/city/district/detail components can form a snapshot longer than the legacy 255 column. Migration and checkout tests include the longest valid composed address and prove it is copied without truncation.

- [ ] **Step 3: Implement numeric enums**

Implement LogisticsType and DeliveryMode with JsonCreator and JsonValue. Use this complete pattern:

~~~java
public enum LogisticsType {
    EXPRESS(1),
    LOCAL_DELIVERY(2),
    VIRTUAL(3),
    PICKUP(4);

    private final int value;

    LogisticsType(int value) {
        this.value = value;
    }

    @JsonValue
    public int value() {
        return value;
    }

    @JsonCreator
    public static LogisticsType fromValue(int value) {
        return Arrays.stream(values())
                .filter(item -> item.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported logistics type: " + value));
    }
}
~~~

DeliveryMode uses the same pattern with UNIFIED(1). CheckoutSource, OrderStatusGroup, and WechatProviderMode are ordinary string enums; provider modes are REAL, MOCK, DISABLED, UNKNOWN. Add UPLOADING, UNAVAILABLE, and UNKNOWN to WechatShippingUploadStatus without removing existing values.

- [ ] **Step 4: Keep every existing shipment query compatible with the renamed column**

Update ShipmentSchemaTest and AdminShipmentControllerTest inserts/assertions to use express_company_name, supplies logistics_type=1, delivery_mode=1, and item_desc, and asserts tracking_no remains nullable for non-express rows.

Change only the database column references in AdminShipmentService, AppOrderService, and AdminOrderService from express_company to express_company_name. Where the current mapper still expects express_company, select express_company_name as express_company. Do not introduce the new mode behavior until Tasks 8 and 9. This compatibility step keeps the full pre-existing shipment/order suite green immediately after V10.

- [ ] **Step 5: Run focused migration tests**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='CommerceFulfillmentSchemaTest,CommerceFulfillmentMigrationTest,CommerceFulfillmentMySqlMigrationTest,ShipmentSchemaTest,OrderSchemaTest,AdminShipmentControllerTest,AppOrderControllerTest,AdminOrderControllerTest' test

Expected GREEN: BUILD SUCCESS with zero failures and both legacy and clean-schema migration paths at V10.

- [ ] **Step 6: Review, fix, re-review, and commit**

Review package scope: V10, five enum files, and schema tests only. Critical/Important review findings must be fixed and the command in Step 5 rerun.

Commit:

    git add backend/shop-server/pom.xml backend/shop-server/src/main/resources/db/migration/V10__commerce_fulfillment.sql backend/shop-server/src/main/java/org/muybaby/shopserver/order/CheckoutSource.java backend/shop-server/src/main/java/org/muybaby/shopserver/order/OrderStatusGroup.java backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/LogisticsType.java backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/DeliveryMode.java backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatProviderMode.java backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatShippingUploadStatus.java backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/AdminShipmentService.java backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AdminOrderService.java backend/shop-server/src/test/java/org/muybaby/shopserver/fulfillment backend/shop-server/src/test/java/org/muybaby/shopserver/logistics backend/shop-server/src/test/java/org/muybaby/shopserver/order
    git commit -m "feat: add commerce fulfillment schema"

---

### Task 2: Backend App Session Rotation, Logout, Me, And Canonical Profile

**Goal:** Turn the issued opaque refresh token into a one-time rotating session, revoke the current session on logout, and expose one profile mapping from login/refresh/me/phone.

**Files:**
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppUserProfile.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppSessionResponse.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/RefreshTokenRequest.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/service/AppUserProfileMapper.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/user/AppUserController.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/AppAuthController.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/service/AppAuthService.java
- Delete after callers migrate: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppLoginResponse.java
- Delete after callers migrate: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/AppUserSummary.java
- Delete after callers migrate: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/dto/PhoneAuthorizeResponse.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenStore.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/TokenGrant.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/InMemoryTokenStore.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/RedisTokenStore.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/auth/token/OpaqueTokenService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/security/AuthenticatedPrincipal.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/security/TokenAuthentication.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/security/PathTokenKindResolver.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/auth/AppAuthControllerTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/auth/service/AppAuthServiceTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token/OpaqueTokenServiceTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token/InMemoryTokenStoreTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token/RedisTokenStoreTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/auth/token/RedisTokenStoreIntegrationTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/security/PathTokenKindResolverTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/security/AuthenticatedPrincipalTest.java

**Interfaces:**
- AppUserProfile(Long userId, String openidMasked, boolean phoneAuthorized, String phoneNumberMasked).
- AppSessionResponse(String token, String refreshToken, long expiresIn, AppUserProfile user).
- RefreshTokenRequest with required apr_ refreshToken.
- TokenStore.saveFamily(String sessionId, List<TokenGrant> grants) atomically stores and indexes a pair.
- TokenStore.consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl) atomically returns the refresh session, marks its session revoked, and revokes its indexed family.
- TokenStore.revokeSession(String sessionId, Duration revokedTtl) and isSessionRevoked(String sessionId).
- OpaqueTokenService.consumeRefreshToken(String token, TokenKind kind) returns the old TokenSession after atomically consuming and revoking its family.
- OpaqueTokenService.revokeSession(String sessionId, TokenKind kind).

- [ ] **Step 1: Add failing controller and rotation tests**

Extend AppAuthControllerTest to cover:

~~~java
@Test
void loginMeAndPhoneReturnTheSameProfileShape() throws Exception {
    AppSession login = login("profile-consistency");
    mockMvc.perform(get("/app/users/me")
            .header("Authorization", bearer(login.token())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(login.userId()))
        .andExpect(jsonPath("$.data.phoneAuthorized").value(false))
        .andExpect(jsonPath("$.data.phoneNumberMasked").doesNotExist());

    mockMvc.perform(post("/app/auth/phone")
            .header("Authorization", bearer(login.token()))
            .contentType(APPLICATION_JSON)
            .content("{\"code\":\"test-phone-code\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(login.userId()))
        .andExpect(jsonPath("$.data.phoneAuthorized").value(true))
        .andExpect(jsonPath("$.data.phoneNumberMasked").value("138****5678"));
}

@Test
void refreshRotatesOnceAndLogoutRevokesTheNewSession() throws Exception {
    AppSession login = login("refresh-once");
    MvcResult refreshed = mockMvc.perform(post("/app/auth/refresh")
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("refreshToken", login.refreshToken()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token", startsWith("app_")))
        .andExpect(jsonPath("$.data.refreshToken", startsWith("apr_")))
        .andReturn();

    mockMvc.perform(post("/app/auth/refresh")
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("refreshToken", login.refreshToken()))))
        .andExpect(status().isUnauthorized());

    String newAccess = read(refreshed, "/data/token");
    mockMvc.perform(post("/app/auth/logout")
            .header("Authorization", bearer(newAccess)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/app/users/me")
            .header("Authorization", bearer(newAccess)))
        .andExpect(status().isUnauthorized());

    String newRefresh = read(refreshed, "/data/refreshToken");
    mockMvc.perform(post("/app/auth/refresh")
            .contentType(APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("refreshToken", newRefresh))))
        .andExpect(status().isUnauthorized());
}
~~~

Add a bind-phone-then-later-login-and-me test that asserts both later responses still contain the same masked number and never contain the full number. Add expired-refresh, wrong-kind refresh, and malformed-refresh tests. Seed a pre-Task-2 legacy pair that has TokenSession values but no session-index set; after logout, both its old access request and its still-present old refresh token must return 401 through a revoked-session marker. Add OpaqueTokenServiceTest concurrency coverage with two threads consuming one apr_ token and assert exactly one success. Add store tests for atomic family issue, consume-and-revoke, logout revoke, marker expiry, and legacy no-index revocation. Concurrent tests use a barrier, bounded Future.get timeouts, executor shutdown/join, and final session-key assertions.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppAuthControllerTest,AppAuthServiceTest,OpaqueTokenServiceTest,InMemoryTokenStoreTest,RedisTokenStoreTest,RedisTokenStoreIntegrationTest,PathTokenKindResolverTest,SecurityConfigTest' test

Expected RED: 404 for refresh/me/logout and compilation failures for the new token-store methods/profile records.

- [ ] **Step 2: Implement the canonical profile mapper**

Create:

~~~java
public record AppUserProfile(
        Long userId,
        String openidMasked,
        boolean phoneAuthorized,
        String phoneNumberMasked
) {
}
~~~

AppUserProfileMapper exposes:

~~~java
@Component
public class AppUserProfileMapper {
    public AppUserProfile from(AppUser user) {
        boolean authorized = Boolean.TRUE.equals(user.phoneAuthorized());
        return new AppUserProfile(
                user.id(),
                mask(user.openid(), 4, 4),
                authorized,
                authorized ? mask(user.phoneNumber(), 3, 4) : null
        );
    }
}
~~~

Move masking out of AppAuthService. Login, refresh, me, and phone call the same mapper.

- [ ] **Step 3: Add atomic take and session-family indexing**

Change TokenStore to:

~~~java
public interface TokenStore {
    void saveFamily(String sessionId, List<TokenGrant> grants);
    Optional<TokenSession> find(String key);
    Optional<TokenSession> consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl);
    void revokeSession(String sessionId, Duration revokedTtl);
    boolean isSessionRevoked(String sessionId);
}
~~~

InMemoryTokenStore synchronizes saveFamily/consumeRefreshAndRevokeFamily/revokeSession and keeps a ConcurrentHashMap from sessionId to token keys.

Replace separate token writes with TokenStore.saveFamily(sessionId, grants), where each TokenGrant contains one already-hashed key, serialized TokenSession, and TTL. Replace take+revoke with consumeRefreshAndRevokeFamily(refreshKey).

RedisTokenStore executes two Lua scripts:

- saveFamily atomically SETs the access/refresh values with their TTLs, SADDs both hashed keys into shop:auth:session:<sessionId>, and expires the index at the refresh TTL.
- consumeRefreshAndRevokeFamily atomically GETs the refresh JSON and obtains sessionId. It first checks shop:auth:revoked:<sessionId>; when the marker already exists, it deletes the presented stale refresh key and returns nil. Otherwise it SETs the marker with a TTL at least as long as the refresh TTL, reads the indexed hashed keys, deletes every family key plus the index, and returns the consumed JSON. A missing/expired refresh returns nil without mutation.
- revokeSession atomically writes the same revoked marker and deletes every indexed key plus the index in one Lua invocation.
- isSessionRevoked checks that marker. OpaqueTokenService.lookupAccessToken rejects a stored access session when its marker exists.

InMemoryTokenStore implements the same family operations, marker-first consume check, and expiring revoked markers in one synchronized critical section. The marker is essential for pre-Task-2 Redis pairs that have no family index: refresh/logout still invalidates both sibling access and still-stored refresh tokens. Do not store raw tokens in the session set or Lua arguments; store only hashed keys produced by OpaqueTokenService.

- [ ] **Step 4: Implement issue, refresh consumption, and logout**

When issuing a pair, pass both hashed TokenGrant values to one saveFamily call under TokenSession.sessionId.

Add:

~~~java
public TokenSession consumeRefreshToken(String token, TokenKind requiredKind) {
    if (token == null || !token.startsWith(requiredKind.refreshPrefix())) {
        throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
    }
    TokenSession session = tokenStore.consumeRefreshAndRevokeFamily(
                    key(requiredKind, "refresh", token), refreshTtl(requiredKind))
            .filter(value -> value.kind() == requiredKind)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    return session;
}

public void revokeSession(String sessionId, TokenKind kind) {
    if (sessionId != null && !sessionId.isBlank()) {
        tokenStore.revokeSession(sessionId, refreshTtl(kind));
    }
}
~~~

Add sessionId to AuthenticatedPrincipal. Preserve a five-argument convenience constructor for existing tests, and have TokenAuthentication map the real TokenSession.sessionId.

When issuing a pair, OpaqueTokenService calls saveFamily once rather than save twice. Add RedisTokenStoreIntegrationTest using a disposable redis:latest Testcontainer to prove issue is all-or-nothing, concurrent consume has one winner, and refresh-vs-logout leaves no usable old access/refresh token. Unit tests also verify no raw token is present in Redis keys or session-index members.

AppAuthService methods:

~~~java
public AppSessionResponse login(AppLoginRequest request);
public AppSessionResponse refresh(RefreshTokenRequest request);
public AppUserProfile authorizePhone(AuthenticatedPrincipal principal, PhoneAuthorizeRequest request);
public AppUserProfile me(AuthenticatedPrincipal principal);
public void logout(AuthenticatedPrincipal principal);
~~~

refresh consumes the old token, re-reads an enabled app user, creates a fresh TokenSession.app, issues a new pair, and returns the current profile.

- [ ] **Step 5: Wire endpoints and security**

AppAuthController exposes login, refresh, phone, and logout. AppUserController exposes GET /app/users/me.

PathTokenKindResolver and SecurityConfig must both treat exactly /app/auth/refresh as public. /app/auth/logout and /app/users/me remain authenticated APP routes.

Malformed, expired, consumed, or admin refresh tokens return HTTP 401 with code AUTHENTICATION_REQUIRED.

- [ ] **Step 6: Run focused auth tests**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppAuthControllerTest,AppAuthServiceTest,AppUserServiceTest,OpaqueTokenServiceTest,InMemoryTokenStoreTest,RedisTokenStoreTest,RedisTokenStoreIntegrationTest,AuthenticatedPrincipalTest,PathTokenKindResolverTest,SecurityConfigTest' test

Expected GREEN: BUILD SUCCESS, one successful concurrent refresh at most, and logout invalidates its access session.

- [ ] **Step 7: Review, fix, re-review, and commit**

Review especially Redis atomicity, raw-token leakage, wrong-kind refresh, and accidental admin-token behavior changes. Re-run Step 6 after every fix.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/auth backend/shop-server/src/main/java/org/muybaby/shopserver/user/AppUserController.java backend/shop-server/src/main/java/org/muybaby/shopserver/security backend/shop-server/src/test/java/org/muybaby/shopserver/auth backend/shop-server/src/test/java/org/muybaby/shopserver/security
    git commit -m "feat: add app session lifecycle"

---

### Task 3: Mini Program Versioned Session, Single-Flight Recovery, And Profile

**Goal:** Restore valid sessions without repeated wx.login, rotate on 401 once, migrate legacy storage safely, and keep the masked phone visible after restart.

**Files:**
- Modify: miniprogram/package.json
- Modify: miniprogram/pnpm-lock.yaml
- Modify: miniprogram/tsconfig.json
- Create: miniprogram/tsconfig.test.json
- Modify: miniprogram/types/api.ts
- Create: miniprogram/utils/http.ts
- Create: miniprogram/services/session.ts
- Modify: miniprogram/services/auth.ts
- Modify: miniprogram/utils/request.ts
- Modify: miniprogram/services/storage.ts
- Modify: miniprogram/app.ts
- Modify: miniprogram/pages/profile/profile.ts
- Modify: miniprogram/pages/profile/profile.wxml
- Modify: miniprogram/pages/profile/profile.wxss
- Create: miniprogram/tests/session.test.ts
- Create: miniprogram/tests/request-recovery.test.ts

**Interfaces:**
- AuthStateV1 has version, accessToken, refreshToken, accessExpiresAt, and AppUserProfile|null.
- createSessionManager(dependencies) exposes restore, ensureSession, silentLogin, refreshSession, updateProfile, logout, clear, getState.
- rawRequest in utils/http.ts performs one wx.request and returns status plus envelope without session recovery.
- request in utils/request.ts owns the one-refresh/one-retry state machine.
- uploadEvidenceFile uses the same withAuthRecovery coordinator.

- [ ] **Step 1: Add the lightweight test runtime and failing tests**

Add dev dependency tsx and script:

~~~json
{
  "scripts": {
    "typecheck": "tsc --noEmit",
    "test:typecheck": "tsc --noEmit -p tsconfig.test.json",
    "test": "tsx --test tests/*.test.ts"
  },
  "devDependencies": {
    "@types/node": "^24.0.0",
    "miniprogram-api-typings": "^4.0.8",
    "tsx": "^4.20.6",
    "typescript": "^5.9.0"
  }
}
~~~

Create tests with injected in-memory storage and fake login/request functions. Required assertions:

~~~ts
test("migrates legacy tokens without throwing on malformed values", () => {
  const storage = fakeStorage({
    shop_app_token: "app_old",
    shop_app_refresh_token: "apr_old"
  })
  const manager = createSessionManager(fakeDependencies({ storage }))
  const state = manager.restore()
  assert.equal(state.version, 1)
  assert.equal(state.accessToken, "app_old")
  assert.equal(state.refreshToken, "apr_old")
})

test("coalesces concurrent silent login", async () => {
  let loginCalls = 0
  const manager = createSessionManager(fakeDependencies({
    login: async () => {
      loginCalls += 1
      return sessionResponse("app_new", "apr_new")
    }
  }))
  await Promise.all([manager.silentLogin(), manager.silentLogin(), manager.ensureSession()])
  assert.equal(loginCalls, 1)
})

test("refreshes once and retries the original request once", async () => {
  const result = await exerciseRecovery({
    originalStatuses: [401, 200],
    refreshResult: sessionResponse("app_rotated", "apr_rotated")
  })
  assert.equal(result.refreshCalls, 1)
  assert.equal(result.originalCalls, 2)
})

test("falls back to one silent login without a retry loop", async () => {
  const result = await exerciseRecovery({
    originalStatuses: [401, 401],
    refreshError: new Error("expired"),
    loginResult: sessionResponse("app_login", "apr_login")
  })
  assert.equal(result.refreshCalls, 1)
  assert.equal(result.loginCalls, 1)
  assert.equal(result.originalCalls, 2)
})
~~~

Also add tests that:

- persist AppUserProfile with only phoneNumberMasked, rebuild a new manager from storage, and restore the same masked value after restart.
- retain and render the cached masked profile when /me fails with a non-auth network/5xx error; only a fully cleared auth state renders logged out.
- logout calls the backend once and clears versioned plus legacy keys even when the backend rejects.
- a barrier-controlled burst of concurrent and slightly staggered 401 responses performs one refresh/recovery cycle, while every original request runs at most twice.
- refresh failure across concurrent requests performs one shared silent login rather than one login per request.
- uploadEvidenceFile performs the same single recovery and one upload retry, then stops on a second 401.
- a late second-attempt 401 calls clearIfCurrent(second.authTokenUsed) and cannot erase a newer token rotated by another request; cover both normal request and uploadEvidenceFile.

Run:

    cd miniprogram
    pnpm test

Expected RED: module-not-found for services/session and missing recovery helpers.

tsconfig.json excludes tests/**/*.ts from the mini-program production typecheck. tsconfig.test.json extends it, overrides exclude, includes tests plus imported source, and loads both node and miniprogram API types. The test task must pass test:typecheck as well as runtime tests.

- [ ] **Step 2: Align API types and split raw transport**

Replace AppUserSummary with:

~~~ts
export interface AppUserProfile {
  userId: number
  openidMasked: string
  phoneAuthorized: boolean
  phoneNumberMasked: string | null
}

export interface AppSessionResponse {
  token: string
  refreshToken: string
  expiresIn: number
  user: AppUserProfile
}
~~~

utils/http.ts exports rawRequest with options { url, method, data, authToken } and returns:

~~~ts
export interface RawHttpResult<T> {
  statusCode: number
  body: ApiResponse<T> | null
  authTokenUsed: string | null
}
~~~

It does not import session.ts and never retries.

- [ ] **Step 3: Implement AuthStateV1 and the session manager**

Use constants:

~~~ts
export const AUTH_STATE_KEY = "shop_app_auth_state_v1"
export const LEGACY_ACCESS_KEY = "shop_app_token"
export const LEGACY_REFRESH_KEY = "shop_app_refresh_token"
export const AUTH_STATE_VERSION = 1
export const EXPIRY_SKEW_MS = 30_000
~~~

restore validates object shape and version. Legacy values migrate only when they are strings. Persist the new entry before removing legacy keys. A migrated legacy access token has accessExpiresAt=0 so ensureSession attempts the existing refresh token instead of trusting an unknown expiry.

silentLogin owns one shared loginFlight cleared in finally; ensureSession returns that same promise whenever login is already running. refreshSession owns one refreshFlight. recoverAfterUnauthorized(failedAccessToken) owns one recoveryFlight spanning refresh and fallback login. accessExpiresAt is Date.now() + expiresIn * 1000. The mixed silentLogin/silentLogin/ensureSession test must still observe one wx.login call.

ensureSession branches exactly: if accessToken exists and accessExpiresAt > Date.now() + EXPIRY_SKEW_MS, reuse it; otherwise refresh when a refresh token exists; if refresh is absent or fails, clear and use the shared silentLogin. services/auth.ts retains ensureAppLogin as a thin compatibility wrapper around ensureSession, and retains current authorizePhone exports so existing cart/product/coupon/order/after-sale pages continue to compile until their later task-specific edits.

Refresh calls POST /app/auth/refresh through rawRequest with no bearer recovery. Silent login calls wx.login once and POST /app/auth/login.

logout calls POST /app/auth/logout once with the current access token through rawRequest and clears memory plus storage in finally, whether the network call succeeds or fails.

- [ ] **Step 4: Implement one-cycle request recovery**

request.ts records the access token used by the first attempt and performs:

~~~ts
const first = await sendWithCurrentToken(options)
if (first.statusCode !== 401 || options.auth === false || options.recoverAuth === false) {
  return unwrap(first)
}

await session.recoverAfterUnauthorized(first.authTokenUsed)

const second = await sendWithCurrentToken({ ...options, recoverAuth: false })
if (second.statusCode === 401) {
  session.clearIfCurrent(second.authTokenUsed)
}
return unwrap(second)
~~~

There is no recursive call to request. Login, refresh, and logout use rawRequest or recoverAuth:false.

recoverAfterUnauthorized first compares failedAccessToken with the current token. If another request already rotated the token, it returns without a second refresh. Otherwise all callers join one recoveryFlight: attempt refresh once; on failure clear once and run the shared silentLogin once. This prevents a late stale 401 from rotating the new refresh token again. clearIfCurrent(tokenUsed) removes state only when tokenUsed still equals the stored access token, so a late second 401 cannot erase a newer session.

Refactor uploadEvidenceFile so its first 401 invokes the same session recovery helper and performs one second wx.uploadFile call only.

- [ ] **Step 5: Replace profile onShow and phone update**

profile onShow:

~~~ts
async onShow() {
  const restored = restoreSession()
  if (restored.profile) {
    this.applyProfile(restored.profile)
  }
  this.setData({ isLoggingIn: true })
  try {
    await ensureSession()
    const profile = await getCurrentUser()
    updateProfile(profile)
    this.applyProfile(profile)
  } catch (error) {
    const current = getSessionState()
    if (current.accessToken && current.profile) {
      this.applyProfile(current.profile)
      this.setData({ profileWarning: "资料刷新失败，请稍后重试" })
    } else {
      this.applyLoggedOutState()
    }
  } finally {
    this.setData({ isLoggingIn: false })
  }
}
~~~

Phone authorization remains bound only to open-type=getPhoneNumber. On success, store the returned AppUserProfile through updateProfile. If phoneAuthorized is true, render the masked number and label the button 更换手机号; otherwise label it 授权手机号.

Map cancellation, no code, errno 1400001, and backend capability errors to non-blocking Chinese messages. Do not call wx.getUserProfile.

- [ ] **Step 6: Run mini program tests and typecheck**

Run:

    cd miniprogram
    pnpm test:typecheck
    pnpm test
    pnpm typecheck

Expected GREEN: all node tests pass and TypeScript reports no diagnostics.

- [ ] **Step 7: Review, fix, re-review, and commit**

Review malformed storage, circular imports, simultaneous 401s, refresh endpoint recursion, upload retries, and full-phone persistence. Re-run Step 6 after fixes.

Commit:

    git add miniprogram/package.json miniprogram/pnpm-lock.yaml miniprogram/tsconfig.json miniprogram/tsconfig.test.json miniprogram/types/api.ts miniprogram/utils miniprogram/services miniprogram/app.ts miniprogram/pages/profile miniprogram/tests
    git commit -m "feat: add mini program session recovery"

---
### Task 4: Backend User Address Book And Ownership Rules

**Goal:** Add the minimum current-user address book with transactional default invariants and an ownership-safe lookup that checkout can reuse.

**Files:**
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/AppAddressController.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/dto/AddressUpsertRequest.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/dto/AddressResponse.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/service/AppAddressService.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/user/address/service/OwnedAddress.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/user/address/AppAddressControllerTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/user/address/service/AppAddressServiceTest.java

**Interfaces:**
- GET /app/addresses returns the current user's addresses, default first and then newest first.
- GET /app/addresses/{addressId} returns one owned address.
- POST /app/addresses creates one address.
- PUT /app/addresses/{addressId} updates one owned address.
- DELETE /app/addresses/{addressId} deletes one owned address and returns an empty successful envelope.
- POST /app/addresses/{addressId}/default makes one owned address default.
- AddressUpsertRequest has receiverName, receiverPhone, province, city, district, detailAddress, and isDefault.
- AddressResponse additionally has id, formattedAddress, createdAt, and updatedAt.
- AppAddressService.requireOwnedForUpdate(userId, addressId) locks and returns OwnedAddress for order snapshotting.

- [ ] **Step 1: Add failing ownership and invariant tests**

Create AppAddressControllerTest with authenticated APP users A and B. Cover:

Use deterministic per-test cleanup or @DirtiesContext(AFTER_EACH_TEST_METHOD); no address rows from one method may influence another.

~~~java
@Test
void firstAddressBecomesDefaultAndExplicitDefaultMovesAtomically() throws Exception {
    long first = createAddress(userAToken, "张三", false);
    long second = createAddress(userAToken, "李四", false);

    getAddress(userAToken, first)
            .andExpect(jsonPath("$.data.isDefault").value(true));
    mockMvc.perform(post("/app/addresses/{id}/default", second)
            .header(AUTHORIZATION, bearer(userAToken)))
            .andExpect(status().isOk());

    getAddress(userAToken, first)
            .andExpect(jsonPath("$.data.isDefault").value(false));
    getAddress(userAToken, second)
            .andExpect(jsonPath("$.data.isDefault").value(true));
}

@Test
void cannotReadUpdateDeleteDefaultOrSelectAnotherUsersAddress() throws Exception {
    long addressId = createAddress(userAToken, "张三", true);
    assertForbiddenOrNotFoundForEveryAddressOperation(userBToken, addressId);
    assertThatThrownBy(() -> appAddressService.requireOwnedForUpdate(userBId, addressId))
            .isInstanceOf(BusinessException.class);
}

@Test
void deletingDefaultPromotesOldestRemainingAddress() throws Exception {
    long first = createAddress(userAToken, "张三", true);
    long second = createAddress(userAToken, "李四", false);
    createAddress(userAToken, "王五", false);
    deleteAddress(userAToken, first);
    getAddress(userAToken, second)
            .andExpect(jsonPath("$.data.isDefault").value(true));
}
~~~

Also test trimming, blank region/detail rejection, phone/name length validation, empty list, and exact formattedAddress order.

Add barrier-controlled tests for two simultaneous first-address creates, simultaneous default switches, and delete-default racing with a create. Every committed result must have exactly one default. Updating the current default with isDefault=false keeps it default unless another address is explicitly made default, so an update cannot leave a nonempty address book without a default.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppAddressControllerTest,AppAddressServiceTest' test

Expected RED: compilation failure because the address controller, DTOs, and service do not exist.

- [ ] **Step 2: Implement validated DTOs and mappings**

Use Jakarta validation on AddressUpsertRequest:

~~~java
public record AddressUpsertRequest(
        @NotBlank @Size(max = 64) String receiverName,
        @NotBlank @Size(max = 32) String receiverPhone,
        @NotBlank @Size(max = 64) String province,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String district,
        @NotBlank @Size(max = 255) String detailAddress,
        boolean isDefault
) {
}
~~~

Trim every field before persistence; validation after trimming must reject whitespace-only input. formattedAddress joins province, city, district, and detailAddress without persisting a duplicate field.

- [ ] **Step 3: Implement transactional default and ownership behavior**

AppAddressService methods:

~~~java
public List<AddressResponse> list(long userId);
public AddressResponse get(long userId, long addressId);
public AddressResponse create(long userId, AddressUpsertRequest request);
public AddressResponse update(long userId, long addressId, AddressUpsertRequest request);
public void delete(long userId, long addressId);
public AddressResponse setDefault(long userId, long addressId);
public OwnedAddress requireOwnedForUpdate(long userId, long addressId);
~~~

For create/update/delete/setDefault, first lock the owning app_user row, then lock that user's address rows before counting or switching defaults. The app_user lock serializes two concurrent first-address creates even when no address row exists yet. First address is default even when isDefault=false. Clear other defaults before setting the selected row. When deleting a default, promote the oldest remaining row ordered by created_at and id. Use the existing not-found/error conventions and do not reveal whether another user's id exists.

- [ ] **Step 4: Wire the current-user controller**

AppAddressController reads userId only from AuthenticatedPrincipal, never from query/body input. Annotate create/update request bodies with @Valid and keep the existing ApiResponse envelope.

- [ ] **Step 5: Run focused address verification**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppAddressControllerTest,AppAddressServiceTest,SecurityConfigTest' test

Expected GREEN: all address ownership/default cases pass with zero failures.

- [ ] **Step 6: Review, fix, re-review, and commit**

Spec review must confirm every endpoint and default invariant. Code review must inspect lock ordering, cross-user enumeration, raw phone logging, and promotion races. Fix every Critical/Important finding and rerun Step 5.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/user/address backend/shop-server/src/test/java/org/muybaby/shopserver/user/address
    git commit -m "feat: add app address book"

---

### Task 5: Backend Shared CART/DIRECT Checkout And Receiver Snapshots

**Goal:** Route CART and DIRECT preview/submit through one pricing, coupon, inventory-lock, idempotency, and snapshot flow while guaranteeing DIRECT never mutates the cart.

**Files:**
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/CheckoutSelectionService.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/CheckoutSelection.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/CheckoutRequest.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/CheckoutRequestDigest.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AppOrderPreviewRequest.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AppOrderSubmitRequest.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderPreviewItemResponse.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/AppOrderController.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/order/service/AppOrderServiceTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/order/service/CheckoutSelectionServiceTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/order/service/CheckoutRequestDigestTest.java

**API contracts:**

~~~java
public record AppOrderPreviewRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity,
        Long addressId,
        Long userCouponId
) {
}

public record AppOrderSubmitRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity,
        @NotNull Long addressId,
        Long userCouponId,
        @NotBlank @Size(max = 80) String idempotencyKey
) {
}
~~~

Normalize null source to CART for compatibility. Updated clients send source explicitly. OrderPreviewItemResponse.cartItemId becomes nullable for DIRECT.

CheckoutRequest is the normalized internal record shared by preview/submit selection code:

~~~java
public record CheckoutRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity,
        Long addressId,
        Long userCouponId
) {
}
~~~

Provide factories CheckoutRequest.from(AppOrderPreviewRequest) and CheckoutRequest.from(AppOrderSubmitRequest). Both default null source to CART and defensively copy/deduplicate ids; conditional validation remains in CheckoutSelectionService. The record contains no idempotency key and no receiver PII; CheckoutRequestDigest accepts it plus the submit idempotency inputs.

**Conditional validation:**
- CART: normalized distinct cartItemIds are nonempty; skuId and quantity are absent; every row belongs to the user.
- DIRECT: cartItemIds are absent/empty; skuId is present; quantity is 1..999.
- Preview addressId is optional; supplied address must be owned.
- Submit addressId is required and locked for the current user.
- Both sources reuse current SKU/SPU/category enabled, stock, price, coupon, inventory-lock, and order-item snapshot rules.

- [ ] **Step 1: Add failing contract and non-mutation tests**

Add controller/service tests for both sources. The key regression test captures cart state before and after DIRECT preview, successful submit, idempotent replay, and rejected submit:

~~~java
@Test
void directSubmitCreatesDirectOrderAndNeverMutatesExistingCart() {
    long existingCartId = addCartItem(userId, otherSkuId, 3);
    Map<Long, Integer> before = cartQuantities(userId);

    OrderSubmitResponse result = appOrderService.submit(appPrincipal(userId),
            directSubmit(directSkuId, 2, addressId, null, "direct-001"));

    assertThat(orderSource(result.orderId())).isEqualTo("DIRECT");
    assertThat(cartQuantities(userId)).isEqualTo(before);
    assertThat(cartRowExists(existingCartId)).isTrue();
    assertReceiverSnapshot(result.orderId(), "张三", "13800138000", "北京市朝阳区火锅路1号");

    OrderSubmitResponse replay = appOrderService.submit(appPrincipal(userId),
            directSubmit(directSkuId, 2, addressId, null, "direct-001"));
    assertThat(replay.orderId()).isEqualTo(result.orderId());
    assertThat(cartQuantities(userId)).isEqualTo(before);
}
~~~

Required tests also cover:

- CART request with direct fields rejects.
- DIRECT request with cart ids rejects.
- Quantity 0 and 1000 reject.
- Disabled/sold-out direct SKU matches CART error behavior.
- Another user's address rejects.
- CART submit deletes only selected owned rows; DIRECT deletes none.
- Matching idempotency digest replays even after CART rows were deleted.
- Same key with changed source, ids, quantity, address, or requested coupon rejects ORDER_STATE_CONFLICT.
- Missing source remains CART-compatible.
- Two same-key/same-digest CART threads return the same order id even when the winner deletes the cart rows.
- Two same-key/different-digest threads yield one order and one ORDER_STATE_CONFLICT.
- If the first ownership transaction rolls back before completing selection, the waiting request can acquire ownership and complete; no permanent stub row remains.

Concurrency tests use barriers and bounded futures. They assert one order, one set of stock/coupon locks, and no duplicate cart deletion.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppOrderControllerTest,AppOrderServiceTest,CheckoutSelectionServiceTest,CheckoutRequestDigestTest' test

Expected RED: request constructors and service do not support source/sku/quantity/address, and receiver snapshots remain blank.

- [ ] **Step 2: Implement request-level idempotency digest first**

CheckoutRequestDigest canonicalizes and SHA-256 hashes only request fields:

~~~text
source=CART|cartItemIds=2,7,9|addressId=4|userCouponId=<AUTO>
source=DIRECT|skuId=18|quantity=2|addressId=4|userCouponId=<AUTO>
~~~

Sort and deduplicate cart ids before hashing. Use an explicit automatic-coupon null marker. Do not include receiver phone/full address, database cart quantities, or mutable product fields. On existing user/idempotency key, matching nonblank checkout_request_digest returns the existing result; a mismatch returns ORDER_STATE_CONFLICT. Preserve legacy empty-digest behavior only for migrated rows.

- [ ] **Step 3: Extract normalized shared selection**

CheckoutSelectionService exposes separate preview and locked-submit loaders but one snapshot/pricing builder:

~~~java
public CheckoutSelection preview(long userId, CheckoutRequest request);
public CheckoutSelection lockForSubmit(long userId, CheckoutRequest request);
~~~

CheckoutSelection contains source, previewItems, checkoutItems, selectedCartItemIds, productOriginalAmountCent, productAmountCent, and CheckoutContext. DIRECT constructs one in-memory selected item from the locked SKU and never calls cart insert/update/delete methods. CART preserves the existing row-lock behavior.

- [ ] **Step 4: Refactor AppOrderService transaction around selection**

Submit order preserves the existing ownership-first pattern:

1. Normalize and conditionally validate the request.
2. Compute digest and check for an existing order before any cart/SKU/address read.
3. Insert a minimal shop_order ownership row containing user, idempotency key, order number, source, digest, and timestamps before taking checkout locks.
4. If the unique key loses, use a locking/current read, not a repeatable-read snapshot, to load the committed winner after the wait. Return it only when the nonblank digest matches; otherwise return ORDER_STATE_CONFLICT.
5. Only the ownership winner builds the locked CheckoutSelection.
6. Lock and validate AppAddressService.requireOwnedForUpdate.
7. Apply the existing promotion/coupon calculator.
8. Update the owned row with receiver_name, receiver_phone, receiver_address, and all amounts.
9. Insert the existing immutable order-item snapshots.
10. Reuse existing inventory and coupon locks.
11. Delete selected cart rows only when selection.source() == CART.
12. Return the existing OrderSubmitResponse shape.

All steps remain in one transaction, so any winner failure rolls back its ownership row. The waiting insert can then succeed. Never lock/read cart rows before ownership is decided.

Preview uses the same selection/pricing path without locks that mutate state. If addressId is present it validates ownership; the client continues to render the AddressResponse already loaded from the address service. If absent, preview still prices the selection without adding an address field to the existing price response.

- [ ] **Step 5: Run focused checkout and regression tests**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppOrderControllerTest,AppOrderServiceTest,CheckoutSelectionServiceTest,CheckoutRequestDigestTest,AppCartControllerTest,CouponDiscountCalculatorTest' test

Expected GREEN: both sources pass identical pricing/stock/coupon paths, snapshots are populated, and every DIRECT cart assertion is unchanged.

- [ ] **Step 6: Review, fix, re-review, and commit**

Spec review must trace CART and DIRECT through the same final calculation and lock code. Code review must inspect transaction order, lost cart selections, duplicate idempotency races, address ownership, and direct cart writes. Fix all Critical/Important findings and rerun Step 5.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/order
    git commit -m "feat: add direct checkout and address snapshots"

---

### Task 6: Mini Program Quantity, Direct Buy, Address Pages, And Unified Preview

**Goal:** Make direct buy usable without cart pollution and complete address selection on the existing order preview page.

**Files:**
- Modify: miniprogram/package.json
- Modify: miniprogram/pnpm-lock.yaml
- Modify: miniprogram/app.json
- Modify: miniprogram/types/api.ts
- Modify: miniprogram/services/order.ts
- Create: miniprogram/services/address.ts
- Create: miniprogram/features/checkout.ts
- Modify: miniprogram/pages/product/detail/detail.ts
- Modify: miniprogram/pages/product/detail/detail.wxml
- Modify: miniprogram/pages/product/detail/detail.wxss
- Modify: miniprogram/pages/cart/cart.ts
- Modify: miniprogram/pages/order/preview/preview.ts
- Modify: miniprogram/pages/order/preview/preview.wxml
- Modify: miniprogram/pages/order/preview/preview.wxss
- Create: miniprogram/pages/address/list/list.json
- Create: miniprogram/pages/address/list/list.ts
- Create: miniprogram/pages/address/list/list.wxml
- Create: miniprogram/pages/address/list/list.wxss
- Create: miniprogram/pages/address/edit/edit.json
- Create: miniprogram/pages/address/edit/edit.ts
- Create: miniprogram/pages/address/edit/edit.wxml
- Create: miniprogram/pages/address/edit/edit.wxss
- Create: miniprogram/tests/checkout.test.ts
- Create: miniprogram/tests/address-selection.test.ts

**Client contracts:**
- CheckoutQuery is { source:'CART', cartItemIds:number[] } or { source:'DIRECT', skuId:number, quantity:number }.
- Preview and submit send explicit source and source-specific fields.
- Submit always sends selected addressId and one stable page-instance idempotencyKey.
- Address list supports normal management mode and selection mode; selection returns the complete AddressResponse over eventChannel.

- [ ] **Step 1: Add failing pure behavior tests**

Add checkout.test.ts coverage:

~~~ts
test("clamps quantity to selected sku stock and 999", () => {
  assert.equal(clampQuantity(0, 5), 1)
  assert.equal(clampQuantity(7, 5), 5)
  assert.equal(clampQuantity(1200, 5000), 999)
})

test("builds direct query without cart ids", () => {
  assert.deepEqual(parseCheckoutQuery(buildDirectBuyUrl(18, 2)), {
    source: "DIRECT",
    skuId: 18,
    quantity: 2
  })
})

test("direct submit body contains no cartItemIds", () => {
  const body = buildSubmitRequest(directSelection(18, 2), 7, null, "idem-1")
  assert.equal(body.source, "DIRECT")
  assert.equal("cartItemIds" in body, false)
})
~~~

Add address-selection.test.ts for default address resolution, eventChannel replacement, deleted selection fallback, and submit-disabled-without-address.

Add product-selection cases for no SKU selected, disabled SKU, stock=0, stock=1 boundaries, add-to-cart using selected quantity, and switching SKU resetting/clamping quantity. Assert direct-buy URL is never produced for disabled/sold-out selections and no cart service operation appears in the direct-buy command.

Run:

    cd miniprogram
    pnpm test

Expected RED: checkout/address helpers and direct selection types are missing.

- [ ] **Step 2: Implement product quantity and direct-buy state**

Use one selectedQuantity defaulting to 1. Clamp it to min(999, selected SKU stock) whenever the SKU changes. Minus/plus controls honor bounds. A disabled or zero-stock SKU shows an explicit toast and cannot enable the direct-buy button.

Keep add-to-cart, but pass selectedQuantity to the existing add call. onBuyNow validates an enabled selected SKU and navigates to the existing preview page with encoded source=DIRECT, sku_id, and quantity. It never invokes the cart service.

- [ ] **Step 3: Implement address service and management pages**

services/address.ts implements the six Task 4 endpoints. Address list provides create, edit, set-default, delete-confirmation, loading/empty/error/retry, and selection mode. Address edit trims fields and surfaces backend validation errors. Register both pages in app.json.

In selection mode, tap returns AddressResponse through eventChannel and navigates back. In normal mode, tap edits. Do not persist full receiver phone in auth/session storage.

- [ ] **Step 4: Refactor cart and preview to discriminated checkout selection**

Cart navigation sends source=CART and encoded selected ids. Preview parses both sources through features/checkout.ts, loads addresses, chooses an existing selection or default, then requests price/coupons with the same explicit selection.

Preview UI shows the address or a clear 添加收货地址 action, opens the selection-mode list, and disables submit until an address exists. Coupon/address changes refresh preview without replacing the page's stable idempotency key. Successful DIRECT submit leaves the cart untouched; CART keeps existing behavior.

- [ ] **Step 5: Run mini program verification**

Run:

    cd miniprogram
    pnpm test:typecheck
    pnpm test
    pnpm typecheck

Expected GREEN: test typecheck, quantity/direct/address runtime tests, and production typecheck all pass.

- [ ] **Step 6: Review, fix, re-review, and commit**

Spec review must exercise sold-out selection, both URLs, no-address preview, address switch, and one idempotency key. Code review must inspect query decoding, double submit, eventChannel lifetime, full-phone storage, and any cart call on DIRECT. Fix all Critical/Important findings and rerun Step 5.

Commit:

    git add miniprogram/package.json miniprogram/pnpm-lock.yaml miniprogram/app.json miniprogram/types/api.ts miniprogram/services/order.ts miniprogram/services/address.ts miniprogram/features/checkout.ts miniprogram/pages/product/detail miniprogram/pages/cart miniprogram/pages/order/preview miniprogram/pages/address miniprogram/tests
    git commit -m "feat: add mini program direct checkout"

---

### Task 7: Backend Order Center, Detail Truth, After-Sales, And Receipt Confirmation

**Goal:** Complete current-user order paging/detail/after-sale APIs and the real SHIPPED to COMPLETED local state transition without changing payment/refund semantics.

**Files:**
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/AppOrderController.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AppOrderDetailResponse.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderSummaryResponse.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderReceiptResponse.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/AppAfterSaleController.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/AppAfterSaleService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale/service/AdminAfterSaleService.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/AppOrderControllerTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/service/AppOrderServiceTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AppAfterSaleControllerTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/AdminAfterSaleControllerTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale/service/AppAfterSaleServiceTest.java

**API contracts:**
- GET /app/orders?current=1&size=10&statusGroup=ALL supports ALL, UNPAID, TO_SHIP, TO_RECEIVE, COMPLETED.
- Existing exact status remains supported; reject simultaneous nonblank status and non-ALL statusGroup as an ambiguous query.
- POST /app/orders/{orderId}/confirm-receipt transitions owned SHIPPED to COMPLETED, repeats idempotently on COMPLETED, and rejects every other status.
- GET /app/after-sales?current=1&size=10&status=optional lists only the current user's records.
- GET /app/after-sales/{afterSaleId} returns only a current-user-owned record.
- AppOrderDetailResponse carries receiver snapshot, paymentStatus/outTradeNo/transactionId/paidAt, shipment, latestAfterSale, createdAt/closedAt/shippedAt/completedAt/refundingAt/refundedAt. The existing admin OrderDetailResponse remains the admin contract.

Task 7 creates these exact records:

~~~java
public record OrderReceiptResponse(
        Long orderId,
        String status,
        LocalDateTime completedAt
) {
}

public record AppOrderDetailResponse(
        Long orderId,
        String orderNo,
        String status,
        String source,
        Long productOriginalAmountCent,
        Long productAmountCent,
        Long userCouponId,
        String couponName,
        Long couponDiscountCent,
        Long freightCent,
        Long payableAmountCent,
        Long paidAmountCent,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String paymentTransactionId,
        String merchantTradeNo,
        String paymentStatus,
        String outTradeNo,
        String transactionId,
        LocalDateTime paidAt,
        String closeReason,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime shippedAt,
        LocalDateTime completedAt,
        LocalDateTime refundingAt,
        LocalDateTime refundedAt,
        OrderShipmentResponse shipment,
        AfterSaleResponse latestAfterSale,
        List<OrderItemResponse> items
) {
}
~~~

Coupon/payment/trade/close/shipment/after-sale fields and non-applicable times are nullable. Receiver strings are immutable order snapshots; legacy rows may contain empty strings. In Task 7 the app record temporarily uses the existing shipment response so the commit compiles; Task 9 replaces that field type with AppOrderShipmentResponse and removes admin-only diagnostics from the app JSON.

- [ ] **Step 1: Add failing paging, detail, ownership, and transition tests**

Add parameterized status-group assertions:

~~~java
@ParameterizedTest
@CsvSource({
        "UNPAID,CREATED;PAYING",
        "TO_SHIP,PAID",
        "TO_RECEIVE,SHIPPED",
        "COMPLETED,COMPLETED"
})
void statusGroupReturnsOnlyMappedStatuses(OrderStatusGroup group, String allowedCsv) {
    Set<String> allowed = Set.of(allowedCsv.split(";"));
    PageResult<OrderSummaryResponse> page = appOrderService.list(
            appPrincipal(userId), 1L, 10L, null, group);
    assertThat(page.records()).allMatch(item -> allowed.contains(item.status()));
}
~~~

Required tests:

- current/size paging total and order are stable.
- Detail returns real latest payment fields, receiver snapshot, shipment, latest after-sale, and key times.
- An order without payment or after-sale still returns detail with nullable nested fields.
- Confirm owned SHIPPED sets COMPLETED/completed_at.
- Repeat on COMPLETED succeeds without changing completed_at.
- Other user and invalid state reject.
- Completed order can still create the existing supported after-sale request.
- A completed order's request can still be approved by AdminAfterSaleService and enter the existing refund flow; app/admin policy sets remain aligned.
- After-sale list/detail exclude another user's ids and page correctly.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppOrderControllerTest,AppOrderServiceTest,AppAfterSaleControllerTest,AppAfterSaleServiceTest,AdminAfterSaleControllerTest' test

Expected RED: missing statusGroup/confirm/list/detail APIs and incomplete app detail mapping.

- [ ] **Step 2: Implement status-group query without changing OrderStatus**

Map groups exactly:

~~~java
ALL          -> no predicate
UNPAID       -> CREATED, PAYING
TO_SHIP      -> PAID
TO_RECEIVE   -> SHIPPED
COMPLETED    -> COMPLETED
~~~

Use one count query and one bounded page query ordered by created_at desc, id desc. Keep exact status compatibility. Clamp size to the project's existing maximum and reject current < 1 or size < 1.

- [ ] **Step 3: Make app order detail match persisted reality**

Return the new AppOrderDetailResponse from the app controller so app-specific visibility can diverge safely from the admin contract. Use the same latest-payment selection/fallback as the existing admin detail query instead of hardcoded nulls. Map the one local shipment row and current upload state. Return the latest AfterSaleResponse independently nullable. Add shippedAt, completedAt, refundingAt, and refundedAt from their authoritative tables/columns without synthesizing times.

- [ ] **Step 4: Implement atomic receipt confirmation and current-user after-sales**

For confirmation, lock the owned order. SHIPPED updates status=COMPLETED and completed_at exactly once. COMPLETED returns current state. All other statuses return ORDER_STATE_CONFLICT. This endpoint never calls or waits for a WeChat receipt component.

Extend both AppAfterSaleService.ALLOWED_ORDER_STATUSES and AdminAfterSaleService.REFUNDABLE_ORDER_STATUSES from PAID/SHIPPED to PAID/SHIPPED/COMPLETED while preserving refund amount, duplicate request, payment, audit, and item ownership checks. List/detail queries join through shop_order.user_id and never accept a body/query user id. The existing GET /app/after-sales/{afterSaleId} and AfterSaleResponse are reused rather than reimplemented.

- [ ] **Step 5: Run focused order-center verification**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AppOrderControllerTest,AppOrderServiceTest,AppAfterSaleControllerTest,AppAfterSaleServiceTest,AdminAfterSaleControllerTest,AdminOrderControllerTest,RefundCallbackServiceTest' test

Expected GREEN: paging/detail/receipt/current-user after-sale cases pass and admin/refund regressions remain green.

- [ ] **Step 6: Review, fix, re-review, and commit**

Spec review must map every public field and every status group. Code review must inspect ownership, page count/query parity, confirm races, payment fallback, and completed after-sale safety. Fix all Critical/Important findings and rerun Step 5.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/main/java/org/muybaby/shopserver/aftersale backend/shop-server/src/test/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/aftersale
    git commit -m "feat: complete app order center backend"

---

### Task 8: WeChat Capability, Carrier Directory, And Exact Four-Mode Gateway

**Goal:** Model the official WeChat APIs as a safe typed gateway, prove exact JSON for all four logistics types, and expose capability/carrier data without coupling it to local shipment state.

**Files:**
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatShippingProvider.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/RealWechatShippingProvider.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/MockWechatShippingProvider.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatShippingUploadRequest.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatShippingUploadResult.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatShippingItem.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatShippingCapabilityResult.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/provider/WechatDeliveryCompanyResult.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/WechatShippingCapabilityState.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/AdminWechatShippingController.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/WechatShippingCapabilityResponse.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/WechatDeliveryCompanyResponse.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/WechatShippingCatalogService.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/WechatShippingProviderTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/WechatShippingCatalogServiceTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/AdminWechatShippingControllerTest.java

**Provider contracts:**

~~~java
public interface WechatShippingProvider {
    WechatProviderMode mode();
    WechatShippingUploadResult upload(WechatShippingUploadRequest request);
    WechatShippingCapabilityResult queryCapability();
    List<WechatDeliveryCompanyResult> getDeliveryCompanies();
}

public record WechatShippingUploadRequest(
        Long orderId,
        String transactionId,
        String openid,
        LogisticsType logisticsType,
        DeliveryMode deliveryMode,
        String uploadTime,
        List<WechatShippingItem> shippingList
) {
}
~~~

The public request model is a list so it can grow to multiple packages later, but this phase validates shippingList.size()==1 and the local service constructs List.of(one item). WechatShippingUploadResult carries UPLOADED, FAILED, UNAVAILABLE, or UNKNOWN plus a safe errorCode/errorMessage. Only an explicit JSON errcode=0 from WechatProviderMode.REAL is UPLOADED. Empty/malformed bodies and ambiguous transport failures are UNKNOWN, never success.

**Admin contracts:**
- GET /admin/wechat-shipping/capability returns uploadEnabled, providerMode, state, tradeManaged, errorCode, errorMessage, checkedAt.
- GET /admin/wechat-shipping/carriers returns enabled cached deliveryId/deliveryName rows.
- POST /admin/wechat-shipping/carriers/sync fetches official rows, upserts them, disables absent old rows, and returns the enabled list.
- All three endpoints require the existing order:ship authority; no new RBAC migration or unprotected admin utility endpoint is introduced.

~~~java
public record WechatShippingCapabilityResponse(
        boolean uploadEnabled,
        WechatProviderMode providerMode,
        WechatShippingCapabilityState state,
        Boolean tradeManaged,
        String errorCode,
        String errorMessage,
        OffsetDateTime checkedAt
) {
}

public record WechatDeliveryCompanyResponse(
        String deliveryId,
        String deliveryName,
        LocalDateTime syncedAt
) {
}
~~~

tradeManaged and error fields are nullable according to state. Carrier list/sync return List<WechatDeliveryCompanyResponse> inside the standard API envelope.

- [ ] **Step 1: Add exact mock-HTTP payload tests before changing the provider**

Use MockRestServiceServer and Jackson tree equality. Pass fixed upload_time=2026-07-09T12:34:56Z and verify the request URI contains an access token without ever asserting/logging its raw value. EXPRESS expected body:

Reset MockRestServiceServer, cached carrier rows, provider configuration, and captured logs before every test. Carrier sync tests use transaction rollback or deterministic table cleanup.

~~~json
{
  "order_key": {
    "order_number_type": 2,
    "transaction_id": "4200000000000000001"
  },
  "logistics_type": 1,
  "delivery_mode": 1,
  "shipping_list": [
    {
      "tracking_no": "SF1234567890",
      "express_company": "SF",
      "item_desc": "菌汤锅底 2份",
      "contact": {
        "consignor_contact": "*******4321",
        "receiver_contact": "*******8000"
      }
    }
  ],
  "upload_time": "2026-07-09T12:34:56Z",
  "payer": {
    "openid": "openid-test-value"
  }
}
~~~

For LOCAL_DELIVERY, VIRTUAL, and PICKUP, assert logistics_type 2, 3, and 4 respectively and exact item shape:

~~~json
{
  "item_desc": "菌汤锅底 2份"
}
~~~

Assert shipping_list size is exactly one and the serialized tree has no tracking_no, express_company, or contact key for non-express modes. Add tests for explicit errcode=0, nonzero errcode, empty object, blank/malformed body, and RestClient transport exception.

Use Spring Boot's OutputCaptureExtension on success and failure paths. Assert captured output does not contain the synthetic access token, Authorization value, full openid, full contacts, tracking number, or serialized payload; assert it contains only safe order/result/exception metadata.

Add exact endpoint tests for is_trade_managed and get_delivery_list response parsing. Run:

AdminWechatShippingControllerTest asserts unauthenticated 401, authenticated-without-order:ship 403, and authorized capability/list/sync success.

    cd backend/shop-server
    ./mvnw -Dtest='WechatShippingProviderTest,WechatShippingCatalogServiceTest,AdminWechatShippingControllerTest' test

Expected RED: missing provider methods/types and existing payload hardcodes logistics_type=1 and shipmentNote as item_desc.

- [ ] **Step 2: Build payloads from typed persisted input**

RealWechatShippingProvider reports mode REAL and uses request.logisticsType().value(), request.deliveryMode().value(), request.uploadTime(), and the sole request.shippingList() item. It does not generate upload time or substitute shipmentNote.

Use @JsonInclude(NON_NULL) on the shipping item/contact payloads. Construct express-only fields only for EXPRESS; construct no contact object when both contacts are null. Validate shipping_list remains List.of(one item). Use the payment transaction-id order-key branch with order_number_type=2.

The provider must never log the serialized body, access token, openid, contact, authorization header, or request object. Logs contain only orderId, sanitized result, and exception class.

- [ ] **Step 3: Implement capability and carrier provider calls**

queryCapability calls the official is_trade_managed endpoint. Map explicit managed=true to AVAILABLE, explicit false to UNAVAILABLE, known capability/account errcodes to UNAVAILABLE, and malformed/transport-ambiguous responses to UNKNOWN.

getDeliveryCompanies calls the official get_delivery_list endpoint and returns only nonblank delivery_id/delivery_name pairs on explicit errcode=0. Nonzero/malformed/transport failures throw a safe provider exception so a failed sync cannot disable the existing cache.

Runtime MockWechatShippingProvider reports mode MOCK. Its upload and capability methods always return UNAVAILABLE with safe code MOCK_PROVIDER, never UPLOADED or AVAILABLE; it may return representative carrier rows only for local UI tests. Exact success payload tests instantiate RealWechatShippingProvider with MockRestServiceServer. Tests needing an existing UPLOADED row seed that terminal state directly instead of asking the runtime mock provider to fabricate it.

- [ ] **Step 4: Implement cached carrier synchronization and capability endpoint**

WechatShippingCatalogService requires ADMIN principal and AdminWechatShippingController enforces order:ship. capability combines ShippingProperties.uploadEnabled with the provider result. Configured upload=false reports providerMode=DISABLED and uploadEnabled=false without fabricating tradeManaged=true; mock mode is always visibly MOCK/UNAVAILABLE.

sync runs provider fetch before its database transaction. A TransactionTemplate starts only after the provider call succeeds; inside it, upsert all returned ids/names with enabled=true and one synced_at, then mark only missing old ids enabled=false. An empty successful official list is accepted and disables old rows; an exception leaves the table unchanged. list returns enabled rows ordered by delivery_name and delivery_id.

- [ ] **Step 5: Run focused gateway verification**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='WechatShippingProviderTest,WechatShippingCatalogServiceTest,AdminWechatShippingControllerTest' test

Expected GREEN: all four exact payload trees, one-item enforcement, omission checks, response-safety cases, capability/provider modes, no-mock-UPLOADED rule, and carrier cache cases pass.

- [ ] **Step 6: Review, fix, re-review, and commit**

Spec review compares payload keys against the official contract. Code review inspects token/body logging, null serialization, empty-response success, cache loss on provider failure, and mock/real ambiguity. Fix all Critical/Important findings and rerun Step 5.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/logistics backend/shop-server/src/test/java/org/muybaby/shopserver/logistics
    git commit -m "feat: add wechat shipping gateway"

---

### Task 9: Four-Mode Local Shipment, Upload State Machine, And Safe Retry

**Goal:** Persist complete mode-specific shipment facts and move the local order to SHIPPED before any optional WeChat attempt, with retry rebuilt from persisted data and guarded by compare-and-set state transitions.

**Files:**
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/AdminShipOrderRequest.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/OrderShipmentResponse.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/dto/AppOrderShipmentResponse.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/AdminShipmentController.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/AdminShipmentService.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/LocalShipmentService.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/WechatShippingUploadCoordinator.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/WechatShippingUploadRecovery.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/ShipmentContactMasker.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/logistics/service/WechatShippingErrorSanitizer.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AppOrderService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/service/AdminOrderService.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/AppOrderDetailResponse.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/order/dto/OrderDetailResponse.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/AdminShipmentControllerTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/service/LocalShipmentServiceTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/service/WechatShippingUploadCoordinatorTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/logistics/service/WechatShippingUploadRecoveryTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/service/AppOrderServiceTest.java
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/order/AdminOrderControllerTest.java

**Ship request:**

~~~java
public record AdminShipOrderRequest(
        @NotNull LogisticsType logisticsType,
        @NotBlank String itemDesc,
        @Size(max = 128) String expressCompanyCode,
        @Size(max = 80) String trackingNo,
        @Size(max = 128) String consignorContact,
        @Size(max = 255) String shipmentNote
) {
}
~~~

deliveryMode is never accepted from the client; backend persists UNIFIED=1. receiverContact is never accepted from the client; for SF it is derived and masked from the immutable order receiver_phone.

**Response contracts:**
- shipmentId, orderId, logisticsType, deliveryMode, itemDesc.
- expressCompanyCode/name and trackingNo, nullable outside EXPRESS.
- shipmentNote, localShipmentStatus, wechatProviderMode, WeChat upload status, retryCount.
- shippedAt, uploadTime, wechatUploadedAt, lastAttemptAt.
- Admin OrderShipmentResponse includes safe WeChat error code/message. AppOrderShipmentResponse excludes those internal diagnostics and exposes only a fixed user-safe wechatUploadMessage derived from the state.

Admin response fields are exactly shipmentId, orderId, logisticsType, deliveryMode, itemDesc, expressCompanyCode, expressCompanyName, trackingNo, shipmentNote, localShipmentStatus, wechatProviderMode, wechatUploadStatus, wechatErrorCode, wechatErrorMessage, retryCount, shippedAt, uploadTime, wechatUploadedAt, and lastAttemptAt. App response omits shipmentNote, wechatErrorCode, and wechatErrorMessage and adds wechatUploadMessage. Nullable fields are absent under the repository's global non-null JSON policy; non-express express-only keys are also absent from the outbound WeChat payload.

~~~java
public record OrderShipmentResponse(
        Long shipmentId, Long orderId,
        LogisticsType logisticsType, DeliveryMode deliveryMode,
        String itemDesc,
        String expressCompanyCode, String expressCompanyName, String trackingNo,
        String shipmentNote, String localShipmentStatus,
        WechatProviderMode wechatProviderMode,
        WechatShippingUploadStatus wechatUploadStatus,
        String wechatErrorCode, String wechatErrorMessage,
        int retryCount,
        LocalDateTime shippedAt, String uploadTime,
        LocalDateTime wechatUploadedAt, LocalDateTime lastAttemptAt
) {
}

public record AppOrderShipmentResponse(
        Long shipmentId, Long orderId,
        LogisticsType logisticsType, DeliveryMode deliveryMode,
        String itemDesc,
        String expressCompanyCode, String expressCompanyName, String trackingNo,
        String localShipmentStatus,
        WechatProviderMode wechatProviderMode,
        WechatShippingUploadStatus wechatUploadStatus,
        String wechatUploadMessage,
        LocalDateTime shippedAt, String uploadTime,
        LocalDateTime wechatUploadedAt
) {
}
~~~

**State machine:**

~~~text
first local save: PAID -> SHIPPED, shipment upload state SKIPPED
initial eligible claim: SKIPPED -> UPLOADING, retryCount unchanged
operator retry claim: FAILED | UNAVAILABLE | eligible SKIPPED -> UPLOADING, retryCount + 1
attempt result: UPLOADING -> UPLOADED | FAILED | UNAVAILABLE | UNKNOWN
ordinary retry rejected: UPLOADING | UPLOADED | UNKNOWN
~~~

A claim older than a fixed 10-minute safety threshold is never resent. Startup recovery and order-detail/retry preflight atomically change stale UPLOADING to UNKNOWN with safe code ATTEMPT_OUTCOME_UNKNOWN and zero provider calls. UNKNOWN remains blocked from ordinary retry.

- [ ] **Step 1: Add four-mode persistence and validation tests**

Parameterize successful local ship over types 1..4. Assert each stores its type, delivery_mode=1, item_desc, local status SHIPPED, and moves order PAID to SHIPPED even when provider result is FAILED or UNAVAILABLE.

Each method starts from deterministic order/payment/carrier/shipment state and resets provider calls/properties afterward. Concurrent tests use a barrier, bounded futures, executor shutdown/join, and final row assertions.

Required validation tests:

- Blank itemDesc rejects every mode.
- 120 Unicode code points succeeds; 121 rejects, including surrogate-pair characters.
- EXPRESS requires enabled expressCompanyCode and trackingNo.
- Display text such as 顺丰速运 submitted as code rejects unless it is an enabled delivery_id.
- SF code requires at least one usable masked contact; receiver snapshot auto-derivation satisfies it when the order phone has final four digits.
- Nonblank optional consignor contact is normalized/masked server-side.
- Over-limit carrier code, tracking number, consignor contact, and shipment note return validation errors rather than database exceptions.
- Non-express blank express fields normalize to null; nonblank express-only fields reject rather than being persisted.
- LOCAL_DELIVERY, VIRTUAL, and PICKUP do not require company/tracking/contact.
- First ship accepts PAID only; duplicate ship rejects.
- Submitted itemDesc remains independent from shipmentNote and is returned unchanged after persistence.
- Disabled upload persists DISABLED + SKIPPED; runtime mock persists MOCK + UNAVAILABLE/MOCK_PROVIDER; only REAL + explicit errcode=0 can persist UPLOADED.
- Initial failure leaves retryCount=0; each successfully claimed operator retry increments it exactly once.

The itemDesc suggestion assertion belongs to Task 10's admin pure helper; backend tests only assert the submitted itemDesc is independently validated and persisted.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AdminShipmentControllerTest,LocalShipmentServiceTest,WechatShippingUploadCoordinatorTest,AppOrderServiceTest,AdminOrderControllerTest' test

Expected RED: old DTO requires free-text company/tracking, schema mapping lacks mode fields, and provider call occurs inside the local transaction.

- [ ] **Step 2: Split local commit from external upload**

AdminShipmentService is a non-transactional orchestrator:

~~~java
public OrderShipmentResponse ship(AuthenticatedPrincipal principal, long orderId,
                                  AdminShipOrderRequest request) {
    OrderShipmentResponse local = localShipmentService.create(principal, orderId, request);
    wechatShippingUploadCoordinator.attemptInitial(local.shipmentId());
    return localShipmentService.getForAdmin(orderId);
}
~~~

LocalShipmentService.create is transactional: lock PAID order, normalize/validate, resolve enabled carrier code/name, derive contacts, insert every original fact, update order SHIPPED/shipped_at, and commit. Do not catch provider failures there because no provider is called there.

For non-express rows store null company, tracking, and contacts. For express store official delivery id and name snapshots. Keep shipment_note independent from item_desc. Initialize wechat_provider_mode to DISABLED when upload is configured off, otherwise to the active provider.mode().

- [ ] **Step 3: Rebuild every attempt exclusively from persistence**

WechatShippingUploadCoordinator loads shipment, order, latest paid payment transaction_id, and app-user openid after a successful compare-and-set claim. It creates a fresh RFC 3339 upload_time, persists it and the actual provider mode before the HTTP call, and constructs WechatShippingUploadRequest with List.of(one persisted item) entirely from those rows. It never reuses the admin request object.

If upload is configured off, initial state remains SKIPPED with mode DISABLED. Missing transaction id records FAILED with MISSING_TRANSACTION_ID. Capability=false records UNAVAILABLE. Runtime mock records MOCK + UNAVAILABLE/MOCK_PROVIDER and can never record UPLOADED. As a defense in depth, any non-REAL provider result claiming UPLOADED is downgraded to UNAVAILABLE/MOCK_PROVIDER. REAL provider UPLOADED/FAILED/UNAVAILABLE/UNKNOWN maps exactly and always updates last_attempt_at; only REAL + UPLOADED sets wechat_uploaded_at.

Wrap the entire post-claim path. Deterministic failures before upload dispatch (reconstruction, missing transaction, known capability failure) end as FAILED or UNAVAILABLE and remain safely retryable. Once the upload HTTP call may have been dispatched, any ambiguous exception ends as UNKNOWN. A primary terminal-write failure triggers one fresh-transaction fallback from UPLOADING to UNKNOWN; test this injected failure. Every successful database path updates last_attempt_at, so no caught in-process exception leaves a permanent UPLOADING row.

WechatShippingErrorSanitizer removes control characters, redacts any known access-token/openid/contact/tracking substrings passed by the coordinator, and truncates errcode/errmsg to database limits; unsafe or blank text becomes a fixed generic message. Persist and return only that safe form. Logs contain order/shipment ids, provider mode, result status, safe code, and exception class only. OutputCaptureExtension and database-response tests assert no token, authorization value, openid, full phone/contact, tracking number, or serialized payload appears on success, capability failure, payload failure, provider exception, echoed WeChat errmsg, or terminal-write fallback.

- [ ] **Step 4: Implement compare-and-set retry rules**

retryWechatUpload requires ADMIN and order SHIPPED. One SQL update claims the row only when its state is FAILED, UNAVAILABLE, or SKIPPED while upload is now enabled. It sets UPLOADING, increments retry_count once, and updates last_attempt_at. A zero-row claim reloads the state and returns ORDER_STATE_CONFLICT for UPLOADING, UPLOADED, or UNKNOWN.

Use a two-thread barrier test against one FAILED row and assert one provider call, one retry increment, and one terminal result. Test UPLOADED and UNKNOWN reject without calling provider. Parameterize retry reconstruction over EXPRESS, LOCAL_DELIVERY, VIRTUAL, and PICKUP and assert each rebuilds its original logistics type and fields rather than defaulting to EXPRESS. Assert every retry upload_time is later than the persisted preceding attempt time.

WechatShippingUploadRecovery listens for application ready and reconciles at most 100 stale claims ordered by last_attempt_at/id; the detail/retry path also reconciles its one shipment before rendering/validation. Seed a stale UPLOADING row and assert it becomes UNKNOWN/ATTEMPT_OUTCOME_UNKNOWN with provider call count zero. A fresh UPLOADING row stays unchanged.

- [ ] **Step 5: Align app/admin order-detail shipment mappings**

Update both existing order-detail services to map LogisticsType, DeliveryMode, itemDesc, carrier code/name, nullable tracking, localShipmentStatus, wechatProviderMode, upload state, retryCount, shippedAt, uploadTime, and uploadedAt. Admin gets OrderShipmentResponse with sanitized diagnostics. App gets AppOrderShipmentResponse with fixed user-safe wording and no WeChat errcode/errmsg fields; it does not claim platform acceptance unless mode is REAL and state is UPLOADED.

Legacy EXPRESS rows with null carrier code continue to show the preserved name/tracking. Ordinary retry validates reconstructability before the compare-and-set claim, returns ORDER_STATE_CONFLICT, leaves the prior state unchanged, and makes no provider call rather than inventing a code.

- [ ] **Step 6: Run focused fulfillment verification**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='AdminShipmentControllerTest,LocalShipmentServiceTest,WechatShippingUploadCoordinatorTest,WechatShippingUploadRecoveryTest,WechatShippingProviderTest,AppOrderServiceTest,AdminOrderControllerTest' test

Expected GREEN: four modes persist/query/retry correctly, local state survives every provider failure, and forbidden retries make zero calls.

- [ ] **Step 7: Review, fix, re-review, and commit**

Spec review traces all four modes from request to row to retry payload. Code review inspects transaction boundaries, CAS concurrency, code/name confusion, SF masking, code-point length, ambiguous UNKNOWN, UPLOADED protection, and secret/PII logging. Fix all Critical/Important findings and rerun Step 6.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/logistics backend/shop-server/src/main/java/org/muybaby/shopserver/order backend/shop-server/src/test/java/org/muybaby/shopserver/logistics backend/shop-server/src/test/java/org/muybaby/shopserver/order
    git commit -m "feat: add four-mode shipment workflow"

---

### Task 10: Admin Four-Mode Shipment Form, Capability, Carriers, And Detail

**Goal:** Replace the free-text express-only form in the existing order drawer with a mode-first, capability-aware form and truthful local-versus-WeChat result messaging.

**Files:**
- Create: admin/src/api/wechat-shipping.ts
- Modify: admin/src/api/order.ts
- Modify: admin/src/types/api/api.d.ts
- Create: admin/src/views/order/list/shipping-form.ts
- Create: admin/src/views/order/list/shipping-form.test.ts
- Modify: admin/src/views/order/list/index.vue

**Typed UI contracts:**
- LogisticsType is 1 | 2 | 3 | 4 and labels are 实体快递, 同城配送, 虚拟商品, 用户自提.
- DeliveryMode is 1 only.
- WechatShippingUploadStatus includes SKIPPED, UPLOADING, UPLOADED, FAILED, UNAVAILABLE, UNKNOWN.
- WechatProviderMode includes REAL, MOCK, DISABLED, UNKNOWN and is present in capability and shipment responses.
- ShipOrderForm has logisticsType, itemDesc, optional expressCompanyCode/trackingNo/consignorContact/shipmentNote.
- Capability and carrier response types exactly match Task 8.

- [ ] **Step 1: Add failing pure dynamic-form tests**

Create shipping-form.test.ts:

~~~ts
test("shows express fields only for type 1", () => {
  assert.deepEqual(visibleShippingFields(1), [
    "logisticsType", "itemDesc", "expressCompanyCode",
    "trackingNo", "consignorContact", "shipmentNote"
  ])
  for (const type of [2, 3, 4] as const) {
    assert.deepEqual(visibleShippingFields(type), [
      "logisticsType", "itemDesc", "shipmentNote"
    ])
  }
})

test("validates all four modes conditionally", () => {
  assert.deepEqual(validateShippingForm(validForm(2)), [])
  assert.match(validateShippingForm(validForm(1, { trackingNo: "" }))[0], /快递单号/)
  assert.match(validateShippingForm(validForm(1, { expressCompanyCode: "" }))[0], /快递公司/)
  assert.match(validateShippingForm(validForm(4, { itemDesc: "" }))[0], /商品描述/)
})

test("counts item description by Unicode code point", () => {
  assert.equal(itemDescLength("🔥".repeat(120)), 120)
  assert.equal(validateShippingForm(validForm(3, { itemDesc: "🔥".repeat(121) })).length, 1)
})

test("suggests editable item description from order snapshots", () => {
  const suggestion = suggestItemDesc([
    { productTitle: "菌汤锅底", specText: "300g", quantity: 2 },
    { productTitle: "牛油锅底", specText: "", quantity: 1 }
  ])
  assert.match(suggestion, /菌汤锅底.*300g.*2/)
  assert.match(suggestion, /牛油锅底.*1/)
  assert.ok(itemDescLength(suggestion) <= 120)
})

test("only real uploaded outcome claims platform acceptance", () => {
  assert.equal(shippingOutcomeMessage(shipment("REAL", "UPLOADED")), "本地发货成功，真实微信发货信息已上传")
  assert.match(shippingOutcomeMessage(shipment("MOCK", "UNAVAILABLE")), /模拟环境/)
  assert.equal(canRetryWechatUpload(shipment("REAL", "UPLOADED")), false)
  assert.equal(canRetryWechatUpload(shipment("REAL", "UNKNOWN")), false)
  assert.equal(canRetryWechatUpload(shipment("REAL", "FAILED")), true)
})
~~~

Run:

    cd admin
    pnpm exec tsx --test src/views/order/list/shipping-form.test.ts

Expected RED: shipping-form module does not exist.

- [ ] **Step 2: Align admin types and API clients**

Add fetchWechatShippingCapability, fetchWechatShippingCarriers, and syncWechatShippingCarriers. Change shipOrder to disable unconditional showSuccessMessage; the caller chooses success or warning after reading localShipmentStatus and wechatUploadStatus. Retry does the same.

Update OrderSource to include CART and DIRECT while tolerating legacy MINI_PROGRAM strings. Because backend JSON omits nulls, declare carrier/tracking, error, and nullable time properties as optional (`field?: T | null`), not required `T | null`. Add every Task 9 shipment field, wechatProviderMode, and capability state/provider mode type. Pure tests pass non-express objects with carrier/tracking keys entirely absent and assert formatters do not throw.

- [ ] **Step 3: Implement mode-first dynamic form in the existing dialog**

Form order:

1. Logistics type select.
2. Required itemDesc textarea with code-point counter and 120 limit.
3. EXPRESS only: searchable carrier selector using deliveryId as value and deliveryName as label, tracking input, optional consignor contact, and derived receiver-contact/SF help text.
4. Optional local shipment note.

Do not rely on HTML/Element Plus maxlength for itemDesc because JavaScript UTF-16 length counts some characters twice. The pure code-point helper is the validation and counter source; trim/limit by code point before submit.

Opening the dialog pre-fills itemDesc from order items using the pure helper, loads capability and cached carriers, and presents a carrier-sync action. Switching from EXPRESS to another mode clears express-only fields before submit. Never submit a carrier display name as expressCompanyCode.

- [ ] **Step 4: Render capability and result truthfully**

Show AVAILABLE, UNAVAILABLE, UNKNOWN, or configured-disabled state near the form without blocking local shipment. After submit:

- REAL + UPLOADED: success message says local shipment saved and real WeChat uploaded.
- MOCK + any status: warning explicitly says simulation/local test and never claims platform acceptance; the backend should normally return MOCK + UNAVAILABLE.
- SKIPPED/FAILED/UNAVAILABLE/UNKNOWN: warning says local shipment saved, names the safe platform state, and keeps the order detail visible.
- Never label a mock or unavailable response as real WeChat success.

Detail drawer renders mode-specific wording, itemDesc, carrier code/name/tracking for EXPRESS, local shipment state/time, provider mode, WeChat state/time, retry count, and safe admin error. Retry button appears only for FAILED, UNAVAILABLE, or eligible SKIPPED; hide it for UPLOADING, UPLOADED, UNKNOWN.

- [ ] **Step 5: Run admin tests, typecheck, and production build**

Run:

    cd admin
    pnpm exec tsx --test src/views/order/list/shipping-form.test.ts
    pnpm typecheck
    CI=true pnpm build

Expected GREEN: pure tests pass, typecheck emits no diagnostics, and Vite production build succeeds.

- [ ] **Step 6: Review, fix, re-review, and commit**

Spec review checks all four field sets and result messages. Code review checks stale express fields, carrier id/name mixups, async dialog races, generic success toasts, code-point count, and retry visibility. Fix all Critical/Important findings and rerun Step 5.

Commit:

    git add admin/src/api/order.ts admin/src/api/wechat-shipping.ts admin/src/types/api/api.d.ts admin/src/views/order/list
    git commit -m "feat: add admin logistics mode form"

---

### Task 11: Mini Program Order Center, Logistics Detail, And After-Sales

**Goal:** Make the existing order pages reachable and production-usable with status paging, resilient detail refresh, all four logistics labels, local receipt confirmation, and current-user after-sale list/detail pages.

**Files:**
- Modify: miniprogram/app.json
- Modify: miniprogram/types/api.ts
- Modify: miniprogram/services/order.ts
- Modify: miniprogram/services/aftersale.ts
- Create: miniprogram/features/order-center.ts
- Modify: miniprogram/pages/profile/profile.ts
- Modify: miniprogram/pages/profile/profile.wxml
- Modify: miniprogram/pages/profile/profile.wxss
- Modify: miniprogram/pages/order/list/list.json
- Modify: miniprogram/pages/order/list/list.ts
- Modify: miniprogram/pages/order/list/list.wxml
- Modify: miniprogram/pages/order/list/list.wxss
- Modify: miniprogram/pages/order/detail/detail.json
- Modify: miniprogram/pages/order/detail/detail.ts
- Modify: miniprogram/pages/order/detail/detail.wxml
- Modify: miniprogram/pages/order/detail/detail.wxss
- Create: miniprogram/pages/aftersale/list/list.json
- Create: miniprogram/pages/aftersale/list/list.ts
- Create: miniprogram/pages/aftersale/list/list.wxml
- Create: miniprogram/pages/aftersale/list/list.wxss
- Create: miniprogram/pages/aftersale/detail/detail.json
- Create: miniprogram/pages/aftersale/detail/detail.ts
- Create: miniprogram/pages/aftersale/detail/detail.wxml
- Create: miniprogram/pages/aftersale/detail/detail.wxss
- Create: miniprogram/tests/order-center.test.ts
- Create: miniprogram/tests/order-detail.test.ts

**Client contracts:**
- OrderStatusGroup is ALL | UNPAID | TO_SHIP | TO_RECEIVE | COMPLETED.
- getOrders accepts current, size, statusGroup, and the legacy optional exact status.
- confirmReceipt(orderId) calls POST /app/orders/{orderId}/confirm-receipt.
- listAfterSales and getAfterSaleDetail use the Task 7 current-user endpoints.
- OrderDetail types include receiver snapshot, payment fields, Shipment, latestAfterSale, and every nullable key time; all globally omitted nullable response properties use `?: T | null`.
- Shipment type uses LogisticsType 1..4, DeliveryMode 1, optional carrier/tracking, and all upload states. Tests pass non-express JSON with those keys absent and assert display formatters accept undefined.

- [ ] **Step 1: Add failing pure order-center and display tests**

Create order-center.test.ts:

~~~ts
test("profile exposes fixed order, after-sale, and address destinations", () => {
  assert.deepEqual(PROFILE_COMMERCE_ACTIONS.map(item => item.key), [
    "orders", "unpaid", "toShip", "toReceive", "afterSales", "addresses"
  ])
  assert.match(PROFILE_COMMERCE_ACTIONS[1].path, /statusGroup=UNPAID/)
  assert.match(PROFILE_COMMERCE_ACTIONS[2].path, /statusGroup=TO_SHIP/)
  assert.match(PROFILE_COMMERCE_ACTIONS[3].path, /statusGroup=TO_RECEIVE/)
})

test("page append deduplicates and preserves server order", () => {
  assert.deepEqual(
    mergeOrderPages([{ orderId: 3 }, { orderId: 2 }], [{ orderId: 2 }, { orderId: 1 }])
      .map(item => item.orderId),
    [3, 2, 1]
  )
})

test("all four logistics modes have user-facing labels", () => {
  assert.equal(formatShipmentMode(shipment(1)), "实体快递")
  assert.equal(formatShipmentMode(shipment(2)), "同城配送，无快递单号")
  assert.equal(formatShipmentMode(shipment(3)), "虚拟商品交付")
  assert.equal(formatShipmentMode(shipment(4)), "用户自提")
})
~~~

Create order-detail.test.ts to prove after-sale rejection leaves the mapped order detail intact, SHIPPED enables confirm receipt, COMPLETED permits after-sale when no active case, and internal WeChat error text is not returned by user-facing formatters.

Add status-tab-to-request assertions for every OrderStatusGroup; current/size/total/hasMore boundary cases; stale requestSequence results being discarded; overlapping reach-bottom coalescing; and next-page failure retaining the already rendered records plus a retry-more state.

Extend session.test.ts with sequential valid-session ensure/onShow simulation and assert loginCalls remains one while meCalls can refresh profile each time.

Run:

    cd miniprogram
    pnpm test

Expected RED: missing order-center helpers, groups, shipment fields, and after-sale routes.

- [ ] **Step 2: Align API types and service methods**

Add exact Task 7/9 response fields. Encode statusGroup with encodeURIComponent. Add confirmReceipt, listAfterSales({current,size,status}), and getAfterSaleDetail(id). Keep existing payment, cancel, sync, apply-after-sale, and per-order after-sale methods working.

Do not map PAID to a new backend enum: UI text may say 待发货 while the typed status remains PAID.

- [ ] **Step 3: Add profile navigation without duplicating pages**

Use PROFILE_COMMERCE_ACTIONS from the pure helper. 我的订单 opens the existing list with ALL; 待付款, 待发货, 待收货 pass their statusGroup. 我的售后 opens the new after-sale list. 收货地址 opens Task 6's existing address list in management mode.

Keep coupon entries. Repeated profile onShow uses Task 3 ensureSession + /me and never calls silentLogin directly.

- [ ] **Step 4: Implement status tabs and race-safe page loading**

The existing list page tracks current=1, size=10, selectedGroup, records, total, hasMore, initialLoading, nextPageLoading, refreshing, errorText, and requestSequence.

- onLoad parses initial statusGroup and defaults to ALL.
- onShow resets and refreshes unless an equivalent request is already active.
- Tab change resets current and records.
- onReachBottom appends one next page only when hasMore and no request is active.
- Merge by orderId to prevent duplicates.
- Pull-to-refresh resets and always stops in finally.
- Initial error keeps an explicit retry action; next-page error keeps existing records and exposes retry-more.
- Render distinct loading, empty, error, and content states.

- [ ] **Step 5: Split detail and after-sale loading**

onLoad only stores orderId. onShow always invokes loadDetail. The method first awaits getOrderDetail and immediately displays it. It then calls per-order after-sale independently:

~~~ts
const detail = await getOrderDetail(orderId)
this.applyDetail(detail)
try {
  const afterSales = await getOrderAfterSales(orderId)
  this.applyAfterSales(afterSales)
} catch (error) {
  this.setData({ afterSaleErrorText: toErrorMessage(error, "售后信息暂时无法加载") })
}
~~~

An after-sale error never sets detail=null. Enable enablePullDownRefresh in detail.json and stop the indicator in finally.

Render receiver snapshot, items/amounts, payment status/trade fields/paid time, four-mode shipment text, carrier/tracking only for EXPRESS, user-safe upload state, latest after-sale, and all available key times. Keep existing pay, cancel, and apply-after-sale actions. Add SHIPPED-only 确认收货 with confirmation dialog, idempotent endpoint call, and refresh.

- [ ] **Step 6: Add after-sale list and detail pages**

Register both pages. List uses current/size paging with loading/empty/error/retry and reach-bottom behavior. Detail shows type, status, requested/approved/refund amounts, reason/evidence, audit note, refund result, and times already returned by backend. Navigate from profile, order detail's latest case, and list rows. Reuse the existing apply page; do not create a second application flow.

- [ ] **Step 7: Run mini program tests and typecheck**

Run:

    cd miniprogram
    pnpm test:typecheck
    pnpm test
    pnpm typecheck

Expected GREEN: test typecheck, order-center/session/display runtime tests, and production typecheck all pass.

- [ ] **Step 8: Review, fix, re-review, and commit**

Spec review traverses every profile entry, tab, paging state, action, logistics mode, and after-sale page. Code review checks overlapping onShow/reach-bottom, detail clearing, pull-refresh finally, PAID label mapping, internal error leakage, and duplicate actions. Fix all Critical/Important findings and rerun Step 7.

Commit:

    git add miniprogram/app.json miniprogram/types/api.ts miniprogram/services/order.ts miniprogram/services/aftersale.ts miniprogram/features/order-center.ts miniprogram/pages/profile miniprogram/pages/order miniprogram/pages/aftersale miniprogram/tests
    git commit -m "feat: complete mini program order center"

---

### Task 12: Scheduled, Bounded, Failure-Isolated Payment Timeout Close

**Goal:** Turn the existing callable timeout-close method into a real configurable schedule that releases stock/coupons, processes bounded batches, and does not abandon later rows after one provider failure.

**Files:**
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/payment/PaymentTimeoutScanProperties.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/payment/config/PaymentTimeoutSchedulingConfiguration.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/PaymentTimeoutCloseScheduler.java
- Create: backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/PaymentTimeoutCloseWorker.java
- Modify: backend/shop-server/src/main/java/org/muybaby/shopserver/payment/service/PaymentTimeoutCloseService.java
- Modify: backend/shop-server/src/main/resources/application.yaml
- Modify: backend/shop-server/src/test/resources/application-test.yaml
- Modify: backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentTimeoutCloseServiceTest.java
- Create: backend/shop-server/src/test/java/org/muybaby/shopserver/payment/PaymentTimeoutCloseSchedulerTest.java

**Configuration contract:**

~~~yaml
shop:
  pay:
    timeout-scan-enabled: true
    timeout-scan-delay: 60s
    timeout-scan-batch-size: 50
~~~

Environment overrides are SHOP_PAY_TIMEOUT_SCAN_ENABLED, SHOP_PAY_TIMEOUT_SCAN_DELAY, and SHOP_PAY_TIMEOUT_SCAN_BATCH_SIZE. application-test.yaml sets timeout-scan-enabled=false so the background scheduler never races deterministic tests.

**Execution contract:**
- Scheduler exists only when timeout-scan-enabled=true.
- Each tick calls closeExpiredPayments(batchSize) once.
- Candidate query is ordered by expires_at/id and limited to batchSize.
- Each candidate is locked and processed in its own REQUIRES_NEW transaction.
- One provider/row failure is logged safely and the loop continues.
- Existing OrderCloseService performs idempotent stock/coupon release.

- [ ] **Step 1: Add failing scheduler, batch, and failure-isolation tests**

Required tests:

~~~java
@Test
void closesOnlyConfiguredBatchAndContinuesAfterOneFailure() {
    seedThreeExpiredPayments();
    mockWechatPayProvider.failFor(firstOutTradeNo);

    int closed = paymentTimeoutCloseService.closeExpiredPayments(2);

    assertThat(closed).isEqualTo(1);
    assertThat(status(firstPaymentId)).isEqualTo("PAYING");
    assertThat(status(secondPaymentId)).isEqualTo("CLOSED");
    assertThat(status(thirdPaymentId)).isEqualTo("PAYING");
}
~~~

PaymentTimeoutCloseSchedulerTest starts one context with timeout-scan-enabled=false and asserts no scheduler bean/registered task, then one with true and a long test delay and asserts:

- the scheduler bean exists.
- PaymentTimeoutScanProperties contains the configured delay and batch size.
- ScheduledAnnotationBeanPostProcessor/ScheduledTaskHolder reports the annotated scheduled task as registered.
- invoking runOnce directly passes the configured batch size to the service.

Keep wall-clock waiting out of assertions and close each context plus scheduler executor in finally.

Retain and expand the existing integration assertion that closed payment/order rows release stock_lock and the locked coupon exactly once. Repeat a tick and assert zero additional closes.

Run:

    cd backend/shop-server
    ./mvnw -Dtest='PaymentTimeoutCloseServiceTest,PaymentTimeoutCloseSchedulerTest' test

Expected RED: no scheduler/configuration, unbounded query, and one transaction/exception aborting all candidates.

- [ ] **Step 2: Bind validated scan properties and scheduler**

PaymentTimeoutScanProperties binds prefix shop.pay with timeoutScanEnabled, Duration timeoutScanDelay, and timeoutScanBatchSize. Reject nonpositive delay and sizes outside 1..500 at startup.

PaymentTimeoutSchedulingConfiguration enables scheduling and provides Clock.systemDefaultZone so existing LocalDateTime/database semantics do not shift. Tests override it with a fixed Clock. PaymentTimeoutCloseScheduler is conditional on timeout-scan-enabled=true and uses the configured delay. Its scheduled runOnce method delegates only; it does not contain business SQL.

- [ ] **Step 3: Refactor bounded coordinator and per-row worker**

Remove @Transactional from the batch coordinator. Query at most batchSize lightweight candidate ids ordered by expiry/id. Resolve payment configuration once per tick, then invoke a separate proxied PaymentTimeoutCloseWorker for each row inside try/catch.

Worker uses REQUIRES_NEW, locks only its candidate row, rechecks PAYING and expires_at against injected Clock, closes the WeChat payment, updates payment_order, and invokes OrderCloseService. A failure rolls back only that candidate. Do not log outTradeNo, credentials, request bodies, or authorization data.

- [ ] **Step 4: Run focused timeout and close regressions**

Run:

    cd backend/shop-server
    ./mvnw -Dtest='PaymentTimeoutCloseServiceTest,PaymentTimeoutCloseSchedulerTest,AppPaymentControllerTest,AppOrderControllerTest' test

Expected GREEN: bounded/failure-isolated/scheduled behavior passes and existing close/payment behavior remains green.

- [ ] **Step 5: Review, fix, re-review, and commit**

Spec review proves a real scheduled bean calls the existing business close. Code review checks self-invocation transaction loss, long batch transactions, duplicate release, unbounded scans, test races, and sensitive logs. Fix all Critical/Important findings and rerun Step 4.

Commit:

    git add backend/shop-server/src/main/java/org/muybaby/shopserver/payment backend/shop-server/src/main/resources/application.yaml backend/shop-server/src/test/resources/application-test.yaml backend/shop-server/src/test/java/org/muybaby/shopserver/payment
    git commit -m "feat: schedule payment timeout close"

---

### Task 13: Documentation, Full Verification, Real-Smoke Evidence, And Main Handoff

**Goal:** Document the new contracts and environment limitations, run fresh full automation, perform the available real local smoke separately, and leave every accepted task commit on the primary main checkout.

**Precondition:** Before the Task 13 implementer writes docs, the controller converges accepted Tasks 1-12 to /Users/muybaby/Project/Production/Shop main and stops any feature-worktree writers. Task 13 and all recorded verification therefore run only on primary main.

**Files:**
- Modify: docs/dev-setup.md
- Modify: docs/smoke-checks.md
- Modify: README.md
- Modify: docs/foundation-completion.md
- Create: docs/commerce-fulfillment-completion.md

**Documentation contract:**
- dev-setup documents V10, session storage key, legacy migration, refresh/logout/me, shipping enablement/capability/carrier sync, scheduler properties, and safe local configuration.
- smoke-checks has separate Automated Mock Verification and Real WeChat / DevTools Smoke sections.
- README links the current design and all phase plans including this spec/plan.
- foundation-completion no longer says auth/RBAC is the next unimplemented phase; it remains a historical foundation record and links current completion status.
- commerce-fulfillment-completion records exact commit ids, review/fix/re-review outcomes, test counts, build results, mock results, real results, external limitations, and deferred work.

- [ ] **Step 1: Update docs from implemented behavior, not planned assumptions**

Document exact endpoint examples without real tokens, phone numbers, openids, secrets, certificate paths, or screenshots. Include the four ship request shapes with synthetic data and show that non-express shapes omit express fields.

Document these safe operational checks:

- GET capability and list/sync carriers before real express upload.
- Local SHIPPED is authoritative even when WeChat is SKIPPED/FAILED/UNAVAILABLE/UNKNOWN.
- FAILED/UNAVAILABLE may retry; UPLOADING/UPLOADED/UNKNOWN may not ordinary-retry.
- Mock provider verifies code shape only and is never evidence of platform acceptance.
- Phone authorization is a user-click getPhoneNumber flow; capability/quota failure is non-blocking and never fabricated.

- [ ] **Step 2: Run every focused frontend behavior test**

Run:

    cd admin
    pnpm exec tsx --test src/views/order/list/shipping-form.test.ts

    cd ../miniprogram
    pnpm test:typecheck
    pnpm test

Record exact test/pass/fail counts in docs/commerce-fulfillment-completion.md. Any failure returns to its owning task and receives a fix plus re-review before continuing.

- [ ] **Step 3: Invoke verification-before-completion and run fresh full automation**

From the primary checkout run, without relying on prior output:

    cd backend/shop-server
    ./mvnw test

    cd ../../admin
    pnpm exec tsx --test src/views/order/list/shipping-form.test.ts
    pnpm typecheck
    CI=true pnpm build

    cd ../miniprogram
    pnpm test:typecheck
    pnpm test
    pnpm typecheck

    cd /Users/muybaby/Project/Production/Shop
    git diff --check
    git status --short --ignored

Capture Maven's Tests run, Failures, Errors, Skipped totals. Capture exit codes for admin typecheck/build and mini-program typecheck. Do not describe an ignored target/node_modules/dist/local env file as a tracked change.

- [ ] **Step 4: Perform real local mini-program commerce smoke separately**

Prerequisites must be recorded first: backend uses the dev profile and real WeChat clients rather than the test/mock profile; SHOP_WECHAT_SHIPPING_UPLOAD_ENABLED=true for the upload branch; WeChat DevTools uses the actual mini-program account; capability is queried; carrier sync result is inspected; and the account/environment identity is recorded only in masked form. With backend, admin, tunnel, WeChat DevTools, and an actual app account available, execute and record each as PASS, FAIL, BLOCKED_EXTERNAL, or NOT_RUN with evidence that contains no secrets:

1. Restart/reopen Profile repeatedly; network log shows no repeated /app/auth/login while /me refreshes profile.
2. The user explicitly taps getPhoneNumber; when account capability permits, a later restart still shows phoneNumberMasked. If capability/quota blocks it, record BLOCKED_EXTERNAL and the non-blocking message.
3. Direct buy uses selected quantity and a cart before/after comparison shows no cart mutation.
4. CART and DIRECT each create an order and source is correct.
5. Selected address is copied into the order snapshot; later address edit does not change it.
6. Profile entries open order list/status groups/detail/after-sales/addresses.
7. Detail can pay/cancel where eligible, show payment/logistics, confirm receipt, and apply after-sale.
8. Admin submits EXPRESS, LOCAL_DELIVERY, VIRTUAL, and PICKUP with their dynamic fields; record redacted order/shipment ids, providerMode, local state before/after, upload state, and safe errcode only.
9. If WeChat rejects an unfiled/unapproved account, local order remains SHIPPED, upload state/error are stored, and only safe retry states show a retry action.
10. No mock success or external-capability failure is reported as real upload success.

When capability is REAL/AVAILABLE, attempt at least one upload through the REAL provider and record the actual result. Only a real explicit errcode=0 is recorded as REAL + UPLOADED; FAILED/UNKNOWN is reported honestly and real success is not a code-completion gate. If phone/shipping capability, filing, or account approval is unavailable, mark only the platform step BLOCKED_EXTERNAL and still verify the local non-blocking state. Never alter database status to manufacture a passing smoke. Mock/MockRestServiceServer evidence never satisfies this branch.

- [ ] **Step 5: Inspect sensitive and ignored artifacts before documentation commit**

First stage only the five documented files, then run:

    cd /Users/muybaby/Project/Production/Shop
    git add docs/dev-setup.md docs/smoke-checks.md README.md docs/foundation-completion.md docs/commerce-fulfillment-completion.md
    git diff --cached --check
    git diff --cached --name-only
    git diff --cached -- docs README.md
    git status --short --ignored

Inspect staged paths/content and ensure none are .env.local, token/secret/certificate/private-key material, raw phone/openid data, target, node_modules, dist, upload roots, or smoke screenshots.

- [ ] **Step 6: Review docs, fix, re-verify, and commit**

Spec review cross-checks every acceptance item against code/test/smoke evidence. Code/document review rejects stale counts, unverified claims, secret-bearing examples, and any wording that conflates mock with real WeChat. Fix all Critical/Important findings, rerun affected verification, and re-review.

After any review fix, repeat Step 5's exact git add and cached-diff checks so the committed index contains the reviewed version.

Commit:

    git commit -m "docs: verify commerce fulfillment phase"

- [ ] **Step 7: Converge and re-run on the primary main checkout**

Verify every design, plan, and Task 1-13 commit is an ancestor of primary main. Rerun Step 3 in full, including both frontend behavior-test commands, and then:

    git log --oneline --decorate -n 20
    git status --short --branch
    git status --short --ignored

Expected final tracked state: clean. Ignored local development files may remain and must be reported separately.

If the final main rerun differs from the evidence recorded before the docs commit, update commerce-fulfillment-completion.md with the actual main result, re-review it, commit a narrowly named documentation correction, and rerun the affected command. Never leave a stale green count in the completion document.

---

## Approved Plan Preflight Commit

Before Task 1 or any functional subagent starts, the controller performs this documentation-only gate on primary main:

    cd /Users/muybaby/Project/Production/Shop
    git diff --check
    git status --short --branch
    git add docs/superpowers/plans/2026-07-09-shop-mini-program-commerce-fulfillment-implementation-plan.md
    git diff --cached --check
    git diff --cached --name-only
    git commit -m "docs: plan mini program commerce fulfillment"
    git merge-base --is-ancestor de666058 main
    git log --oneline --decorate -n 3

Expected staged path before commit: exactly this implementation plan. The approved design commit de666058 and the new plan commit must both be ancestors of main. Only after this gate does superpowers:subagent-driven-development begin.

---

## Subagent-Driven Execution Protocol

The controller executes Tasks 1 through 13 sequentially. No two implementation agents edit the same checkout concurrently.

For each task:

1. Start a fresh implementation subagent at the last accepted commit with only that task's files and acceptance tests in scope.
2. Require the subagent to show the RED command/output before production implementation.
3. Require minimal GREEN implementation and the focused command from the task.
4. Stop implementation writes and dispatch a spec-review subagent against the approved design plus task diff.
5. Dispatch a separate code-review subagent against the same diff and test output.
6. Send every Critical/Important finding back to that task's implementer; do not waive it without concrete contradictory evidence.
7. Rerun the covering tests and send the corrected diff back to the relevant reviewer for re-review.
8. Commit only after both reviews have no unresolved Critical/Important findings.
9. Record commit id, tests, review findings, fixes, and re-review result before starting the next task.

Review agents are read-only. A later implementation task begins only after the earlier task commit is accepted. Tasks 2/3, 4/5/6, 5/7/9, 8/9/10, and 3/6/11 are explicitly serialized because they share session, checkout, order, logistics, or mini-program surfaces.

## Commit Sequence

1. feat: add commerce fulfillment schema
2. feat: add app session lifecycle
3. feat: add mini program session recovery
4. feat: add app address book
5. feat: add direct checkout and address snapshots
6. feat: add mini program direct checkout
7. feat: complete app order center backend
8. feat: add wechat shipping gateway
9. feat: add four-mode shipment workflow
10. feat: add admin logistics mode form
11. feat: complete mini program order center
12. feat: schedule payment timeout close
13. docs: verify commerce fulfillment phase

The design and implementation-plan commits precede this sequence and remain separate documentation commits.

## Acceptance-To-Test Map

| Acceptance area | Automated proof | Real smoke proof |
| --- | --- | --- |
| Stable app session | App auth rotation tests; mini session/request recovery tests | Reopen Profile; inspect login/me calls |
| Masked phone restore | Backend profile consistency tests; client storage tests | Restart bound account and view masked number |
| CART and DIRECT | Checkout controller/service tests; mini checkout tests | Create both orders; compare cart before/after DIRECT |
| Address ownership/snapshot | Address service tests; order snapshot tests | Select/edit address and compare immutable order detail |
| Order center | Status paging/detail/receipt/after-sale tests; mini helper tests | Navigate tabs/detail/actions/after-sales |
| Four logistics modes | Exact mock HTTP JSON; local persistence/retry tests; admin helper tests | Submit four modes; inspect local rows and UI |
| Platform limitation | Capability/error response tests | Record real available/unavailable response separately |
| Timeout close | Scheduler, batch, failure-isolation, stock/coupon release tests | Optional controlled expired-order observation |

## Final Verification Matrix

Automated completion requires all commands below on primary main:

    (cd /Users/muybaby/Project/Production/Shop/backend/shop-server && ./mvnw test)
    (cd /Users/muybaby/Project/Production/Shop/admin && pnpm exec tsx --test src/views/order/list/shipping-form.test.ts)
    (cd /Users/muybaby/Project/Production/Shop/admin && pnpm typecheck)
    (cd /Users/muybaby/Project/Production/Shop/admin && CI=true pnpm build)
    (cd /Users/muybaby/Project/Production/Shop/miniprogram && pnpm test:typecheck)
    (cd /Users/muybaby/Project/Production/Shop/miniprogram && pnpm test)
    (cd /Users/muybaby/Project/Production/Shop/miniprogram && pnpm typecheck)
    (cd /Users/muybaby/Project/Production/Shop && git diff --check)
    (cd /Users/muybaby/Project/Production/Shop && git status --short --ignored)

Completion reporting must list the spec and plan paths, every task commit, review/fix/re-review status, backend test totals and failures, admin/miniprogram results, mock-versus-real WeChat results, external filing/capability limits, final tracked status, ignored local files, and all deliberately deferred next-phase items.

Deferred by design: split shipment/multiple packages, delivery dispatch, virtual-entitlement issuance, pickup verification, live third-party tracking, multi-warehouse inventory, loyalty points/new marketing, WeChat receipt-component dependency, and any payment/refund rewrite.
