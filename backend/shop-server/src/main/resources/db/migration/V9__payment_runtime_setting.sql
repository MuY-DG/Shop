create table payment_runtime_setting
(
    id            bigint      not null primary key,
    config_source varchar(16) not null,
    created_at    timestamp   not null default current_timestamp,
    updated_at    timestamp   not null default current_timestamp,
    constraint chk_payment_runtime_setting_config_source check (config_source in ('AUTO', 'ENV', 'DB'))
);
