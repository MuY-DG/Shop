#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/.." && pwd)"
repository_dir="$(cd -- "${service_dir}/../.." && pwd)"
ssh_target="${1:-txcloud}"
platform="${SHOP_DEPLOY_PLATFORM:-linux/amd64}"
transport="${SHOP_DEPLOY_TRANSPORT:-remote-build}"
revision="$(git -C "$repository_dir" rev-parse --short=12 HEAD)"
timestamp="$(date -u +%Y%m%d%H%M%S)"
build_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
release_image="shop-server:${revision}-${timestamp}"

case "$transport" in
  remote-build|image-stream) ;;
  *)
    printf '不支持的 SHOP_DEPLOY_TRANSPORT：%s\n' "$transport" >&2
    printf '可选值：remote-build、image-stream。\n' >&2
    exit 2
    ;;
esac

for command in git ssh tar; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

if [[ -n "$(git -C "$repository_dir" status --porcelain --untracked-files=normal)" ]]; then
  printf '工作区存在未提交改动，拒绝使用不准确的 Git SHA 部署。\n' >&2
  printf '请先完成验证并提交本次发布范围，再重新执行部署。\n' >&2
  exit 1
fi

"${script_dir}/validate-prod-env.sh"
"${script_dir}/verify-flyway-migrations.sh"
"${script_dir}/verify-test-layers.sh"

if [[ "${SHOP_DEPLOY_SKIP_TESTS:-false}" != true ]]; then
  printf '正在运行无 Docker 单元/H2 测试层（不包含 Testcontainers）。\n'
  (cd "$service_dir" && ./mvnw test)
  printf '正在运行 Docker/Testcontainers 集成测试层。\n'
  (cd "$service_dir" && ./mvnw -Pintegration verify)
  "${script_dir}/assert-integration-test-results.sh" "${service_dir}/target/failsafe-reports"
fi

printf '正在通过 SSH 上传部署配置。\n'
COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata \
  -C "$service_dir" -czf - \
  compose.prod.yaml \
  .env.prod.local \
  .env.infrastructure.local \
  scripts/remote-deploy.sh \
  scripts/backup-mysql.sh |
  ssh "$ssh_target" '
    set -eu
    stage_dir="$(mktemp -d /tmp/shop-deploy.XXXXXX)"
    tar -xzf - -C "$stage_dir"
    sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server
    # 仅兼容尚未导入数据库的旧支付 PEM；完成迁移后删除 secrets 目录处理。
    sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server/secrets
    sudo install -d -o root -g root -m 700 /opt/shop/shop-server/backups
    sudo install -o root -g root -m 644 "$stage_dir/compose.prod.yaml" /opt/shop/shop-server/compose.prod.yaml
    sudo install -o 10001 -g 10001 -m 600 "$stage_dir/.env.prod.local" /opt/shop/shop-server/.env.prod.local
    sudo install -o root -g root -m 600 "$stage_dir/.env.infrastructure.local" /opt/shop/shop-server/.env.infrastructure.local
    sudo install -d -o root -g root -m 755 /opt/shop/shop-server/scripts
    sudo install -o root -g root -m 755 "$stage_dir/scripts/remote-deploy.sh" /opt/shop/shop-server/scripts/remote-deploy.sh
    sudo install -o root -g root -m 755 "$stage_dir/scripts/backup-mysql.sh" /opt/shop/shop-server/scripts/backup-mysql.sh
    sudo find /opt/shop/shop-server/secrets -type f -exec chown 10001:10001 {} \;
    sudo find /opt/shop/shop-server/secrets -type f -exec chmod 600 {} \;
  '

case "$transport" in
  remote-build)
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
          rm -rf \"\$build_dir\"
        }
        trap cleanup EXIT
        tar -xzf - -C \"\$build_dir\"
        sudo docker build \
          --platform '$platform' \
          --build-arg 'SHOP_BUILD_GIT_SHA=$revision' \
          --build-arg 'SHOP_BUILD_TIME=$build_time' \
          --tag '$release_image' \
          \"\$build_dir\"
      "
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
    docker buildx build \
      --platform "$platform" \
      --build-arg "SHOP_BUILD_GIT_SHA=$revision" \
      --build-arg "SHOP_BUILD_TIME=$build_time" \
      --load \
      --tag "$release_image" \
      "$service_dir"

    image_stage_dir="$(mktemp -d "${TMPDIR:-/tmp}/shop-image.XXXXXX")"
    image_archive="${image_stage_dir}/shop-server.tar.gz"
    cleanup_image_archive() {
      rm -rf "$image_stage_dir"
    }
    trap cleanup_image_archive EXIT

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
        rm -f '$remote_archive'
      }
      trap cleanup EXIT
      printf '%s  %s\n' '$archive_sha256' '$remote_archive' |
        sha256sum --check --status -
      gzip -dc '$remote_archive' | sudo docker image load
    "
    ;;
esac

printf '正在远程启动 MySQL、Redis 和生产后端。\n'
ssh "$ssh_target" \
  "sudo /opt/shop/shop-server/scripts/remote-deploy.sh '$release_image' '$revision' '$build_time'"

printf '部署完成：%s\n' "$release_image"
