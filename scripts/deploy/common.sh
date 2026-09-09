#!/usr/bin/env bash

# 本机与远端共用；部署记录只作为数据解析，绝不 source/eval 服务器内容。
configure_target() {
  ssh_target="$1"
  case "$ssh_target" in
    txcloud) api_host=api.muybaby6.icu; admin_host=admin.muybaby6.icu ;;
    shop) api_host=api.junxiangshiping.cn; admin_host=admin.junxiangshiping.cn ;;
    *) printf '部署目标只能是 txcloud 或 shop。\n' >&2; return 2 ;;
  esac
  remote_deploy_dir=/opt/shop/shop-server
  remote_runtime_file="$remote_deploy_dir/config/runtime/runtime.env"
  state_dir=/opt/shop/.deploy-state
  admin_site_root="/opt/1panel/www/sites/$admin_host"
  api_site_root="/opt/1panel/www/sites/$api_host"
}

require_commands() {
  local command_name
  for command_name in "$@"; do
    command -v "$command_name" >/dev/null 2>&1 || {
      printf '缺少命令：%s\n' "$command_name" >&2
      return 1
    }
  done
}

lock_deployment() {
  # 保留现有锁路径；不删除锁文件，进程退出时释放描述符。
  exec 9>"${HOME:?}/.shop-deploy-${ssh_target}.lock"
  flock -n 9 || {
    printf '另一个 %s 部署正在执行，请等待它结束后重试。\n' "$ssh_target" >&2
    return 75
  }
}

component_fingerprint() {
  local component="$1"
  local paths=(deploy.sh scripts/deploy/common.sh scripts/deploy/remote.sh)
  case "$component" in
    admin) paths+=(admin .nvmrc) ;;
    backend)
      paths+=(backend/shop-server/Dockerfile backend/shop-server/.dockerignore
        backend/shop-server/pom.xml backend/shop-server/src
        backend/shop-server/compose.prod.yaml backend/shop-server/scripts/config)
      ;;
    *) return 2 ;;
  esac
  # 路径、文件模式和 Git blob ID 都参与比较；不依赖最后一个提交或文件修改时间。
  git -C "$repository_dir" ls-tree -r -z HEAD -- "${paths[@]}" | shasum -a 256 | awk '{print $1}'
}

valid_record() {
  [[ "$1" =~ ^v1\|[0-9a-f]{64}\|[0-9a-f]{40}\|[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\|[0-9a-f]{64}$ ]]
}

record_field() {
  printf '%s\n' "$1" | cut -d '|' -f "$2"
}

file_sha() {
  sudo sha256sum "$1" | awk '{print $1}'
}

backend_version_matches() {
  local expected_revision="$1" expected_time="$2" info
  curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    http://127.0.0.1:8080/actuator/health >/dev/null || return 1
  info="$(curl --fail --silent --show-error --connect-timeout 5 --max-time 30 \
    http://127.0.0.1:8080/actuator/info)" || return 1
  [[ "$info" == *"\"gitSha\":\"${expected_revision:0:12}\""* &&
     "$info" == *"\"buildTime\":\"$expected_time\""* ]]
}

read_component_record() {
  local component="$1" record artifact revision timestamp actual
  if ! sudo test -f "$state_dir/$component.state"; then
    printf 'missing\n'
    return
  fi
  record="$(sudo cat "$state_dir/$component.state")" || return 1
  if ! valid_record "$record"; then
    printf 'missing\n'
    return
  fi
  artifact="$(record_field "$record" 5)"
  if [[ "$component" == admin ]]; then
    if ! sudo test -f "$admin_site_root/index/index.html"; then
      printf 'missing\n'
      return
    fi
    actual="$(file_sha "$admin_site_root/index/index.html")" || return 1
  else
    if ! sudo test -f "$remote_deploy_dir/compose.prod.yaml"; then
      printf 'missing\n'
      return
    fi
    actual="$(file_sha "$remote_deploy_dir/compose.prod.yaml")" || return 1
    revision="$(record_field "$record" 3)"
    timestamp="$(record_field "$record" 4)"
    if ! backend_version_matches "$revision" "$timestamp"; then
      printf 'missing\n'
      return
    fi
  fi
  if [[ "$artifact" == "$actual" ]]; then
    printf '%s\n' "$record"
  else
    printf 'missing\n'
  fi
}

deployment_snapshot() {
  local admin_record backend_record
  admin_record="$(read_component_record admin)" || return 1
  backend_record="$(read_component_record backend)" || return 1
  printf 'admin=%s\nbackend=%s\n' "$admin_record" "$backend_record"
}

parse_snapshot() {
  local snapshot="$1"
  # 必须恰好两行；拒绝把 SSH 噪声或异常响应当成尚未部署。
  admin_record="${snapshot%%$'\n'*}"
  backend_record="${snapshot#*$'\n'}"
  [[ "$snapshot" == *$'\n'* && "$backend_record" != *$'\n'* &&
     "$admin_record" == admin=* && "$backend_record" == backend=* ]] || return 1
  admin_record="${admin_record#admin=}"
  backend_record="${backend_record#backend=}"
  [[ "$admin_record" == missing ]] || valid_record "$admin_record" || return 1
  [[ "$backend_record" == missing ]] || valid_record "$backend_record" || return 1
}

select_components() {
  local requested_scope="$1" current_admin="$2" current_backend="$3"
  deploy_admin=false
  deploy_backend=false
  case "$requested_scope" in
    all) deploy_admin=true; deploy_backend=true ;;
    admin) deploy_admin=true ;;
    backend) deploy_backend=true ;;
    auto)
      if [[ "$admin_record" == missing || "$(record_field "$admin_record" 2)" != "$current_admin" ]]; then
        deploy_admin=true
      fi
      if [[ "$backend_record" == missing || "$(record_field "$backend_record" 2)" != "$current_backend" ]]; then
        deploy_backend=true
      fi
      ;;
    *) return 2 ;;
  esac
}

write_component_record() {
  local component="$1" fingerprint="$2" revision="$3" timestamp="$4" artifact="$5" temp record
  record="v1|$fingerprint|$revision|$timestamp|$artifact"
  valid_record "$record" || return 1
  sudo install -d -o root -g root -m 755 "$state_dir"
  temp="$(sudo mktemp "$state_dir/.$component.XXXXXX")"
  if ! printf '%s\n' "$record" | sudo tee "$temp" >/dev/null ||
      ! sudo chmod 644 "$temp" || ! sudo mv -f -- "$temp" "$state_dir/$component.state"; then
    sudo rm -f -- "$temp"
    return 1
  fi
}

compose() {
  sudo docker compose --env-file "$remote_runtime_file" \
    -f "$remote_deploy_dir/compose.prod.yaml" "$@"
}

bootstrap_count() {
  compose exec -T mysql sh -ec '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    export MYSQL_PWD
    exec mysql --batch --skip-column-names --user=root "$MYSQL_DATABASE" \
      --execute="SELECT COUNT(*) FROM admin_user WHERE id = 1 AND username = '\''Super'\'' AND status = '\''DISABLED'\'' AND max_sessions = 0 AND auth_version = 1;"
  ' | tr -d '[:space:]'
}

finalize_backend_record() {
  local fingerprint="$1" revision="$2" timestamp="$3" expected_compose_sha="$4"
  valid_record "v1|$fingerprint|$revision|$timestamp|$expected_compose_sha" || return 2
  backend_version_matches "$revision" "$timestamp" || return 1
  [[ "$(file_sha "$remote_deploy_dir/compose.prod.yaml")" == "$expected_compose_sha" ]] || return 1
  [[ "$(bootstrap_count)" == 0 ]] || return 1
  write_component_record backend "$fingerprint" "$revision" "$timestamp" "$expected_compose_sha"
}

# 通过 SSH stdin 运行时，只支持只读快照或首次 Super 引导完成后的记录收尾。
if [[ "${BASH_SOURCE[0]:-$0}" == "$0" ]]; then
  set -Eeuo pipefail
  configure_target "${1:-}"
  require_commands sudo flock curl sha256sum
  sudo -n true
  lock_deployment
  case "${2:-snapshot}" in
    snapshot) deployment_snapshot ;;
    finalize-backend)
      [[ $# -eq 6 ]] || exit 2
      finalize_backend_record "$3" "$4" "$5" "$6"
      ;;
    *) exit 2 ;;
  esac
fi
