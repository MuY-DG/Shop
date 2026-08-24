#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != --deploy-lock-held ) ]]; then
  printf '用法：%s [--deploy-lock-held]\n' "$0" >&2
  exit 2
fi

deploy_dir="/opt/shop/shop-server"
runtime_env="${deploy_dir}/config/runtime/runtime.env"
backup_dir="${deploy_dir}/backups/mysql"
retention_days="${SHOP_BACKUP_RETENTION_DAYS:-14}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="${backup_dir}/hotpot_shop-${timestamp}.sql.gz"
partial="${destination}.partial"
checksum_destination="${destination}.sha256"
checksum_partial="${checksum_destination}.partial"
published=false

umask 077
install -d -o root -g root -m 700 "$backup_dir"
cd "$deploy_dir"

if [[ ! "$retention_days" =~ ^[0-9]+$ ]]; then
  printf 'SHOP_BACKUP_RETENTION_DAYS 必须是非负整数。\n' >&2
  exit 2
fi
for command in docker flock gzip readlink sha256sum; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '服务器缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

deploy_lock="${deploy_dir}/.deploy.lock"
if [[ "${1:-}" == --deploy-lock-held ]]; then
  inherited_lock_target="$(readlink -f "/proc/$$/fd/9" 2>/dev/null || true)"
  if [[ "$inherited_lock_target" != "$deploy_lock" ]] || ! flock -n 9; then
    printf '内部备份调用没有继承有效的 Shop 发布锁。\n' >&2
    exit 75
  fi
else
  exec 9>"$deploy_lock"
  if ! flock -n 9; then
    printf 'Shop 后端发布正在进行，拒绝并发备份。\n' >&2
    exit 75
  fi
fi

exec 8>"${deploy_dir}/.backup.lock"
if ! flock -n 8; then
  printf '另一条 MySQL 备份正在进行，拒绝并发写入。\n' >&2
  exit 75
fi

if [[ ! -f "$runtime_env" ]]; then
  printf '缺少服务器 runtime manifest：%s\n' "$runtime_env" >&2
  exit 1
fi
if [[ -e "$destination" || -e "$partial" ||
      -e "$checksum_destination" || -e "$checksum_partial" ]]; then
  printf '备份目标时间戳发生冲突，拒绝覆盖已有文件：%s\n' "$destination" >&2
  exit 1
fi

cleanup_partial() {
  local status=$?
  trap - EXIT
  trap '' HUP INT TERM
  rm -f -- "$partial" "$checksum_partial"
  if [[ "$published" != true ]]; then
    rm -f -- "$destination" "$checksum_destination"
  fi
  exit "$status"
}

trap cleanup_partial EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

docker compose --env-file "$runtime_env" -f compose.prod.yaml exec -T mysql sh -ec '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  export MYSQL_PWD
  exec mysqldump \
    --user=root \
    --single-transaction \
    --routines \
    --events \
    --triggers \
    --set-gtid-purged=OFF \
    "$MYSQL_DATABASE"
' | gzip -6 >"$partial"

gzip -t "$partial"
archive_sha256="$(sha256sum "$partial" | awk '{print $1}')"
printf '%s  %s\n' "$archive_sha256" "$(basename "$destination")" \
  >"$checksum_partial"
mv "$partial" "$destination"
mv "$checksum_partial" "$checksum_destination"
(cd "$backup_dir" &&
  sha256sum --check --status "$(basename "$checksum_destination")")
published=true

while IFS= read -r -d '' expired_backup; do
  rm -f -- "$expired_backup" "${expired_backup}.sha256"
done < <(find "$backup_dir" -type f -name 'hotpot_shop-*.sql.gz' \
  -mtime "+${retention_days}" -print0)

trap - EXIT
printf 'MySQL 备份完成：%s\n' "$destination"
printf 'SHA-256 校验文件：%s\n' "$checksum_destination"
