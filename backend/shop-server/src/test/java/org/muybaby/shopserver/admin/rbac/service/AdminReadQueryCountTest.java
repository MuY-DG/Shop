package org.muybaby.shopserver.admin.rbac.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.rbac.dto.AdminRouteResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserQueryRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(AdminReadQueryCountTest.CountingDataSourceConfiguration.class)
@Transactional
class AdminReadQueryCountTest {

    private static final long FIRST_ROLE_ID = 9_410_001L;
    private static final long SECOND_ROLE_ID = 9_410_002L;
    private static final long FIRST_USER_ID = 9_420_001L;
    private static final long DISABLED_ROLE_ID = 9_430_001L;
    private static final long DISABLED_ROLE_PERMISSION_ID = 9_430_002L;

    @Autowired
    private AdminManagementService adminManagementService;

    @Autowired
    private AdminMenuRouteService adminMenuRouteService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SqlCounter sqlCounter;

    @Test
    void adminUserPageUsesThreeQueriesRegardlessOfPageSizeAndPreservesRoleOrdering() {
        insertRole(FIRST_ROLE_ID, "R_QUERY_COUNT_FIRST", true);
        insertRole(SECOND_ROLE_ID, "R_QUERY_COUNT_SECOND", false);
        insertUser(FIRST_USER_ID, "qc-admin-01");
        insertUserRole(FIRST_USER_ID, SECOND_ROLE_ID);
        insertUserRole(FIRST_USER_ID, FIRST_ROLE_ID);

        sqlCounter.reset();
        PageResult<AdminUserResponse> single = adminManagementService.userPage(
                new AdminUserQueryRequest(1L, 100L, "qc-admin-", "", "")
        );
        int singlePageQueries = sqlCounter.count();

        assertThat(single.records()).hasSize(1);
        assertThat(single.records().getFirst().roleIds())
                .containsExactly(FIRST_ROLE_ID, SECOND_ROLE_ID);
        assertThat(single.records().getFirst().roleCodes())
                .containsExactly("R_QUERY_COUNT_FIRST", "R_QUERY_COUNT_SECOND");

        for (int index = 2; index <= 20; index++) {
            long userId = FIRST_USER_ID + index - 1;
            insertUser(userId, "qc-admin-%02d".formatted(index));
            insertUserRole(userId, index % 2 == 0 ? SECOND_ROLE_ID : FIRST_ROLE_ID);
        }

        sqlCounter.reset();
        PageResult<AdminUserResponse> many = adminManagementService.userPage(
                new AdminUserQueryRequest(1L, 100L, "qc-admin-", "", "")
        );
        int manyPageQueries = sqlCounter.count();

        assertThat(many.records()).hasSize(20);
        assertThat(many.records()).extracting(AdminUserResponse::id).isSorted();
        assertThat(singlePageQueries).isEqualTo(3);
        assertThat(manyPageQueries).isEqualTo(singlePageQueries);
        assertThat(many.records()).allSatisfy(user -> {
            assertThat(user.roleIds()).hasSizeBetween(1, 2).isSorted();
            assertThat(user.roleCodes()).hasSameSizeAs(user.roleIds());
        });
    }

    @Test
    void menuRouteAssemblyUsesTwoQueriesAndPreservesPermissionScopeAndOrdering() {
        insertRole(DISABLED_ROLE_ID, "R_QUERY_COUNT_DISABLED", false);
        insertUserRole(1L, DISABLED_ROLE_ID);
        jdbcClient.sql("""
                        insert into admin_permission (id, auth_mark, title)
                        values (:id, 'query-count:disabled-only', 'Disabled role only')
                        """)
                .param("id", DISABLED_ROLE_PERMISSION_ID)
                .update();
        jdbcClient.sql("""
                        insert into admin_menu_permission (menu_id, permission_id)
                        values (201, :permissionId)
                        """)
                .param("permissionId", DISABLED_ROLE_PERMISSION_ID)
                .update();
        jdbcClient.sql("""
                        insert into admin_role_permission (role_id, permission_id)
                        values (:roleId, :permissionId)
                        """)
                .param("roleId", DISABLED_ROLE_ID)
                .param("permissionId", DISABLED_ROLE_PERMISSION_ID)
                .update();

        sqlCounter.reset();
        List<AdminRouteResponse> userRoutes = adminMenuRouteService.routesForUser(1L);
        int userRouteQueries = sqlCounter.count();

        AdminRouteResponse userMenu = findRoute(userRoutes, 201L);
        assertThat(userRoutes.getFirst().id()).isEqualTo(100L);
        assertThat(userMenu.meta().authList())
                .extracting("id")
                .containsExactly(1000L, 1001L, 1002L, 1003L);
        assertThat(userMenu.meta().authList())
                .extracting("authMark")
                .doesNotContain("query-count:disabled-only");

        sqlCounter.reset();
        List<AdminRouteResponse> allRoutes = adminMenuRouteService.allRoutes();
        int allRouteQueries = sqlCounter.count();

        AdminRouteResponse allUserMenu = findRoute(allRoutes, 201L);
        assertThat(allRoutes.getFirst().id()).isEqualTo(100L);
        assertThat(allUserMenu.meta().authList())
                .extracting("id")
                .containsExactly(1000L, 1001L, 1002L, 1003L, DISABLED_ROLE_PERMISSION_ID);
        assertThat(userRouteQueries).isEqualTo(2);
        assertThat(allRouteQueries).isEqualTo(userRouteQueries);
    }

    private AdminRouteResponse findRoute(List<AdminRouteResponse> routes, Long routeId) {
        for (AdminRouteResponse route : routes) {
            AdminRouteResponse match = findRoute(route, routeId);
            if (match != null) {
                return match;
            }
        }
        throw new AssertionError("Route not found: " + routeId);
    }

    private AdminRouteResponse findRoute(AdminRouteResponse route, Long routeId) {
        if (routeId.equals(route.id())) {
            return route;
        }
        for (AdminRouteResponse child : route.children()) {
            AdminRouteResponse match = findRoute(child, routeId);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private void insertRole(long roleId, String code, boolean enabled) {
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:id, :code, :code, '', :enabled)
                        """)
                .param("id", roleId)
                .param("code", code)
                .param("enabled", enabled)
                .update();
    }

    private void insertUser(long userId, String username) {
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, avatar, status)
                        values
                            (:id, :username, 'unused-test-hash', :username, :email, '', 'ENABLED')
                        """)
                .param("id", userId)
                .param("username", username)
                .param("email", username + "@example.test")
                .update();
    }

    private void insertUserRole(long userId, long roleId) {
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:userId, :roleId)
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
    }

    static final class SqlCounter {
        private final AtomicInteger count = new AtomicInteger();

        int count() {
            return count.get();
        }

        void increment() {
            count.incrementAndGet();
        }

        void reset() {
            count.set(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingDataSourceConfiguration {

        @Bean
        SqlCounter sqlCounter() {
            return new SqlCounter();
        }

        @Bean
        @Primary
        CountingDataSource dataSource(DataSourceProperties properties, SqlCounter sqlCounter) {
            return new CountingDataSource(properties.initializeDataSourceBuilder().build(), sqlCounter);
        }
    }

    static final class CountingDataSource extends AbstractDataSource implements AutoCloseable {
        private final DataSource delegate;
        private final SqlCounter sqlCounter;

        private CountingDataSource(DataSource delegate, SqlCounter sqlCounter) {
            this.delegate = delegate;
            this.sqlCounter = sqlCounter;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return countingConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return countingConnection(delegate.getConnection(username, password));
        }

        private Connection countingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        String methodName = method.getName();
                        if ("prepareStatement".equals(methodName)
                                || "prepareCall".equals(methodName)
                                || "createStatement".equals(methodName)) {
                            sqlCounter.increment();
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                    }
            );
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
