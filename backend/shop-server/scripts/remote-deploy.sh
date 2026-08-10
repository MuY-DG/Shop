#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $# -ne 3 ]]; then
  printf '用法：%s <已加载的版本镜像> <Git SHA> <构建时间>\n' "$0" >&2
  exit 2
fi

release_image="$1"
expected_git_sha="$2"
expected_build_time="$3"
deploy_dir="/opt/shop/shop-server"
legacy_was_active=false
deployment_switched=false
previous_compose_image_id=""
rollback_image="shop-server:rollback-before-${expected_git_sha}"

if [[ ! "$expected_git_sha" =~ ^[0-9a-f]{12}$ ]]; then
  printf 'Git SHA 必须是 12 位小写十六进制字符。\n' >&2
  exit 2
fi
if [[ ! "$expected_build_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
  printf '构建时间必须是 UTC RFC3339 格式。\n' >&2
  exit 2
fi

cd "$deploy_dir"

compose() {
  docker compose -f compose.prod.yaml "$@"
}

rollback() {
  local status=$?
  if [[ $status -ne 0 ]]; then
    if [[ -n "$previous_compose_image_id" ]]; then
      docker tag "$rollback_image" shop-server:local >/dev/null 2>&1 || true
    fi
    if [[ "$deployment_switched" == true ]]; then
      printf '新容器部署失败，正在恢复部署前应用。\n' >&2
      compose stop shop-server >/dev/null 2>&1 || true
      if [[ -n "$previous_compose_image_id" ]]; then
        compose up -d --no-deps --force-recreate shop-server || true
        compose up -d --wait --wait-timeout 300 shop-server || true
      elif [[ "$legacy_was_active" == true ]]; then
        systemctl start shop-server || true
      fi
    fi
  fi
  exit "$status"
}

trap rollback EXIT

current_container_id="$(compose ps --status running -q shop-server 2>/dev/null || true)"
if [[ -n "$current_container_id" ]]; then
  previous_compose_image_id="$(docker inspect --format '{{.Image}}' "$current_container_id")"
  docker tag "$previous_compose_image_id" "$rollback_image"
  printf '已记录部署前 Compose 镜像：%s\n' "$rollback_image"
fi

docker image inspect "$release_image" >/dev/null
docker tag "$release_image" shop-server:local
compose config --quiet

# 先准备数据服务，旧 Java 应用在此期间继续向 Caddy 提供服务。
compose up -d --wait --wait-timeout 300 mysql redis

if systemctl is-active --quiet shop-server; then
  legacy_was_active=true
  deployment_switched=true
  systemctl stop shop-server
fi

# 只有 8080 已释放后才启动生产 Profile 容器。
deployment_switched=true
compose up -d --no-deps --force-recreate shop-server
compose up -d --wait --wait-timeout 300 shop-server
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null
release_info="$(curl --fail --silent --show-error http://127.0.0.1:8080/actuator/info)"
if [[ "$release_info" != *"\"gitSha\":\"$expected_git_sha\""* ]]; then
  printf '版本校验失败：/actuator/info 中的 Git SHA 与部署版本不一致。\n' >&2
  exit 1
fi
if [[ "$release_info" != *"\"buildTime\":\"$expected_build_time\""* ]]; then
  printf '版本校验失败：/actuator/info 中的构建时间与部署版本不一致。\n' >&2
  exit 1
fi
if [[ ! "$release_info" =~ \"version\":\"[^\"]+\" ]] ||
   [[ ! "$release_info" =~ \"flywayVersion\":\"[0-9]+\" ]]; then
  printf '版本校验失败：/actuator/info 缺少应用版本或 Flyway 当前版本。\n' >&2
  exit 1
fi

# 新应用健康后关闭旧开机启动，但保留 unit 与 JAR 作为首次迁移回滚点。
if systemctl list-unit-files shop-server.service >/dev/null 2>&1; then
  systemctl disable shop-server >/dev/null
fi

trap - EXIT
compose ps
printf '生产 Compose 已健康启动，镜像：%s\n' "$release_image"
