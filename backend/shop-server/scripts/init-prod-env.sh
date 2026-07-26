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

upsert_property "$prod_file" SHOP_DB_URL 'jdbc:mysql://mysql:3306/hotpot_shop?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false' "Docker Compose 内的生产 MySQL JDBC 地址。"
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
