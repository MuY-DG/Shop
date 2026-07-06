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
        Map<Long, MutableRoute> routesById = new LinkedHashMap<>();
        for (MenuRow menuRow : menuRows) {
            routesById.put(menuRow.id(), MutableRoute.from(menuRow, authListForMenu(userId, menuRow.id())));
        }

        List<MutableRoute> roots = new ArrayList<>();
        for (MenuRow menuRow : menuRows) {
            MutableRoute route = routesById.get(menuRow.id());
            MutableRoute parent = routesById.get(menuRow.parentId());
            if (parent == null) {
                roots.add(route);
            } else {
                parent.children().add(route);
            }
        }

        return roots.stream()
                .map(MutableRoute::toResponse)
                .toList();
    }

    private List<MenuRow> menuRowsForUser(Long userId) {
        return jdbcClient.sql("""
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
                        """)
                .param("userId", userId)
                .query(this::mapMenuRow)
                .list();
    }

    private List<AdminRouteAuthResponse> authListForMenu(Long userId, Long menuId) {
        return jdbcClient.sql("""
                        select distinct p.id, p.title, p.auth_mark
                        from admin_permission p
                        join admin_menu_permission mp on mp.permission_id = p.id
                        join admin_role_permission rp on rp.permission_id = p.id
                        join admin_user_role ur on ur.role_id = rp.role_id
                        where ur.user_id = :userId and mp.menu_id = :menuId
                        order by p.id
                        """)
                .param("userId", userId)
                .param("menuId", menuId)
                .query((rs, rowNum) -> new AdminRouteAuthResponse(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("auth_mark")
                ))
                .list();
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
