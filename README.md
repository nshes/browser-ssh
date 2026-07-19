# Browser SSH

Self-hosted browser terminal for SSH-accessible Linux hosts. The web gateway
is protected by Cloudflare Access and cannot read the SSH keys used for target
servers. A dedicated localhost OpenSSH broker owns those keys and resolves a
configured target ID to a fixed host, port, user and path policy.

OpenStack is not required. A target can be a local machine, a VM, a bare-metal
server or any other Linux host reachable from the broker.

## Key Features

- right-click copies the current terminal selection, or pastes from the
  clipboard when no text is selected
- two independent SSH panes, shown side by side on desktop and stacked on
  narrow screens
- xterm.js terminal with resize, Ctrl/Cmd-click links and IME support
- streamed file upload and single-use file or directory download
- Cloudflare Access identity validation at the application origin
- isolated SSH broker with target-only keys and pinned host keys
- configurable session concurrency, idle, duration, input-rate and transfer
  limits
- provider-neutral Java core for embedding the terminal protocol and session
  policy in another Spring application

The repository contains two Maven modules:

- `browser-ssh-core`: provider-neutral terminal messages, dimensions, idle and
  hard-duration tracking, and input-rate enforcement for embedding in another
  Java application.
- `browser-ssh-gateway`: the standalone Cloudflare Access application shown
  below.

Provider integrations remain outside the core module. An OpenStack adapter, for
example, owns project authorization, instance lookup, network namespaces and
SSH key selection while reusing the same terminal protocol and session policy.

This is an independent project and is not affiliated with, endorsed by or
sponsored by Cloudflare. Cloudflare product names are used only to describe
the supported integration.

## Project Status

This is an early public release maintained for a small, owner-operated
deployment. The security boundary is deliberate and tested, but the project has
not received an independent security audit. Pin a reviewed revision, test the
deployment in your environment and do not expose the application listeners
directly to the Internet.

## Non-goals

- multi-tenant shell hosting or arbitrary destination SSH
- browser-side storage of SSH credentials
- replacing target account authorization, auditing or operating-system
  hardening
- automatically trusting a target host key

## Architecture

```text
browser
  -> Cloudflare Access
  -> cloudflared
  -> Browser SSH Gateway (127.0.0.1:9999, user browser-ssh)
  -> constrained gateway key
  -> Broker sshd (127.0.0.1:10022, forced command)
  -> browser-ssh-broker
  -> pinned target host key + target-only private key
  -> configured SSH target
```

The gateway receives only a target ID such as `default`. It does not accept a
host, user, port, private-key path or arbitrary SSH option from the browser.

## Security Boundary

- Gateway and management listeners bind to loopback by default.
- Cloudflare Access JWT signature, issuer, audience, expiry and email are
  independently verified at the origin.
- WebSocket and state-changing HTTP requests require the configured HTTPS
  Origin.
- The gateway key can log in only to the localhost broker and is constrained by
  the broker sshd forced command.
- Target keys and target registry files are readable only by the broker.
- Every target uses a pinned `known_hosts` file. Trust-on-first-use is rejected
  by the target configuration helper.
- The broker independently enforces target IDs, operation names, transfer size
  limits and upload/download roots.
- Agent, TCP, X11 and local-command forwarding are disabled on both SSH hops.
- Terminal sessions have concurrency, input-rate, idle and hard-duration
  limits.
- File-transfer sockets and remote inspection/download processes have separate
  concurrency limits; inactive WebSockets have a finite timeout.

This remains a full shell on the configured target. Compromise of an allowed
Cloudflare Access identity can exercise all permissions granted to the target
account. This project is not a multi-tenant shell hosting platform.

See [Threat Model](docs/threat-model.md) and [Broker Protocol](docs/broker.md).
Applications embedding the core module should also read the
[Embedding Guide](docs/embedding.md).
Report suspected vulnerabilities through the private process in
[Security Policy](SECURITY.md), not a public issue.

## Requirements

- Linux host with systemd and OpenSSH server/client
- Java 21
- Docker and `maven:3.9-eclipse-temurin-21`, or Maven 3.9
- GNU `base64`, `realpath` and `zip`
- Cloudflare Tunnel and Access application
- An SSH target with an independently verified ED25519 host-key fingerprint

## Build

```bash
git clone https://github.com/nshes/browser-ssh.git
cd browser-ssh

sudo docker run --rm --network host \
  -v "$PWD:/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-21 \
  mvn -q test package
```

## Install

The scripts are dry-run by default. Review their output before setting
`APPLY=1`.

1. Install the gateway account, client and service without starting it.

```bash
sudo APPLY=1 BUILD=0 START=0 ./deploy/install.sh
```

2. Install the isolated broker account, keys and localhost sshd.

```bash
sudo APPLY=1 START=1 ./deploy/install-broker.sh
```

3. Verify the target host key through a trusted channel, then register it.

```bash
sudo APPLY=1 \
  TARGET_ID=default \
  TARGET_HOST=192.0.2.10 \
  TARGET_PORT=22 \
  TARGET_USER=operator \
  TARGET_REMOTE_HOME=/home/operator \
  TARGET_UPLOAD_ROOT=/home/operator/uploads \
  TARGET_DOWNLOAD_ROOT=/home/operator \
  TARGET_HOST_KEY_SHA256='SHA256:replace-with-verified-fingerprint' \
  ./deploy/configure-target.sh
```

The helper prints a target-specific public key. Add it to the target account's
`authorized_keys` with agent, TCP and X11 forwarding disabled. Do not copy the
private key out of `/etc/browser-ssh-broker/keys`.

4. Copy [the environment example](deploy/browser-ssh.env.example) to
   `/etc/browser-ssh-gateway/browser-ssh.env`. At minimum, replace these values:

```dotenv
BROWSER_SSH_ACCESS_TEAM_DOMAIN=example.cloudflareaccess.com
BROWSER_SSH_ACCESS_AUDIENCE=replace-with-access-aud
BROWSER_SSH_PUBLIC_ORIGIN=https://ssh.example.com
BROWSER_SSH_ALLOWED_EMAILS=operator@example.com
BROWSER_SSH_TARGET_ID=default
```

The example also documents the display label, transfer paths, broker connection
and terminal resource limits. Keep production secrets and identity values out of
the repository.

5. Start the gateway.

```bash
sudo systemctl enable --now browser-ssh-gateway.service
curl -fsS http://127.0.0.1:9998/actuator/health
```

6. Configure Cloudflare Access and Tunnel using
   [docs/cloudflare-access.md](docs/cloudflare-access.md).

## Target Registry

Each target is a root-managed file under:

```text
/etc/browser-ssh-broker/targets/<target-id>.conf
```

See `deploy/target.conf.example`. Values are parsed as data; the broker never
sources target files as shell code. Keys and pinned host records live in
separate broker-only directories.

The current gateway selects one target through `BROWSER_SSH_TARGET_ID`. The
registry supports multiple target definitions so future authorization/provider
layers can choose among them without changing the broker protocol.

## File Transfer

- Upload: one stream at a time, maximum 100 MB.
- Download: two-minute identity-bound single-use link, maximum 2 GiB.
- Transfer WebSockets and remote inspection/download processes have configurable
  concurrency limits. The defaults are two of each.
- Directory download: ZIP is streamed without a gateway-host temporary file.
- The gateway only validates request shape. The broker is authoritative for
  upload and download roots.

The current remote transfer implementation expects common GNU/Linux tools on
the target. A future SFTP provider can replace those commands without changing
the browser protocol.

## Validation

```bash
./tests/broker-policy-test.sh
sudo /usr/sbin/sshd -t -f /etc/browser-ssh-broker/sshd_config
systemctl is-active browser-ssh-broker browser-ssh-gateway
systemctl show browser-ssh-gateway -p User -p NoNewPrivileges -p FragmentPath
ss -ltn | grep -E '127\.0\.0\.1:(9999|9998|10022)'
```

Browser acceptance should cover terminal resize, split/close, clipboard,
Korean IME, Ctrl/Cmd-click links, upload and one-time download behavior.

## Development Disclosure

This project was developed with substantial assistance from AI coding agents.
The maintainer directed the architecture and behavior, reviewed the resulting
changes, and validated them with automated tests and a live deployment. AI
assistance does not guarantee correctness or security; review the threat model,
configuration and code before using the project in a sensitive environment.

Maintainers preparing a release should also complete the
[Public Release Checklist](docs/public-release-checklist.md).
Contributions are accepted at the maintainer's discretion under the policy in
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

The project is licensed under the [Apache License 2.0](LICENSE). Vendored
xterm.js components retain their own license notices under
`browser-ssh-gateway/src/main/resources/static/vendor/xterm/`. See
[Third-Party Notices](THIRD-PARTY-NOTICES.md) for bundled runtime dependencies
and external integrations. Exact origins and hashes for files committed under
`vendor/` are recorded in [Dependency Provenance](docs/dependency-provenance.md).
