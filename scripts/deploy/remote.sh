#!/usr/bin/env bash

set -Eeuo pipefail

# 由 deploy.sh 在持有同一目标部署锁的 SSH 会话中调用。
[[ $# -eq 9 ]] || exit 2
stage_dir="$1"
source "$stage_dir/scripts/deploy/common.sh"
configure_target "$2"
scope="$3"
revision="$4"
build_time="$5"
admin_fingerprint="$6"
backend_fingerprint="$7"
expected_snapshot_sha="$8"
admin_index_sha="$9"
valid_record "v1|$admin_fingerprint|$revision|$build_time|$expected_snapshot_sha" || exit 2
[[ "$backend_fingerprint" =~ ^[0-9a-f]{64}$ ]] || exit 2

sudo -n true
snapshot="$(deployment_snapshot)"
actual_snapshot_sha="$(printf '%s' "$snapshot" | sha256sum | awk '{print $1}')"
if [[ "$actual_snapshot_sha" != "$expected_snapshot_sha" ]]; then
  printf '服务器组件版本在构建期间发生变化，已停止发布；请重新执行部署以更新计划。\n' >&2
  exit 1
fi
parse_snapshot "$snapshot"
select_components "$scope" "$admin_fingerprint" "$backend_fingerprint"

release_id="${revision:0:12}-${build_time//[-:]/}-${stage_dir##*.}"
admin_release_root="$admin_site_root/.shop-admin-releases"
admin_release_dir="$admin_release_root/$release_id"
admin_next="$admin_release_root/.next-$release_id"
admin_next_link="$admin_site_root/.index-next-$release_id"

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if [[ "$deploy_admin" == true ]]; then
    sudo rm -rf -- "$admin_next" >/dev/null 2>&1 || true
    sudo rm -f -- "$admin_next_link" >/dev/null 2>&1 || true
    # 信号也可能落在原子切换与下一条命令之间；以实际链接判断，保护当前站点。
    if [[ "$(sudo readlink "$admin_site_root/index" 2>/dev/null || true)" != ".shop-admin-releases/$release_id" ]]; then
      sudo rm -rf -- "$admin_release_dir" >/dev/null 2>&1 || true
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

public_get() {
  local host="$1" path="$2"
  curl --fail --silent --show-error --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
    --resolve "$host:443:127.0.0.1" "https://$host$path"
}

verify_websocket() {
  local host="$1" status
  status="$(curl --http1.1 --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
    --resolve "$host:443:127.0.0.1" \
    --header 'Connection: Upgrade' --header 'Upgrade: websocket' \
    --header 'Sec-WebSocket-Version: 13' --header 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    "https://$host/realtime?ticket=__shop.deploy-probe.invalid__-$release_id")"
  if [[ "$status" != 401 ]]; then
    printf '%s WebSocket 路由校验失败：无效 ticket 应返回 401，实际为 %s。\n' "$host" "$status" >&2
    printf '请按 docs/deployment-guide.md 检查 WebSocket 代理。\n' >&2
    return 1
  fi
}

verify_admin_api() {
  local backend_response admin_response
  backend_response="$(curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    http://127.0.0.1:8080/admin/auth/registration)"
  admin_response="$(public_get "$admin_host" /admin/auth/registration)"
  if [[ "$admin_response" != "$backend_response" ||
        "$admin_response" != *'"code":200'* || "$admin_response" != *'"msg":"success"'* ]] ||
      ! printf '%s' "$admin_response" | grep -E '"enabled":(true|false)' >/dev/null; then
    printf 'Admin API 校验失败：请检查 /admin/ 代理是否保留路径并返回后端 JSON。\n' >&2
    return 1
  fi
}

deploy_backend_component() {
  local backend_stage="$stage_dir/backend/shop-server"
  local runtime_stage="$backend_stage/config/runtime/$ssh_target.env"
  local compose_stage="$backend_stage/compose.prod.yaml"
  local candidate_runtime_sha canonical_runtime_sha old_image_id new_image_id public_info count containers volumes
  require_commands docker
  sudo test -d "$api_site_root"
  test -f "$backend_stage/Dockerfile"
  test -f "$runtime_stage"
  test -f "$compose_stage"

  candidate_runtime_sha="$(sha256sum "$runtime_stage" | awk '{print $1}')"
  if sudo test -f "$remote_runtime_file"; then
    canonical_runtime_sha="$(file_sha "$remote_runtime_file")"
    if [[ "$candidate_runtime_sha" != "$canonical_runtime_sha" ]]; then
      printf '服务器运行密钥与候选版本不一致，拒绝覆盖。\n' >&2
      return 1
    fi
  else
    containers="$(sudo docker ps --all --quiet --filter 'label=com.docker.compose.project=shop')"
    volumes="$(sudo docker volume ls --quiet --filter 'label=com.docker.compose.project=shop')"
    if [[ -n "$containers" || -n "$volumes" ]] ||
        sudo docker volume inspect shop_mysql-data >/dev/null 2>&1 ||
        sudo docker volume inspect shop_redis-data >/dev/null 2>&1; then
      printf '服务器运行密钥不存在，但已出现 Shop 容器或数据卷，拒绝初始化。\n' >&2
      return 1
    fi
  fi

  sudo docker compose --env-file "$runtime_stage" -f "$compose_stage" config --quiet
  old_image_id="$(sudo docker image inspect --format '{{.Id}}' shop-server:local 2>/dev/null || true)"
  sudo docker build --build-arg "SHOP_BUILD_GIT_SHA=${revision:0:12}" \
    --build-arg "SHOP_BUILD_TIME=$build_time" --tag shop-server:local "$backend_stage"

  # 从更改实际运行配置开始，旧成功记录不能再代表本次结果。
  sudo rm -f -- "$state_dir/backend.state"
  sudo install -d -o root -g root -m 750 "$remote_deploy_dir"
  sudo install -d -o root -g root -m 700 "$remote_deploy_dir/config/runtime"
  sudo install -o root -g root -m 644 "$compose_stage" "$remote_deploy_dir/compose.prod.yaml"
  sudo install -o root -g root -m 600 "$runtime_stage" "$remote_runtime_file"
  compose up -d --wait --wait-timeout 300 mysql redis
  compose run --rm --no-deps shop-server-log-init
  compose up -d --no-deps --force-recreate shop-server
  compose up -d --no-deps --wait --wait-timeout 300 shop-server

  backend_version_matches "$revision" "$build_time"
  public_info="$(public_get "$api_host" /actuator/info)"
  [[ "$public_info" == *"\"gitSha\":\"${revision:0:12}\""* &&
     "$public_info" == *"\"buildTime\":\"$build_time\""* ]]
  verify_websocket "$api_host"
  # 即使仅更新后端，也确认已有管理后台的 API 和实时代理仍能使用。
  if sudo test -d "$admin_site_root"; then
    verify_admin_api
    verify_websocket "$admin_host"
  fi

  count="$(bootstrap_count)"
  case "$count" in
    0)
      write_component_record backend "$backend_fingerprint" "$revision" "$build_time" \
        "$(file_sha "$remote_deploy_dir/compose.prod.yaml")"
      ;;
    1) printf '后端已就绪；首次 Super 引导完成后再记录部署成功。\n' ;;
    *) printf '无法确认 Super 引导状态。\n' >&2; return 1 ;;
  esac
  new_image_id="$(sudo docker image inspect --format '{{.Id}}' shop-server:local)"
  if [[ -n "$old_image_id" && "$old_image_id" != "$new_image_id" ]]; then
    sudo docker image rm "$old_image_id" >/dev/null 2>&1 || true
  fi
  compose ps
  printf '后端发布及路由验收完成。\n'
}

deploy_admin_component() {
  local served_sha route_sha
  sudo test -d "$admin_site_root"
  test -f "$stage_dir/admin/dist/index.html"
  [[ "$admin_index_sha" =~ ^[0-9a-f]{64}$ ]]
  [[ "$(sha256sum "$stage_dir/admin/dist/index.html" | awk '{print $1}')" == "$admin_index_sha" ]]

  # 单独更新静态站点前，先确认现有后端可用；不要求它等于本次仓库 SHA。
  curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    http://127.0.0.1:8080/actuator/health >/dev/null
  verify_admin_api
  verify_websocket "$admin_host"

  sudo install -d -o root -g root -m 755 "$admin_release_root" "$admin_next"
  sudo cp -R "$stage_dir/admin/dist/." "$admin_next/"
  sudo chown -R root:root "$admin_next"
  sudo find "$admin_next" -type d -exec chmod 755 {} +
  sudo find "$admin_next" -type f -exec chmod 644 {} +
  sudo mv -- "$admin_next" "$admin_release_dir"
  sudo ln -s ".shop-admin-releases/$release_id" "$admin_next_link"
  sudo rm -f -- "$state_dir/admin.state"
  if sudo test -e "$admin_site_root/index" && ! sudo test -L "$admin_site_root/index"; then
    sudo rm -rf -- "$admin_site_root/index"
  fi
  sudo mv -Tf -- "$admin_next_link" "$admin_site_root/index"

  served_sha="$(public_get "$admin_host" / | sha256sum | awk '{print $1}')"
  route_sha="$(public_get "$admin_host" "/__shop_deploy_spa_probe__/$release_id" | sha256sum | awk '{print $1}')"
  if [[ "$served_sha" != "$admin_index_sha" || "$route_sha" != "$admin_index_sha" ]]; then
    printf 'Admin 静态文件或 SPA 回退校验失败，请检查网站配置。\n' >&2
    return 1
  fi
  verify_admin_api
  verify_websocket "$admin_host"
  write_component_record admin "$admin_fingerprint" "$revision" "$build_time" "$admin_index_sha"
  sudo find "$admin_release_root" -mindepth 1 -maxdepth 1 -type d ! -name "$release_id" \
    -exec rm -rf -- {} +
  printf 'Admin 发布及路由验收完成。\n'
}

if [[ "$deploy_backend" == true ]]; then
  deploy_backend_component
fi
if [[ "$deploy_admin" == true ]]; then
  deploy_admin_component
fi
