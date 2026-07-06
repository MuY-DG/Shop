package org.muybaby.shopserver.admin.rbac.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.rbac.dto.AdminRouteAuthResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRouteMetaResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRouteResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminMenuRouteServiceTest {

    @Autowired
    private AdminMenuRouteService menuRouteService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void routesForUserReturnsNoRoutesForDisabledUser() {
        jdbcClient.sql("update admin_user set status = 'DISABLED' where id = :userId")
                .param("userId", 1L)
                .update();

        assertThat(menuRouteService.routesForUser(1L)).isEmpty();
    }

    @Test
    void authListIgnoresPermissionsGrantedOnlyByDisabledRoles() {
        insertRole(3L, "R_DISABLED", false);
        insertUserRole(1L, 3L);
        insertRolePermission(3L, 1001L);
        jdbcClient.sql("delete from admin_role_permission where role_id = :roleId and permission_id = :permissionId")
                .param("roleId", 1L)
                .param("permissionId", 1001L)
                .update();

        AdminRouteResponse userRoute = findRoute(menuRouteService.routesForUser(1L), 201L);

        assertThat(userRoute.meta().authList())
                .extracting(AdminRouteAuthResponse::authMark)
                .doesNotContain("system:user:create");
    }

    @Test
    void orphanChildMenusAreDroppedWhenAuthorizedParentIsAbsent() {
        jdbcClient.sql("delete from admin_role_menu where role_id = :roleId and menu_id = :menuId")
                .param("roleId", 1L)
                .param("menuId", 200L)
                .update();

        assertThat(menuRouteService.routesForUser(1L))
                .extracting(AdminRouteResponse::id)
                .doesNotContain(201L, 202L, 203L);
    }

    @Test
    void duplicatePermissionGrantsDoNotDuplicateAuthEntries() {
        insertRole(3L, "R_SUPPORT", true);
        insertUserRole(1L, 3L);
        insertRoleMenu(3L, 201L);
        insertRolePermission(3L, 1001L);

        AdminRouteResponse userRoute = findRoute(menuRouteService.routesForUser(1L), 201L);

        assertThat(userRoute.meta().authList())
                .extracting(AdminRouteAuthResponse::authMark)
                .filteredOn("system:user:create"::equals)
                .hasSize(1);
    }

    @Test
    void routeDtoListFieldsAreNonNullImmutableCopies() {
        List<AdminRouteAuthResponse> authList = new ArrayList<>();
        authList.add(new AdminRouteAuthResponse(1L, "Create", "system:user:create"));
        AdminRouteMetaResponse meta = new AdminRouteMetaResponse("User", "icon", true, authList);
        authList.clear();

        AdminRouteResponse route = new AdminRouteResponse(1L, "User", "user", "/system/user", meta, null);

        assertThat(meta.authList()).hasSize(1);
        assertThat(route.children()).isEmpty();
        assertThatThrownBy(() -> meta.authList().add(new AdminRouteAuthResponse(2L, "Update", "system:user:update")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> route.children().add(route))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private AdminRouteResponse findRoute(List<AdminRouteResponse> routes, Long id) {
        return routes.stream()
                .flatMap(route -> route.children().stream())
                .filter(route -> id.equals(route.id()))
                .findFirst()
                .orElseThrow();
    }

    private void insertRole(Long id, String code, boolean enabled) {
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:id, :code, :name, '', :enabled)
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", code)
                .param("enabled", enabled)
                .update();
    }

    private void insertUserRole(Long userId, Long roleId) {
        jdbcClient.sql("insert into admin_user_role (user_id, role_id) values (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
    }

    private void insertRoleMenu(Long roleId, Long menuId) {
        jdbcClient.sql("insert into admin_role_menu (role_id, menu_id) values (:roleId, :menuId)")
                .param("roleId", roleId)
                .param("menuId", menuId)
                .update();
    }

    private void insertRolePermission(Long roleId, Long permissionId) {
        jdbcClient.sql("insert into admin_role_permission (role_id, permission_id) values (:roleId, :permissionId)")
                .param("roleId", roleId)
                .param("permissionId", permissionId)
                .update();
    }
}
