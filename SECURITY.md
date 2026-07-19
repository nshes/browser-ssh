# Security Policy

## Supported Versions

Security fixes are made on the current `main` branch. This early project does
not yet maintain multiple supported release lines.

## Reporting a Vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub private
vulnerability reporting for this repository and include the affected revision,
deployment assumptions, reproduction steps and expected impact. Reports are
reviewed on a best-effort basis; no response-time or bounty commitment is made.

Do not include live credentials, private keys, Access tokens, target addresses
or terminal output containing private data. Use redacted examples or a minimal
local reproduction.

## Supported Configuration

Security reports are evaluated against the documented architecture:

- loopback-only gateway, health and broker listeners
- Cloudflare Access JWT validation at the origin
- dedicated gateway and broker operating-system accounts
- forced-command localhost broker SSH service
- pinned host keys on both SSH hops
- target credentials unreadable by the gateway
- target host, user, port and roots selected only by broker configuration

Deployments that expose application ports directly, disable host-key checking,
share target private keys with the gateway or accept arbitrary SSH destinations
are outside the supported security model.
