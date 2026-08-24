#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/../.." && pwd)"

if [[ $# -ne 1 ]]; then
  printf '用法：%s <local|txcloud|shop>\n' "$0" >&2
  exit 2
fi

target="$1"
case "$target" in
  local | txcloud | shop) ;;
  *)
    printf '目标只能是 local、txcloud 或 shop。\n' >&2
    exit 2
    ;;
esac

for command in openssl awk; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

runtime_dir="${service_dir}/config/runtime"
template_file="${runtime_dir}/runtime.env.example"
runtime_file="${runtime_dir}/${target}.env"

if [[ ! -f "$template_file" ]]; then
  printf '缺少 runtime manifest 模板：%s\n' "$template_file" >&2
  exit 1
fi
if [[ -e "$runtime_file" ]]; then
  printf '目标 manifest 已存在，拒绝静默覆盖：%s\n' "$runtime_file" >&2
  exit 1
fi

umask 077
install -d -m 700 "$runtime_dir"
install -m 600 "$template_file" "$runtime_file"
replacement_temp=""

cleanup_on_failure() {
  local status=$?
  trap - EXIT
  trap '' HUP INT TERM
  if [[ -n "$replacement_temp" ]]; then
    rm -f -- "$replacement_temp"
  fi
  if [[ $status -ne 0 ]]; then
    rm -f -- "$runtime_file"
  fi
  exit "$status"
}
trap cleanup_on_failure EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

generate_hex_secret() {
  openssl rand -hex 32
}

generate_base64_key() {
  openssl rand -base64 32 | tr -d '\n'
}

replace_property() {
  local key="$1"
  local value="$2"
  replacement_temp="$(mktemp "${runtime_file}.tmp.XXXXXX")"
  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      replaced = 1
      next
    }
    { print }
    END { if (!replaced) exit 3 }
  ' "$runtime_file" >"$replacement_temp"
  chmod 600 "$replacement_temp"
  mv "$replacement_temp" "$runtime_file"
  replacement_temp=""
}

replace_property SHOP_DB_PASSWORD "$(generate_hex_secret)"
replace_property SHOP_DB_ROOT_PASSWORD "$(generate_hex_secret)"
replace_property SHOP_REDIS_PASSWORD "$(generate_hex_secret)"

active_key_id="k$(date -u +%Y%m%d)-$(openssl rand -hex 4)"
replace_property SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID "$active_key_id"
replace_property \
  SHOP_SECRET_ENCRYPTION_KEY_RING \
  "${active_key_id}=base64:$(generate_base64_key)"

chmod 600 "$runtime_file"
trap - EXIT

printf 'runtime manifest 已生成：%s（秘密未打印）。\n' "$runtime_file"
printf '请使用该文件的数据库/Redis 密码重建或对齐目标服务。\n'
