# Validation State

This repository intentionally contains no deployment-specific hostname,
Cloudflare account, Tunnel ID, email address, target username or internal IP.
Keep those values in the host's private operations repository.

Before release or deployment, verify:

- Maven tests pass.
- `tests/broker-policy-test.sh` passes.
- Shell scripts pass `bash -n`.
- Gateway, management and broker ports bind only to loopback.
- Gateway cannot read `/etc/browser-ssh-broker`.
- Broker target keys are root-owned, mode `0640`, and readable only by the broker group.
- Both SSH hops use `StrictHostKeyChecking=yes` with pinned host keys.
- Gateway has `NoNewPrivileges=true`.
- Cloudflare Access rejects identities outside the explicit allowlist.
- Terminal and upload/download browser acceptance tests pass.
