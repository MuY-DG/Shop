#!/usr/bin/env bash

set -Eeuo pipefail

repository_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="${repository_dir}/backend/shop-server"

if [[ $# -ne 1 ]]; then
  printf '用法：%s <txcloud|shop>\n' "$0" >&2
  exit 2
fi

ssh_target="$1"
case "$ssh_target" in
  txcloud)
    api_host="api.muybaby6.icu"
    admin_host="admin.muybaby6.icu"
    ;;
  shop)
    api_host="api.junxiangshiping.cn"
    admin_host="admin.junxiangshiping.cn"
    ;;
  *)
    printf '部署目标只能是 txcloud 或 shop。\n' >&2
    exit 2
    ;;
esac

runtime_file="${service_dir}/config/runtime/${ssh_target}.env"
remote_deploy_dir="/opt/shop/shop-server"
remote_runtime_file="${remote_deploy_dir}/config/runtime/runtime.env"
admin_site_root="/opt/1panel/www/sites/${admin_host}"
api_site_root="/opt/1panel/www/sites/${api_host}"

for command in git htpasswd openssl pnpm shasum ssh tar; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少本机命令：%s\n' "$command" >&2
    exit 1
  }
done

for local_env_file in \
  "${repository_dir}/admin/.env.local" \
  "${repository_dir}/admin/.env.production.local"; do
  if [[ -e "$local_env_file" ]]; then
    printf '检测到会覆盖生产构建的本地环境文件：%s\n' "$local_env_file" >&2
    printf '请删除该文件并把公开配置写入已提交的 Admin 环境文件。\n' >&2
    exit 1
  fi
done

vite_override_names="$(env | awk -F= '$1 ~ /^VITE_/ { print $1 }' | sort | tr '\n' ' ')"
if [[ -n "$vite_override_names" ]]; then
  printf '检测到会覆盖 Admin 构建的环境变量：%s\n' "$vite_override_names" >&2
  printf '请清除这些变量后重新部署。\n' >&2
  exit 1
fi

require_clean_worktree() {
  if [[ -n "$(git -C "$repository_dir" status --porcelain --untracked-files=normal)" ]]; then
    printf '工作区存在未提交文件，拒绝部署无法准确识别的版本。\n' >&2
    printf '请先检查并提交当前改动。\n' >&2
    return 1
  fi
}

require_clean_worktree

printf '正在检查 %s 的 SSH、Docker 和 1Panel 网站目录。\n' "$ssh_target"
ssh "$ssh_target" "
  set -eu
  command -v docker >/dev/null
  command -v tar >/dev/null
  command -v curl >/dev/null
  command -v grep >/dev/null
  command -v flock >/dev/null
  command -v sha256sum >/dev/null
  command -v sort >/dev/null
  sudo -n true
  compose_version=\$(sudo docker compose version --short | sed 's/^v//; s/[-+].*//')
  minimum_compose_version=2.33.1
  oldest_version=\$(printf '%s\n' \
    \"\$minimum_compose_version\" \"\$compose_version\" | sort -V | head -n 1)
  if [ \"\$oldest_version\" != \"\$minimum_compose_version\" ]; then
    printf 'Docker Compose 版本过低：%s，需要 >= %s。\n' \
      \"\$compose_version\" \"\$minimum_compose_version\" >&2
    exit 1
  fi
  sudo test -d '$api_site_root'
  sudo test -d '$admin_site_root'
"

remote_runtime_exists=false
if ssh "$ssh_target" "sudo test -f '$remote_runtime_file'"; then
  remote_runtime_exists=true
else
  remote_runtime_status=$?
  if [[ "$remote_runtime_status" -ne 1 ]]; then
    printf '无法确认 %s 的运行密钥状态（SSH 状态码 %s）。\n' \
      "$ssh_target" "$remote_runtime_status" >&2
    exit 1
  fi
fi

if [[ "$remote_runtime_exists" == false ]]; then
  remote_residual_state=false
  if ssh "$ssh_target" '
    set -eu
    if sudo docker ps --all --quiet \
        --filter "label=com.docker.compose.project=shop" | grep -q .; then
      exit 0
    fi
    if sudo docker volume ls --quiet \
        --filter "label=com.docker.compose.project=shop" | grep -q .; then
      exit 0
    fi
    if sudo docker volume inspect shop_mysql-data >/dev/null 2>&1; then
      exit 0
    fi
    if sudo docker volume inspect shop_redis-data >/dev/null 2>&1; then
      exit 0
    fi
    exit 1
  '; then
    remote_residual_state=true
  else
    remote_residual_status=$?
    if [[ "$remote_residual_status" -ne 1 ]]; then
      printf '无法确认 %s 是否留有旧 Shop 容器或数据卷（SSH 状态码 %s）。\n' \
        "$ssh_target" "$remote_residual_status" >&2
      exit 1
    fi
  fi

  if [[ "$remote_residual_state" == true ]]; then
    printf '%s 缺少运行密钥，但仍存在 Shop 容器或数据卷。\n' "$ssh_target" >&2
    printf '请先完成服务器重置，或找回与这些数据匹配的运行密钥。\n' >&2
    exit 1
  fi
fi

shopt -s nullglob
pending_credential_files=(
  "${service_dir}/config/runtime/bootstrap-admin.${ssh_target}.pending."*.txt
)
shopt -u nullglob
if ((${#pending_credential_files[@]} > 0)); then
  if [[ "$remote_runtime_exists" == true ]]; then
    printf '发现结果未确认的 Super 临时凭据，拒绝继续部署：\n' >&2
    printf '  %s\n' "${pending_credential_files[@]}" >&2
    printf '请先核对当前 Super 状态并处理这些文件。\n' >&2
    exit 1
  fi
  printf '服务器已是空环境，正在删除旧环境遗留的待确认 Super 凭据。\n'
  rm -f -- "${pending_credential_files[@]}"
fi

if [[ ! -f "$runtime_file" ]]; then
  if [[ "$remote_runtime_exists" == true ]]; then
    printf '本机缺少 %s，但服务器已有运行密钥；拒绝生成新密钥覆盖现有环境。\n' \
      "$runtime_file" >&2
    exit 1
  fi
  printf '首次部署：正在生成 %s。\n' "$runtime_file"
  "${service_dir}/scripts/config/init-runtime-env.sh" "$ssh_target"
fi

"${service_dir}/scripts/config/validate-runtime-env.sh" "$ssh_target" "$runtime_file"

if [[ "$remote_runtime_exists" == true ]]; then
  local_runtime_sha="$(shasum -a 256 "$runtime_file" | awk '{print $1}')"
  remote_runtime_sha="$(ssh "$ssh_target" \
    "sudo sha256sum '$remote_runtime_file' | awk '{print \$1}'")"
  if [[ "$local_runtime_sha" != "$remote_runtime_sha" ]]; then
    printf '本机与 %s 的运行密钥不一致，拒绝用普通部署修改数据库密码或主密钥。\n' \
      "$ssh_target" >&2
    exit 1
  fi
fi

printf '正在构建 Admin。\n'
CI=true pnpm --dir "${repository_dir}/admin" build
pnpm --dir "${repository_dir}/admin" check:generated-imports
require_clean_worktree

revision="$(git -C "$repository_dir" rev-parse --short=12 HEAD)"
build_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
release_id="${revision}-${build_time//[-:]/}"
admin_index_sha="$(shasum -a 256 "${repository_dir}/admin/dist/index.html" | awk '{print $1}')"

printf '正在上传并部署 %s（Git %s）。\n' "$ssh_target" "$revision"
COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata \
  -C "$repository_dir" -czf - \
  backend/shop-server/Dockerfile \
  backend/shop-server/.dockerignore \
  backend/shop-server/pom.xml \
  backend/shop-server/src \
  backend/shop-server/compose.prod.yaml \
  "backend/shop-server/config/runtime/${ssh_target}.env" \
  admin/dist |
  ssh "$ssh_target" "
    set -eu
    lock_file=\"\${HOME:?}/.shop-deploy-${ssh_target}.lock\"
    exec 9>\"\$lock_file\"
    if ! flock -n 9; then
      printf '另一个 %s 部署正在执行，请等待它结束后重试。\n' '$ssh_target' >&2
      exit 75
    fi

    stage_dir=\$(mktemp -d /tmp/shop-deploy.XXXXXX)
    admin_release_root='$admin_site_root/.shop-admin-releases'
    admin_release_dir=\"\$admin_release_root/$release_id\"
    admin_next=\"\$admin_release_root/.next-$release_id\"
    admin_next_link='$admin_site_root/.index-next-$release_id'
    admin_switched=false

    cleanup() {
      status=\$?
      trap - EXIT HUP INT TERM
      rm -rf -- \"\$stage_dir\"
      sudo rm -rf -- \"\$admin_next\" >/dev/null 2>&1 || true
      sudo rm -f -- \"\$admin_next_link\" >/dev/null 2>&1 || true
      if [ \"\$admin_switched\" != true ]; then
        sudo rm -rf -- \"\$admin_release_dir\" >/dev/null 2>&1 || true
      fi
      exit \"\$status\"
    }
    trap cleanup EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    tar -xzf - -C \"\$stage_dir\"
    backend_stage=\"\$stage_dir/backend/shop-server\"
    runtime_stage=\"\$backend_stage/config/runtime/${ssh_target}.env\"
    compose_stage=\"\$backend_stage/compose.prod.yaml\"

    test -f \"\$backend_stage/Dockerfile\"
    test -f \"\$runtime_stage\"
    test -f \"\$compose_stage\"
    test -f \"\$stage_dir/admin/dist/index.html\"

    candidate_runtime_sha=\$(sha256sum \"\$runtime_stage\" | awk '{print \$1}')
    if sudo test -f '$remote_runtime_file'; then
      canonical_runtime_sha=\$(sudo sha256sum '$remote_runtime_file' | awk '{print \$1}')
      if [ \"\$candidate_runtime_sha\" != \"\$canonical_runtime_sha\" ]; then
        printf '服务器运行密钥已被另一条部署初始化，且与本机不一致。\n' >&2
        exit 1
      fi
    else
      residual_state=false
      if sudo docker ps --all --quiet \
          --filter 'label=com.docker.compose.project=shop' | grep -q .; then
        residual_state=true
      fi
      if sudo docker volume ls --quiet \
          --filter 'label=com.docker.compose.project=shop' | grep -q .; then
        residual_state=true
      fi
      if sudo docker volume inspect shop_mysql-data >/dev/null 2>&1; then
        residual_state=true
      fi
      if sudo docker volume inspect shop_redis-data >/dev/null 2>&1; then
        residual_state=true
      fi
      if [ \"\$residual_state\" = true ]; then
        printf '服务器运行密钥不存在，但已出现 Shop 容器或数据卷。\n' >&2
        exit 1
      fi
    fi

    sudo docker compose \
      --env-file \"\$runtime_stage\" \
      -f \"\$compose_stage\" \
      config --quiet

    old_image_id=\$(sudo docker image inspect \
      --format '{{.Id}}' shop-server:local 2>/dev/null || true)

    sudo docker build \
      --build-arg 'SHOP_BUILD_GIT_SHA=$revision' \
      --build-arg 'SHOP_BUILD_TIME=$build_time' \
      --tag shop-server:local \
      \"\$backend_stage\"

    sudo install -d -o root -g root -m 750 '$remote_deploy_dir'
    sudo install -d -o root -g root -m 700 '$remote_deploy_dir/config/runtime'
    sudo install -o root -g root -m 644 \
      \"\$compose_stage\" '$remote_deploy_dir/compose.prod.yaml'
    sudo install -o root -g root -m 600 \
      \"\$runtime_stage\" '$remote_runtime_file'

    cd '$remote_deploy_dir'
    compose() {
      sudo docker compose \
        --env-file '$remote_runtime_file' \
        -f '$remote_deploy_dir/compose.prod.yaml' \
        \"\$@\"
    }

    compose up -d --wait --wait-timeout 300 mysql redis
    compose run --rm --no-deps shop-server-log-init
    compose up -d --no-deps --force-recreate shop-server
    compose up -d --no-deps --wait --wait-timeout 300 shop-server

    curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 \
      http://127.0.0.1:8080/actuator/health >/dev/null
    release_info=\$(curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 \
      http://127.0.0.1:8080/actuator/info)
    printf '%s' \"\$release_info\" | grep -F '\"gitSha\":\"$revision\"' >/dev/null
    printf '%s' \"\$release_info\" | grep -F '\"buildTime\":\"$build_time\"' >/dev/null

    sudo install -d -o root -g root -m 755 \"\$admin_release_root\"
    sudo rm -rf -- \"\$admin_next\" \"\$admin_release_dir\"
    sudo rm -f -- \"\$admin_next_link\"
    sudo install -d -o root -g root -m 755 \"\$admin_next\"
    sudo cp -R \"\$stage_dir/admin/dist/.\" \"\$admin_next/\"
    sudo chown -R root:root \"\$admin_next\"
    sudo find \"\$admin_next\" -type d -exec chmod 755 {} +
    sudo find \"\$admin_next\" -type f -exec chmod 644 {} +
    sudo mv -- \"\$admin_next\" \"\$admin_release_dir\"
    sudo ln -s '.shop-admin-releases/$release_id' \"\$admin_next_link\"
    if [ -e '$admin_site_root/index' ] && [ ! -L '$admin_site_root/index' ]; then
      sudo rm -rf -- '$admin_site_root/index'
    fi
    admin_switched=true
    sudo mv -Tf -- \"\$admin_next_link\" '$admin_site_root/index'

    public_release_info=\$(curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
      --resolve '$api_host:443:127.0.0.1' \
      'https://$api_host/actuator/info')
    printf '%s' \"\$public_release_info\" | \
      grep -F '\"gitSha\":\"$revision\"' >/dev/null
    printf '%s' \"\$public_release_info\" | \
      grep -F '\"buildTime\":\"$build_time\"' >/dev/null

    served_admin_sha=\$(curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
      --resolve '$admin_host:443:127.0.0.1' \
      'https://$admin_host/' | sha256sum | awk '{print \$1}')
    if [ \"\$served_admin_sha\" != '$admin_index_sha' ]; then
      printf 'Admin 公网文件校验失败：期望 %s，实际 %s。\n' \
        '$admin_index_sha' \"\$served_admin_sha\" >&2
      exit 1
    fi

    admin_route_sha=\$(curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
      --resolve '$admin_host:443:127.0.0.1' \
      'https://$admin_host/__shop_deploy_spa_probe__/$release_id' | \
      sha256sum | awk '{print \$1}')
    if [ \"\$admin_route_sha\" != '$admin_index_sha' ]; then
      printf 'Admin SPA 回退校验失败：随机深链没有返回当前 index.html。\n' >&2
      printf '请按 docs/deployment-guide.md 配置 Admin 的 location /。\n' >&2
      exit 1
    fi

    if ! backend_admin_api_response=\$(curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 \
      'http://127.0.0.1:8080/admin/auth/registration'); then
      printf 'Admin API 校验失败：后端注册配置接口不可用。\n' >&2
      exit 1
    fi
    if ! admin_api_response=\$(curl --fail --silent --show-error \
      --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
      --resolve '$admin_host:443:127.0.0.1' \
      'https://$admin_host/admin/auth/registration'); then
      printf 'Admin API 校验失败：/admin/ 没有正确反向代理到后端。\n' >&2
      printf '请按 docs/deployment-guide.md 配置 Admin 的 location ^~ /admin/。\n' >&2
      exit 1
    fi
    if [ \"\$admin_api_response\" != \"\$backend_admin_api_response\" ] || \
        ! printf '%s' \"\$admin_api_response\" | grep -F '\"code\":200' >/dev/null || \
        ! printf '%s' \"\$admin_api_response\" | grep -F '\"msg\":\"success\"' >/dev/null || \
        ! printf '%s' \"\$admin_api_response\" | grep -E '\"enabled\":(true|false)' >/dev/null; then
      printf 'Admin API 校验失败：/admin/auth/registration 未返回后端 JSON。\n' >&2
      printf '请检查 proxy_pass 是否保留 /admin/ 路径。\n' >&2
      exit 1
    fi

    if ! api_websocket_status=\$(curl --http1.1 --silent --show-error \
      --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
      --resolve '$api_host:443:127.0.0.1' \
      --header 'Connection: Upgrade' \
      --header 'Upgrade: websocket' \
      --header 'Sec-WebSocket-Version: 13' \
      --header 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
      'https://$api_host/realtime?ticket=__shop.deploy-probe.invalid__-$release_id'); then
      printf 'API WebSocket 路由校验失败：无法访问 /realtime。\n' >&2
      exit 1
    fi
    if [ \"\$api_websocket_status\" != 401 ]; then
      printf 'API WebSocket 路由校验失败：无效 ticket 应返回 401，实际为 %s。\n' \
        \"\$api_websocket_status\" >&2
      printf '请按 docs/deployment-guide.md 配置 API 的 WebSocket 代理。\n' >&2
      exit 1
    fi

    if ! admin_websocket_status=\$(curl --http1.1 --silent --show-error \
      --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 5 --max-time 30 --retry 3 --retry-delay 1 \
      --resolve '$admin_host:443:127.0.0.1' \
      --header 'Connection: Upgrade' \
      --header 'Upgrade: websocket' \
      --header 'Sec-WebSocket-Version: 13' \
      --header 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
      'https://$admin_host/realtime?ticket=__shop.deploy-probe.invalid__-$release_id'); then
      printf 'Admin WebSocket 路由校验失败：无法访问 /realtime。\n' >&2
      exit 1
    fi
    if [ \"\$admin_websocket_status\" != 401 ]; then
      printf 'Admin WebSocket 路由校验失败：无效 ticket 应返回 401，实际为 %s。\n' \
        \"\$admin_websocket_status\" >&2
      printf '请按 docs/deployment-guide.md 配置 Admin 的 WebSocket 代理。\n' >&2
      exit 1
    fi

    sudo find \"\$admin_release_root\" \
      -mindepth 1 -maxdepth 1 -type d ! -name '$release_id' \
      -exec rm -rf -- {} +

    new_image_id=\$(sudo docker image inspect --format '{{.Id}}' shop-server:local)
    if [ -n \"\$old_image_id\" ] && [ \"\$old_image_id\" != \"\$new_image_id\" ]; then
      sudo docker image rm \"\$old_image_id\" >/dev/null 2>&1 || true
    fi

    compose ps
  "

bootstrap_needed="$(ssh "$ssh_target" "
  cd '$remote_deploy_dir'
  sudo docker compose \
    --env-file '$remote_runtime_file' \
    -f '$remote_deploy_dir/compose.prod.yaml' \
    exec -T mysql sh -ec '
      MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\"
      export MYSQL_PWD
      exec mysql --batch --skip-column-names --user=root \"\$MYSQL_DATABASE\" \
        --execute=\"SELECT COUNT(*) FROM admin_user WHERE id = 1 AND username = '\''Super'\'' AND status = '\''DISABLED'\'' AND max_sessions = 0 AND auth_version = 1;\"
    '
" | tr -d '[:space:]')"

case "$bootstrap_needed" in
  1)
    printf '检测到首次空库，正在引导 Super 管理员。\n'
    stale_credential_file="${service_dir}/config/runtime/bootstrap-admin.${ssh_target}.txt"
    if [[ -e "$stale_credential_file" ]]; then
      printf '正在删除已失效的旧环境 Super 临时凭据：%s\n' "$stale_credential_file"
      rm -f -- "$stale_credential_file"
    fi
    "${service_dir}/scripts/config/bootstrap-admin.sh" "$ssh_target"
    ;;
  0) ;;
  *)
    printf '无法确认 Super 引导状态：%s\n' "$bootstrap_needed" >&2
    exit 1
    ;;
esac

printf '部署完成：%s，Git %s。\n' "$ssh_target" "$revision"
printf 'API：https://%s\nAdmin：https://%s\n' "$api_host" "$admin_host"
