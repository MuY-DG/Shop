INSERT INTO admin_permission (id, auth_mark, title, created_at)
VALUES (2003, 'product:category:delete', '删除商品分类', CURRENT_TIMESTAMP);

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES (1, 2003);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES (301, 2003);

CREATE INDEX idx_home_banner_jump_target
  ON home_banner (jump_type, jump_target_id);
