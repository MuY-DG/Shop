#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/.." && pwd)"
prod_file="${service_dir}/.env.prod.local"
infra_file="${service_dir}/.env.infrastructure.local"

for file in "$prod_file" "$infra_file"; do
  if [[ ! -f "$file" ]]; then
    printf '缺少生产环境文件：%s\n' "$file" >&2
    exit 1
  fi
done

read_property() {
  local file="$1"
  local key="$2"
  awk -v prefix="${key}=" 'index($0, prefix) == 1 { print substr($0, length(prefix) + 1); exit }' "$file"
}

require_value() {
  local file="$1"
  local key="$2"
  local value
  value="$(read_property "$file" "$key")"
  if [[ -z "$value" || "$value" == *'<'* || "$value" == *'>'* ]]; then
    printf '%s 中的 %s 尚未填写有效值。\n' "$file" "$key" >&2
    exit 1
  fi
}

for key in MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD; do
  require_value "$infra_file" "$key"
done

for key in \
  SHOP_DB_URL \
  SHOP_DB_USERNAME \
  SHOP_DB_PASSWORD \
  SHOP_REDIS_HOST \
  SHOP_REDIS_PORT \
  SHOP_REDIS_PASSWORD \
  SHOP_DEFAULT_ADMIN_PASSWORD_HASH \
  SHOP_PAYMENT_SECRET_KEY \
  SHOP_PAYMENT_SECRET_ACTIVE_KEY_ID \
  SHOP_PAYMENT_SECRET_KEY_RING; do
  require_value "$prod_file" "$key"
done

if [[ "$(read_property "$prod_file" SHOP_DB_USERNAME)" != "$(read_property "$infra_file" MYSQL_USER)" ]]; then
  printf 'SHOP_DB_USERNAME 与 MYSQL_USER 不一致。\n' >&2
  exit 1
fi

if [[ "$(read_property "$prod_file" SHOP_DB_PASSWORD)" != "$(read_property "$infra_file" MYSQL_PASSWORD)" ]]; then
  printf 'SHOP_DB_PASSWORD 与 MYSQL_PASSWORD 不一致。\n' >&2
  exit 1
fi

if [[ "$(read_property "$prod_file" SHOP_REDIS_PASSWORD)" != "$(read_property "$infra_file" REDIS_PASSWORD)" ]]; then
  printf 'SHOP_REDIS_PASSWORD 与 REDIS_PASSWORD 不一致。\n' >&2
  exit 1
fi

if [[ "$(read_property "$prod_file" SHOP_DB_URL)" != jdbc:mysql://mysql:* ]]; then
  printf 'SHOP_DB_URL 必须连接 Compose 服务名 mysql。\n' >&2
  exit 1
fi

if [[ "$(read_property "$prod_file" SHOP_REDIS_HOST)" != redis ]]; then
  printf 'SHOP_REDIS_HOST 必须连接 Compose 服务名 redis。\n' >&2
  exit 1
fi

chmod 600 "$prod_file" "$infra_file"
printf '生产环境变量检查通过；敏感值未打印。\n'
