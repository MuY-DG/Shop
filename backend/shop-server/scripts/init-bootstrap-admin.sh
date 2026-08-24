#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/.." && pwd)"

if [[ $# -ne 1 ]]; then
  printf '用法：%s <environment>\n' "$0" >&2
  exit 2
fi

environment="$1"
if [[ ! "$environment" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then
  printf '环境名称只能包含小写字母、数字、点、下划线和连字符。\n' >&2
  exit 2
fi

for command in htpasswd openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

prod_file="${service_dir}/.env.${environment}.local"
credential_file="${service_dir}/.env.bootstrap-admin.${environment}.local"
if [[ ! -f "$prod_file" ]]; then
  printf '请先初始化环境文件：%s\n' "$prod_file" >&2
  exit 1
fi
if [[ -e "$credential_file" ]]; then
  printf '引导凭据文件已存在，拒绝静默轮换：%s\n' "$credential_file" >&2
  exit 1
fi

umask 077
password="$(openssl rand -base64 30 | tr -d '\n/+=')"
if [[ ${#password} -lt 20 ]]; then
  printf '生成的临时密码长度异常。\n' >&2
  exit 1
fi
password_hash="$(htpasswd -bnBC 12 bootstrap "$password" | awk -F: '{print $2}')"
if [[ "$password_hash" != \$2* ]]; then
  printf 'BCrypt 哈希生成失败。\n' >&2
  exit 1
fi

upsert_property() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temporary
  temporary="$(mktemp "${file}.tmp.XXXXXX")"
  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      replaced = 1
      next
    }
    { print }
    END {
      if (!replaced) {
        print key "=" value
      }
    }
  ' "$file" >"$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$file"
}

upsert_property "$prod_file" SHOP_DEFAULT_ADMIN_STATUS ENABLED
upsert_property "$prod_file" SHOP_DEFAULT_ADMIN_PASSWORD_HASH "$password_hash"

printf 'SHOP_INITIAL_ADMIN_USERNAME=Super\n' >"$credential_file"
printf 'SHOP_INITIAL_ADMIN_PASSWORD=%s\n' "$password" >>"$credential_file"
chmod 600 "$prod_file" "$credential_file"

unset password password_hash
printf '临时 Super 凭据已保存到 %s（600 权限，未打印明文）。\n' "$credential_file"
printf '首次登录并修改密码后，请删除该文件。\n'
