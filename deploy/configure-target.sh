#!/usr/bin/env bash
set -euo pipefail

APPLY="${APPLY:-0}"
TARGET_ID="${TARGET_ID:-default}"
TARGET_HOST="${TARGET_HOST:-}"
TARGET_PORT="${TARGET_PORT:-22}"
TARGET_USER="${TARGET_USER:-}"
TARGET_REMOTE_HOME="${TARGET_REMOTE_HOME:-}"
TARGET_UPLOAD_ROOT="${TARGET_UPLOAD_ROOT:-${TARGET_REMOTE_HOME}/uploads}"
TARGET_DOWNLOAD_ROOT="${TARGET_DOWNLOAD_ROOT:-${TARGET_REMOTE_HOME}}"
TARGET_HOST_KEY_SHA256="${TARGET_HOST_KEY_SHA256:-}"
BROKER_USER="${BROKER_USER:-browser-ssh-broker}"
CONFIG_ROOT="${CONFIG_ROOT:-/etc/browser-ssh-broker}"

fail() { printf '%s\n' "$1" >&2; exit 2; }
[[ "$TARGET_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail 'invalid TARGET_ID'
[[ "$TARGET_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.:%_-]{0,252}$ ]] || fail 'invalid TARGET_HOST'
[[ "$TARGET_PORT" =~ ^[0-9]+$ ]] && (( TARGET_PORT >= 1 && TARGET_PORT <= 65535 )) \
  || fail 'invalid TARGET_PORT'
[[ "$TARGET_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || fail 'invalid TARGET_USER'
for path in "$TARGET_REMOTE_HOME" "$TARGET_UPLOAD_ROOT" "$TARGET_DOWNLOAD_ROOT"; do
  [[ "$path" == /* && "$path" != *$'\n'* && "$path" != *$'\r'* ]] \
    || fail 'target paths must be absolute and single-line'
done
[[ "$TARGET_HOST_KEY_SHA256" =~ ^SHA256:[A-Za-z0-9+/=]+$ ]] \
  || fail 'TARGET_HOST_KEY_SHA256 is required to prevent trust-on-first-use'
if [[ "$APPLY" == "1" && "$(id -u)" != "0" ]]; then
  fail 'run with sudo when APPLY=1'
fi
getent passwd "$BROKER_USER" >/dev/null || fail 'broker user does not exist'

key_path="${CONFIG_ROOT}/keys/${TARGET_ID}"
known_hosts="${CONFIG_ROOT}/known-hosts/${TARGET_ID}"
target_file="${CONFIG_ROOT}/targets/${TARGET_ID}.conf"

if [[ "$APPLY" != "1" ]]; then
  printf 'dry run: configure target %s as %s@%s:%s\n' \
    "$TARGET_ID" "$TARGET_USER" "$TARGET_HOST" "$TARGET_PORT"
  printf 'key: %s\nknown hosts: %s\ntarget config: %s\n' \
    "$key_path" "$known_hosts" "$target_file"
  exit 0
fi

install -d -o root -g "$BROKER_USER" -m 0750 \
  "$CONFIG_ROOT/keys" "$CONFIG_ROOT/known-hosts" "$CONFIG_ROOT/targets"
if [[ ! -f "$key_path" ]]; then
  ssh-keygen -q -t ed25519 -N '' -C "browser-ssh-target-${TARGET_ID}" -f "$key_path"
fi
chown root:"$BROKER_USER" "$key_path"
chmod 0640 "$key_path"
chown root:root "${key_path}.pub"
chmod 0644 "${key_path}.pub"

scan="$(mktemp)"
trap 'rm -f "$scan"' EXIT
ssh-keyscan -T 10 -t ed25519 -p "$TARGET_PORT" "$TARGET_HOST" >"$scan" 2>/dev/null
mapfile -t actual_fingerprints < <(ssh-keygen -lf "$scan" -E sha256 | awk '{ print $2 }' | sort -u)
[[ "${#actual_fingerprints[@]}" -eq 1 && "${actual_fingerprints[0]}" == "$TARGET_HOST_KEY_SHA256" ]] \
  || fail 'target SSH host key fingerprint mismatch'
install -o root -g "$BROKER_USER" -m 0640 "$scan" "$known_hosts"

{
  printf 'TARGET_HOST=%s\n' "$TARGET_HOST"
  printf 'TARGET_PORT=%s\n' "$TARGET_PORT"
  printf 'TARGET_USER=%s\n' "$TARGET_USER"
  printf 'TARGET_KEY=%s\n' "$key_path"
  printf 'TARGET_KNOWN_HOSTS=%s\n' "$known_hosts"
  printf 'TARGET_REMOTE_HOME=%s\n' "$(realpath -m -- "$TARGET_REMOTE_HOME")"
  printf 'TARGET_UPLOAD_ROOT=%s\n' "$(realpath -m -- "$TARGET_UPLOAD_ROOT")"
  printf 'TARGET_DOWNLOAD_ROOT=%s\n' "$(realpath -m -- "$TARGET_DOWNLOAD_ROOT")"
} | install -o root -g "$BROKER_USER" -m 0640 /dev/stdin "$target_file"

printf 'target configured: %s\n' "$TARGET_ID"
printf 'install this public key for %s@%s with forwarding disabled:\n' "$TARGET_USER" "$TARGET_HOST"
cat "${key_path}.pub"
