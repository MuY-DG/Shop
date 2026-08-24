#!/usr/bin/env bash

set -Eeuo pipefail

service_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
migration_dir="${1:-${service_dir}/src/main/resources/db/migration}"

if [[ ! -d "$migration_dir" ]]; then
  printf 'Flyway 迁移目录不存在：%s\n' "$migration_dir" >&2
  exit 1
fi

file_count=0
while IFS= read -r file; do
  base_name="${file##*/}"
  if [[ ! "$base_name" =~ ^V[1-9][0-9]*__[a-z0-9]+(_[a-z0-9]+)*\.sql$ ]]; then
    printf 'Flyway 迁移文件名不合规：%s\n' "$base_name" >&2
    exit 1
  fi
  file_count=$((file_count + 1))
done < <(find "$migration_dir" -maxdepth 1 -type f -name 'V*__*.sql' -print)

if ((file_count == 0)); then
  printf 'Flyway 迁移门禁失败：未找到版本化迁移。\n' >&2
  exit 1
fi

expected=1
while IFS= read -r version; do
  if ((version != expected)); then
    printf 'Flyway 迁移版本必须从 V1 连续递增：期望 V%s，实际 V%s。\n' \
      "$expected" "$version" >&2
    exit 1
  fi
  expected=$((expected + 1))
done < <(
  find "$migration_dir" -maxdepth 1 -type f -name 'V*__*.sql' -exec basename {} \; |
    sed -E 's/^V([0-9]+)__.*/\1/' |
    sort -n
)

latest=$((expected - 1))
if ((latest != file_count)); then
  printf 'Flyway 迁移门禁失败：发现重复或无法识别的版本。\n' >&2
  exit 1
fi

printf 'Flyway 静态门禁通过：V1-V%s，共 %s 个连续迁移。\n' "$latest" "$file_count"
