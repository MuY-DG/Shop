#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $# -ne 4 ]]; then
  printf '用法：%s <已加载的版本镜像> <Git SHA> <构建时间> <deploy_id>\n' "$0" >&2
  exit 2
fi

release_image="$1"
expected_git_sha="$2"
expected_build_time="$3"
deploy_id="$4"
deploy_dir="/opt/shop/shop-server"
runtime_env="${deploy_dir}/config/runtime/runtime.env"
next_runtime_env="${runtime_env}.next.${deploy_id}"
compose_file="${deploy_dir}/compose.prod.yaml"
next_compose_file="${compose_file}.next.${deploy_id}"
versioned_backup_script="${deploy_dir}/scripts/deploy/backup-mysql.${deploy_id}.sh"
canonical_remote_script="${deploy_dir}/scripts/deploy/remote-deploy.sh"
canonical_backup_script="${deploy_dir}/scripts/deploy/backup-mysql.sh"
next_canonical_remote_script="${canonical_remote_script}.next.${deploy_id}"
next_canonical_backup_script="${canonical_backup_script}.next.${deploy_id}"
runtime_backup=""
compose_backup=""
remote_script_backup=""
mysql_backup_script_backup=""
runtime_manifest_switched=false
compose_manifest_switched=false
canonical_remote_script_switched=false
canonical_backup_script_switched=false
had_previous_runtime=false
had_previous_compose=false
had_previous_remote_script=false
had_previous_backup_script=false
previous_app_running=false
had_existing_project_state=false
data_services_touched=false
deployment_switched=false
previous_compose_image_id=""
previous_local_image_id=""
had_previous_local_image=false
local_image_tag_switched=false
rollback_image="shop-server:rollback-before-${deploy_id}"

if [[ ! "$expected_git_sha" =~ ^[0-9a-f]{12}$ ]]; then
  printf 'Git SHA 必须是 12 位小写十六进制字符。\n' >&2
  exit 2
fi
if [[ ! "$expected_build_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
  printf '构建时间必须是 UTC RFC3339 格式。\n' >&2
  exit 2
fi
if [[ ! "$deploy_id" =~ ^[0-9a-f]{12}-[0-9]{14}-[0-9a-f]{16}$ ||
      "$deploy_id" != "${expected_git_sha}-"* ]]; then
  printf 'deploy_id 格式无效或与 Git SHA 不一致。\n' >&2
  exit 2
fi
if [[ "$release_image" != "shop-server:${deploy_id}" ]]; then
  printf '版本镜像必须与 deploy_id 精确对应。\n' >&2
  exit 2
fi

cd "$deploy_dir"

for command in awk chown chmod curl docker flock install mv; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '服务器缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

compose() {
  docker compose --env-file "$runtime_env" -f "$compose_file" "$@"
}

read_runtime_property() {
  local file="$1"
  local key="$2"
  awk -v prefix="${key}=" '
    index($0, prefix) == 1 { print substr($0, length(prefix) + 1); exit }
  ' "$file"
}

require_unchanged_runtime_secrets() {
  local key
  for key in \
    SHOP_DB_PASSWORD \
    SHOP_DB_ROOT_PASSWORD \
    SHOP_REDIS_PASSWORD \
    SHOP_SECRET_ENCRYPTION_ACTIVE_KEY_ID \
    SHOP_SECRET_ENCRYPTION_KEY_RING; do
    if [[ "$(read_runtime_property "$runtime_env" "$key")" != \
          "$(read_runtime_property "$next_runtime_env" "$key")" ]]; then
      printf '常规部署拒绝修改 %s；任何运行时秘密轮换必须走独立维护流程。\n' \
        "$key" >&2
      exit 1
    fi
  done
}

rollback() {
  local status=$?
  local rollback_failed=false
  local manifests_restored=true
  local image_restored=true
  local runtime_backup_restored=true
  local compose_backup_restored=true
  local remote_script_backup_restored=true
  local mysql_backup_script_backup_restored=true
  trap - EXIT
  trap '' HUP INT TERM
  if [[ $status -ne 0 ]]; then
    if [[ "$deployment_switched" == true ]]; then
      printf '新容器部署失败，正在先停止候选应用并恢复部署前状态。\n' >&2
      printf '警告：候选应用可能已经执行 Flyway；数据库不会自动回灌。\n' >&2
      printf '如旧应用无法健康恢复，请使用本次发布前备份人工恢复数据库。\n' >&2
      if ! compose stop shop-server; then
        printf '严重警告：无法停止候选应用容器。\n' >&2
        rollback_failed=true
      fi
    fi
    if [[ "$runtime_manifest_switched" == true ]]; then
      if [[ "$had_previous_runtime" == true && -n "$runtime_backup" ]]; then
        if ! install -o root -g root -m 600 "$runtime_backup" "$runtime_env"; then
          printf '严重警告：旧 runtime manifest 恢复失败。\n' >&2
          rollback_failed=true
          manifests_restored=false
          runtime_backup_restored=false
        fi
      elif ! rm -f -- "$runtime_env"; then
        printf '严重警告：无法移除首次部署写入的 runtime manifest。\n' >&2
        rollback_failed=true
        manifests_restored=false
      fi
    fi
    if [[ "$compose_manifest_switched" == true ]]; then
      if [[ "$had_previous_compose" == true && -n "$compose_backup" ]]; then
        if ! install -o root -g root -m 644 "$compose_backup" "$compose_file"; then
          printf '严重警告：旧 Compose manifest 恢复失败。\n' >&2
          rollback_failed=true
          manifests_restored=false
          compose_backup_restored=false
        fi
      elif ! rm -f -- "$compose_file"; then
        printf '严重警告：无法移除首次部署写入的 Compose manifest。\n' >&2
        rollback_failed=true
        manifests_restored=false
      fi
    fi
    if [[ "$canonical_remote_script_switched" == true ]]; then
      if [[ "$had_previous_remote_script" == true && -n "$remote_script_backup" ]]; then
        if ! install -o root -g root -m 755 \
          "$remote_script_backup" "$canonical_remote_script"; then
          printf '严重警告：旧 remote-deploy 运维脚本恢复失败。\n' >&2
          rollback_failed=true
          remote_script_backup_restored=false
        fi
      elif ! rm -f -- "$canonical_remote_script"; then
        printf '严重警告：无法移除首次部署写入的 remote-deploy 运维脚本。\n' >&2
        rollback_failed=true
      fi
    fi
    if [[ "$canonical_backup_script_switched" == true ]]; then
      if [[ "$had_previous_backup_script" == true && -n "$mysql_backup_script_backup" ]]; then
        if ! install -o root -g root -m 755 \
          "$mysql_backup_script_backup" "$canonical_backup_script"; then
          printf '严重警告：旧 backup-mysql 运维脚本恢复失败。\n' >&2
          rollback_failed=true
          mysql_backup_script_backup_restored=false
        fi
      elif ! rm -f -- "$canonical_backup_script"; then
        printf '严重警告：无法移除首次部署写入的 backup-mysql 运维脚本。\n' >&2
        rollback_failed=true
      fi
    fi
    if [[ "$data_services_touched" == true && \
          "$had_previous_runtime" == true && "$had_previous_compose" == true ]]; then
      if ! compose up -d --wait --wait-timeout 300 mysql redis; then
        printf '严重警告：旧 MySQL/Redis Compose 状态恢复失败。\n' >&2
        rollback_failed=true
      fi
    fi
    if [[ "$local_image_tag_switched" == true ]]; then
      if [[ -n "$previous_compose_image_id" ]]; then
        if ! docker tag "$rollback_image" shop-server:local; then
          printf '严重警告：旧 Compose 应用镜像标签恢复失败。\n' >&2
          rollback_failed=true
          image_restored=false
        fi
      elif [[ "$had_previous_local_image" == true && -n "$previous_local_image_id" ]]; then
        if ! docker tag "$previous_local_image_id" shop-server:local; then
          printf '严重警告：部署前 shop-server:local 镜像标签恢复失败。\n' >&2
          rollback_failed=true
          image_restored=false
        fi
      elif ! docker image rm shop-server:local >/dev/null; then
        printf '严重警告：无法移除首次部署写入的 shop-server:local 镜像标签。\n' >&2
        rollback_failed=true
        image_restored=false
      fi
    fi
    if [[ "$deployment_switched" == true && \
          -n "$previous_compose_image_id" && "$previous_app_running" == true ]]; then
      if [[ "$manifests_restored" == true && "$image_restored" == true ]] &&
         compose up -d --no-deps --force-recreate shop-server &&
         compose up -d --wait --wait-timeout 300 shop-server &&
         curl --fail --silent --show-error \
           http://127.0.0.1:8080/actuator/health >/dev/null; then
        printf '旧应用已恢复并通过健康检查。\n' >&2
      else
        printf '严重警告：旧应用未能恢复为健康状态；必须人工处置并评估数据库恢复。\n' >&2
        rollback_failed=true
      fi
    elif [[ "$deployment_switched" == true && "$previous_app_running" != true ]]; then
      printf '部署前没有运行中的旧应用，本次失败没有可自动恢复的应用容器。\n' >&2
    fi
  fi
  if [[ -n "$runtime_backup" ]]; then
    if [[ "$runtime_backup_restored" == true ]]; then
      if ! rm -f -- "$runtime_backup"; then
        printf '警告：无法清理 runtime 回滚副本，已保留：%s\n' "$runtime_backup" >&2
        rollback_failed=true
      fi
    else
      printf '必须保留并人工恢复旧 runtime 副本：%s\n' "$runtime_backup" >&2
    fi
  fi
  if [[ -n "$compose_backup" ]]; then
    if [[ "$compose_backup_restored" == true ]]; then
      if ! rm -f -- "$compose_backup"; then
        printf '警告：无法清理 Compose 回滚副本，已保留：%s\n' "$compose_backup" >&2
        rollback_failed=true
      fi
    else
      printf '必须保留并人工恢复旧 Compose 副本：%s\n' "$compose_backup" >&2
    fi
  fi
  if [[ -n "$remote_script_backup" ]]; then
    if [[ "$remote_script_backup_restored" == true ]]; then
      if ! rm -f -- "$remote_script_backup"; then
        printf '警告：无法清理 remote-deploy 回滚副本，已保留：%s\n' \
          "$remote_script_backup" >&2
        rollback_failed=true
      fi
    else
      printf '必须保留并人工恢复旧 remote-deploy 副本：%s\n' \
        "$remote_script_backup" >&2
    fi
  fi
  if [[ -n "$mysql_backup_script_backup" ]]; then
    if [[ "$mysql_backup_script_backup_restored" == true ]]; then
      if ! rm -f -- "$mysql_backup_script_backup"; then
        printf '警告：无法清理 backup-mysql 回滚副本，已保留：%s\n' \
          "$mysql_backup_script_backup" >&2
        rollback_failed=true
      fi
    else
      printf '必须保留并人工恢复旧 backup-mysql 副本：%s\n' \
        "$mysql_backup_script_backup" >&2
    fi
  fi
  rm -f -- \
    "$next_runtime_env" \
    "$next_compose_file" \
    "$next_canonical_remote_script" \
    "$next_canonical_backup_script" || rollback_failed=true
  if [[ "$rollback_failed" == true ]]; then
    printf '严重警告：自动回滚未完整成功，不能假定服务或数据库已恢复。\n' >&2
  fi
  exit "$status"
}

trap rollback EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

exec 9>"${deploy_dir}/.deploy.lock"
if ! flock -n 9; then
  printf '另一条 Shop 后端发布正在进行，拒绝并发切换（deploy_id=%s）。\n' \
    "$deploy_id" >&2
  exit 75
fi
printf '已取得 Shop 后端发布锁（deploy_id=%s）。\n' "$deploy_id"

if [[ ! -f "$next_runtime_env" ]]; then
  printf '缺少待部署 runtime manifest：%s\n' "$next_runtime_env" >&2
  exit 1
fi
if [[ ! -f "$next_compose_file" ]]; then
  printf '缺少待部署 Compose 文件：%s\n' "$next_compose_file" >&2
  exit 1
fi
if [[ ! -f "$versioned_backup_script" ]]; then
  printf '缺少本次部署的版本化备份脚本：%s\n' "$versioned_backup_script" >&2
  exit 1
fi

# 在修改任何 canonical 文件、镜像标签或数据服务之前，先用候选文件完成远端解析。
docker compose \
  --env-file "$next_runtime_env" \
  -f "$next_compose_file" \
  config --quiet
if [[ -f "$runtime_env" ]]; then
  require_unchanged_runtime_secrets
fi

existing_project_container_id="$(docker ps --all --quiet \
  --filter 'label=com.docker.compose.project=shop' | head -n 1)"
existing_project_volume="$(docker volume ls --quiet \
  --filter 'label=com.docker.compose.project=shop' | head -n 1)"
if [[ -n "$existing_project_container_id" || -n "$existing_project_volume" ]]; then
  had_existing_project_state=true
fi
if [[ ( -n "$existing_project_container_id" || -n "$existing_project_volume" ) && \
      ( ! -f "$runtime_env" || ! -f "$compose_file" ) ]]; then
  printf '检测到旧 Shop Compose 容器/数据卷但缺少新拓扑的 canonical 配置；请先显式 down -v 完成空库重建。\n' >&2
  exit 1
fi

if docker image inspect shop-server:local >/dev/null 2>&1; then
  previous_local_image_id="$(docker image inspect --format '{{.Id}}' shop-server:local)"
  had_previous_local_image=true
fi

current_container_id="$(docker ps --all --quiet \
  --filter 'label=com.docker.compose.project=shop' \
  --filter 'label=com.docker.compose.service=shop-server' | head -n 1)"
if [[ -n "$current_container_id" ]]; then
  previous_compose_image_id="$(docker inspect --format '{{.Image}}' "$current_container_id")"
  if [[ "$(docker inspect --format '{{.State.Running}}' "$current_container_id")" == true ]]; then
    previous_app_running=true
  fi
  docker tag "$previous_compose_image_id" "$rollback_image"
  printf '已记录部署前 Compose 镜像：%s\n' "$rollback_image"
fi

docker image inspect "$release_image" >/dev/null
docker tag "$release_image" shop-server:local
local_image_tag_switched=true

if [[ -f "$runtime_env" ]]; then
  runtime_backup="$(mktemp "${runtime_env}.rollback.XXXXXX")"
  install -o root -g root -m 600 "$runtime_env" "$runtime_backup"
  had_previous_runtime=true
fi
if [[ -f "$compose_file" ]]; then
  compose_backup="$(mktemp "${compose_file}.rollback.XXXXXX")"
  install -o root -g root -m 644 "$compose_file" "$compose_backup"
  had_previous_compose=true
fi

# 已有容器或数据卷必须先用旧 canonical 配置启动旧 MySQL 并完成备份；
# 候选 Compose 尚未切换，因此镜像或启动参数变化不能先于恢复点触碰数据卷。
if [[ "$had_existing_project_state" == true ]]; then
  printf '检测到已有 Shop 状态，正在使用部署前 canonical 配置备份 MySQL。\n'
  compose up -d --wait --wait-timeout 300 mysql
  "$versioned_backup_script" --deploy-lock-held
fi

chown root:root "$next_runtime_env"
chmod 600 "$next_runtime_env"
mv -f -- "$next_runtime_env" "$runtime_env"
runtime_manifest_switched=true
chown root:root "$next_compose_file"
chmod 644 "$next_compose_file"
mv -f -- "$next_compose_file" "$compose_file"
compose_manifest_switched=true
compose config --quiet

# 先准备数据服务，已有 Compose 应用在此期间继续向 OpenResty 提供服务。
data_services_touched=true
compose up -d --wait --wait-timeout 300 mysql redis

# 首次部署没有旧数据可备份；数据服务初始化完成后仍产生一份可验证的空库基线备份。
# 已有环境的备份已在候选 manifest 切换前完成，避免候选数据服务先触碰数据卷。
if [[ "$had_existing_project_state" == false ]]; then
  "$versioned_backup_script" --deploy-lock-held
fi

# 数据服务健康且备份完成后，才切换 server Profile 容器。
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

# 本次调用和备份始终使用 deploy_id 对应的稳定脚本；只有应用健康且版本核对成功后，
# 才备份并原子更新服务器 canonical 运维入口。
if [[ -f "$canonical_remote_script" ]]; then
  remote_script_backup="$(mktemp "${canonical_remote_script}.rollback.XXXXXX")"
  install -o root -g root -m 755 "$canonical_remote_script" "$remote_script_backup"
  had_previous_remote_script=true
fi
if [[ -f "$canonical_backup_script" ]]; then
  mysql_backup_script_backup="$(mktemp "${canonical_backup_script}.rollback.XXXXXX")"
  install -o root -g root -m 755 \
    "$canonical_backup_script" "$mysql_backup_script_backup"
  had_previous_backup_script=true
fi
install -o root -g root -m 755 "$0" "$next_canonical_remote_script"
mv -f -- "$next_canonical_remote_script" "$canonical_remote_script"
canonical_remote_script_switched=true
install -o root -g root -m 755 \
  "$versioned_backup_script" "$next_canonical_backup_script"
mv -f -- "$next_canonical_backup_script" "$canonical_backup_script"
canonical_backup_script_switched=true

trap - EXIT
if [[ -n "$runtime_backup" ]]; then
  rm -f -- "$runtime_backup"
fi
if [[ -n "$compose_backup" ]]; then
  rm -f -- "$compose_backup"
fi
if [[ -n "$remote_script_backup" ]]; then
  rm -f -- "$remote_script_backup"
fi
if [[ -n "$mysql_backup_script_backup" ]]; then
  rm -f -- "$mysql_backup_script_backup"
fi
compose ps
printf '生产 Compose 已健康启动，镜像：%s\n' "$release_image"
