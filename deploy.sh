#!/usr/bin/env bash

set -Eeuo pipefail

repository_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="${repository_dir}/backend/shop-server"
source "${repository_dir}/scripts/deploy/common.sh"

usage() {
  printf '用法：%s <txcloud|shop> [admin|backend|all|auto] [--plan]\n' "$0"
  printf '省略范围时为 all；--plan 只读取服务器状态并显示部署计划。\n'
}

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  usage
  exit 0
fi
if [[ $# -lt 1 || $# -gt 3 ]]; then
  usage >&2
  exit 2
fi
configure_target "$1"
shift
scope=all
plan_only=false
if [[ $# -gt 0 && "$1" != --plan ]]; then
  scope="$1"
  shift
fi
case "$scope" in
  admin|backend|all|auto) ;;
  *) usage >&2; exit 2 ;;
esac
if [[ $# -gt 0 && "$1" == --plan ]]; then
  plan_only=true
  shift
fi
[[ $# -eq 0 ]] || { usage >&2; exit 2; }

runtime_file="${service_dir}/config/runtime/${ssh_target}.env"
require_commands git shasum ssh

require_clean_worktree() {
  if [[ -n "$(git -C "$repository_dir" status --porcelain --untracked-files=normal)" ]]; then
    printf '工作区存在未提交文件，拒绝部署无法准确识别的版本。\n' >&2
    printf '请先检查并提交当前改动。\n' >&2
    return 1
  fi
}
require_clean_worktree
revision="$(git -C "$repository_dir" rev-parse HEAD)"
admin_fingerprint="$(component_fingerprint admin)"
backend_fingerprint="$(component_fingerprint backend)"

printf '正在读取 %s 的组件部署记录。\n' "$ssh_target"
snapshot="$(ssh "$ssh_target" "bash -s -- '$ssh_target' snapshot" < "$repository_dir/scripts/deploy/common.sh")"
parse_snapshot "$snapshot" || { printf '服务器部署记录响应无效。\n' >&2; exit 1; }
snapshot_sha="$(printf '%s' "$snapshot" | shasum -a 256 | awk '{print $1}')"
select_components "$scope" "$admin_fingerprint" "$backend_fingerprint"

print_component_plan() {
  local label="$1" selected="$2" record="$3" fingerprint="$4"
  if [[ "$selected" == true ]]; then
    if [[ "$record" == missing ]]; then
      printf '  %s：部署（没有可验证的部署记录）。\n' "$label"
    elif [[ "$(record_field "$record" 2)" == "$fingerprint" ]]; then
      printf '  %s：部署（显式指定，重新发布）。\n' "$label"
    else
      printf '  %s：部署（构建输入已变化）。\n' "$label"
    fi
  elif [[ "$record" == missing || "$(record_field "$record" 2)" != "$fingerprint" ]]; then
    printf '  %s：本次跳过；版本未确认或存在待发布改动，配套接口变更请使用 all。\n' "$label"
  else
    printf '  %s：跳过（构建输入未变化，保留已部署版本）。\n' "$label"
  fi
}
printf '部署计划：%s / %s，目标 Git %s。\n' "$ssh_target" "$scope" "${revision:0:12}"
print_component_plan Admin "$deploy_admin" "$admin_record" "$admin_fingerprint"
print_component_plan 后端 "$deploy_backend" "$backend_record" "$backend_fingerprint"
if [[ "$plan_only" == true ]]; then
  exit 0
fi
if [[ "$deploy_admin" == false && "$deploy_backend" == false ]]; then
  printf '没有需要发布的组件。\n'
  exit 0
fi
require_commands tar

prepare_backend() {
  require_commands htpasswd openssl
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
    if [ '$deploy_admin' = true ]; then
      sudo test -d '$admin_site_root'
    fi
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
}

build_admin() {
  require_commands pnpm
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
  printf '正在构建 Admin。\n'
  CI=true pnpm --dir "${repository_dir}/admin" build
  pnpm --dir "${repository_dir}/admin" check:generated-imports
}

if [[ "$deploy_backend" == true ]]; then
  prepare_backend
fi
if [[ "$deploy_admin" == true ]]; then
  build_admin
fi
require_clean_worktree
if [[ "$(git -C "$repository_dir" rev-parse HEAD)" != "$revision" ]]; then
  printf '构建期间 Git 版本已变化，请重新部署。\n' >&2
  exit 1
fi

build_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
admin_index_sha=none
transfer_files=(scripts/deploy/common.sh scripts/deploy/remote.sh)
if [[ "$deploy_admin" == true ]]; then
  admin_index_sha="$(shasum -a 256 "$repository_dir/admin/dist/index.html" | awk '{print $1}')"
  transfer_files+=(admin/dist)
fi
if [[ "$deploy_backend" == true ]]; then
  transfer_files+=(backend/shop-server/Dockerfile backend/shop-server/.dockerignore
    backend/shop-server/pom.xml backend/shop-server/src backend/shop-server/compose.prod.yaml
    "backend/shop-server/config/runtime/${ssh_target}.env")
fi

printf '正在上传并部署 %s（%s）。\n' "$ssh_target" "$scope"
tar_options=(--no-xattrs)
if [[ "$(uname -s)" == Darwin ]]; then
  tar_options+=(--no-mac-metadata)
fi
COPYFILE_DISABLE=1 tar "${tar_options[@]}" \
  -C "$repository_dir" -czf - "${transfer_files[@]}" |
  ssh "$ssh_target" "
    set -eu
    umask 077
    exec 9>\"\${HOME:?}/.shop-deploy-${ssh_target}.lock\"
    if ! flock -n 9; then
      printf '另一个 %s 部署正在执行，请等待它结束后重试。\\n' '$ssh_target' >&2
      exit 75
    fi
    stage_dir=\$(mktemp -d /tmp/shop-deploy.XXXXXX)
    trap 'rm -rf -- \"\$stage_dir\"' EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    tar -xzf - -C \"\$stage_dir\"
    bash \"\$stage_dir/scripts/deploy/remote.sh\" \"\$stage_dir\" \
      '$ssh_target' '$scope' '$revision' '$build_time' \
      '$admin_fingerprint' '$backend_fingerprint' '$snapshot_sha' '$admin_index_sha'
  "

# 前端单独发布不读取数据库，也不参与首次 Super 初始化。
if [[ "$deploy_backend" == true ]]; then
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
  compose_sha="$(shasum -a 256 "$service_dir/compose.prod.yaml" | awk '{print $1}')"
  ssh "$ssh_target" "bash -s -- '$ssh_target' finalize-backend '$backend_fingerprint' '$revision' '$build_time' '$compose_sha'" \
    < "$repository_dir/scripts/deploy/common.sh"
fi

printf '部署完成：%s，范围 %s，目标 Git %s。\n' "$ssh_target" "$scope" "${revision:0:12}"
printf 'API：https://%s\nAdmin：https://%s\n' "$api_host" "$admin_host"
