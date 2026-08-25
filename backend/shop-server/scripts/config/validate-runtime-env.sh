#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/../.." && pwd)"

if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf '用法：%s <local|txcloud|shop> [runtime-manifest]\n' "$0" >&2
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

runtime_file="${2:-${service_dir}/config/runtime/${target}.env}"
if [[ ! -f "$runtime_file" ]]; then
  printf '缺少 runtime manifest：%s\n' "$runtime_file" >&2
  exit 1
fi

command -v openssl >/dev/null 2>&1 || {
  printf '缺少 openssl，无法校验 AES-256 key ring。\n' >&2
  exit 1
}

allowed_keys=(
  SHOP_DB_PASSWORD
  SHOP_DB_ROOT_PASSWORD
  SHOP_REDIS_PASSWORD
  SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID
  SHOP_SECRET_ENCRYPTION_KEY_RING
)

read_property() {
  local key="$1"
  awk -v prefix="${key}=" 'index($0, prefix) == 1 { print substr($0, length(prefix) + 1); exit }' \
    "$runtime_file"
}

is_allowed_key() {
  local candidate="$1"
  local allowed
  for allowed in "${allowed_keys[@]}"; do
    [[ "$candidate" == "$allowed" ]] && return 0
  done
  return 1
}

invalid_line="$(awk '
  /^[[:space:]]*($|#)/ { next }
  !/^[A-Z][A-Z0-9_]*=/ { print NR; exit }
' "$runtime_file")"
if [[ -n "$invalid_line" ]]; then
  printf 'runtime manifest 第 %s 行不是合法的 KEY=value 格式。\n' "$invalid_line" >&2
  exit 1
fi

while IFS= read -r configured_key; do
  [[ -n "$configured_key" ]] || continue
  if ! is_allowed_key "$configured_key"; then
    printf 'runtime manifest 中存在非白名单变量：%s\n' "$configured_key" >&2
    exit 1
  fi
  occurrence_count="$(awk -v prefix="${configured_key}=" '
    index($0, prefix) == 1 { count++ }
    END { print count + 0 }
  ' "$runtime_file")"
  if ((occurrence_count != 1)); then
    printf 'runtime manifest 中的 %s 必须且只能出现一次。\n' "$configured_key" >&2
    exit 1
  fi
done < <(awk -F= '/^[A-Z][A-Z0-9_]*=/ { print $1 }' "$runtime_file" | sort -u)

for required_key in "${allowed_keys[@]}"; do
  value="$(read_property "$required_key")"
  if [[ -z "$value" || "$value" == '<generated-'*'>' ]]; then
    printf 'runtime manifest 中的 %s 尚未填写有效值。\n' "$required_key" >&2
    exit 1
  fi
done

if [[ "$target" != local ]]; then
  for password_key in SHOP_DB_PASSWORD SHOP_DB_ROOT_PASSWORD SHOP_REDIS_PASSWORD; do
    password_value="$(read_property "$password_key")"
    if [[ ! "$password_value" =~ ^[0-9a-f]{64}$ ]]; then
      printf '%s runtime manifest 中的 %s 必须是 init 脚本生成的 64 位小写十六进制值。\n' \
        "$target" "$password_key" >&2
      exit 1
    fi
  done
fi

active_key_id="$(read_property SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID)"
if [[ ! "$active_key_id" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]]; then
  printf 'SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID 必须是 3-64 位小写 key-id。\n' >&2
  exit 1
fi

key_ring="$(read_property SHOP_SECRET_ENCRYPTION_KEY_RING)"
active_key_present=false
seen_key_ids=','
IFS=';' read -r -a key_ring_entries <<<"$key_ring"
if ((${#key_ring_entries[@]} < 1 || ${#key_ring_entries[@]} > 16)); then
  printf 'SHOP_SECRET_ENCRYPTION_KEY_RING 必须包含 1-16 个密钥。\n' >&2
  exit 1
fi
for key_ring_entry in "${key_ring_entries[@]}"; do
  if [[ ! "$key_ring_entry" =~ ^([a-z0-9][a-z0-9._-]{2,63})=base64:([A-Za-z0-9+/]+={0,2})$ ]]; then
    printf 'SHOP_SECRET_ENCRYPTION_KEY_RING 格式无效。\n' >&2
    exit 1
  fi
  key_id="${BASH_REMATCH[1]}"
  encoded_key="${BASH_REMATCH[2]}"
  if [[ "$seen_key_ids" == *",${key_id},"* ]]; then
    printf 'SHOP_SECRET_ENCRYPTION_KEY_RING 不得重复 key-id。\n' >&2
    exit 1
  fi
  seen_key_ids="${seen_key_ids}${key_id},"
  decoded_key_length="$(printf '%s' "$encoded_key" |
    openssl base64 -d -A 2>/dev/null |
    wc -c |
    tr -d '[:space:]')"
  if [[ "$decoded_key_length" != 32 ]]; then
    printf 'SHOP_SECRET_ENCRYPTION_KEY_RING 中每个密钥必须解码为 32 字节。\n' >&2
    exit 1
  fi
  if [[ "$key_id" == "$active_key_id" ]]; then
    active_key_present=true
  fi
done
if [[ "$active_key_present" != true ]]; then
  printf 'SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID 必须存在于 key ring。\n' >&2
  exit 1
fi

chmod 600 "$runtime_file"
printf '%s runtime manifest 检查通过；敏感值未打印。\n' "$target"
