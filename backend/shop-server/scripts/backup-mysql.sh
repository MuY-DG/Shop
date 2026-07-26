#!/usr/bin/env bash

set -Eeuo pipefail

deploy_dir="/opt/shop/shop-server"
backup_dir="${deploy_dir}/backups/mysql"
retention_days="${SHOP_BACKUP_RETENTION_DAYS:-14}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
destination="${backup_dir}/hotpot_shop-${timestamp}.sql.gz"
partial="${destination}.partial"

umask 077
install -d -o root -g root -m 700 "$backup_dir"
cd "$deploy_dir"

cleanup_partial() {
  rm -f -- "$partial"
}

trap cleanup_partial EXIT

docker compose -f compose.prod.yaml exec -T mysql sh -ec '
  exec mysqldump \
    --user=root \
    --password="$MYSQL_ROOT_PASSWORD" \
    --single-transaction \
    --routines \
    --events \
    --triggers \
    --set-gtid-purged=OFF \
    "$MYSQL_DATABASE"
' | gzip -6 >"$partial"

gzip -t "$partial"
mv "$partial" "$destination"
trap - EXIT

find "$backup_dir" -type f -name 'hotpot_shop-*.sql.gz' -mtime "+${retention_days}" -delete
printf 'MySQL 备份完成：%s\n' "$destination"
