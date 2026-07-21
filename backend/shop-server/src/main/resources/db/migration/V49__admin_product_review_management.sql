ALTER TABLE product_review
    ADD COLUMN moderated_by_admin_user_id BIGINT NULL;

ALTER TABLE product_review
    ADD COLUMN moderated_at TIMESTAMP NULL;

CREATE INDEX idx_product_review_status_rating_created
    ON product_review(status, rating, created_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (2801, 'product:review:read', 'Read product reviews'),
    (2802, 'product:review:moderate', 'Moderate product reviews');

INSERT INTO admin_menu
    (id, parent_id, name, path, component, title, icon, sort_order, keep_alive, visible, enabled)
VALUES
    (306, 300, 'ProductReview', 'review', '/product/review',
     '商品评论', 'ri:chat-quote-line', 36, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT r.id, 306
FROM admin_role r
WHERE r.code = 'R_SUPER';

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM admin_role r
CROSS JOIN admin_permission p
WHERE r.code = 'R_SUPER'
  AND p.auth_mark IN ('product:review:read', 'product:review:moderate');

INSERT INTO admin_menu_permission (menu_id, permission_id)
SELECT 306, p.id
FROM admin_permission p
WHERE p.auth_mark IN ('product:review:read', 'product:review:moderate');
