#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/../.." && pwd)"
repository_dir="$(cd -- "${service_dir}/../.." && pwd)"

if [[ $# -ne 1 ]]; then
  printf '用法：%s <txcloud|shop>\n' "$0" >&2
  exit 2
fi

ssh_target="$1"
case "$ssh_target" in
  txcloud | shop) ;;
  *)
    printf '部署目标只能是 txcloud 或 shop。\n' >&2
    exit 2
    ;;
esac

platform="${SHOP_DEPLOY_PLATFORM:-linux/amd64}"
transport="${SHOP_DEPLOY_TRANSPORT:-remote-build}"
revision="$(git -C "$repository_dir" rev-parse --short=12 HEAD)"
timestamp="$(date -u +%Y%m%d%H%M%S)"
build_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

runtime_file="${service_dir}/config/runtime/${ssh_target}.env"

case "$platform" in
  linux/amd64 | linux/arm64) ;;
  *)
    printf 'SHOP_DEPLOY_PLATFORM 只能是 linux/amd64 或 linux/arm64。\n' >&2
    exit 2
    ;;
esac

case "$transport" in
  remote-build|image-stream) ;;
  *)
    printf '不支持的 SHOP_DEPLOY_TRANSPORT：%s\n' "$transport" >&2
    printf '可选值：remote-build、image-stream。\n' >&2
    exit 2
    ;;
esac

for command in git ssh tar gzip openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

deploy_id="${revision}-${timestamp}-$(openssl rand -hex 8)"
release_image="shop-server:${deploy_id}"
remote_next_runtime="/opt/shop/shop-server/config/runtime/runtime.env.next.${deploy_id}"
remote_next_compose="/opt/shop/shop-server/compose.prod.yaml.next.${deploy_id}"
remote_deploy_script="/opt/shop/shop-server/scripts/deploy/remote-deploy.${deploy_id}.sh"
remote_backup_script="/opt/shop/shop-server/scripts/deploy/backup-mysql.${deploy_id}.sh"

config_stage_dir=''
image_stage_dir=''
remote_stage_uploaded=false

require_clean_worktree() {
  if [[ -n "$(git -C "$repository_dir" status --porcelain --untracked-files=normal)" ]]; then
    printf '工作区存在未提交改动，拒绝使用不准确的 Git SHA 部署。\n' >&2
    printf '请先完成验证并提交本次发布范围，再重新执行部署。\n' >&2
    return 1
  fi
}

cleanup_config_stage() {
  [[ -n "$config_stage_dir" ]] || return 0
  case "$config_stage_dir" in
    "${TMPDIR:-/tmp}"/shop-deploy-config.*) rm -rf -- "$config_stage_dir" ;;
    *) printf '拒绝清理异常部署配置目录：%s\n' "$config_stage_dir" >&2 ;;
  esac
  config_stage_dir=''
}

cleanup_image_stage() {
  [[ -n "$image_stage_dir" ]] || return 0
  case "$image_stage_dir" in
    "${TMPDIR:-/tmp}"/shop-image.*) rm -rf -- "$image_stage_dir" ;;
    *) printf '拒绝清理异常镜像目录：%s\n' "$image_stage_dir" >&2 ;;
  esac
  image_stage_dir=''
}

cleanup_remote_stage() {
  [[ "$remote_stage_uploaded" == true ]] || return 0
  if ssh "$ssh_target" "
    set -eu
    sudo rm -f -- \
      '$remote_next_runtime' \
      '$remote_next_compose' \
      '$remote_deploy_script' \
      '$remote_backup_script'
  "; then
    remote_stage_uploaded=false
  else
    printf '警告：未能清理 %s 的本次唯一候选文件（deploy_id=%s），请人工核对。\n' \
      "$ssh_target" "$deploy_id" >&2
  fi
}

cleanup_all() {
  local status=$?
  trap - EXIT
  trap '' HUP INT TERM
  cleanup_image_stage
  cleanup_config_stage
  cleanup_remote_stage
  exit "$status"
}

trap cleanup_all EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

require_clean_worktree

"${service_dir}/scripts/config/validate-runtime-env.sh" "$ssh_target" "$runtime_file"
"${service_dir}/scripts/ci/verify-flyway-migrations.sh"
"${service_dir}/scripts/ci/verify-test-layers.sh"

if [[ "${SHOP_DEPLOY_SKIP_TESTS:-false}" != true ]]; then
  printf '正在运行无 Docker 单元/H2 测试层（不包含 Testcontainers）。\n'
  (cd "$service_dir" && ./mvnw test)
  printf '正在运行 Docker/Testcontainers 集成测试层。\n'
  (cd "$service_dir" && ./mvnw -Pintegration verify)
  "${service_dir}/scripts/ci/assert-integration-test-results.sh" \
    "${service_dir}/target/failsafe-reports"
fi

printf '测试后再次核对工作区与 Git SHA。\n'
require_clean_worktree

printf '正在通过 SSH 上传部署配置。\n'
config_stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/shop-deploy-config.XXXXXX")"
install -d -m 700 "$config_stage_dir/config/runtime"
install -m 600 "$runtime_file" "$config_stage_dir/config/runtime/runtime.env.next"

remote_stage_uploaded=true
COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata \
  -C "$service_dir" -cf - \
  compose.prod.yaml \
  scripts/deploy/remote-deploy.sh \
  scripts/deploy/backup-mysql.sh \
  -C "$config_stage_dir" \
  config/runtime/runtime.env.next | gzip -c |
  ssh "$ssh_target" "
    set -eu
    deploy_id='$deploy_id'
    stage_dir=\"\$(mktemp -d /tmp/shop-deploy.XXXXXX)\"
    cleanup() {
      status=\$?
      trap - EXIT
      trap '' HUP INT TERM
      rm -rf \"\$stage_dir\"
      exit \"\$status\"
    }
    trap cleanup EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    tar -xzf - -C \"\$stage_dir\"
    sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server
    sudo install -d -o root -g root -m 700 /opt/shop/shop-server/backups
    # Compose 与 runtime manifest 都先上传为候选文件，由 remote-deploy 原子切换/回滚。
    sudo install -o root -g root -m 644 \
      \"\$stage_dir/compose.prod.yaml\" \
      \"/opt/shop/shop-server/compose.prod.yaml.next.\$deploy_id\"
    sudo install -d -o root -g root -m 700 /opt/shop/shop-server/config/runtime
    # 先上传为候选文件；remote-deploy 校验/切换，并在失败时恢复旧 manifest。
    sudo install -o root -g root -m 600 \
      \"\$stage_dir/config/runtime/runtime.env.next\" \
      \"/opt/shop/shop-server/config/runtime/runtime.env.next.\$deploy_id\"
    sudo install -d -o root -g root -m 755 /opt/shop/shop-server/scripts/deploy
    # 每次调用使用唯一脚本文件，避免另一次上传覆盖正在执行的 shell 文件。
    sudo install -o root -g root -m 755 \
      \"\$stage_dir/scripts/deploy/remote-deploy.sh\" \
      \"/opt/shop/shop-server/scripts/deploy/remote-deploy.\$deploy_id.sh\"
    sudo install -o root -g root -m 755 \
      \"\$stage_dir/scripts/deploy/backup-mysql.sh\" \
      \"/opt/shop/shop-server/scripts/deploy/backup-mysql.\$deploy_id.sh\"
  "

cleanup_config_stage

case "$transport" in
  remote-build)
    require_clean_worktree
    printf '正在上传精简构建上下文，并由服务器构建 %s（%s）。\n' \
      "$release_image" "$platform"
    COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata \
      -C "$service_dir" -czf - \
      Dockerfile \
      .dockerignore \
      pom.xml \
      mvnw \
      .mvn \
      src |
      ssh "$ssh_target" "
        set -eu
        build_dir=\"\$(mktemp -d /tmp/shop-build.XXXXXX)\"
        cleanup() {
          status=\$?
          trap - EXIT
          trap '' HUP INT TERM
          rm -rf \"\$build_dir\"
          exit \"\$status\"
        }
        trap cleanup EXIT
        trap 'exit 129' HUP
        trap 'exit 130' INT
        trap 'exit 143' TERM
        tar -xzf - -C \"\$build_dir\"
        sudo docker build \
          --platform '$platform' \
          --build-arg 'SHOP_BUILD_GIT_SHA=$revision' \
          --build-arg 'SHOP_BUILD_TIME=$build_time' \
          --tag '$release_image' \
          \"\$build_dir\"
      "
    require_clean_worktree
    ;;
  image-stream)
    for command in docker gzip rsync shasum; do
      command -v "$command" >/dev/null 2>&1 || {
        printf '缺少命令：%s\n' "$command" >&2
        exit 1
      }
    done
    ssh "$ssh_target" '
      set -eu
      for command in rsync sha256sum gzip; do
        command -v "$command" >/dev/null 2>&1 || {
          printf "服务器缺少命令：%s\n" "$command" >&2
          exit 1
        }
      done
    '

    transfer_attempts="${SHOP_DEPLOY_TRANSFER_ATTEMPTS:-3}"
    transfer_retry_delay="${SHOP_DEPLOY_TRANSFER_RETRY_DELAY_SECONDS:-5}"
    if [[ ! "$transfer_attempts" =~ ^[1-9][0-9]*$ ]]; then
      printf 'SHOP_DEPLOY_TRANSFER_ATTEMPTS 必须是大于 0 的整数。\n' >&2
      exit 2
    fi
    if [[ ! "$transfer_retry_delay" =~ ^[0-9]+$ ]]; then
      printf 'SHOP_DEPLOY_TRANSFER_RETRY_DELAY_SECONDS 必须是非负整数。\n' >&2
      exit 2
    fi

    printf '正在本机构建 %s（%s）。\n' "$release_image" "$platform"
    require_clean_worktree
    docker buildx build \
      --platform "$platform" \
      --build-arg "SHOP_BUILD_GIT_SHA=$revision" \
      --build-arg "SHOP_BUILD_TIME=$build_time" \
      --load \
      --tag "$release_image" \
      "$service_dir"
    require_clean_worktree

    image_stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/shop-image.XXXXXX")"
    image_archive="${image_stage_dir}/shop-server.tar.gz"

    printf '正在生成本地压缩镜像包。\n'
    docker image save "$release_image" | gzip -1 >"$image_archive"
    archive_sha256="$(shasum -a 256 "$image_archive" | awk '{print $1}')"
    archive_size="$(wc -c <"$image_archive" | tr -d '[:space:]')"
    remote_archive="/tmp/shop-image-${archive_sha256}.tar.gz"
    printf '压缩镜像大小：%s MiB。\n' \
      "$(((archive_size + 1048575) / 1048576))"

    transfer_complete=false
    transfer_attempt=1
    while ((transfer_attempt <= transfer_attempts)); do
      printf '正在通过 SSH 传输完整压缩镜像（第 %s/%s 次，支持断点续传）。\n' \
        "$transfer_attempt" "$transfer_attempts"

      if rsync \
        --partial \
        --append \
        --progress \
        --timeout=60 \
        -e ssh \
        "$image_archive" \
        "${ssh_target}:${remote_archive}"; then
        if ssh "$ssh_target" \
          "printf '%s  %s\n' '$archive_sha256' '$remote_archive' |
            sha256sum --check --status -"; then
          transfer_complete=true
          break
        fi

        printf '远端镜像包校验失败，将删除损坏文件后重新传输。\n' >&2
        ssh "$ssh_target" "rm -f '$remote_archive'" || true
      fi

      if ((transfer_attempt == transfer_attempts)); then
        printf '完整镜像传输在 %s 次尝试后仍然失败。\n' \
          "$transfer_attempts" >&2
        ssh "$ssh_target" "rm -f '$remote_archive'" || true
        exit 1
      fi

      printf '%s 秒后继续断点续传。\n' "$transfer_retry_delay" >&2
      sleep "$transfer_retry_delay"
      transfer_attempt=$((transfer_attempt + 1))
    done

    if [[ "$transfer_complete" != true ]]; then
      printf '完整镜像传输未完成。\n' >&2
      exit 1
    fi

    printf '传输完成，正在校验并加载镜像。\n'
    ssh "$ssh_target" "
      set -eu
      cleanup() {
        status=\$?
        trap - EXIT
        trap '' HUP INT TERM
        rm -f '$remote_archive'
        exit \"\$status\"
      }
      trap cleanup EXIT
      trap 'exit 129' HUP
      trap 'exit 130' INT
      trap 'exit 143' TERM
      printf '%s  %s\n' '$archive_sha256' '$remote_archive' |
        sha256sum --check --status -
      gzip -dc '$remote_archive' | sudo docker image load
    "
    ;;
esac

printf '正在远程启动 MySQL、Redis 和生产后端。\n'
ssh "$ssh_target" \
  "sudo '$remote_deploy_script' '$release_image' '$revision' '$build_time' '$deploy_id'"

cleanup_remote_stage
printf '部署完成：%s\n' "$release_image"
