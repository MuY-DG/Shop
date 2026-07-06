# Shop Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the monorepo foundation for the hotpot shop system: Spring Boot backend, Art Design Pro admin, native WeChat mini program shell, and shared development conventions.

**Architecture:** This is the first implementation plan from the product design spec. It does not implement product, order, payment, shipment, or refund business behavior. It creates runnable foundations for all three applications and locks the shared API contract so subsequent feature plans can build vertically.

**Tech Stack:** Java 21 target, Spring Boot 3.5.16, Spring Security, MyBatis-Plus 3.5.16, Flyway, MySQL, Redis, Art Design Pro, Vue 3, TypeScript, Vite, pnpm, native WeChat mini program TypeScript, TDesign MiniProgram.

---

## Scope Boundary

The approved design covers a full commerce system across backend, admin, and mini program. A single implementation plan for the entire system would be too large to execute safely. This plan covers Milestone 0 and the minimum foundation for Milestone 1:

- Repository toolchain conventions.
- Spring Boot backend skeleton with health endpoint, unified response envelope, error envelope, and security baseline.
- Art Design Pro source scaffold with backend access mode configured.
- Native WeChat mini program shell with request wrapper and shell pages.
- Cross-app smoke checks.

Separate implementation plans will follow for:

- Authentication and RBAC.
- Product catalog.
- Cart, checkout, and coupon.
- Order and WeChat Pay.
- WeChat shipping upload.
- After-sale and WeChat refund.

## References

- Product design: `docs/superpowers/specs/2026-07-06-hotpot-shop-design.md`
- Art Design Pro quick start: https://www.artd.pro/docs/zh/guide/quick-start.html
- Art Design Pro must-read: https://www.artd.pro/docs/zh/guide/must-read.html
- Art Design Pro GitHub repository: https://github.com/Daymychen/art-design-pro

## File Structure

Planned files and responsibilities:

```text
Shop/
  .editorconfig                         Shared editor defaults.
  .java-version                         Java target marker for local tools.
  .nvmrc                                Node target marker for admin tooling.
  README.md                             Monorepo overview and dev commands.
  docs/dev-setup.md                     Local setup and verification guide.
  docs/superpowers/plans/...            This implementation plan.

  backend/
    shop-server/
      pom.xml                           Maven project and dependency versions.
      mvnw, mvnw.cmd, .mvn/             Maven wrapper from the user-created scaffold.
      src/main/java/org/muybaby/shopserver/
        ShopServerApplication.java      Spring Boot entrypoint.
        common/api/ApiResponse.java     `{ code, msg, data }` envelope.
        common/api/PageResult.java      Admin table page envelope.
        common/error/ErrorCode.java     Stable business error codes.
        common/error/BusinessException.java
        common/error/GlobalExceptionHandler.java
        common/web/RequestIdFilter.java Request id logging context.
        security/SecurityConfig.java    Initial Spring Security filter chain.
        health/HealthController.java    `/app/health` smoke endpoint.
      src/main/resources/
        application.yaml                Shared config.
        application-dev.yaml            Dev datasource and Redis sample config.
        db/migration/V1__init_foundation.sql
      src/test/java/org/muybaby/shopserver/
        common/api/ApiResponseTest.java
        health/HealthControllerTest.java
        security/SecurityConfigTest.java

  admin/
    package.json                        Art Design Pro package metadata from upstream.
    .env                                Access mode changed to backend.
    src/                                Art Design Pro source from upstream.

  miniprogram/
    package.json                        Mini program npm dependencies.
    tsconfig.json                       TypeScript compiler config.
    project.config.json                 WeChat DevTools project config.
    app.json                            Page registration and window config.
    app.ts                              Mini program bootstrap.
    app.wxss                            Global styles.
    sitemap.json                        WeChat sitemap config.
    utils/request.ts                    Unified request wrapper.
    types/api.ts                        Shared response and request types.
    pages/home/*                        Home shell page.
    pages/profile/*                     Profile shell page.
    pages/order/detail/*                Order detail shell page for WeChat message jump path.
```

## Task 1: Root Toolchain Conventions

**Files:**

- Create: `.editorconfig`
- Create: `.java-version`
- Create: `.nvmrc`
- Create: `docs/dev-setup.md`
- Modify: `README.md`

- [ ] **Step 1: Write the toolchain files**

Create `.editorconfig`:

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 2
trim_trailing_whitespace = true

[*.java]
indent_size = 4

[*.{yml,yaml}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false
```

Create `.java-version`:

```text
21
```

Create `.nvmrc`:

```text
20.19.0
```

- [ ] **Step 2: Write local setup guide**

Create `docs/dev-setup.md`:

````markdown
# Development Setup

## Runtime Targets

- Java source target: 21
- Node.js target for admin tooling: 20.19.0 or newer
- Package manager: pnpm

## Repository Layout

```text
Shop/
  backend/shop-server/  Spring Boot backend
  admin/                Art Design Pro admin console
  miniprogram/          Native WeChat mini program
  docs/                 Design and implementation docs
```

## Backend Checks

```bash
cd backend/shop-server
./mvnw test
```

Expected result:

```text
BUILD SUCCESS
```

## Admin Checks

```bash
cd admin
pnpm install
pnpm build
```

Expected result: Vite production build completes without TypeScript or bundling errors.

## Mini Program Checks

```bash
cd miniprogram
pnpm install
pnpm typecheck
```

Expected result: TypeScript completes without diagnostics.

## Local Secret Policy

Do not commit real WeChat app secrets, merchant certificates, private keys, database passwords, Redis passwords, or production URLs. Use local environment files ignored by Git.
````

- [ ] **Step 3: Update README**

Replace `README.md` with:

````markdown
# Hotpot Shop

WeChat mini program commerce system for selling hotpot base products.

## Structure

```text
Shop/
  backend/shop-server/  Spring Boot backend
  admin/                Art Design Pro admin console
  miniprogram/          Native WeChat mini program
  docs/                 Product and technical design docs
```

## Current Design

- [Hotpot Shop WeChat Mini Program Design](docs/superpowers/specs/2026-07-06-hotpot-shop-design.md)

## Implementation Plans

- [Shop Foundation Implementation Plan](docs/superpowers/plans/2026-07-06-shop-foundation-implementation-plan.md)

## Development

See [Development Setup](docs/dev-setup.md).
````

- [ ] **Step 4: Verify root files**

Run:

```bash
git diff -- .editorconfig .java-version .nvmrc README.md docs/dev-setup.md
```

Expected:

```text
diff --git ...
```

- [ ] **Step 5: Commit root conventions**

Run:

```bash
git add .editorconfig .java-version .nvmrc README.md docs/dev-setup.md
git commit -m "chore: add repository development conventions"
```

Expected:

```text
[main ...] chore: add repository development conventions
```

## Task 2: Backend Spring Boot Scaffold

**Files:**

- Delete: `backend/.gitkeep`
- Keep: `backend/shop-server/.gitattributes`
- Keep: `backend/shop-server/.gitignore`
- Keep: `backend/shop-server/pom.xml`
- Keep: `backend/shop-server/mvnw`
- Keep: `backend/shop-server/mvnw.cmd`
- Keep: `backend/shop-server/.mvn/wrapper/maven-wrapper.properties`
- Keep: `backend/shop-server/src/main/java/org/muybaby/shopserver/ShopServerApplication.java`
- Modify: `backend/shop-server/pom.xml`
- Modify: `backend/shop-server/src/main/resources/application.yaml`
- Modify: `backend/shop-server/src/test/java/org/muybaby/shopserver/ShopServerApplicationTests.java`
- Create: `backend/shop-server/src/main/resources/application-dev.yaml`
- Create: `backend/shop-server/src/test/resources/application-test.yaml`

- [ ] **Step 1: Adopt the existing user-created scaffold**

The backend project already exists at `backend/shop-server` with group `org.muybaby`, artifact `shop-server`, Java 21, Spring Boot `3.5.16`, and package `org.muybaby.shopserver`.

Run:

```bash
find backend/shop-server -maxdepth 4 -type f | sort
git rm backend/.gitkeep
```

Expected:

```text
backend/shop-server/.gitattributes
backend/shop-server/.gitignore
backend/shop-server/.mvn/wrapper/maven-wrapper.properties
backend/shop-server/mvnw
backend/shop-server/mvnw.cmd
backend/shop-server/pom.xml
backend/shop-server/src/main/resources/application.yaml
rm 'backend/.gitkeep'
```

- [ ] **Step 2: Add backend dependencies**

Modify `backend/shop-server/pom.xml`. Keep the existing Spring Boot parent and existing `spring-boot-starter-security`, `spring-boot-starter-web`, `springdoc-openapi-starter-webmvc-ui`, `mysql-connector-j`, `lombok`, `spring-boot-starter-test`, and `spring-security-test` dependencies. Keep `springdoc-openapi-starter-webmvc-ui` pinned to version `2.8.17`, because it is not managed by the Spring Boot parent. Add these dependencies inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.16</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
<dependency>
    <groupId>com.github.wechatpay-apiv3</groupId>
    <artifactId>wechatpay-java</artifactId>
    <version>0.2.17</version>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Configure application YAML**

Replace `backend/shop-server/src/main/resources/application.yaml` with:

```yaml
spring:
  application:
    name: shop-server
  profiles:
    active: dev
  jackson:
    default-property-inclusion: non_null
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true

shop:
  api:
    success-code: 200
    success-message: success
```

Create `backend/shop-server/src/main/resources/application-dev.yaml`:

```yaml
spring:
  datasource:
    url: ${SHOP_DB_URL:jdbc:mysql://127.0.0.1:3306/hotpot_shop?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}
    username: ${SHOP_DB_USERNAME:root}
    password: ${SHOP_DB_PASSWORD:123456}
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      database: 0

logging:
  level:
    org.muybaby.shopserver: debug
```

- [ ] **Step 4: Prepare the local development database**

Run:

```bash
mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS hotpot_shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

Expected:

```text
Query OK
```

- [ ] **Step 5: Add test profile config**

Create `backend/shop-server/src/test/resources/application-test.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:shop_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
    username: sa
    password:
    driver-class-name: org.h2.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  flyway:
    enabled: false
```

- [ ] **Step 6: Make generated context test use the test profile**

Replace `backend/shop-server/src/test/java/org/muybaby/shopserver/ShopServerApplicationTests.java` with:

```java
package org.muybaby.shopserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShopServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
```

- [ ] **Step 7: Run generated backend test**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 8: Commit backend scaffold**

Run:

```bash
git add backend
git commit -m "chore: scaffold Spring Boot backend"
```

Expected:

```text
[main ...] chore: scaffold Spring Boot backend
```

## Task 3: Backend API Envelope And Health Endpoint

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/api/ApiResponse.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/api/PageResult.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/health/HealthController.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/common/api/ApiResponseTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/health/HealthControllerTest.java`

- [ ] **Step 1: Write failing response tests**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/common/api/ApiResponseTest.java`:

```java
package org.muybaby.shopserver.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void successUsesArtDesignProEnvelope() {
        ApiResponse<String> response = ApiResponse.success("ok");

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.msg()).isEqualTo("success");
        assertThat(response.data()).isEqualTo("ok");
    }

    @Test
    void pageResultUsesAdminTableFields() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 2, 1, 10);

        assertThat(page.records()).containsExactly("a", "b");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.current()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: Run response tests to verify failure**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=ApiResponseTest test
```

Expected:

```text
cannot find symbol
```

- [ ] **Step 3: Implement response records**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/common/api/ApiResponse.java`:

```java
package org.muybaby.shopserver.common.api;

public record ApiResponse<T>(int code, String msg, T data) {

    private static final int SUCCESS_CODE = 200;
    private static final String SUCCESS_MESSAGE = "success";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> fail(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/common/api/PageResult.java`:

```java
package org.muybaby.shopserver.common.api;

import java.util.List;

public record PageResult<T>(List<T> records, long total, long current, long size) {

    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        return new PageResult<>(records, total, current, size);
    }
}
```

- [ ] **Step 4: Verify response tests pass**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=ApiResponseTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Write failing health controller test**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/health/HealthControllerTest.java`:

```java
package org.muybaby.shopserver.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appHealthReturnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/app/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("success")))
                .andExpect(jsonPath("$.data.status", is("UP")))
                .andExpect(jsonPath("$.data.service", is("shop-server")));
    }
}
```

- [ ] **Step 6: Run health test to verify failure**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=HealthControllerTest test
```

Expected:

```text
cannot find symbol
```

- [ ] **Step 7: Implement health controller**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/health/HealthController.java`:

```java
package org.muybaby.shopserver.health;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/app/health")
    public ApiResponse<HealthStatus> health() {
        return ApiResponse.success(new HealthStatus("UP", "shop-server"));
    }

    public record HealthStatus(String status, String service) {
    }
}
```

- [ ] **Step 8: Verify backend API foundation tests**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=ApiResponseTest,HealthControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 9: Commit API envelope and health endpoint**

Run:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/common/api backend/shop-server/src/main/java/org/muybaby/shopserver/health backend/shop-server/src/test/java/org/muybaby/shopserver/common/api backend/shop-server/src/test/java/org/muybaby/shopserver/health
git commit -m "feat: add backend API envelope and health endpoint"
```

Expected:

```text
[main ...] feat: add backend API envelope and health endpoint
```

## Task 4: Backend Error Handling And Security Baseline

**Files:**

- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/BusinessException.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/GlobalExceptionHandler.java`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/common/error/GlobalExceptionHandlerTest.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java`

- [ ] **Step 1: Write failing error handler test**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/common/error/GlobalExceptionHandlerTest.java`:

```java
package org.muybaby.shopserver.common.error;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void businessExceptionReturnsStableCodeAndMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.STOCK_SHORTAGE)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(200100);
        assertThat(response.getBody().msg()).isEqualTo("Stock shortage");
    }
}
```

- [ ] **Step 2: Run error handler test to verify failure**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=GlobalExceptionHandlerTest test
```

Expected:

```text
cannot find symbol
```

- [ ] **Step 3: Implement error handling**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/ErrorCode.java`:

```java
package org.muybaby.shopserver.common.error;

public enum ErrorCode {
    AUTHENTICATION_REQUIRED(100001, "Authentication required"),
    PERMISSION_DENIED(100003, "Permission denied"),
    VALIDATION_FAILED(100400, "Validation failed"),
    PRODUCT_UNAVAILABLE(200001, "Product unavailable"),
    SKU_UNAVAILABLE(200002, "SKU unavailable"),
    STOCK_SHORTAGE(200100, "Stock shortage"),
    COUPON_UNAVAILABLE(300001, "Coupon unavailable"),
    ORDER_STATE_CONFLICT(400001, "Order state conflict"),
    PAYMENT_PENDING(500001, "Payment pending"),
    WECHAT_SHIPPING_UPLOAD_FAILED(600001, "WeChat shipping upload failed"),
    WECHAT_REFUND_FAILED(700001, "WeChat refund failed");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/BusinessException.java`:

```java
package org.muybaby.shopserver.common.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/common/error/GlobalExceptionHandler.java`:

```java
package org.muybaby.shopserver.common.error;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.errorCode();
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException() {
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }
}
```

- [ ] **Step 4: Verify error handler test passes**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=GlobalExceptionHandlerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Write failing security tests**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/security/SecurityConfigTest.java`:

```java
package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appHealthIsPublic() throws Exception {
        mockMvc.perform(get("/app/health"))
                .andExpect(status().isOk());
    }

    @Test
    void adminApisRequireAuthentication() throws Exception {
        mockMvc.perform(get("/admin/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appApisExceptHealthRequireAuthentication() throws Exception {
        mockMvc.perform(get("/app/probe"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 6: Run security tests to verify failure**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=SecurityConfigTest test
```

Expected:

```text
Status expected:<401> but was:<403>
```

- [ ] **Step 7: Implement security config**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/security/SecurityConfig.java`:

```java
package org.muybaby.shopserver.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/app/health", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/admin/**", "/app/**").authenticated()
                        .anyRequest().permitAll())
                .build();
    }
}
```

- [ ] **Step 8: Verify backend foundation tests**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 9: Commit error handling and security baseline**

Run:

```bash
git add backend/shop-server/src/main/java/org/muybaby/shopserver/common/error backend/shop-server/src/main/java/org/muybaby/shopserver/security backend/shop-server/src/test/java/org/muybaby/shopserver/common/error backend/shop-server/src/test/java/org/muybaby/shopserver/security
git commit -m "feat: add backend error handling and security baseline"
```

Expected:

```text
[main ...] feat: add backend error handling and security baseline
```

## Task 5: Backend Migration And Request Id Baseline

**Files:**

- Create: `backend/shop-server/src/main/resources/db/migration/V1__init_foundation.sql`
- Create: `backend/shop-server/src/main/java/org/muybaby/shopserver/common/web/RequestIdFilter.java`
- Create: `backend/shop-server/src/test/java/org/muybaby/shopserver/common/web/RequestIdFilterTest.java`

- [ ] **Step 1: Add initial migration**

Create `backend/shop-server/src/main/resources/db/migration/V1__init_foundation.sql`:

```sql
CREATE TABLE system_health_marker (
    id BIGINT PRIMARY KEY,
    marker_key VARCHAR(64) NOT NULL,
    marker_value VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO system_health_marker (id, marker_key, marker_value)
VALUES (1, 'schema', 'foundation');
```

- [ ] **Step 2: Write failing request id filter test**

Create `backend/shop-server/src/test/java/org/muybaby/shopserver/common/web/RequestIdFilterTest.java`:

```java
package org.muybaby.shopserver.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestIdFilterTest {

    @Test
    void keepsIncomingRequestId() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        request.addHeader("X-Request-Id", "req-123");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123");
        verify(chain).doFilter(request, response);
    }

    @Test
    void createsRequestIdWhenMissing() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        verify(chain).doFilter(request, response);
    }
}
```

- [ ] **Step 3: Run request id test to verify failure**

Run:

```bash
cd backend/shop-server
./mvnw -Dtest=RequestIdFilterTest test
```

Expected:

```text
cannot find symbol
```

- [ ] **Step 4: Implement request id filter**

Create `backend/shop-server/src/main/java/org/muybaby/shopserver/common/web/RequestIdFilter.java`:

```java
package org.muybaby.shopserver.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter implements Filter {

    public static final String HEADER_NAME = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String requestId = resolveRequestId(request);

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_NAME);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }
}
```

- [ ] **Step 5: Verify backend tests**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit migration and request id filter**

Run:

```bash
git add backend/shop-server/src/main/resources/db/migration backend/shop-server/src/main/java/org/muybaby/shopserver/common/web backend/shop-server/src/test/java/org/muybaby/shopserver/common/web
git commit -m "chore: add backend migration and request id baseline"
```

Expected:

```text
[main ...] chore: add backend migration and request id baseline
```

## Task 6: Art Design Pro Admin Scaffold

**Files:**

- Delete: `admin/.gitkeep`
- Create/Modify: files copied from `https://github.com/Daymychen/art-design-pro`
- Modify: `admin/.env`
- Create: `admin/SHOP_NOTES.md`

- [ ] **Step 1: Copy Art Design Pro source**

Run:

```bash
git clone --depth 1 https://github.com/Daymychen/art-design-pro /tmp/art-design-pro
rsync -a --exclude .git /tmp/art-design-pro/ admin/
git rm admin/.gitkeep
```

Expected:

```text
rm 'admin/.gitkeep'
```

- [ ] **Step 2: Set backend access mode**

Run:

```bash
perl -0pi -e 's/VITE_ACCESS_MODE = frontend/VITE_ACCESS_MODE = backend/' admin/.env
```

Then run:

```bash
rg -n "VITE_ACCESS_MODE = backend" admin/.env
```

Expected:

```text
admin/.env:1:...VITE_ACCESS_MODE = backend...
```

- [ ] **Step 3: Add admin integration notes**

Create `admin/SHOP_NOTES.md`:

````markdown
# Shop Admin Notes

This directory is based on Art Design Pro.

## Required Backend Contracts

All JSON APIs use:

```json
{
  "code": 200,
  "msg": "success",
  "data": {}
}
```

Admin table pages expect page data inside `data`:

```json
{
  "records": [],
  "total": 0,
  "current": 1,
  "size": 10
}
```

Access mode is configured in `.env`:

```env
VITE_ACCESS_MODE = backend
```

Backend menu APIs must return route records with `name`, `path`, `component`, `meta`, and optional `children`.
````

- [ ] **Step 4: Install admin dependencies**

Run:

```bash
cd admin
pnpm install
```

Expected:

```text
Done in
```

If the install fails on lifecycle scripts, run the Art Design Pro documented fallback:

```bash
cd admin
pnpm install --ignore-scripts
```

Expected:

```text
Done in
```

- [ ] **Step 5: Build admin**

Run:

```bash
cd admin
pnpm build
```

Expected:

```text
✓ built in
```

- [ ] **Step 6: Commit admin scaffold**

Run:

```bash
git add admin
git commit -m "chore: scaffold Art Design Pro admin"
```

Expected:

```text
[main ...] chore: scaffold Art Design Pro admin
```

## Task 7: Native WeChat Mini Program Scaffold

**Files:**

- Delete: `miniprogram/.gitkeep`
- Create: `miniprogram/package.json`
- Create: `miniprogram/tsconfig.json`
- Create: `miniprogram/project.config.json`
- Create: `miniprogram/app.json`
- Create: `miniprogram/app.ts`
- Create: `miniprogram/app.wxss`
- Create: `miniprogram/sitemap.json`
- Create: `miniprogram/types/api.ts`
- Create: `miniprogram/utils/request.ts`
- Create: `miniprogram/pages/home/home.json`
- Create: `miniprogram/pages/home/home.wxml`
- Create: `miniprogram/pages/home/home.wxss`
- Create: `miniprogram/pages/home/home.ts`
- Create: `miniprogram/pages/profile/profile.json`
- Create: `miniprogram/pages/profile/profile.wxml`
- Create: `miniprogram/pages/profile/profile.wxss`
- Create: `miniprogram/pages/profile/profile.ts`
- Create: `miniprogram/pages/order/detail/detail.json`
- Create: `miniprogram/pages/order/detail/detail.wxml`
- Create: `miniprogram/pages/order/detail/detail.wxss`
- Create: `miniprogram/pages/order/detail/detail.ts`

- [ ] **Step 1: Write mini program package files**

Run:

```bash
git rm miniprogram/.gitkeep
```

Expected:

```text
rm 'miniprogram/.gitkeep'
```

Create `miniprogram/package.json`:

```json
{
  "name": "hotpot-shop-miniprogram",
  "version": "0.1.0",
  "private": true,
  "scripts": {
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "tdesign-miniprogram": "^1.9.8"
  },
  "devDependencies": {
    "typescript": "^5.9.0",
    "miniprogram-api-typings": "^4.0.8"
  }
}
```

Create `miniprogram/tsconfig.json`:

```json
{
  "compilerOptions": {
    "strict": true,
    "target": "ES2020",
    "module": "CommonJS",
    "moduleResolution": "Node",
    "types": ["miniprogram-api-typings"],
    "baseUrl": ".",
    "paths": {
      "@/*": ["./*"]
    },
    "skipLibCheck": true,
    "esModuleInterop": true,
    "forceConsistentCasingInFileNames": true
  },
  "include": ["./**/*.ts"]
}
```

- [ ] **Step 2: Write WeChat project files**

Create `miniprogram/project.config.json`:

```json
{
  "description": "Hotpot Shop Mini Program",
  "packOptions": {
    "ignore": []
  },
  "setting": {
    "urlCheck": true,
    "es6": true,
    "enhance": true,
    "postcss": true,
    "minified": true,
    "compileHotReLoad": false
  },
  "compileType": "miniprogram",
  "libVersion": "latest",
  "appid": "touristappid",
  "projectname": "hotpot-shop-miniprogram",
  "condition": {}
}
```

Create `miniprogram/app.json`:

```json
{
  "pages": [
    "pages/home/home",
    "pages/profile/profile",
    "pages/order/detail/detail"
  ],
  "window": {
    "navigationBarTitleText": "火锅底料商城",
    "navigationBarBackgroundColor": "#b3261e",
    "navigationBarTextStyle": "white",
    "backgroundColor": "#f7f3ee"
  },
  "tabBar": {
    "color": "#6b5f58",
    "selectedColor": "#b3261e",
    "backgroundColor": "#ffffff",
    "list": [
      {
        "pagePath": "pages/home/home",
        "text": "首页"
      },
      {
        "pagePath": "pages/profile/profile",
        "text": "我的"
      }
    ]
  },
  "usingComponents": {}
}
```

Create `miniprogram/sitemap.json`:

```json
{
  "rules": [
    {
      "action": "allow",
      "page": "*"
    }
  ]
}
```

- [ ] **Step 3: Write app bootstrap**

Create `miniprogram/app.ts`:

```ts
App<IAppOption>({
  globalData: {
    apiBaseUrl: "http://localhost:8080",
    token: ""
  },
  onLaunch() {
    wx.getSystemInfo({
      success: (info) => {
        this.globalData.systemInfo = info;
      }
    });
  }
});

interface IAppOption {
  globalData: {
    apiBaseUrl: string;
    token: string;
    systemInfo?: WechatMiniprogram.SystemInfo;
  };
}
```

Create `miniprogram/app.wxss`:

```css
page {
  min-height: 100%;
  background: #f7f3ee;
  color: #241f1c;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif;
}

.page {
  min-height: 100vh;
  padding: 32rpx;
  box-sizing: border-box;
}

.section-title {
  font-size: 36rpx;
  font-weight: 700;
  margin-bottom: 16rpx;
}

.muted {
  color: #7a6f68;
  font-size: 26rpx;
}
```

- [ ] **Step 4: Write API types and request wrapper**

Create `miniprogram/types/api.ts`:

```ts
export interface ApiResponse<T> {
  code: number;
  msg: string;
  data: T;
}

export interface RequestOptions<TBody = unknown> {
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  data?: TBody;
  auth?: boolean;
}
```

Create `miniprogram/utils/request.ts`:

```ts
import type { ApiResponse, RequestOptions } from "../types/api";

const SUCCESS_CODE = 200;

export function request<TData, TBody = unknown>(options: RequestOptions<TBody>): Promise<TData> {
  const app = getApp<{
    globalData: {
      apiBaseUrl: string;
      token: string;
    };
  }>();
  const headers: Record<string, string> = {
    "Content-Type": "application/json"
  };

  if (options.auth !== false && app.globalData.token) {
    headers.Authorization = `Bearer ${app.globalData.token}`;
  }

  return new Promise<TData>((resolve, reject) => {
    wx.request<ApiResponse<TData>>({
      url: `${app.globalData.apiBaseUrl}${options.url}`,
      method: options.method ? options.method : "GET",
      data: options.data,
      header: headers,
      success: (response) => {
        const body = response.data;
        if (body && body.code === SUCCESS_CODE) {
          resolve(body.data);
          return;
        }
        reject(new Error(body?.msg || "请求失败"));
      },
      fail: (error) => {
        reject(new Error(error.errMsg));
      }
    });
  });
}
```

- [ ] **Step 5: Write home page**

Create `miniprogram/pages/home/home.json`:

```json
{
  "navigationBarTitleText": "火锅底料商城"
}
```

Create `miniprogram/pages/home/home.wxml`:

```xml
<view class="page home-page">
  <view class="hero">
    <view class="eyebrow">Hotpot Shop</view>
    <view class="title">火锅底料商城</view>
    <view class="subtitle">精选牛油、清油、番茄和菌汤锅底</view>
  </view>

  <view class="panel">
    <view class="section-title">系统状态</view>
    <view class="muted">{{healthText}}</view>
  </view>
</view>
```

Create `miniprogram/pages/home/home.wxss`:

```css
.hero {
  padding: 48rpx 32rpx;
  border-radius: 16rpx;
  background: #b3261e;
  color: #ffffff;
}

.eyebrow {
  font-size: 24rpx;
  opacity: 0.82;
  margin-bottom: 12rpx;
}

.title {
  font-size: 48rpx;
  font-weight: 800;
}

.subtitle {
  margin-top: 16rpx;
  font-size: 28rpx;
}

.panel {
  margin-top: 28rpx;
  padding: 28rpx;
  border-radius: 12rpx;
  background: #ffffff;
}
```

Create `miniprogram/pages/home/home.ts`:

```ts
import { request } from "../../utils/request";

interface HealthStatus {
  status: string;
  service: string;
}

Page({
  data: {
    healthText: "正在连接后端..."
  },
  async onLoad() {
    try {
      const health = await request<HealthStatus>({ url: "/app/health", auth: false });
      this.setData({
        healthText: `${health.service}: ${health.status}`
      });
    } catch (error) {
      this.setData({
        healthText: error instanceof Error ? error.message : "后端暂不可用"
      });
    }
  }
});
```

- [ ] **Step 6: Write profile page**

Create `miniprogram/pages/profile/profile.json`:

```json
{
  "navigationBarTitleText": "我的"
}
```

Create `miniprogram/pages/profile/profile.wxml`:

```xml
<view class="page">
  <view class="section-title">我的</view>
  <view class="muted">微信静默登录和手机号授权将在认证阶段接入。</view>
</view>
```

Create `miniprogram/pages/profile/profile.wxss`:

```css
```

Create `miniprogram/pages/profile/profile.ts`:

```ts
Page({});
```

- [ ] **Step 7: Write order detail page for WeChat message jump path**

Create `miniprogram/pages/order/detail/detail.json`:

```json
{
  "navigationBarTitleText": "订单详情"
}
```

Create `miniprogram/pages/order/detail/detail.wxml`:

```xml
<view class="page">
  <view class="section-title">订单详情</view>
  <view class="muted">支付单号：{{transactionId}}</view>
  <view class="muted">商户单号：{{merchantTradeNo}}</view>
</view>
```

Create `miniprogram/pages/order/detail/detail.wxss`:

```css
```

Create `miniprogram/pages/order/detail/detail.ts`:

```ts
Page({
  data: {
    transactionId: "",
    merchantTradeNo: ""
  },
  onLoad(query: Record<string, string | undefined>) {
    this.setData({
      transactionId: query.transaction_id ? query.transaction_id : "",
      merchantTradeNo: query.merchant_trade_no ? query.merchant_trade_no : ""
    });
  }
});
```

- [ ] **Step 8: Typecheck mini program**

Run:

```bash
cd miniprogram
pnpm install
pnpm typecheck
```

Expected:

```text
Done in
```

and:

```text
Process exited with code 0
```

- [ ] **Step 9: Commit mini program scaffold**

Run:

```bash
git add miniprogram
git commit -m "chore: scaffold native WeChat mini program"
```

Expected:

```text
[main ...] chore: scaffold native WeChat mini program
```

## Task 8: Cross-App Smoke Documentation

**Files:**

- Create: `docs/smoke-checks.md`
- Modify: `README.md`

- [ ] **Step 1: Write smoke check documentation**

Create `docs/smoke-checks.md`:

````markdown
# Smoke Checks

## Backend

```bash
cd backend/shop-server
./mvnw test
./mvnw spring-boot:run
```

Expected health response:

```bash
curl http://localhost:8080/app/health
```

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "status": "UP",
    "service": "shop-server"
  }
}
```

## Admin

```bash
cd admin
pnpm install
pnpm dev
```

Expected:

```text
Local: http://localhost:3006/
```

Confirm `.env` contains:

```env
VITE_ACCESS_MODE = backend
```

## Mini Program

```bash
cd miniprogram
pnpm install
pnpm typecheck
```

Expected:

```text
Process exited with code 0
```

Open `miniprogram/` in WeChat DevTools and compile. The home page should request `/app/health` from `http://localhost:8080`.
````

- [ ] **Step 2: Link smoke checks in README**

Add this section to `README.md` after the Development section:

````markdown
## Smoke Checks

See [Smoke Checks](docs/smoke-checks.md).
````

- [ ] **Step 3: Verify docs links**

Run:

```bash
rg -n "Development Setup|Smoke Checks|Shop Foundation Implementation Plan" README.md docs/dev-setup.md docs/smoke-checks.md
```

Expected:

```text
README.md:
docs/dev-setup.md:
docs/smoke-checks.md:
```

- [ ] **Step 4: Commit smoke documentation**

Run:

```bash
git add README.md docs/smoke-checks.md
git commit -m "docs: add foundation smoke checks"
```

Expected:

```text
[main ...] docs: add foundation smoke checks
```

## Task 9: Final Foundation Verification

**Files:**

- Verify only.

- [ ] **Step 1: Verify backend**

Run:

```bash
cd backend/shop-server
./mvnw test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 2: Verify admin build**

Run:

```bash
cd admin
pnpm build
```

Expected:

```text
✓ built in
```

- [ ] **Step 3: Verify mini program typecheck**

Run:

```bash
cd miniprogram
pnpm typecheck
```

Expected:

```text
Process exited with code 0
```

- [ ] **Step 4: Verify Git status**

Run:

```bash
git status --short --ignored
```

Expected tracked status:

```text
```

Allowed ignored entries:

```text
!! .DS_Store
!! .superpowers/
!! docs/.DS_Store
!! docs/superpowers/.DS_Store
```

- [ ] **Step 5: Record foundation completion note**

Create `docs/foundation-completion.md`:

````markdown
# Foundation Completion

The foundation milestone is complete when these checks pass:

- `backend/shop-server/./mvnw test`
- `admin/pnpm build`
- `miniprogram/pnpm typecheck`

The next implementation plan should begin with authentication and RBAC:

- Spring Security token model.
- Admin login API.
- Backend-driven Art Design Pro menu API.
- Mini program silent login API.
- Token separation between `/admin/**` and `/app/**`.
````

- [ ] **Step 6: Commit completion note**

Run:

```bash
git add docs/foundation-completion.md
git commit -m "docs: record foundation completion criteria"
```

Expected:

```text
[main ...] docs: record foundation completion criteria
```

## Plan Self-Review

Spec coverage:

- Repository structure from the design is covered by Tasks 1, 2, 6, and 7.
- Backend Spring Boot 3, Spring Security, MyBatis-Plus, Flyway, MySQL, Redis, OpenAPI-ready structure, and WeChat Pay SDK dependency are covered by Tasks 2 through 5.
- Art Design Pro backend access mode and response contract are covered by Task 6.
- Native WeChat mini program TypeScript shell and request layer are covered by Task 7.
- WeChat shipping message jump path preparation is covered by the mini program order detail page in Task 7.
- Cross-app verification is covered by Tasks 8 and 9.

Known exclusions from this plan:

- Real admin login and RBAC.
- Product catalog.
- Cart, checkout, coupon.
- Order, payment, shipment upload, after-sale, and refund business flows.

Those exclusions are intentional because this plan is scoped to the foundation milestone.
