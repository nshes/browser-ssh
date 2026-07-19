# Cloudflare Access and Tunnel

## Access Application

1. Create a self-hosted Access application for the terminal hostname.
2. Allow only the required identities.
3. Prefer phishing-resistant MFA and keep the Access session short.
4. Record the team domain and Application Audience (`AUD`) value.
5. Configure the gateway environment:

```text
BROWSER_SSH_ACCESS_TEAM_DOMAIN=example.cloudflareaccess.com
BROWSER_SSH_ACCESS_AUDIENCE=replace-with-access-aud
BROWSER_SSH_PUBLIC_ORIGIN=https://ssh.example.com
BROWSER_SSH_ALLOWED_EMAILS=owner@example.com
```

The origin verifies the Access JWT itself. A Tunnel route without its Access
policy must still receive `401` from the gateway.

## Tunnel

Route the public hostname to:

```text
http://127.0.0.1:9999
```

## Ingress Boundary

Treat the ingress or reverse proxy as a separate security boundary. Route only
the public application listener for the intended hostname, reject unmatched
hostnames and routes by default, and never publish management, health, broker or
local SSH listeners through the same ingress. Keep this separation explicit
even though the application independently authenticates every public request.

Public documentation should describe this policy rather than copying a private
deployment's complete route table. Operators should keep the concrete hostname,
path and listener mapping in their private operations repository.

Store the Tunnel token outside the repository with mode `0640` or stricter.
The included `cloudflared-browser-ssh.service` expects:

```text
/etc/cloudflared-browser-ssh/tunnel-token
```

Install it only after deciding which locked service account should own the
connector. A separate Tunnel account provides stronger process isolation; a
shared gateway account is operationally simpler but must rely on systemd mount
namespaces to hide each service's credentials.

## Verification

```bash
curl -i http://127.0.0.1:9999/
curl -fsS http://127.0.0.1:9998/actuator/health
```

Expected results are `401` for an unauthenticated application request and `UP`
for the loopback health request.

From an external browser:

- An unauthenticated request redirects to Access.
- A disallowed identity is rejected.
- An allowed identity can open at most the configured terminal sessions.
- WebSocket requests from an unexpected Origin are rejected.
- Access logout invalidates new gateway requests.

Do not expose ports `9999`, `9998` or `10022` through a router or public
firewall. The target SSH service should have its own network access policy; the
browser gateway does not require public inbound SSH.
