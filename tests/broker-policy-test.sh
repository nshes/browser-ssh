#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/config/targets" "$work/config/keys" "$work/config/known-hosts"
mkdir -p "$work/remote/uploads" "$work/outside"
touch "$work/config/keys/default" "$work/config/known-hosts/default"
cat >"$work/config/targets/default.conf" <<EOF
TARGET_HOST=127.0.0.1
TARGET_PORT=22
TARGET_USER=operator
TARGET_KEY=$work/config/keys/default
TARGET_KNOWN_HOSTS=$work/config/known-hosts/default
TARGET_REMOTE_HOME=$work/remote
TARGET_UPLOAD_ROOT=$work/remote/uploads
TARGET_DOWNLOAD_ROOT=$work/remote
EOF
cat >"$work/fake-ssh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
command="${!#}"
exec /bin/bash -c "$command"
EOF
chmod 0700 "$work/fake-ssh"

encode_path() {
  printf '%s' "$1" | base64 -w 0 | tr '+/' '-_' | tr -d '='
}

expect_rejected() {
  local command="$1" expected="$2" output
  if output="$(BROWSER_SSH_BROKER_CONFIG_ROOT="$work/config" \
      BROWSER_SSH_BROKER_SSH_BIN="$work/fake-ssh" \
      SSH_ORIGINAL_COMMAND="$command" \
      "$ROOT/deploy/browser-ssh-broker" 2>&1)"; then
    printf 'expected broker request to fail: %s\n' "$command" >&2
    exit 1
  fi
  [[ "$output" == *"$expected"* ]] || {
    printf 'unexpected broker error: %s\n' "$output" >&2
    exit 1
  }
}

expect_rejected \
  "v1 default upload 10 $(encode_path "$work/remote/uploads/../.ssh/authorized_keys")" \
  'outside the allowed root'
expect_rejected \
  "v1 default download-file 10 $(encode_path '/etc/passwd')" \
  'outside the allowed root'
expect_rejected \
  'v1 ../default terminal 0 -' \
  'invalid target id'
expect_rejected \
  'v1 default terminal 0 - unexpected' \
  'invalid broker request'
expect_rejected \
  "v1 default upload 104857601 $(encode_path '/home/operator/uploads/file')" \
  'upload exceeds broker limit'

ln -s "$work/outside" "$work/remote/uploads/link"
expect_rejected \
  "v1 default upload 3 $(encode_path "$work/remote/uploads/link/escaped.txt")" \
  'outside the allowed root'

printf 'abc' | BROWSER_SSH_BROKER_CONFIG_ROOT="$work/config" \
  BROWSER_SSH_BROKER_SSH_BIN="$work/fake-ssh" \
  SSH_ORIGINAL_COMMAND="v1 default upload 3 $(encode_path "$work/remote/uploads/allowed.txt")" \
  "$ROOT/deploy/browser-ssh-broker"
[[ "$(<"$work/remote/uploads/allowed.txt")" == 'abc' ]] \
  || { printf 'allowed broker upload did not preserve bytes\n' >&2; exit 1; }

inspection="$(BROWSER_SSH_BROKER_CONFIG_ROOT="$work/config" \
  BROWSER_SSH_BROKER_SSH_BIN="$work/fake-ssh" \
  SSH_ORIGINAL_COMMAND="v1 default inspect 2147483648 $(encode_path "$work/remote")" \
  "$ROOT/deploy/browser-ssh-broker")"
[[ "$inspection" == DIRECTORY$'\t'* ]] \
  || { printf 'download root inspection was rejected\n' >&2; exit 1; }

printf 'broker policy tests passed\n'
