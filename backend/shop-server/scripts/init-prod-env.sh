#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/.." && pwd)"
prod_file="${service_dir}/.env.prod.local"
infra_file="${service_dir}/.env.infrastructure.local"
rotate=false

if [[ "${1:-}" == "--rotate-infrastructure" ]]; then
  rotate=true
elif [[ $# -gt 0 ]]; then
  printf '用法：%s [--rotate-infrastructure]\n' "$0" >&2
  exit 2
fi

command -v openssl >/dev/null 2>&1 || {
  printf '缺少 openssl，无法安全生成生产密码。\n' >&2
  exit 1
}

umask 077

if [[ ! -f "$prod_file" ]]; then
  cp "${service_dir}/.env.prod.example" "$prod_file"
fi

if [[ ! -f "$infra_file" ]]; then
  cp "${service_dir}/.env.infrastructure.example" "$infra_file"
fi

read_property() {
  local file="$1"
  local key="$2"
  awk -v prefix="${key}=" 'index($0, prefix) == 1 { print substr($0, length(prefix) + 1); exit }' "$file"
}

is_usable_secret() {
  local value="$1"
  [[ -n "$value" && "$value" != *'<'* && "$value" != *'>'* ]]
}

generate_secret() {
  openssl rand -hex 32
}

upsert_property() {
  local file="$1"
  local key="$2"
  local value="$3"
  local comment="$4"
  local temporary
  temporary="$(mktemp "${file}.tmp.XXXXXX")"

  awk -v key="$key" -v value="$value" -v comment="$comment" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      replaced = 1
      next
    }
    { print }
    END {
      if (!replaced) {
        print ""
        print "# " comment
        print key "=" value
      }
    }
  ' "$file" >"$temporary"

  chmod 600 "$temporary"
  mv "$temporary" "$file"
}

remove_property() {
  local file="$1"
  local key="$2"
  local temporary
  temporary="$(mktemp "${file}.tmp.XXXXXX")"

  awk -v key="$key" '
    index($0, key "=") == 1 { next }
    { print }
  ' "$file" >"$temporary"

  chmod 600 "$temporary"
  mv "$temporary" "$file"
}

migrate_property() {
  local file="$1"
  local old_key="$2"
  local new_key="$3"
  local comment="$4"
  local old_value
  local new_value
  old_value="$(read_property "$file" "$old_key")"
  [[ -n "$old_value" ]] || return 0

  new_value="$(read_property "$file" "$new_key")"
  if [[ -n "$new_value" && "$new_value" != "$old_value" ]]; then
    printf '%s 中的 %s 与待迁移的 %s 值不一致，请人工确认主密钥。\n' \
      "$file" "$new_key" "$old_key" >&2
    exit 1
  fi
  if [[ -z "$new_value" ]]; then
    upsert_property "$file" "$new_key" "$old_value" "$comment"
  fi
  remove_property "$file" "$old_key"
}

migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_WRITE_VERSION \
  SHOP_SECRET_ENCRYPTION_WRITE_VERSION \
  "敏感配置新写入密文使用的格式版本。"
migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_ACTIVE_KEY_ID \
  SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID \
  "当前用于加密新敏感配置的主密钥 ID。"
migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_KEY_RING \
  SHOP_SECRET_ENCRYPTION_KEY_RING \
  "可读取当前及历史敏感配置密文的主密钥集合。"
migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_KEY \
  SHOP_SECRET_ENCRYPTION_LEGACY_KEY \
  "仅用于读取或迁移旧版 v1 密文的兼容主密钥。"
migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_ROTATION_ENABLED \
  SHOP_SECRET_ENCRYPTION_ROTATION_ENABLED \
  "是否启用敏感配置主密钥轮换任务。"
migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_ROTATION_DELAY \
  SHOP_SECRET_ENCRYPTION_ROTATION_DELAY \
  "敏感配置密钥轮换任务每批之间的等待时间。"
migrate_property \
  "$prod_file" \
  SHOP_PAYMENT_SECRET_ROTATION_BATCH_SIZE \
  SHOP_SECRET_ENCRYPTION_ROTATION_BATCH_SIZE \
  "敏感配置密钥轮换任务每批处理数量。"

for obsolete_storage_key in \
  SHOP_STORAGE_PROVIDER \
  SHOP_STORAGE_PUBLIC_BASE_URL \
  SHOP_STORAGE_LOCAL_ROOT \
  SHOP_STORAGE_TENCENT_COS_REGION \
  SHOP_STORAGE_TENCENT_COS_BUCKET \
  SHOP_STORAGE_TENCENT_COS_SECRET_ID \
  SHOP_STORAGE_TENCENT_COS_SECRET_KEY \
  SHOP_STORAGE_TENCENT_COS_PUBLIC_BASE_URL; do
  remove_property "$prod_file" "$obsolete_storage_key"
done

mysql_password="$(read_property "$infra_file" MYSQL_PASSWORD)"
if [[ "$rotate" == true ]] || ! is_usable_secret "$mysql_password"; then
  mysql_password="$(read_property "$prod_file" SHOP_DB_PASSWORD)"
fi
if [[ "$rotate" == true ]] || ! is_usable_secret "$mysql_password"; then
  mysql_password="$(generate_secret)"
fi

mysql_root_password="$(read_property "$infra_file" MYSQL_ROOT_PASSWORD)"
if [[ "$rotate" == true ]] || ! is_usable_secret "$mysql_root_password"; then
  mysql_root_password="$(generate_secret)"
fi

redis_password="$(read_property "$infra_file" REDIS_PASSWORD)"
if [[ "$rotate" == true ]] || ! is_usable_secret "$redis_password"; then
  redis_password="$(read_property "$prod_file" SHOP_REDIS_PASSWORD)"
fi
if [[ "$rotate" == true ]] || ! is_usable_secret "$redis_password"; then
  redis_password="$(generate_secret)"
fi

upsert_property "$infra_file" MYSQL_DATABASE hotpot_shop "MySQL 首次启动时创建的生产数据库名称。"
upsert_property "$infra_file" MYSQL_USER shop "MySQL 首次启动时创建的最小权限业务账号。"
upsert_property "$infra_file" MYSQL_PASSWORD "$mysql_password" "MySQL 业务账号密码。"
upsert_property "$infra_file" MYSQL_ROOT_PASSWORD "$mysql_root_password" "MySQL root 维护密码。"
upsert_property "$infra_file" REDIS_PASSWORD "$redis_password" "Redis requirepass 密码。"

upsert_property "$prod_file" SHOP_DB_URL 'jdbc:mysql://mysql:3306/hotpot_shop?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false' "Docker Compose 内的生产 MySQL JDBC 地址。"
upsert_property "$prod_file" SHOP_DB_USERNAME shop "生产 MySQL 业务账号。"
upsert_property "$prod_file" SHOP_DB_PASSWORD "$mysql_password" "生产 MySQL 业务账号密码。"
upsert_property "$prod_file" SHOP_REDIS_HOST redis "Docker Compose 内的生产 Redis 服务名。"
upsert_property "$prod_file" SHOP_REDIS_PORT 6379 "生产 Redis 服务端口。"
upsert_property "$prod_file" SHOP_REDIS_DATABASE 0 "生产 Redis 逻辑数据库编号。"
upsert_property "$prod_file" SHOP_REDIS_USERNAME '' "当前 requirepass 模式不使用 Redis ACL 用户名。"
upsert_property "$prod_file" SHOP_REDIS_PASSWORD "$redis_password" "生产 Redis 密码。"

if [[ -z "$(read_property "$prod_file" SHOP_TRUSTED_PROXY_CIDRS)" ]]; then
  upsert_property \
    "$prod_file" \
    SHOP_TRUSTED_PROXY_CIDRS \
    '127.0.0.0/8,::1/128,<docker-edge-gateway-ip>/32' \
    "实机核验后填写 OpenResty 进入容器时看到的单个 Docker bridge 网关 /32。"
fi
if [[ -z "$(read_property "$prod_file" SHOP_MAX_FORWARDED_HOPS)" ]]; then
  upsert_property \
    "$prod_file" \
    SHOP_MAX_FORWARDED_HOPS \
    1 \
    "OpenResty 覆盖 X-Forwarded-For 后只接受一跳转发。"
fi

chmod 600 "$prod_file" "$infra_file"

printf '生产环境文件已准备并设为 600 权限；敏感值未打印。\n'
