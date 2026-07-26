#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd -- "${script_dir}/.." && pwd)"
credentials_file="${service_dir}/.1panel.local"
ssh_target="${1:-txcloud}"

for command in awk mktemp openssl ssh; do
  command -v "$command" >/dev/null 2>&1 || {
    printf '缺少命令：%s\n' "$command" >&2
    exit 1
  }
done

[[ -f "$credentials_file" ]] || {
  printf '缺少 1Panel 本机登录信息：%s\n' "$credentials_file" >&2
  exit 1
}

umask 077
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

new_password="$(openssl rand -hex 18)"
password_file="${temporary_dir}/password"
updated_credentials="${temporary_dir}/.1panel.local"
printf '%s\n' "$new_password" >"$password_file"
printf '%s\n' "$new_password" >>"$password_file"

awk -v password="$new_password" '
  BEGIN { password_written = 0; marker_written = 0 }
  /^PANEL_PASSWORD=/ {
    print "PANEL_PASSWORD=" password
    password_written = 1
    next
  }
  /^PANEL_PASSWORD_ROTATED=/ {
    print "PANEL_PASSWORD_ROTATED=true"
    marker_written = 1
    next
  }
  { print }
  END {
    if (!password_written) {
      print "PANEL_PASSWORD=" password
    }
    if (!marker_written) {
      print "PANEL_PASSWORD_ROTATED=true"
    }
  }
' "$credentials_file" >"$updated_credentials"

if ! ssh -tt "$ssh_target" \
  'sudo /usr/bin/1panel -l zh update password' \
  <"$password_file" >/dev/null 2>&1; then
  printf '1Panel 密码轮换失败，本机登录信息未修改。\n' >&2
  exit 1
fi

install -m 600 "$updated_credentials" "$credentials_file"
printf '1Panel 密码已轮换，本机登录信息已安全更新（未打印密码）。\n'
