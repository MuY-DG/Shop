#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/.." && pwd)"
credentials_file="${service_dir}/.1panel.local"
ssh_target="${1:-txcloud}"
remote_credentials="/tmp/shop-1panel-install.local"

for command in openssl scp ssh; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

umask 077

if [[ ! -f "$credentials_file" ]]; then
  panel_entrance="shop$(openssl rand -hex 8)"
  panel_password="$(openssl rand -hex 14)"
  {
    printf '# 1Panel 本机保存的登录信息；禁止提交 Git 或发送到聊天中。\n'
    printf 'PANEL_PORT=18080\n'
    printf 'PANEL_ENTRANCE=%s\n' "$panel_entrance"
    printf 'PANEL_USERNAME=shopadmin\n'
    printf 'PANEL_PASSWORD=%s\n' "$panel_password"
  } >"$credentials_file"
fi

chmod 600 "$credentials_file"
scp -p "$credentials_file" "${ssh_target}:${remote_credentials}"

ssh "$ssh_target" '
  set -eu
  remote_credentials=/tmp/shop-1panel-install.local
  sudo install -o root -g root -m 600 "$remote_credentials" /root/.1panel-install.local

  cleanup() {
    sudo rm -f /root/.1panel-install.local
    rm -f "$remote_credentials"
  }
  trap cleanup EXIT

  if command -v 1pctl >/dev/null 2>&1; then
    printf "1Panel 已安装，跳过重复安装。\n"
    exit 0
  fi

  sudo bash -c '\''
    set -eu
    set -a
    . /root/.1panel-install.local
    set +a
    export PANEL_NON_INTERACTIVE=true
    export PANEL_LANG=zh
    export PANEL_INSTALL_DIR=/opt
    export PANEL_INSTALL_DOCKER=y
    export PANEL_DOCKER_MODE=auto
    export PANEL_CONFIGURE_ACCELERATOR=n
    export PANEL_REPLACE_DAEMON_JSON=n
    bash -c "$(curl -sSL https://resource.fit2cloud.com/1panel/package/v2/quick_start.sh)"
  '\''
'

if ! grep -q '^PANEL_PASSWORD_ROTATED=true$' "$credentials_file"; then
  "${script_dir}/secure-1panel.sh" "$ssh_target"
fi

printf '1Panel/Docker 初始化完成；登录信息保存在 %s（未打印）。\n' "$credentials_file"
