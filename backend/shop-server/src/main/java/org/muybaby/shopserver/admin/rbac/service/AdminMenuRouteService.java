package org.muybaby.shopserver.admin.rbac.service;

import org.muybaby.shopserver.admin.rbac.dto.AdminRouteAuthResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRouteMetaResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRouteResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminMenuRouteService {

    private final JdbcClient jdbcClient;

    public AdminMenuRouteService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AdminRouteResponse> routesForUser(Long userId) {
        List<MenuRow> menuRows = menuRowsForUser(userId);
        return buildRoutes(menuRows, authListsForUser(userId, menuIds(menuRows)));
    }

    public List<AdminRouteResponse> allRoutes() {
        List<MenuRow> menuRows = jdbcClient.sql("""
                        select * from admin_menu
                        where enabled = true
                        order by sort_order, id
                        """)
                .query(this::mapMenuRow)
                .list();
        return buildRoutes(menuRows, allAuthLists(menuIds(menuRows)));
    }

    private List<AdminRouteResponse> buildRoutes(
            List<MenuRow> menuRows,
            Map<Long, List<AdminRouteAuthResponse>> authByMenuId
    ) {
        Map<Long, MutableRoute> routesById = new LinkedHashMap<>();
        for (MenuRow menuRow : menuRows) {
            routesById.put(menuRow.id(), MutableRoute.from(
                    menuRow,
                    authByMenuId.getOrDefault(menuRow.id(), List.of())
            ));
        }

        List<MutableRoute> roots = new ArrayList<>();
        for (MenuRow menuRow : menuRows) {
            MutableRoute route = routesById.get(menuRow.id());
            MutableRoute parent = routesById.get(menuRow.parentId());
            if (menuRow.parentId() == null) {
                roots.add(route);
            } else if (parent != null) {
                parent.children().add(route);
            }
        }

        return roots.stream()
                .map(MutableRoute::toResponse)
                .toList();
    }

    private Map<Long, List<AdminRouteAuthResponse>> allAuthLists(List<Long> menuIds) {
        if (menuIds.isEmpty()) {
            return Map.of();
        }
        List<MenuAuthRow> authRows = jdbcClient.sql("""
                        select distinct mp.menu_id, p.id, p.title, p.auth_mark
                        from admin_permission p
                        join admin_menu_permission mp on mp.permission_id = p.id
                        where mp.menu_id in (:menuIds)
                        order by mp.menu_id, p.id
                        """)
                .param("menuIds", menuIds)
                .query(this::mapMenuAuthRow)
                .list();
        return groupAuthByMenuId(authRows);
    }

    private List<MenuRow> menuRowsForUser(Long userId) {
        return jdbcClient.sql("""
                        select distinct m.*
                        from admin_menu m
                        join admin_role_menu rm on rm.menu_id = m.id
                        join admin_user_role ur on ur.role_id = rm.role_id
                        join admin_role r on r.id = ur.role_id
                        join admin_user u on u.id = ur.user_id
                        where ur.user_id = :userId
                          and u.status = 'ENABLED'
                          and r.enabled = true
                          and m.enabled = true
                          and m.visible = true
                        order by m.sort_order, m.id
                        """)
                .param("userId", userId)
                .query(this::mapMenuRow)
                .list();
    }

    private Map<Long, List<AdminRouteAuthResponse>> authListsForUser(Long userId, List<Long> menuIds) {
        if (menuIds.isEmpty()) {
            return Map.of();
        }
        List<MenuAuthRow> authRows = jdbcClient.sql("""
                        select distinct mp.menu_id, p.id, p.title, p.auth_mark
                        from admin_permission p
                        join admin_menu_permission mp on mp.permission_id = p.id
                        join admin_role_permission rp on rp.permission_id = p.id
                        join admin_user_role ur on ur.role_id = rp.role_id
                        join admin_role r on r.id = ur.role_id
                        join admin_user u on u.id = ur.user_id
                        where ur.user_id = :userId
                          and mp.menu_id in (:menuIds)
                          and u.status = 'ENABLED'
                          and r.enabled = true
                        order by mp.menu_id, p.id
                        """)
                .param("userId", userId)
                .param("menuIds", menuIds)
                .query(this::mapMenuAuthRow)
                .list();
        return groupAuthByMenuId(authRows);
    }

    private List<Long> menuIds(List<MenuRow> menuRows) {
        return menuRows.stream().map(MenuRow::id).toList();
    }

    private Map<Long, List<AdminRouteAuthResponse>> groupAuthByMenuId(List<MenuAuthRow> authRows) {
        Map<Long, List<AdminRouteAuthResponse>> authByMenuId = new LinkedHashMap<>();
        for (MenuAuthRow authRow : authRows) {
            authByMenuId.computeIfAbsent(authRow.menuId(), ignored -> new ArrayList<>())
                    .add(new AdminRouteAuthResponse(authRow.id(), authRow.title(), authRow.authMark()));
        }
        authByMenuId.replaceAll((menuId, authList) -> List.copyOf(authList));
        return Map.copyOf(authByMenuId);
    }

    private MenuAuthRow mapMenuAuthRow(ResultSet rs, int rowNum) throws SQLException {
        return new MenuAuthRow(
                rs.getLong("menu_id"),
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("auth_mark")
        );
    }

    private MenuRow mapMenuRow(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        long parentId = rs.getLong("parent_id");
        return new MenuRow(
                id,
                rs.wasNull() ? null : parentId,
                rs.getString("name"),
                rs.getString("path"),
                rs.getString("component"),
                rs.getString("title"),
                rs.getString("icon"),
                rs.getBoolean("keep_alive")
        );
    }

    private record MenuRow(
            Long id,
            Long parentId,
            String name,
            String path,
            String component,
            String title,
            String icon,
            boolean keepAlive
    ) {
    }

    private record MenuAuthRow(Long menuId, Long id, String title, String authMark) {
    }

    private record MutableRoute(
            Long id,
            String name,
            String path,
            String component,
            AdminRouteMetaResponse meta,
            List<MutableRoute> children
    ) {

        private static MutableRoute from(MenuRow menuRow, List<AdminRouteAuthResponse> authList) {
            return new MutableRoute(
                    menuRow.id(),
                    menuRow.name(),
                    menuRow.path(),
                    menuRow.component(),
                    new AdminRouteMetaResponse(menuRow.title(), menuRow.icon(), menuRow.keepAlive(), authList),
                    new ArrayList<>()
            );
        }

        private AdminRouteResponse toResponse() {
            return new AdminRouteResponse(
                    id,
                    name,
                    path,
                    component,
                    meta,
                    children.stream()
                            .map(MutableRoute::toResponse)
                            .toList()
            );
        }
    }
}
