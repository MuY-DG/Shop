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
    printf '管理员引导目标只能是 local、txcloud 或 shop。\n' >&2
    exit 2
    ;;
esac

required_commands=(htpasswd openssl)
if [[ "$target" == local ]]; then
  required_commands+=(mysql)
else
  required_commands+=(ssh)
fi
for command in "${required_commands[@]}"; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

"${service_dir}/scripts/config/validate-runtime-env.sh" "$target"

credential_file="${service_dir}/config/runtime/bootstrap-admin.${target}.txt"
if [[ -e "$credential_file" ]]; then
  printf '一次性管理员凭据已存在，拒绝静默覆盖：%s\n' "$credential_file" >&2
  exit 1
fi

umask 077
password="$(openssl rand -base64 36 | tr -d '\n/+=')"
if ((${#password} < 24)); then
  printf '一次性密码生成失败。\n' >&2
  exit 1
fi
password_hash="$(printf '%s\n' "$password" | htpasswd -niBC 12 bootstrap | awk -F: '{ print $2 }')"
case "$password_hash" in
  '$2a$12$'* | '$2b$12$'* | '$2y$12$'*) ;;
  *)
    printf 'BCrypt 哈希生成失败。\n' >&2
    exit 1
    ;;
esac

request_id="bootstrap-admin-${target}-$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 8)"
sentinel_hash='$2a$10$dSCU.t56l8Z7MPya89bXnuiMIjScayWL.KeTgc92TqlfLu.woUoYm'
generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
sql_file=""
credential_temp=""
retain_credential_temp=false

cleanup() {
  local status=$?
  trap - EXIT
  trap '' HUP INT TERM
  if [[ -n "$sql_file" ]]; then
    rm -f -- "$sql_file"
  fi
  if [[ -n "$credential_temp" ]]; then
    if [[ $status -ne 0 && "$retain_credential_temp" == true ]]; then
      chmod 600 "$credential_temp" >/dev/null 2>&1 || true
      printf '警告：数据库 CAS 结果可能已提交，一次性凭据临时文件已保留：%s\n' \
        "$credential_temp" >&2
      printf '核验 Super 状态后，将该文件原子改名为 %s 或安全删除。\n' \
        "$credential_file" >&2
    else
      rm -f -- "$credential_temp"
    fi
  fi
  unset password password_hash
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

sql_file="$(mktemp "${TMPDIR:-/tmp}/shop-bootstrap-admin.XXXXXX")"
# 临时文件与最终凭据位于同一目录，保持 0600；成功后 mv 是同一文件系统内的原子发布。
# 文件名仍匹配 bootstrap-admin.*.txt 的 Git ignore 规则。
credential_temp="$(mktemp "${credential_file%.txt}.pending.XXXXXX.txt")"
{
  printf 'target=%s\n' "$target"
  printf 'username=Super\n'
  printf 'temporary_password=%s\n' "$password"
  printf 'generated_at=%s\n' "$generated_at"
} >"$credential_temp"
chmod 600 "$credential_temp"

{
  printf '%s\n' 'START TRANSACTION;'
  printf '%s\n' "SET @schema_generation_ready = (SELECT CASE WHEN EXISTS (SELECT 1 FROM system_health_marker WHERE id = 1 AND marker_key = 'schema' AND marker_value = 'generation-2') AND EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '7' AND description = 'reference and bootstrap data' AND success = 1) AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success = 0) THEN 1 ELSE 0 END);"
  printf "UPDATE admin_user SET password_hash = '%s', status = 'ENABLED', auth_version = auth_version + 1, updated_at = CURRENT_TIMESTAMP WHERE @schema_generation_ready = 1 AND id = 1 AND username = 'Super' AND username_normalized = 'super' AND status = 'DISABLED' AND password_hash = '%s' AND max_sessions = 0 AND auth_version = 1;\n" \
    "$password_hash" "$sentinel_hash"
  printf '%s\n' 'SET @bootstrap_changed = ROW_COUNT();'
  printf "INSERT INTO admin_system_log (log_type, result, level, operator_id, operator_name, module, action, request_method, request_path, http_status, duration_ms, client_ip, request_id) SELECT 'OPERATION', 'SUCCESS', 'INFO', 1, 'Super', 'bootstrap-admin', 'bootstrap', 'SCRIPT', 'bootstrap-admin', 200, 0, '127.0.0.1', '%s' WHERE @bootstrap_changed = 1;\n" \
    "$request_id"
  printf '%s\n' "SELECT CONCAT('BOOTSTRAP_RESULT=', @bootstrap_changed);"
  printf '%s\n' 'COMMIT;'
} >"$sql_file"
chmod 600 "$sql_file"

# 从这里开始，SSH/客户端异常可能发生在数据库 COMMIT 之后；失败时保留 0600 临时凭据供核验。
retain_credential_temp=true
if [[ "$target" == local ]]; then
  local_runtime_file="${service_dir}/config/runtime/local.env"
  local_root_password="$(awk -F= '
    $1 == "SHOP_DB_ROOT_PASSWORD" { print substr($0, index($0, "=") + 1); exit }
  ' "$local_runtime_file")"
  execution_output="$(MYSQL_PWD="$local_root_password" mysql \
    --batch --skip-column-names \
    --host=127.0.0.1 --port=3306 --user=root hotpot_shop <"$sql_file")"
  unset local_root_password
else
  execution_output="$(ssh "$target" '
    set -eu
    cd /opt/shop/shop-server
    sudo docker compose \
      --env-file config/runtime/runtime.env \
      -f compose.prod.yaml \
      exec -T mysql sh -ec '\''
        MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
        export MYSQL_PWD
        exec mysql --batch --skip-column-names --user=root "$MYSQL_DATABASE"
      '\''
  ' <"$sql_file")"
fi

bootstrap_result="$(printf '%s\n' "$execution_output" | awk '
  /^BOOTSTRAP_RESULT=[01]$/ { result = $0 }
  END { print result }
')"
case "$bootstrap_result" in
  BOOTSTRAP_RESULT=1) ;;
  BOOTSTRAP_RESULT=0)
    retain_credential_temp=false
    printf '管理员引导被拒绝：数据库不是第二代基线，或 Super 不再处于未引导哨兵状态；数据库未改动。\n' >&2
    exit 1
    ;;
  *)
    printf '管理员引导结果无法确认；已保留一次性凭据临时文件，禁止直接重试。\n' >&2
    exit 1
    ;;
esac

mv -- "$credential_temp" "$credential_file"
credential_temp=''
retain_credential_temp=false

rm -f -- "$sql_file"
sql_file=''
unset password password_hash generated_at
trap - EXIT

printf '管理员引导完成，一次性凭据已保存到 %s（未打印明文）。\n' "$credential_file"
printf '首次登录并修改密码后，请删除该凭据文件。\n'
