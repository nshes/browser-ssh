# Threat Model

## Scope

The project provides a single-owner browser terminal for a fixed SSH target.
It is not designed to isolate mutually untrusted users or provide public shell
accounts.

## Protected Assets

- Target SSH private keys and pinned host keys
- Target host integrity, data and availability
- Cloudflare Tunnel and Access credentials
- Terminal and file-transfer confidentiality
- Gateway and broker host integrity

## Trust Boundaries

1. Cloudflare Access authenticates an allowed identity and signs a JWT.
2. The gateway verifies the JWT and exact browser Origin.
3. The gateway starts a fixed broker client with a configured target ID.
4. A localhost-only broker sshd authenticates the constrained gateway key and
   forces the broker executable.
5. The broker resolves the target ID from root-managed configuration, applies
   path and operation policy, and uses a target-specific key.
6. The target sshd creates the final login shell.

## Controls

| Risk | Control |
|---|---|
| Tunnel route without Access policy | Origin independently requires and validates an Access JWT. |
| Ingress route exposes an internal listener | Public ingress routes only the application listener; management and broker listeners remain separate and loopback-only. |
| Stolen Access cookie | Exact email allowlist, short Access lifetime, terminal idle and hard limits. |
| Cross-site WebSocket or transfer request | Exact configured HTTPS Origin and JWT required. |
| Terminal output presents a malicious link | Plain-text and OSC links allow only HTTP(S), require an explicit Ctrl/Cmd-click and open without an opener or referrer. |
| Browser supplies an arbitrary SSH destination | Browser never sends host, user, port, key path or SSH options. |
| Gateway compromise leaks target keys | Gateway owns only the forced-command localhost broker key; target keys are broker-only. |
| Gateway uses broker as a general SSH proxy | Broker accepts a fixed versioned grammar and registered target IDs and operations only. |
| Host-key interception | Both SSH hops require pinned host keys and disable trust-on-first-use. |
| Path traversal or symlink escape | Shape check at gateway plus lexical and remote canonical-path checks at broker. |
| File-transfer exhaustion | 100 MB upload cap, 2 GiB download cap, one active upload, bounded transfer sockets/processes and a finite WebSocket idle timeout. |
| Input flood | Bounded message size/rate and maximum concurrent sessions. |
| Service privilege escalation | Locked service accounts, loopback listeners, systemd sandbox and no Docker socket. |
| Credential leakage in logs | JWTs, keys, commands, keystrokes and terminal output are not logged. |

## Residual Risks

- An authenticated terminal is a normal target shell. Target account privileges,
  including sudo, remain reachable.
- A compromised gateway cannot extract target keys but can request the same
  target sessions the gateway is configured to provide.
- The broker currently uses common GNU/Linux commands for transfer operations.
  Target shell behavior is part of the compatibility boundary until an SFTP
  implementation replaces it.
- Cloudflare Access identity compromise can become target-account compromise.
  Use phishing-resistant MFA where possible and keep session duration short.
- Availability still depends on Cloudflare, the gateway host, both SSH hops and
  the configured target.
