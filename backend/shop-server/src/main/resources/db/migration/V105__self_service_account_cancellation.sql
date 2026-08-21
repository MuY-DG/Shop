DELETE FROM admin_menu_permission WHERE menu_id = 910;
DELETE FROM admin_role_permission WHERE permission_id IN (20001, 20002);
DELETE FROM admin_role_menu WHERE menu_id = 910;
DELETE FROM admin_menu WHERE id = 910;
DELETE FROM admin_permission WHERE id IN (20001, 20002);

DROP TABLE app_user_rights_request_audit;
DROP TABLE app_user_rights_request;

ALTER TABLE legal_document_revision
    DROP CONSTRAINT chk_legal_document_type;

ALTER TABLE legal_document_revision
    ADD CONSTRAINT chk_legal_document_type CHECK (
        document_type IN (
            'PRIVACY_POLICY',
            'USER_AGREEMENT',
            'AFTER_SALE_POLICY',
            'ACCOUNT_CANCELLATION_NOTICE'
        )
    );

INSERT INTO legal_document_revision (
    document_type, version, title, content, content_sha256,
    status, current_publication_key, effective_at,
    created_by, published_by, published_at, created_at, updated_at
) VALUES (
    'ACCOUNT_CANCELLATION_NOTICE',
    '1.0',
    '账号注销须知',
    '一、注销条件：所有订单、支付、退款和售后事项均已结束。二、注销后果：注销立即生效且不可恢复；当前微信身份将与原账户解绑，再次登录会创建新账户，原账户权益不能恢复。三、即时清理：昵称、头像、手机号等身份资料，以及收货地址、购物车、收藏、浏览记录和未使用优惠券将被清除或去标识化。四、依法保留：已完成的订单、支付、退款、售后、客服、评价、安全和审计记录，将仅为履行法定义务、处理争议和保障交易安全，在适用期限内保留，期满后删除或匿名化。五、身份确认：勾选“我已阅读并了解”并提交，仅表示您确认已知悉本须知；提交时系统将重新核验微信身份。如仍有进行中的交易或售后，系统不会执行注销。',
    'f1c2011691f8470e31a2aac0fc9c36fd311f0c925262f3ebace01a4cd811e0bc',
    'PUBLISHED',
    'ACCOUNT_CANCELLATION_NOTICE',
    CURRENT_TIMESTAMP,
    1,
    1,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

CREATE TABLE app_user_account_cancellation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    legal_document_revision_id BIGINT NOT NULL,
    notice_version VARCHAR(40) NOT NULL,
    notice_content_sha256 CHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    mini_program_env VARCHAR(16) NOT NULL,
    identity_verified_at TIMESTAMP NOT NULL,
    deleted_data_categories VARCHAR(1000) NOT NULL,
    retained_data_categories VARCHAR(1000) NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_account_cancellation_user UNIQUE (user_id),
    CONSTRAINT chk_account_cancellation_channel CHECK (
        channel = 'WECHAT_MINIPROGRAM'
    ),
    CONSTRAINT chk_account_cancellation_env CHECK (
        mini_program_env IN ('develop', 'trial', 'release')
    ),
    CONSTRAINT fk_account_cancellation_user FOREIGN KEY (user_id)
        REFERENCES app_user(id),
    CONSTRAINT fk_account_cancellation_notice FOREIGN KEY (legal_document_revision_id)
        REFERENCES legal_document_revision(id)
);

CREATE INDEX idx_account_cancellation_completed
    ON app_user_account_cancellation(completed_at, id);
