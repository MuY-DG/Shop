#!/usr/bin/env bash

set -Eeuo pipefail

service_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
test_source_dir="${service_dir}/src/test/java"
integration_count=0

while IFS= read -r test_file; do
  if ! grep -Fq '@Tag("integration")' "$test_file"; then
    printf 'Testcontainers 测试缺少 integration 标签：%s\n' "$test_file" >&2
    exit 1
  fi
  integration_count=$((integration_count + 1))
done < <(grep -R -l --include='*.java' '@Testcontainers' "$test_source_dir" | sort)

if ((integration_count == 0)); then
  printf '测试分层门禁失败：未找到 Testcontainers 测试。\n' >&2
  exit 1
fi

if grep -R -n --include='*.java' 'disabledWithoutDocker[[:space:]]*=[[:space:]]*true' \
  "$test_source_dir"; then
  printf '测试分层门禁失败：集成测试不得在 Docker 缺失时静默跳过。\n' >&2
  exit 1
fi

invalid_mysql="$(grep -R -n --include='*.java' 'new MySQLContainer.*"mysql:' "$test_source_dir" |
  grep -Fv 'mysql:8.4.10' || true)"
if [[ -n "$invalid_mysql" ]]; then
  printf '%s\n' "$invalid_mysql" >&2
  printf '测试分层门禁失败：MySQL Testcontainers 只允许 mysql:8.4.10。\n' >&2
  exit 1
fi

invalid_redis="$(grep -R -n --include='*.java' 'DockerImageName.parse("redis:' "$test_source_dir" |
  grep -Fv 'redis:7.4.9-alpine' || true)"
if [[ -n "$invalid_redis" ]]; then
  printf '%s\n' "$invalid_redis" >&2
  printf '测试分层门禁失败：Redis Testcontainers 只允许 redis:7.4.9-alpine。\n' >&2
  exit 1
fi

printf '测试分层门禁通过：%s 个 Testcontainers 测试均已标记 integration，镜像版本已固定。\n' \
  "$integration_count"
