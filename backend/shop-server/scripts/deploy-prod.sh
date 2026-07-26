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
release_image="shop-server:${revision}-${timestamp}"

case "$transport" in
  remote-build|image-stream) ;;
  *)
    printf '不支持的 SHOP_DEPLOY_TRANSPORT：%s\n' "$transport" >&2
    printf '可选值：remote-build、image-stream。\n' >&2
    exit 2
    ;;
esac

for command in ssh tar; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

"${script_dir}/validate-prod-env.sh"

if [[ "${SHOP_DEPLOY_SKIP_TESTS:-false}" != true ]]; then
  (cd "$service_dir" && ./mvnw test)
fi

printf '正在通过 SSH 上传部署配置。\n'
COPYFILE_DISABLE=1 tar -C "$service_dir" -czf - \
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
    sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server/secrets
    sudo install -d -o 10001 -g 10001 -m 750 /opt/shop/shop-server/var/uploads
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
    COPYFILE_DISABLE=1 tar -C "$service_dir" -czf - \
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
          --tag '$release_image' \
          \"\$build_dir\"
      "
    ;;
  image-stream)
    for command in docker gzip; do
      command -v "$command" >/dev/null 2>&1 || {
        printf '缺少命令：%s\n' "$command" >&2
        exit 1
      }
    done
    printf '正在本机构建 %s（%s）。\n' "$release_image" "$platform"
    docker buildx build \
      --platform "$platform" \
      --load \
      --tag "$release_image" \
      "$service_dir"
    printf '正在通过 SSH 传输完整压缩镜像。\n'
    docker image save "$release_image" |
      gzip -1 |
      ssh "$ssh_target" 'gzip -dc | sudo docker image load'
    ;;
esac

printf '正在远程启动 MySQL、Redis 和生产后端。\n'
ssh "$ssh_target" "sudo /opt/shop/shop-server/scripts/remote-deploy.sh '$release_image'"

printf '部署完成：%s\n' "$release_image"
