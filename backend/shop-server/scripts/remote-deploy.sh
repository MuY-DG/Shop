#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  printf '用法：%s <已加载的版本镜像>\n' "$0" >&2
  exit 2
fi

release_image="$1"
deploy_dir="/opt/shop/shop-server"
legacy_was_active=false

cd "$deploy_dir"

compose() {
  docker compose -f compose.prod.yaml "$@"
}

rollback() {
  local status=$?
  if [[ $status -ne 0 ]]; then
    printf '新容器部署失败，正在恢复旧 systemd 应用。\n' >&2
    compose stop shop-server >/dev/null 2>&1 || true
    if [[ "$legacy_was_active" == true ]]; then
      systemctl start shop-server || true
    fi
  fi
  exit "$status"
}

trap rollback EXIT

docker image inspect "$release_image" >/dev/null
docker tag "$release_image" shop-server:local
compose config --quiet

# 先准备数据服务，旧 Java 应用在此期间继续向 Caddy 提供服务。
compose up -d --wait --wait-timeout 300 mysql redis

if systemctl is-active --quiet shop-server; then
  legacy_was_active=true
  systemctl stop shop-server
fi

# 只有 8080 已释放后才启动生产 Profile 容器。
compose up -d --no-deps --force-recreate shop-server
compose up -d --wait --wait-timeout 300 shop-server
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null

# 新应用健康后关闭旧开机启动，但保留 unit 与 JAR 作为首次迁移回滚点。
if systemctl list-unit-files shop-server.service >/dev/null 2>&1; then
  systemctl disable shop-server >/dev/null
fi

trap - EXIT
compose ps
printf '生产 Compose 已健康启动，镜像：%s\n' "$release_image"
