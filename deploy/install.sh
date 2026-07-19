#!/usr/bin/env bash
set -euo pipefail

APPLY="${APPLY:-0}"
BUILD="${BUILD:-0}"
START="${START:-0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROOT:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
SERVICE_NAME="${SERVICE_NAME:-browser-ssh-gateway}"
SERVICE_USER="${SERVICE_USER:-browser-ssh}"
SERVICE_UID="${SERVICE_UID:-31002}"
SERVICE_GID="${SERVICE_GID:-31002}"
SERVICE_HOME="${SERVICE_HOME:-/home/browser-ssh}"
INSTALL_DIR="${INSTALL_DIR:-${SERVICE_HOME}/app}"
CONFIG_DIR="${CONFIG_DIR:-/etc/browser-ssh-gateway}"
ENV_FILE="${ENV_FILE:-${CONFIG_DIR}/browser-ssh.env}"
JAR_SOURCE="${JAR_SOURCE:-${ROOT}/browser-ssh-gateway/target/browser-ssh-gateway-0.1.0-SNAPSHOT.jar}"
JAR_DEST="${INSTALL_DIR}/browser-ssh-gateway.jar"
UNIT_SOURCE="${ROOT}/deploy/browser-ssh-gateway.service"
UNIT_DEST="/etc/systemd/system/${SERVICE_NAME}.service"
CLIENT_SOURCE="${ROOT}/deploy/browser-ssh-broker-client"
CLIENT_DEST="${SERVICE_HOME}/bin/browser-ssh-broker-client"

run() {
  if [[ "$APPLY" == "1" ]]; then
    printf '+'
    printf ' %q' "$@"
    printf '\n'
    "$@"
  else
    printf '[dry-run]'
    printf ' %q' "$@"
    printf '\n'
  fi
}

require_root() {
  if [[ "$APPLY" == "1" && "$(id -u)" != "0" ]]; then
    printf 'run with sudo when APPLY=1\n' >&2
    exit 1
  fi
}

build_jar() {
  if [[ "$BUILD" != "1" ]]; then
    printf 'skip build: BUILD=%s\n' "$BUILD"
    return
  fi
  run docker run --rm --network host \
    -v "${ROOT}:/workspace" \
    -w /workspace \
    maven:3.9-eclipse-temurin-21 \
    mvn -q test package
}

create_service_user() {
  if getent passwd "$SERVICE_USER" >/dev/null; then
    printf 'exists: user %s\n' "$SERVICE_USER"
    if [[ "$(id -u "$SERVICE_USER")" != "$SERVICE_UID" ]]; then
      printf 'unexpected uid for %s: expected %s, got %s\n' \
        "$SERVICE_USER" "$SERVICE_UID" "$(id -u "$SERVICE_USER")" >&2
      exit 1
    fi
    if [[ "$(id -g "$SERVICE_USER")" != "$SERVICE_GID" ]]; then
      printf 'unexpected gid for %s: expected %s, got %s\n' \
        "$SERVICE_USER" "$SERVICE_GID" "$(id -g "$SERVICE_USER")" >&2
      exit 1
    fi
    return
  fi
  run groupadd --gid "$SERVICE_GID" "$SERVICE_USER"
  run useradd --uid "$SERVICE_UID" --gid "$SERVICE_GID" --create-home \
    --home-dir "$SERVICE_HOME" --shell /bin/bash "$SERVICE_USER"
  run passwd --lock "$SERVICE_USER"
}

install_environment() {
  run install -d -o root -g "$SERVICE_USER" -m 0750 "$CONFIG_DIR"
  if [[ "$APPLY" == "1" && ! -e "$ENV_FILE" ]]; then
    run install -o root -g "$SERVICE_USER" -m 0640 \
      "$ROOT/deploy/browser-ssh.env.example" "$ENV_FILE"
  elif [[ "$APPLY" != "1" ]]; then
    printf '[dry-run] create %s from env example when missing\n' "$ENV_FILE"
  else
    printf 'exists: %s\n' "$ENV_FILE"
  fi
}

validate_environment() {
  if [[ "$START" != "1" || "$APPLY" != "1" ]]; then
    return
  fi
  local invalid=0
  for pattern in 'replace.cloudflareaccess.com' 'replace-with-access-aud' 'replace@example.com'; do
    if grep -Fq "$pattern" "$ENV_FILE"; then
      printf 'replace placeholder in %s: %s\n' "$ENV_FILE" "$pattern" >&2
      invalid=1
    fi
  done
  if [[ "$invalid" == "1" ]]; then
    exit 1
  fi
}

validate_runtime_dependencies() {
  if [[ "$APPLY" != "1" ]]; then
    return
  fi
  if [[ ! -x /usr/bin/zip ]]; then
    printf 'missing /usr/bin/zip; install the zip package before deployment\n' >&2
    exit 1
  fi
  for dependency in /usr/bin/ssh /usr/bin/base64; do
    if [[ ! -x "$dependency" ]]; then
      printf 'missing runtime dependency: %s\n' "$dependency" >&2
      exit 1
    fi
  done
}

wait_for_health() {
  local elapsed=0
  while (( elapsed < 60 )); do
    if curl -fsS http://127.0.0.1:9998/actuator/health; then
      printf '\n'
      return
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  printf 'health check failed\n' >&2
  return 1
}

require_root
build_jar
validate_runtime_dependencies

if [[ "$APPLY" == "1" && ! -r "$JAR_SOURCE" ]]; then
  printf 'missing jar: %s; rerun with BUILD=1\n' "$JAR_SOURCE" >&2
  exit 1
fi

create_service_user
run install -d -o root -g "$SERVICE_USER" -m 0750 "$INSTALL_DIR"
run install -o root -g "$SERVICE_USER" -m 0640 "$JAR_SOURCE" "$JAR_DEST"
run install -d -o root -g "$SERVICE_USER" -m 0750 "${SERVICE_HOME}/bin"
run install -o root -g "$SERVICE_USER" -m 0750 "$CLIENT_SOURCE" "$CLIENT_DEST"
install_environment
run install -o root -g root -m 0644 "$UNIT_SOURCE" "$UNIT_DEST"
run systemctl daemon-reload
validate_environment

if [[ "$START" == "1" && "$APPLY" == "1" ]]; then
  for broker_file in broker-client-key broker-known-hosts; do
    if [[ ! -r "${CONFIG_DIR}/${broker_file}" ]]; then
      printf 'missing broker client file: %s\n' "${CONFIG_DIR}/${broker_file}" >&2
      exit 1
    fi
  done
fi

if [[ "$START" == "1" ]]; then
  run systemctl enable "$SERVICE_NAME.service"
  run systemctl restart "$SERVICE_NAME.service"
  if [[ "$APPLY" == "1" ]]; then
    wait_for_health
    systemctl --no-pager --full status "$SERVICE_NAME.service" || true
  fi
else
  printf 'service installed but not enabled or started: START=%s\n' "$START"
fi
