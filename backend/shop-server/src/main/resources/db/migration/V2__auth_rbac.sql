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
    (1201, 'system:menu:update', 'Update menu'),
    (1202, 'add', 'Add menu');

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
    (1, 1001), (1, 1002), (1, 1003), (1, 1101), (1, 1102), (1, 1103), (1, 1201), (1, 1202);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (201, 1001), (201, 1002), (201, 1003),
    (202, 1101), (202, 1102), (202, 1103),
    (203, 1201), (203, 1202);
