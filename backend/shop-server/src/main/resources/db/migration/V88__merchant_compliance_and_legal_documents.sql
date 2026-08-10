CREATE TABLE merchant_publication_revision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    revision_no BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_publication_key SMALLINT NULL,
    legal_name VARCHAR(160) NOT NULL DEFAULT '',
    entity_type VARCHAR(32) NOT NULL DEFAULT '',
    unified_social_credit_code VARCHAR(32) NOT NULL DEFAULT '',
    business_address VARCHAR(512) NOT NULL DEFAULT '',
    customer_service_phone VARCHAR(32) NOT NULL DEFAULT '',
    complaint_phone VARCHAR(32) NOT NULL DEFAULT '',
    business_license_asset_id BIGINT NULL,
    food_qualification_type VARCHAR(40) NOT NULL DEFAULT '',
    food_qualification_number VARCHAR(96) NOT NULL DEFAULT '',
    food_qualification_asset_id BIGINT NULL,
    food_qualification_valid_from DATE NULL,
    food_qualification_valid_until DATE NULL,
    created_by BIGINT NOT NULL,
    published_by BIGINT NULL,
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_merchant_publication_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')
    ),
    CONSTRAINT chk_merchant_publication_current CHECK (
        current_publication_key IS NULL OR current_publication_key = 1
    )
);

CREATE UNIQUE INDEX uk_merchant_publication_revision
    ON merchant_publication_revision(revision_no);

CREATE UNIQUE INDEX uk_merchant_publication_current
    ON merchant_publication_revision(current_publication_key);

CREATE INDEX idx_merchant_publication_status_revision
    ON merchant_publication_revision(status, revision_no);

CREATE TABLE legal_document_revision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_type VARCHAR(32) NOT NULL,
    version VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_publication_key VARCHAR(32) NULL,
    effective_at TIMESTAMP NULL,
    created_by BIGINT NOT NULL,
    published_by BIGINT NULL,
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_legal_document_type CHECK (
        document_type IN ('PRIVACY_POLICY', 'USER_AGREEMENT', 'AFTER_SALE_POLICY')
    ),
    CONSTRAINT chk_legal_document_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')
    ),
    CONSTRAINT chk_legal_document_current CHECK (
        current_publication_key IS NULL OR current_publication_key = document_type
    )
);

CREATE UNIQUE INDEX uk_legal_document_version
    ON legal_document_revision(document_type, version);

CREATE UNIQUE INDEX uk_legal_document_current
    ON legal_document_revision(current_publication_key);

CREATE INDEX idx_legal_document_history
    ON legal_document_revision(document_type, status, id);

CREATE TABLE app_user_document_consent (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    legal_document_revision_id BIGINT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    document_version VARCHAR(40) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    mini_program_env VARCHAR(16) NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_app_user_document_consent_type CHECK (
        document_type IN ('PRIVACY_POLICY', 'USER_AGREEMENT', 'AFTER_SALE_POLICY')
    ),
    CONSTRAINT chk_app_user_document_consent_env CHECK (
        mini_program_env IN ('develop', 'trial', 'release')
    ),
    CONSTRAINT fk_app_user_document_consent_user FOREIGN KEY (user_id)
        REFERENCES app_user(id),
    CONSTRAINT fk_app_user_document_consent_revision FOREIGN KEY (legal_document_revision_id)
        REFERENCES legal_document_revision(id)
);

CREATE UNIQUE INDEX uk_app_user_document_consent_revision
    ON app_user_document_consent(user_id, legal_document_revision_id);

CREATE INDEX idx_app_user_document_consent_user_time
    ON app_user_document_consent(user_id, accepted_at, id);

INSERT INTO admin_permission (id, auth_mark, title)
VALUES
    (19001, 'compliance:merchant:read', '查看商家经营资质'),
    (19002, 'compliance:merchant:write', '维护并发布商家经营资质'),
    (19003, 'compliance:document:read', '查看法律文档'),
    (19004, 'compliance:document:write', '维护并发布法律文档');

INSERT INTO admin_menu (
    id, parent_id, name, path, component, title, icon,
    sort_order, keep_alive, visible, enabled
)
VALUES
    (900, NULL, 'Compliance', '/compliance', '/index/index', '合规管理',
     'ri:shield-check-line', 90, FALSE, TRUE, TRUE),
    (901, 900, 'MerchantCompliance', 'merchant', '/compliance/merchant', '商家资质',
     'ri:building-2-line', 901, TRUE, TRUE, TRUE),
    (902, 900, 'LegalDocuments', 'documents', '/compliance/documents', '法律文档',
     'ri:file-text-line', 902, TRUE, TRUE, TRUE);

INSERT INTO admin_role_menu (role_id, menu_id)
SELECT role_item.id, menu_item.id
FROM admin_role role_item
CROSS JOIN admin_menu menu_item
WHERE role_item.code = 'R_SUPER'
  AND menu_item.id IN (900, 901, 902);

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT role_item.id, permission_item.id
FROM admin_role role_item
CROSS JOIN admin_permission permission_item
WHERE role_item.code = 'R_SUPER'
  AND permission_item.id IN (19001, 19002, 19003, 19004);

INSERT INTO admin_menu_permission (menu_id, permission_id)
VALUES
    (901, 19001), (901, 19002),
    (902, 19003), (902, 19004);
