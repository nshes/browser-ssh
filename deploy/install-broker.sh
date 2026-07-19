#!/usr/bin/env bash
set -euo pipefail

APPLY="${APPLY:-0}"
START="${START:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROOT:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
GATEWAY_USER="${GATEWAY_USER:-browser-ssh}"
GATEWAY_CONFIG_DIR="${GATEWAY_CONFIG_DIR:-/etc/browser-ssh-gateway}"
BROKER_USER="${BROKER_USER:-browser-ssh-broker}"
BROKER_UID="${BROKER_UID:-31003}"
BROKER_GID="${BROKER_GID:-31003}"
BROKER_HOME="${BROKER_HOME:-/var/lib/browser-ssh-broker}"
BROKER_CONFIG_DIR="${BROKER_CONFIG_DIR:-/etc/browser-ssh-broker}"
BROKER_PORT="${BROKER_PORT:-10022}"

run() {
  if [[ "$APPLY" == "1" ]]; then
    printf '+'; printf ' %q' "$@"; printf '\n'
    "$@"
  else
    printf '[dry-run]'; printf ' %q' "$@"; printf '\n'
  fi
}

[[ "$BROKER_PORT" =~ ^[0-9]+$ ]] && (( BROKER_PORT >= 1024 && BROKER_PORT <= 65535 )) \
  || { printf 'BROKER_PORT must be an unprivileged TCP port\n' >&2; exit 2; }
[[ "$BROKER_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] \
  || { printf 'invalid BROKER_USER\n' >&2; exit 2; }
if [[ "$APPLY" == "1" && "$(id -u)" != "0" ]]; then
  printf 'run with sudo when APPLY=1\n' >&2
  exit 1
fi
getent passwd "$GATEWAY_USER" >/dev/null \
  || { printf 'gateway user does not exist: %s\n' "$GATEWAY_USER" >&2; exit 1; }

if ! getent passwd "$BROKER_USER" >/dev/null; then
  uid_owner="$(getent passwd "$BROKER_UID" | cut -d: -f1 || true)"
  if [[ -n "$uid_owner" ]]; then
    printf 'broker UID %s is already assigned to %s; set BROKER_UID to an unused value\n' \
      "$BROKER_UID" "$uid_owner" >&2
    exit 1
  fi

  if getent group "$BROKER_USER" >/dev/null; then
    [[ "$(getent group "$BROKER_USER" | cut -d: -f3)" == "$BROKER_GID" ]] \
      || { printf 'broker group %s has an unexpected GID\n' "$BROKER_USER" >&2; exit 1; }
  else
    gid_owner="$(getent group "$BROKER_GID" | cut -d: -f1 || true)"
    if [[ -n "$gid_owner" ]]; then
      printf 'broker GID %s is already assigned to %s; set BROKER_GID to an unused value\n' \
        "$BROKER_GID" "$gid_owner" >&2
      exit 1
    fi
    run groupadd --gid "$BROKER_GID" "$BROKER_USER"
  fi
  if [[ "$APPLY" == "1" ]]; then
    command -v openssl >/dev/null || { printf 'missing openssl\n' >&2; exit 1; }
    random_password="$(openssl rand -base64 48)"
    password_hash="$(printf '%s' "$random_password" | openssl passwd -6 -stdin)"
    unset random_password
    useradd --uid "$BROKER_UID" --gid "$BROKER_GID" --create-home \
      --home-dir "$BROKER_HOME" --shell /bin/bash --password "$password_hash" "$BROKER_USER"
    unset password_hash
  else
    printf '[dry-run] create broker user %s with a discarded random password and password SSH disabled\n' "$BROKER_USER"
  fi
else
  [[ "$(id -u "$BROKER_USER")" == "$BROKER_UID" ]] \
    || { printf 'unexpected broker UID\n' >&2; exit 1; }
  [[ "$(id -g "$BROKER_USER")" == "$BROKER_GID" ]] \
    || { printf 'unexpected broker GID\n' >&2; exit 1; }
fi

run install -d -o root -g "$BROKER_USER" -m 0750 "$BROKER_HOME"

run install -d -o root -g "$BROKER_USER" -m 0750 \
  "$BROKER_CONFIG_DIR" "$BROKER_CONFIG_DIR/targets" \
  "$BROKER_CONFIG_DIR/keys" "$BROKER_CONFIG_DIR/known-hosts"
run install -d -o root -g "$GATEWAY_USER" -m 0750 "$GATEWAY_CONFIG_DIR"
run install -d -o root -g root -m 0755 /usr/local/libexec
run install -o root -g root -m 0755 \
  "$ROOT/deploy/browser-ssh-broker" /usr/local/libexec/browser-ssh-broker
run install -o root -g root -m 0644 \
  "$ROOT/deploy/browser-ssh-broker.service" /etc/systemd/system/browser-ssh-broker.service

if [[ "$APPLY" == "1" ]]; then
  sed -e "s/^Port .*/Port ${BROKER_PORT}/" \
    -e "s/^AllowUsers .*/AllowUsers ${BROKER_USER}/" \
    "$ROOT/deploy/browser-ssh-broker-sshd.conf" \
    | install -o root -g root -m 0644 /dev/stdin "$BROKER_CONFIG_DIR/sshd_config"
else
  printf '[dry-run] install broker sshd config with port %s\n' "$BROKER_PORT"
fi

host_key="$BROKER_CONFIG_DIR/ssh_host_ed25519_key"
client_key="$GATEWAY_CONFIG_DIR/broker-client-key"
if [[ "$APPLY" == "1" ]]; then
  if [[ ! -f "$host_key" ]]; then
    ssh-keygen -q -t ed25519 -N '' -C browser-ssh-broker-host -f "$host_key"
  fi
  chown root:root "$host_key" "${host_key}.pub"
  chmod 0600 "$host_key"
  chmod 0644 "${host_key}.pub"

  if [[ ! -f "$client_key" ]]; then
    ssh-keygen -q -t ed25519 -N '' -C browser-ssh-gateway -f "$client_key"
  fi
  chown root:"$GATEWAY_USER" "$client_key"
  chmod 0640 "$client_key"
  chown root:root "${client_key}.pub"
  chmod 0644 "${client_key}.pub"

  public_key="$(<"${client_key}.pub")"
  printf '%s %s\n' \
    'from="127.0.0.1",no-agent-forwarding,no-port-forwarding,no-X11-forwarding,no-user-rc,pty' \
    "$public_key" \
    | install -o root -g root -m 0644 /dev/stdin "$BROKER_CONFIG_DIR/authorized_keys"

  read -r key_type key_data _ <"${host_key}.pub"
  printf '[127.0.0.1]:%s %s %s\n' "$BROKER_PORT" "$key_type" "$key_data" \
    | install -o root -g "$GATEWAY_USER" -m 0640 /dev/stdin \
      "$GATEWAY_CONFIG_DIR/broker-known-hosts"

  if [[ ! -e "$BROKER_CONFIG_DIR/targets/default.conf.example" ]]; then
    install -o root -g "$BROKER_USER" -m 0640 \
      "$ROOT/deploy/target.conf.example" "$BROKER_CONFIG_DIR/targets/default.conf.example"
  fi
else
  printf '[dry-run] create dedicated broker host and gateway client ED25519 keys when missing\n'
  printf '[dry-run] install constrained gateway public key and pinned broker host key\n'
fi

run systemctl daemon-reload
if [[ "$START" == "1" ]]; then
  run /usr/sbin/sshd -t -f "$BROKER_CONFIG_DIR/sshd_config"
  run systemctl enable --now browser-ssh-broker.service
else
  printf 'broker installed but not enabled or started: START=%s\n' "$START"
fi
