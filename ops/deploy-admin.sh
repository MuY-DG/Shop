#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd -- "${script_dir}/.." && pwd)"
dist_dir="${repository_dir}/admin/dist"
ssh_target="${1:-txcloud}"
revision="$(git -C "$repository_dir" rev-parse --short=12 HEAD)"
release_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
release_id="${revision}-${release_stamp}"

for command in curl git ssh tar; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

if [[ -n "$(git -C "$repository_dir" status --porcelain --untracked-files=normal)" ]]; then
  printf '工作区存在未提交改动，拒绝使用不准确的 Git SHA 部署 Admin。\n' >&2
  printf '请先完成验证并提交本次发布范围，再重新执行部署。\n' >&2
  exit 1
fi

if [[ ! -f "${dist_dir}/index.html" ]]; then
  printf '未找到 Admin 构建产物：%s\n' "${dist_dir}/index.html" >&2
  printf '请先在 admin 目录运行 pnpm check、CI=true pnpm build 和 pnpm check:generated-imports。\n' >&2
  exit 1
fi

printf '正在部署 Admin 静态版本 %s。\n' "$release_id"

COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata \
  -C "$dist_dir" -czf - . |
  ssh "$ssh_target" "
    set -Eeuo pipefail
    site_root=/opt/1panel/www/sites/admin.muybaby6.icu
    release_root=\"\$site_root/releases\"
    release_dir=\"\$release_root/$release_id\"
    stage_dir=\"\$(mktemp -d /tmp/shop-admin-release.XXXXXX)\"
    previous_index_kind=missing
    previous_index_target=''
    bootstrap_backup=''
    deployment_switched=false

    cleanup() {
      if [[ -z \"\$stage_dir\" ]]; then
        return
      fi
      case \"\$stage_dir\" in
        /tmp/shop-admin-release.*) rm -rf -- \"\$stage_dir\" ;;
        *) printf '拒绝清理异常临时目录：%s\\n' \"\$stage_dir\" >&2 ;;
      esac
    }

    rollback() {
      local status=\$?
      cleanup
      if [[ \$status -ne 0 && \"\$deployment_switched\" == true ]]; then
        printf 'Admin 健康检查失败，正在恢复部署前站点。\\n' >&2
        rm -f -- \"\$site_root/index\"
        if [[ \"\$previous_index_kind\" == symlink ]]; then
          rollback_link=\"\$site_root/.index-rollback-$release_id\"
          ln -s \"\$previous_index_target\" \"\$rollback_link\"
          mv -Tf \"\$rollback_link\" \"\$site_root/index\"
        elif [[ \"\$previous_index_kind\" == directory ]]; then
          mv \"\$bootstrap_backup\" \"\$site_root/index\"
        fi
      fi
      exit \"\$status\"
    }
    trap rollback EXIT

    tar -xzf - -C \"\$stage_dir\"
    test -f \"\$stage_dir/index.html\"
    install -d -o root -g root -m 755 \"\$release_root\"
    if [[ -e \"\$release_dir\" ]]; then
      printf '目标版本目录已存在：%s\\n' \"\$release_dir\" >&2
      exit 1
    fi
    mv \"\$stage_dir\" \"\$release_dir\"
    stage_dir=''
    chown -R root:root \"\$release_dir\"
    find \"\$release_dir\" -type d -exec chmod 755 {} +
    find \"\$release_dir\" -type f -exec chmod 644 {} +

    if [[ -L \"\$site_root/index\" ]]; then
      previous_index_kind=symlink
      previous_index_target=\"\$(readlink \"\$site_root/index\")\"
    elif [[ -d \"\$site_root/index\" ]]; then
      previous_index_kind=directory
      bootstrap_backup=\"\$site_root/index.bootstrap-$release_stamp\"
      deployment_switched=true
      mv \"\$site_root/index\" \"\$bootstrap_backup\"
    elif [[ -e \"\$site_root/index\" ]]; then
      printf '站点 index 既不是目录也不是软链接，拒绝覆盖。\\n' >&2
      exit 1
    fi
    next_link=\"\$site_root/.index-$release_id\"
    ln -s \"releases/$release_id\" \"\$next_link\"
    deployment_switched=true
    mv -Tf \"\$next_link\" \"\$site_root/index\"

    curl --fail --silent --show-error \
      --retry 3 --retry-delay 1 \
      --resolve admin.muybaby6.icu:443:127.0.0.1 \
      https://admin.muybaby6.icu/ >/dev/null

    trap - EXIT
  "

curl --fail --silent --show-error \
  --retry 3 --retry-delay 1 \
  https://admin.muybaby6.icu/ >/dev/null

printf 'Admin 已部署并通过 HTTPS 首页检查：%s\n' "$release_id"
