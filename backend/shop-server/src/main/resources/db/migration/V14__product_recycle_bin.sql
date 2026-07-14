ALTER TABLE product_spu
    ADD COLUMN purged_at TIMESTAMP NULL;

CREATE INDEX idx_product_spu_recycle_bin
    ON product_spu(purged_at, deleted_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (2105, 'product:spu:restore', 'Restore product SPU'),
    (2106, 'product:spu:purge', 'Permanently delete product SPU');

INSERT INTO admin_role_permission (role_id, permission_id)
VALUES
    (1, 2105),
    (1, 2106);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (302, 2105),
    (302, 2106);
