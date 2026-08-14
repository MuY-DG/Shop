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

reject_property() {
  local file="$1"
  local key="$2"
  if awk -v prefix="${key}=" '
      index($0, prefix) == 1 { found = 1 }
      END { exit found ? 0 : 1 }
    ' "$file"; then
    printf '%s 中仍存在已移除的配置 %s，请删除该行。\n' "$file" "$key" >&2
    exit 1
  fi
}

for key in \
  SHOP_STORAGE_PROVIDER \
  SHOP_STORAGE_PUBLIC_BASE_URL \
  SHOP_STORAGE_LOCAL_ROOT \
  SHOP_STORAGE_TENCENT_COS_REGION \
  SHOP_STORAGE_TENCENT_COS_BUCKET \
  SHOP_STORAGE_TENCENT_COS_SECRET_ID \
  SHOP_STORAGE_TENCENT_COS_SECRET_KEY \
  SHOP_STORAGE_TENCENT_COS_PUBLIC_BASE_URL \
  SHOP_DIRECT_UPLOAD_MAX_ACTIVE_SESSIONS \
  SHOP_DIRECT_UPLOAD_MAX_SESSIONS_PER_HOUR_APP \
  SHOP_DIRECT_UPLOAD_MAX_SESSIONS_PER_HOUR_ADMIN; do
  reject_property "$prod_file" "$key"
done

for key in \
  SHOP_PAYMENT_SECRET_KEY \
  SHOP_PAYMENT_SECRET_WRITE_VERSION \
  SHOP_PAYMENT_SECRET_ACTIVE_KEY_ID \
  SHOP_PAYMENT_SECRET_KEY_RING \
  SHOP_PAYMENT_SECRET_ROTATION_ENABLED \
  SHOP_PAYMENT_SECRET_ROTATION_DELAY \
  SHOP_PAYMENT_SECRET_ROTATION_BATCH_SIZE \
  SHOP_SECRET_ENCRYPTION_ROTATION_DELAY \
  SHOP_SECRET_ENCRYPTION_ROTATION_BATCH_SIZE \
  SHOP_PAY_EXPIRE_MINUTES; do
  reject_property "$prod_file" "$key"
done

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
  SHOP_TRUSTED_PROXY_CIDRS \
  SHOP_MAX_FORWARDED_HOPS \
  SHOP_DEFAULT_ADMIN_PASSWORD_HASH \
  SHOP_SECRET_ENCRYPTION_WRITE_VERSION \
  SHOP_SECRET_ENCRYPTION_LEGACY_KEY \
  SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID \
  SHOP_SECRET_ENCRYPTION_KEY_RING; do
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

if [[ "$(read_property "$prod_file" SHOP_MAX_FORWARDED_HOPS)" != 1 ]]; then
  printf 'SHOP_MAX_FORWARDED_HOPS 必须为 1，OpenResty 需覆盖客户端转发头。\n' >&2
  exit 1
fi

trusted_proxy_cidrs="$(read_property "$prod_file" SHOP_TRUSTED_PROXY_CIDRS)"
trusted_gateway_count=0
IFS=',' read -r -a trusted_proxy_entries <<<"$trusted_proxy_cidrs"
for trusted_proxy_entry in "${trusted_proxy_entries[@]}"; do
  case "$trusted_proxy_entry" in
    127.0.0.0/8 | ::1/128)
      continue
      ;;
  esac

  if [[ ! "$trusted_proxy_entry" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/32$ ]]; then
    printf 'SHOP_TRUSTED_PROXY_CIDRS 仅允许回环地址和一个精确的 IPv4 网关 /32。\n' >&2
    exit 1
  fi

  trusted_gateway_ip="${trusted_proxy_entry%/32}"
  IFS='.' read -r gateway_octet_1 gateway_octet_2 gateway_octet_3 gateway_octet_4 \
    <<<"$trusted_gateway_ip"
  for gateway_octet in \
    "$gateway_octet_1" "$gateway_octet_2" "$gateway_octet_3" "$gateway_octet_4"; do
    if ((10#$gateway_octet > 255)); then
      printf 'SHOP_TRUSTED_PROXY_CIDRS 包含无效的 IPv4 网关地址。\n' >&2
      exit 1
    fi
  done
  if [[ "$gateway_octet_1" == 127 ]]; then
    printf 'Docker bridge 网关不能使用回环地址冒充，请填写实机核验的网关 /32。\n' >&2
    exit 1
  fi
  trusted_gateway_count=$((trusted_gateway_count + 1))
done

if ((trusted_gateway_count != 1)); then
  printf 'SHOP_TRUSTED_PROXY_CIDRS 必须且只能包含一个实机核验的 Docker bridge 网关 /32。\n' >&2
  exit 1
fi

chmod 600 "$prod_file" "$infra_file"
printf '生产环境变量检查通过；敏感值未打印。\n'
